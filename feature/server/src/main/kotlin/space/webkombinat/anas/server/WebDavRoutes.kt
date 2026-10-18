package space.webkombinat.anas.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingHandler
import io.ktor.server.routing.method
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import space.webkombinat.anas.core.model.Entry
import space.webkombinat.anas.core.model.MimeTypes
import space.webkombinat.anas.core.model.SafePath
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.model.StorageException
import space.webkombinat.anas.core.model.VolumeInfo
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV（RFC 4918 のうち、Windows・macOS・Linux のクライアントが使う範囲）。
 * PC にネットワークドライブとしてマウントすると、ふつうのファイルとして読み書きできる。
 *
 * | URL | 中身 |
 * |---|---|
 * | `/dav/` | ボリュームの一覧 |
 * | `/dav/<volume>/<path>` | ボリュームの中身 |
 *
 * ロック（LOCK / UNLOCK）は、Windows や macOS が書き込みの前に求めるので形だけ返す（排他はしない）。
 * 設定で無効なときと認証は [anasModule] の intercept で処理する。
 */
fun Route.webDavRoutes(backend: StorageBackend, config: () -> ServerConfig) = route(PREFIX) {
    val dav = WebDav(backend, config)
    for ((name, handler) in dav.handlers()) {
        method(HttpMethod(name)) { handle(handler) }
        route("{path...}") { method(HttpMethod(name)) { handle(handler) } }
    }
}

private const val PREFIX = "/dav"

private class WebDav(private val backend: StorageBackend, private val config: () -> ServerConfig) {

    fun handlers(): List<Pair<String, RoutingHandler>> = listOf(
        "OPTIONS" to { options(call) },
        "PROPFIND" to { propfind(call) },
        "PROPPATCH" to { proppatch(call) },
        "GET" to { get(call, head = false) },
        "HEAD" to { get(call, head = true) },
        "PUT" to { put(call) },
        "DELETE" to { delete(call) },
        "MKCOL" to { mkcol(call) },
        "MOVE" to { copyOrMove(call, move = true) },
        "COPY" to { copyOrMove(call, move = false) },
        "LOCK" to { lock(call) },
        "UNLOCK" to { call.respond(HttpStatusCode.NoContent) },
    )

    /** `/dav/` なら volume が null */
    private data class Target(val volume: String?, val path: SafePath) {
        val isRoot get() = volume == null
        val isVolumeRoot get() = volume != null && path.isRoot
    }

    private fun target(call: ApplicationCall): Target {
        val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
        if (segments.isEmpty()) return Target(null, SafePath.ROOT)
        return Target(segments.first(), SafePath.parse(segments.drop(1).joinToString("/")))
    }

    /** Destination ヘッダー（`http://host/dav/disk1/a/b.txt`）を読む */
    private fun destination(call: ApplicationCall): Target {
        val raw = call.request.headers["Destination"] ?: throw IllegalArgumentException("Destination ヘッダーがありません")
        val path = runCatching { URI(raw).rawPath }.getOrNull() ?: throw IllegalArgumentException("Destination が不正です: $raw")
        if (path != PREFIX && !path.startsWith("$PREFIX/")) throw ApiException("WebDAV の外には移動できません", HttpStatusCode.BadGateway, "bad_destination")
        val segments = path.removePrefix(PREFIX).split('/').filter { it.isNotEmpty() }.map { it.decodeURLPart() }
        if (segments.size < 2) throw ApiException("ここには置けません", HttpStatusCode.Forbidden, "forbidden")
        return Target(segments.first(), SafePath.parse(segments.drop(1).joinToString("/")))
    }

    private suspend fun volume(id: String): VolumeInfo =
        backend.volumes().firstOrNull { it.id == id } ?: throw StorageException.NotFound(id)

    private suspend fun requireWritable(volume: String) {
        val c = config()
        if (c.readOnly || c.webDavReadOnly || !volume(volume).writable) throw StorageException.ReadOnly(volume)
    }

    /** 項目の情報。ボリュームの一番上は stat できないことがあるので作る */
    private suspend fun stat(volume: String, path: SafePath): Entry? {
        if (path.isRoot) {
            val v = volume(volume)
            if (!v.online) throw StorageException.Offline(volume)
            return Entry(v.name, "/", isDir = true, size = 0, modified = 0, mime = MimeTypes.DIRECTORY)
        }
        return backend.stat(volume, path)
    }

    // ---- メソッド ----

    private suspend fun options(call: ApplicationCall) {
        call.response.header("DAV", "1, 2")
        call.response.header("MS-Author-Via", "DAV")
        call.response.header(HttpHeaders.Allow, ALLOW)
        call.respond(HttpStatusCode.OK)
    }

    private suspend fun propfind(call: ApplicationCall) {
        val t = target(call)
        val depth = call.request.headers["Depth"]?.trim()?.lowercase() ?: "1"
        if (depth == "infinity") {
            throw ApiException("Depth: infinity には対応していません", HttpStatusCode.Forbidden, "propfind-finite-depth")
        }
        val responses = StringBuilder()
        if (t.isRoot) {
            responses.append(response(href(null, SafePath.ROOT, true), "ANAS", null, isDir = true))
            if (depth != "0") {
                for (v in backend.volumes().filter { it.online }) {
                    responses.append(response(href(v.id, SafePath.ROOT, true), v.name, null, isDir = true, volume = v))
                }
            }
        } else {
            val volume = t.volume!!
            val entry = stat(volume, t.path) ?: throw StorageException.NotFound(t.path.toString())
            val vol = if (t.isVolumeRoot) volume(volume) else null
            responses.append(response(href(volume, t.path, entry.isDir), entry.name, entry, entry.isDir, vol))
            if (entry.isDir && depth != "0") {
                for (child in backend.list(volume, t.path)) {
                    if (child.name.startsWith(TEMP_PREFIX)) continue
                    responses.append(response(href(volume, SafePath.parse(child.path), child.isDir), child.name, child, child.isDir))
                }
            }
        }
        call.respond(HttpStatusCode.MultiStatus, xml("<D:multistatus xmlns:D=\"DAV:\">$responses</D:multistatus>"))
    }

    /** プロパティの変更（Windows が更新日時などを送ってくる）。保存はせず、受け付けたことにする */
    private suspend fun proppatch(call: ApplicationCall) {
        val t = target(call)
        if (t.volume != null && stat(t.volume, t.path) == null) throw StorageException.NotFound(t.path.toString())
        val names = runCatching { requestedProps(call.receiveText()) }.getOrDefault(emptyList())
        val props = names.joinToString("") { (ns, local) -> "<x:$local xmlns:x=\"${esc(ns)}\"/>" }
        val href = href(t.volume, t.path, t.isRoot || t.path.isRoot)
        call.respond(
            HttpStatusCode.MultiStatus,
            xml(
                "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>$href</D:href>" +
                    "<D:propstat><D:prop>$props</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>" +
                    "</D:response></D:multistatus>",
            ),
        )
    }

    private suspend fun get(call: ApplicationCall, head: Boolean) {
        val t = target(call)
        if (t.isRoot || stat(t.volume!!, t.path)?.isDir == true) {
            // ブラウザで開いたとき用の簡単な一覧
            val links = if (t.isRoot) {
                backend.volumes().filter { it.online }.map { href(it.id, SafePath.ROOT, true) to it.name + "/" }
            } else {
                backend.list(t.volume!!, t.path).filterNot { it.name.startsWith(TEMP_PREFIX) }
                    .map { href(t.volume, SafePath.parse(it.path), it.isDir) to it.name + if (it.isDir) "/" else "" }
            }
            val html = "<!doctype html><meta charset=\"utf-8\"><title>ANAS WebDAV</title><ul>" +
                links.joinToString("") { (h, n) -> "<li><a href=\"$h\">${esc(n)}</a></li>" } + "</ul>"
            call.response.header("Content-Security-Policy", "sandbox")
            call.respond(TextContent(html, ContentType.Text.Html.withCharset(Charsets.UTF_8)))
            return
        }
        val handle = backend.openRead(t.volume, t.path)
        val entry = handle.entry
        call.response.header(HttpHeaders.ETag, etag(entry))
        if (entry.modified > 0) call.response.header(HttpHeaders.LastModified, httpDate(entry.modified))
        if (head) {
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            call.respond(object : OutgoingContent.NoContent() {
                override val contentLength = entry.size
                override val contentType = ContentType.parse(entry.mime)
            })
            return
        }
        call.respondFile(handle, download = false, sandbox = true)
    }

    private suspend fun put(call: ApplicationCall) {
        val t = target(call)
        val volume = t.volume ?: throw notAllowed()
        if (t.path.isRoot) throw notAllowed()
        requireWritable(volume)
        val existing = backend.stat(volume, t.path)
        if (existing?.isDir == true) throw notAllowed()
        if (stat(volume, t.path.parent)?.isDir != true) throw conflict("親フォルダがありません")
        val channel = call.receiveChannel()
        val entry = backend.write(volume, t.path.parent, t.path.name, overwrite = true) { out ->
            withContext(Dispatchers.IO) { channel.copyTo(out) }
        }
        call.response.header(HttpHeaders.ETag, etag(entry))
        call.respond(if (existing == null) HttpStatusCode.Created else HttpStatusCode.NoContent)
    }

    private suspend fun delete(call: ApplicationCall) {
        val t = target(call)
        val volume = t.volume ?: throw forbidden()
        if (t.path.isRoot) throw forbidden()
        requireWritable(volume)
        if (backend.stat(volume, t.path) == null) throw StorageException.NotFound(t.path.toString())
        backend.delete(volume, t.path)
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun mkcol(call: ApplicationCall) {
        val t = target(call)
        val volume = t.volume ?: throw notAllowed()
        if (t.path.isRoot) throw notAllowed()
        requireWritable(volume)
        if (backend.stat(volume, t.path) != null) throw notAllowed()
        if (stat(volume, t.path.parent)?.isDir != true) throw conflict("親フォルダがありません")
        backend.mkdir(volume, t.path.parent, t.path.name)
        call.respond(HttpStatusCode.Created)
    }

    private suspend fun copyOrMove(call: ApplicationCall, move: Boolean) {
        val src = target(call)
        val srcVolume = src.volume ?: throw forbidden()
        if (src.path.isRoot) throw forbidden()
        val dst = destination(call)
        val dstVolume = dst.volume!!
        if (move) requireWritable(srcVolume)
        requireWritable(dstVolume)
        val entry = backend.stat(srcVolume, src.path) ?: throw StorageException.NotFound(src.path.toString())
        if (srcVolume == dstVolume && (dst.path == src.path || dst.path.relativeTo(src.path) != null)) {
            throw forbidden("自分自身の中には${if (move) "移動" else "コピー"}できません")
        }
        if (stat(dstVolume, dst.path.parent)?.isDir != true) throw conflict("移動先の親フォルダがありません")
        val overwrite = call.request.headers["Overwrite"]?.trim()?.uppercase() != "F"
        val existed = backend.stat(dstVolume, dst.path) != null
        if (existed && !overwrite) throw ApiException("同じ名前のものがあります", HttpStatusCode.PreconditionFailed, "exists")
        if (existed) backend.delete(dstVolume, dst.path)

        if (move && srcVolume == dstVolume && src.path.parent == dst.path.parent) {
            backend.rename(srcVolume, src.path, dst.path.name)
        } else {
            copyTree(srcVolume, entry, dstVolume, dst.path)
            if (move) backend.delete(srcVolume, src.path)
        }
        call.respond(if (existed) HttpStatusCode.NoContent else HttpStatusCode.Created)
    }

    /** StorageBackend に移動・コピーがないので、1つずつ読み書きする */
    private suspend fun copyTree(srcVolume: String, src: Entry, dstVolume: String, dst: SafePath) {
        if (src.isDir) {
            backend.mkdir(dstVolume, dst.parent, dst.name)
            for (child in backend.list(srcVolume, SafePath.parse(src.path))) {
                if (child.name.startsWith(TEMP_PREFIX)) continue
                copyTree(srcVolume, child, dstVolume, dst.child(child.name))
            }
        } else {
            val handle = backend.openRead(srcVolume, SafePath.parse(src.path))
            backend.write(dstVolume, dst.parent, dst.name, overwrite = false) { out ->
                withContext(Dispatchers.IO) { handle.open(0).use { it.copyTo(out) } }
            }
        }
    }

    /**
     * 形だけのロック。Windows や macOS、Office は書き込みの前にロックを取るので、いつでも成功させる。
     * まだないファイルへのロックは、空のファイルを作る（RFC 4918 の決まり）
     */
    private suspend fun lock(call: ApplicationCall) {
        val t = target(call)
        val body = call.receiveText()
        var status = HttpStatusCode.OK
        if (t.volume != null && !t.path.isRoot && backend.stat(t.volume, t.path) == null) {
            requireWritable(t.volume)
            if (stat(t.volume, t.path.parent)?.isDir != true) throw conflict("親フォルダがありません")
            backend.write(t.volume, t.path.parent, t.path.name, overwrite = false) { }
            status = HttpStatusCode.Created
        }
        val token = call.request.headers["If"]?.let { Regex("<(opaquelocktoken:[^>]+)>").find(it)?.groupValues?.get(1) }
            ?: "opaquelocktoken:${UUID.randomUUID()}"
        val owner = Regex("<(?:\\w+:)?owner[^>]*>(.*?)</(?:\\w+:)?owner>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty()
        val depth = if (call.request.headers["Depth"] == "0") "0" else "infinity"
        val href = href(t.volume, t.path, t.isRoot || t.path.isRoot)
        call.response.header("Lock-Token", "<$token>")
        call.respond(
            status,
            xml(
                "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
                    "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>" +
                    "<D:depth>$depth</D:depth><D:owner>$owner</D:owner><D:timeout>Second-3600</D:timeout>" +
                    "<D:locktoken><D:href>$token</D:href></D:locktoken><D:lockroot><D:href>$href</D:href></D:lockroot>" +
                    "</D:activelock></D:lockdiscovery></D:prop>",
            ),
        )
    }

    // ---- XML ----

    private fun response(href: String, name: String, entry: Entry?, isDir: Boolean, volume: VolumeInfo? = null): String {
        val props = StringBuilder()
        props.append("<D:displayname>${esc(name)}</D:displayname>")
        props.append(if (isDir) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/>")
        if (entry != null && !isDir) {
            props.append("<D:getcontentlength>${entry.size}</D:getcontentlength>")
            props.append("<D:getcontenttype>${esc(entry.mime)}</D:getcontenttype>")
            props.append("<D:getetag>${esc(etag(entry))}</D:getetag>")
        }
        if (entry != null && entry.modified > 0) {
            props.append("<D:getlastmodified>${httpDate(entry.modified)}</D:getlastmodified>")
            props.append("<D:creationdate>${Instant.ofEpochMilli(entry.modified)}</D:creationdate>")
        }
        if (volume?.freeBytes != null && volume.totalBytes != null) {
            props.append("<D:quota-available-bytes>${volume.freeBytes}</D:quota-available-bytes>")
            props.append("<D:quota-used-bytes>${volume.totalBytes!! - volume.freeBytes!!}</D:quota-used-bytes>")
        }
        props.append(
            "<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype>" +
                "</D:lockentry></D:supportedlock><D:lockdiscovery/>",
        )
        return "<D:response><D:href>$href</D:href><D:propstat><D:prop>$props</D:prop>" +
            "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }

    /** PROPPATCH の本文から、変えようとしているプロパティの名前（名前空間, ローカル名）を取り出す */
    private fun requestedProps(body: String): List<Pair<String, String>> {
        if (body.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 外部の実体を読ませない（XXE 対策）
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(body.byteInputStream())
        val props = doc.getElementsByTagNameNS("DAV:", "prop")
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until props.length) {
            val children = props.item(i).childNodes
            for (j in 0 until children.length) {
                val el = children.item(j) as? Element ?: continue
                out += (el.namespaceURI ?: "") to (el.localName ?: el.tagName)
            }
        }
        return out
    }

    private fun xml(body: String) =
        TextContent("<?xml version=\"1.0\" encoding=\"utf-8\"?>$body", ContentType.Application.Xml.withCharset(Charsets.UTF_8))

    companion object {
        private const val ALLOW = "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, COPY, MOVE, LOCK, UNLOCK"

        /** 書き込み中の一時ファイル（StorageBackend.write が作る）。一覧には出さない */
        private const val TEMP_PREFIX = ".anas-upload-"

        private val HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

        fun httpDate(ms: Long): String = HTTP_DATE.format(Instant.ofEpochMilli(ms))

        fun etag(e: Entry) = "\"${e.size.toString(16)}-${e.modified.toString(16)}\""

        fun href(volume: String?, path: SafePath, isDir: Boolean): String {
            if (volume == null) return "$PREFIX/"
            val segments = listOf(volume) + path.segments
            return PREFIX + "/" + segments.joinToString("/") { it.encodeURLPathPart() } + if (isDir) "/" else ""
        }

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun notAllowed() = ApiException("この操作はできません", HttpStatusCode.MethodNotAllowed, "method_not_allowed")
        fun forbidden(message: String = "この操作はできません") = ApiException(message, HttpStatusCode.Forbidden, "forbidden")
        fun conflict(message: String) = ApiException(message, HttpStatusCode.Conflict, "conflict")
    }
}

/** WebDAV のパスか */
internal fun isWebDavPath(path: String) = path == PREFIX || path.startsWith("$PREFIX/")
