package space.webkombinat.anas.server

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.webkombinat.anas.core.model.ByteRange
import space.webkombinat.anas.core.model.Entry
import space.webkombinat.anas.core.model.ReadHandle
import space.webkombinat.anas.core.model.SafePath
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.model.StorageException
import space.webkombinat.anas.core.model.mkdirs
import java.io.InputStream
import java.io.OutputStream

/**
 * ファイル API の操作できる範囲。
 * NAS の API ではボリューム全体（[base] = `/`）、拡張機能の API では `/.<id>/data`。
 * クライアントが送るパスは常に [base] からの相対で、返すパスも [base] からの相対にする。
 */
class FsScope(val volume: String, val base: SafePath, val writable: Boolean) {
    fun abs(relative: String?): SafePath = base.resolve(SafePath.parse(relative))
    fun rel(entry: Entry): Entry = entry.copy(path = (SafePath.parse(entry.path).relativeTo(base) ?: SafePath.ROOT).toString())
    fun rel(path: SafePath): String = (path.relativeTo(base) ?: SafePath.ROOT).toString()

    fun requireWritable() {
        if (!writable) throw StorageException.ReadOnly(volume)
    }
}

/**
 * list / stat / file (GET・PUT) / zip / mkdir / rename / delete。
 * NAS の `/api/v1/fs/{volume}/` と拡張機能の `/x/{id}/api/` の両方で使う。
 */
fun Route.fsRoutes(
    backend: StorageBackend,
    scopeOf: suspend (ApplicationCall) -> FsScope,
    listExtra: (FsScope, SafePath) -> ExtensionInfo? = { _, _ -> null },
    search: SearchService? = null,
) {
    get("search") {
        val scope = scopeOf(call)
        val res = searchIn(search, call, volume = scope.volume, base = scope.base)
        call.respond(res.copy(hits = res.hits.map { it.copy(path = scope.rel(SafePath.parse(it.path))) }))
    }

    get("list") {
        val scope = scopeOf(call)
        val path = scope.abs(call.request.queryParameters["path"])
        val entries = backend.list(scope.volume, path).map(scope::rel)
            .sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
        call.respond(ListResponse(scope.volume, scope.rel(path), entries, listExtra(scope, path)))
    }

    get("stat") {
        val scope = scopeOf(call)
        val path = scope.abs(call.request.queryParameters["path"])
        val entry = backend.stat(scope.volume, path) ?: throw StorageException.NotFound(scope.rel(path))
        call.respond(scope.rel(entry))
    }

    get("file") {
        val scope = scopeOf(call)
        val path = scope.abs(call.request.queryParameters["path"])
        val handle = backend.openRead(scope.volume, path)
        call.respondFile(handle, download = call.request.queryParameters["download"] == "1", sandbox = true)
    }

    get("zip") {
        val scope = scopeOf(call)
        val path = scope.abs(call.request.queryParameters["path"])
        val dir = backend.stat(scope.volume, path) ?: throw StorageException.NotFound(scope.rel(path))
        if (!dir.isDir) throw StorageException.NotDirectory(scope.rel(path))
        val rootName = path.name.ifEmpty { scope.volume }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "$rootName.zip", encodeValue = true).toString(),
        )
        // サイズは作りながらでないと分からないので chunked で送る
        call.respondOutputStream(ContentType.Application.Zip, HttpStatusCode.OK) {
            ZipStreamer.write(backend, scope.volume, path, rootName, this)
        }
    }

    put("file") {
        val scope = scopeOf(call)
        scope.requireWritable()
        val path = scope.abs(call.request.queryParameters["path"])
        if (path == scope.base) throw IllegalArgumentException("ファイル名を指定してください")
        val overwrite = call.request.queryParameters["overwrite"] == "1"
        if (call.request.queryParameters["mkdirs"] == "1") backend.mkdirs(scope.volume, path.parent)
        val channel = call.receiveChannel()
        val entry = backend.write(scope.volume, path.parent, path.name, overwrite) { out ->
            withContext(Dispatchers.IO) { channel.copyTo(out) }
        }
        call.respond(HttpStatusCode.Created, scope.rel(entry))
    }

    post("mkdir") {
        val scope = scopeOf(call)
        scope.requireWritable()
        val path = scope.abs(call.receive<PathRequest>().path)
        if (path == scope.base) throw IllegalArgumentException("フォルダ名を指定してください")
        backend.mkdirs(scope.volume, path.parent)
        call.respond(scope.rel(backend.mkdir(scope.volume, path.parent, path.name)))
    }

    post("rename") {
        val scope = scopeOf(call)
        scope.requireWritable()
        val body = call.receive<RenameRequest>()
        val path = scope.abs(body.path)
        if (path == scope.base) throw IllegalArgumentException("このフォルダは名前を変えられません")
        call.respond(scope.rel(backend.rename(scope.volume, path, body.newName)))
    }

    post("delete") {
        val scope = scopeOf(call)
        scope.requireWritable()
        val path = scope.abs(call.receive<PathRequest>().path)
        if (path == scope.base) throw IllegalArgumentException("このフォルダは削除できません")
        backend.delete(scope.volume, path)
        call.respond(OkResponse())
    }
}

/**
 * ファイルを Range 対応で返す。
 * [sandbox] が true なら、NAS 上の HTML や SVG を開いてもスクリプトが動かないよう `CSP: sandbox` を付ける。
 */
suspend fun ApplicationCall.respondFile(handle: ReadHandle, download: Boolean, sandbox: Boolean) {
    val entry = handle.entry
    val size = entry.size
    val type = ContentType.parse(entry.mime)
    response.header(HttpHeaders.AcceptRanges, "bytes")
    response.header(HttpHeaders.CacheControl, "no-cache")
    if (sandbox) response.header("Content-Security-Policy", "sandbox")
    val disposition = if (download) ContentDisposition.Attachment else ContentDisposition.Inline
    response.header(
        HttpHeaders.ContentDisposition,
        disposition.withParameter(ContentDisposition.Parameters.FileName, entry.name, encodeValue = true).toString(),
    )
    when (val range = ByteRange.parse(request.headers[HttpHeaders.Range], size)) {
        ByteRange.Result.Unsatisfiable -> {
            response.header(HttpHeaders.ContentRange, "bytes */$size")
            respond(HttpStatusCode.RequestedRangeNotSatisfiable, ErrorResponse("範囲が不正です", "range"))
        }
        is ByteRange.Result.Partial -> {
            val r = range.range
            response.header(HttpHeaders.ContentRange, "bytes ${r.start}-${r.endInclusive}/$size")
            respondOutputStream(type, HttpStatusCode.PartialContent, r.length) {
                withContext(Dispatchers.IO) { handle.open(r.start).use { it.copyLimited(this@respondOutputStream, r.length) } }
            }
        }
        ByteRange.Result.Full -> respondOutputStream(type, HttpStatusCode.OK, size) {
            withContext(Dispatchers.IO) { handle.open(0).use { it.copyTo(this@respondOutputStream) } }
        }
    }
}

private fun InputStream.copyLimited(out: OutputStream, limit: Long) {
    val buffer = ByteArray(64 * 1024)
    var remaining = limit
    while (remaining > 0) {
        val n = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (n < 0) break
        out.write(buffer, 0, n)
        remaining -= n
    }
}

/** `?q=&limit=&hidden=1` で検索する（NAS 全体・ボリューム・拡張機能の data/ で共通） */
internal suspend fun searchIn(
    search: SearchService?,
    call: ApplicationCall,
    volume: String?,
    base: SafePath = SafePath.ROOT,
): SearchResponse {
    if (search == null) throw ApiException("検索は使えません", HttpStatusCode.ServiceUnavailable, "search_unavailable")
    val params = call.request.queryParameters
    val q = params["q"].orEmpty()
    val limit = params["limit"]?.toIntOrNull() ?: SearchService.DEFAULT_LIMIT
    val scope = SearchScope(volume, base, includeHidden = params["hidden"] == "1", onlineVolumes = search.onlineVolumes())
    return search.search(q, scope, limit)
}
