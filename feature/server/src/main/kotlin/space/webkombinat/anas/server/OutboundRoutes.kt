package space.webkombinat.anas.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.model.StorageException
import space.webkombinat.anas.core.model.mkdirs
import java.net.URLEncoder

/** 外部アクセスの API で、拡張機能ごとに決まるもの */
class OutboundContext(
    val scope: FsScope,
    /** 許可された `network` */
    val allowed: List<String>,
    val userAgent: String,
)

/**
 * `POST fetch`（外部サイトの応答をそのまま返す）と `POST fetch-to-file`（外部サイトの応答を data/ に保存する）。
 * 拡張機能の `/x/<id>/api/` の下で使う。仕様は spec/EXTENSION_SPEC.md の「外部サイトへのアクセス」。
 */
fun Route.outboundRoutes(
    outbound: OutboundHttp,
    backend: StorageBackend,
    contextOf: suspend (ApplicationCall) -> OutboundContext,
) {
    post("fetch") {
        val ctx = contextOf(call)
        val req = call.receiveJson<FetchRequest>()
        outbound.exchange(req, ctx.allowed, ctx.userAgent, FETCH_TIMEOUT_SECONDS) { res ->
            val body = res.body
            val length = body.contentLength()
            if (length > MAX_FETCH_BYTES) throw OutboundHttp.tooLarge(MAX_FETCH_BYTES)
            // 外部サイトのステータスやヘッダーは X-Anas-* で返す（ANAS 自身のエラーと区別するため、ステータスは常に 200）
            call.response.header("X-Anas-Status", res.code.toString())
            call.response.header("X-Anas-Url", res.request.url.toString())
            call.response.header("X-Anas-Headers", encodeHeaders(res.headers))
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("Content-Security-Policy", "sandbox")
            val type = body.contentType()?.let { runCatching { ContentType.parse(it.toString()) }.getOrNull() }
                ?: ContentType.Application.OctetStream
            val declared = length.takeIf { it >= 0 && res.request.method != "HEAD" }
            // エンジンによっては本文の書き出しが respondOutputStream から戻ったあとになるので、
            // 書き終わるまで待ってから応答を閉じる（exchange を抜けると閉じられる）
            val written = CompletableDeferred<Unit>()
            call.respondOutputStream(type, HttpStatusCode.OK, declared) {
                try {
                    withContext(Dispatchers.IO) { body.byteStream().use { it.copyAtMost(this@respondOutputStream, MAX_FETCH_BYTES) } }
                } finally {
                    written.complete(Unit)
                }
            }
            withTimeoutOrNull(FETCH_TIMEOUT_SECONDS * 1000) { written.await() }
        }
    }

    post("fetch-to-file") {
        val ctx = contextOf(call)
        val scope = ctx.scope
        scope.requireWritable()
        val req = call.receiveJson<FetchToFileRequest>()
        val path = scope.abs(req.path)
        if (path == scope.base) throw IllegalArgumentException("ファイル名を指定してください")
        // 外部サイトから取ってきてから 409 にならないよう、先に確かめる
        if (!req.overwrite && backend.stat(scope.volume, path) != null) throw StorageException.AlreadyExists(scope.rel(path))
        outbound.exchange(req.toFetch(), ctx.allowed, ctx.userAgent, callTimeoutSeconds = 0) { res ->
            if (!res.isSuccessful) {
                throw OutboundException("外部サイトが ${res.code} を返しました", HttpStatusCode.BadGateway, "upstream_status")
            }
            val body = res.body
            if (body.contentLength() > MAX_FILE_BYTES) throw OutboundHttp.tooLarge(MAX_FILE_BYTES)
            if (req.mkdirs) backend.mkdirs(scope.volume, path.parent)
            val entry = backend.write(scope.volume, path.parent, path.name, req.overwrite) { out ->
                withContext(Dispatchers.IO) { body.byteStream().use { it.copyAtMost(out, MAX_FILE_BYTES) } }
            }
            call.respond(
                HttpStatusCode.Created,
                FetchToFileResponse(scope.rel(entry), res.request.url.toString(), res.code, body.contentType()?.toString()),
            )
        }
    }
}

/** fetch の応答の上限 */
const val MAX_FETCH_BYTES = 64L * 1024 * 1024

/** fetch-to-file の上限（FAT32 の 1 ファイルの上限） */
const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024 - 1

/** fetch の、本文を読み終わるまでの上限 */
private const val FETCH_TIMEOUT_SECONDS = 120L

/** リクエストの JSON の上限（bodyBase64 で 8MB ほど送れる） */
private const val MAX_REQUEST_BYTES = 12L * 1024 * 1024

/**
 * JSON の本文を読む。Content-Type が application/json でなければ 400。
 * （フォームの POST のような、別のサイトから送れる「単純なリクエスト」を受け付けないため）
 */
private suspend inline fun <reified T : Any> ApplicationCall.receiveJson(): T {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw IllegalArgumentException("Content-Type: application/json で送ってください")
    }
    val bytes = receiveChannel().readRemaining(MAX_REQUEST_BYTES + 1).readByteArray()
    if (bytes.size > MAX_REQUEST_BYTES) {
        throw OutboundException("リクエストが大きすぎます", HttpStatusCode.PayloadTooLarge, "too_large")
    }
    return json.decodeFromString<T>(bytes.decodeToString())
}

/** 応答ヘッダーを `{"name": "value"}` の JSON にして、ヘッダーに載せられるよう URL エンコードする（Set-Cookie は除く） */
private fun encodeHeaders(headers: Headers): String {
    val map = headers.names()
        .filter { it.lowercase() !in HIDDEN_RESPONSE_HEADERS }
        .associate { it.lowercase() to JsonPrimitive(headers.values(it).joinToString(", ")) }
    // Android 13 未満には URLEncoder.encode(String, Charset) がない
    return URLEncoder.encode(JsonObject(map).toString(), "UTF-8").replace("+", "%20")
}

private val HIDDEN_RESPONSE_HEADERS = setOf("set-cookie", "set-cookie2", "content-encoding", "content-length", "transfer-encoding")
