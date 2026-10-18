package space.webkombinat.anas.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 外部アクセスの失敗 */
class OutboundException(
    message: String,
    status: HttpStatusCode,
    code: String,
    cause: Throwable? = null,
) : ApiException(message, status, code, cause)

/** DNS の結果がプライベート IP だけだった */
private class BlockedAddressException(host: String) : UnknownHostException(host)

/**
 * 拡張機能の代わりに外部の Web サーバーへアクセスする（`/x/<id>/api/fetch` と `fetch-to-file`）。
 * ブラウザでは CORS や CSP で読めない外部サイトを、端末から取りに行く。
 *
 * 端末を踏み台にされないよう、次のものには繋がない。
 * - anas.json の `network` に書かれ、アプリで許可されたもの以外のホスト（リダイレクト先も毎回確かめる）
 * - プライベート IP・ループバックなど（DNS で引いた結果で判定するので、DNS リバインディングも防ぐ）
 * - プロキシ経由（プロキシが名前を解決すると上の判定ができないため）
 *
 * [allowHttp] と [isAllowedAddress] はテストで localhost のサーバーに繋ぐためのもの。
 */
class OutboundHttp(
    private val allowHttp: Boolean = false,
    private val isAllowedAddress: (InetAddress) -> Boolean = NetworkGuard::isPublicAddress,
) {
    private val client = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val allowed = Dns.SYSTEM.lookup(hostname).filter(isAllowedAddress)
                if (allowed.isEmpty()) throw BlockedAddressException(hostname)
                return allowed
            }
        })
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val permits = Semaphore(MAX_CONCURRENT)

    /**
     * [req] を送り、リダイレクトをたどった最後の応答を [use] に渡す。応答は [use] のあとで閉じる。
     * [allowed] はその拡張機能が許可されている `network`。[callTimeoutSeconds] は本文を読み終わるまでの上限（0 なら無制限）。
     */
    suspend fun <T> exchange(
        req: FetchRequest,
        allowed: List<String>,
        userAgent: String,
        callTimeoutSeconds: Long,
        use: suspend (Response) -> T,
    ): T {
        if (allowed.isEmpty()) {
            throw denied("この拡張機能はインターネットへのアクセスを許可されていません（anas.json の \"network\" と、アプリでの許可が必要です）")
        }
        var method = req.method.uppercase()
        require(method in METHODS) { "使えないメソッドです: ${req.method}" }
        var body: ByteArray? = when {
            req.bodyBase64 != null -> runCatching { Base64.getDecoder().decode(req.bodyBase64) }
                .getOrElse { throw IllegalArgumentException("bodyBase64 が不正です") }
            req.body != null -> req.body.toByteArray()
            else -> null
        }
        require(body == null || method !in NO_BODY_METHODS) { "$method には本文を付けられません" }
        var url = req.url.toHttpUrlOrNull() ?: throw IllegalArgumentException("URL が不正です: ${req.url}")

        return permits.withPermit {
            var redirects = 0
            while (true) {
                checkUrl(url, allowed)
                val call = client.newCall(buildRequest(url, method, req.headers, body, userAgent))
                call.timeout().timeout(callTimeoutSeconds, TimeUnit.SECONDS)
                val response = try {
                    call.await()
                } catch (e: IOException) {
                    throw e.toOutbound(url)
                }
                if (!response.isRedirect) {
                    return@withPermit response.use { r ->
                        try {
                            use(r)
                        } catch (e: Exception) {
                            // 本文の読み込みの失敗は、保存先の例外に包まれていても外部アクセスのエラーとして返す
                            throw generateSequence<Throwable>(e) { it.cause }.filterIsInstance<OutboundIOException>().firstOrNull()?.outbound ?: e
                        }
                    }
                }
                val location = response.header("Location")
                val code = response.code
                response.close()
                if (++redirects > MAX_REDIRECTS) throw upstream("リダイレクトが多すぎます")
                url = location?.let { url.resolve(it) } ?: throw upstream("リダイレクト先が不正です: $location")
                // ブラウザと同じく、303 と POST の 301/302 は GET に変える
                if ((code == 303 && method != "HEAD") || (code in 301..302 && method == "POST")) {
                    method = "GET"
                    body = null
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    private fun checkUrl(url: HttpUrl, allowed: List<String>) {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw denied("URL にユーザー名・パスワードは書けません")
        if (allowed.none { originMatches(it, url, allowHttp) }) {
            throw denied("${url.scheme}://${url.host}:${url.port} へのアクセスは許可されていません（anas.json の \"network\" に書いて、アプリで許可してください）")
        }
        // IP アドレスの URL は DNS を通らないので、ここで確かめる
        if (IPV4.matches(url.host) || url.host.contains(':')) {
            val addr = InetAddress.getByName(url.host)
            if (!isAllowedAddress(addr)) throw denied("LAN 内や端末自身のアドレスにはアクセスできません: ${url.host}")
        }
    }

    private fun buildRequest(url: HttpUrl, method: String, headers: Map<String, String>, body: ByteArray?, userAgent: String): Request {
        val h = Headers.Builder()
        for ((name, value) in headers) {
            if (name.lowercase() !in DROPPED_HEADERS) h.add(name, value) // 改行などを含むと IllegalArgumentException → 400
        }
        if (h["User-Agent"] == null) h["User-Agent"] = userAgent
        val type = h["Content-Type"]?.toMediaTypeOrNull()
        val requestBody = when {
            body != null -> body.toRequestBody(type)
            method in BODY_METHODS -> ByteArray(0).toRequestBody(type)
            else -> null
        }
        return Request.Builder().url(url).headers(h.build()).method(method, requestBody).build()
    }

    private fun IOException.toOutbound(url: HttpUrl): OutboundException {
        val chain = generateSequence<Throwable>(this) { it.cause }
        return when {
            chain.any { it is BlockedAddressException } ->
                denied("LAN 内や端末自身のアドレスにはアクセスできません: ${url.host}")
            this is InterruptedIOException -> OutboundException("外部サイトの応答がタイムアウトしました: ${url.host}", HttpStatusCode.GatewayTimeout, "timeout", this)
            this is UnknownHostException -> upstream("ホストが見つかりません（端末がインターネットに繋がっていますか？）: ${url.host}", this)
            else -> upstream("外部サイトに接続できません: ${url.host}（${message ?: javaClass.simpleName}）", this)
        }
    }

    companion object {
        /** 同時に外部へアクセスする数 */
        const val MAX_CONCURRENT = 4
        const val MAX_REDIRECTS = 5

        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        private val NO_BODY_METHODS = setOf("GET", "HEAD")
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /** 拡張機能から指定させないヘッダー（接続の制御は OkHttp がする。圧縮の展開も OkHttp に任せる） */
        private val DROPPED_HEADERS = setOf(
            "host", "content-length", "connection", "keep-alive", "transfer-encoding", "te", "trailer",
            "upgrade", "expect", "proxy-authorization", "proxy-connection", "accept-encoding",
        )

        /** anas.json の `network` の1項目（`https:` + `//host`・`//host:8443`、または先頭が `*.` のホスト）と [url] を比べる */
        internal fun originMatches(origin: String, url: HttpUrl, allowHttp: Boolean = false): Boolean {
            val rest = origin.lowercase().removePrefix("https://")
            val host = rest.substringBefore(':')
            val port = rest.substringAfter(':', "").toIntOrNull() ?: 443
            val schemeOk = url.scheme == "https" || (allowHttp && url.scheme == "http")
            if (!schemeOk || url.port != port) return false
            return if (host.startsWith("*.")) url.host.endsWith(host.substring(1)) else url.host == host
        }

        private fun denied(message: String) = OutboundException(message, HttpStatusCode.Forbidden, "network")

        internal fun upstream(message: String, cause: Throwable? = null) =
            OutboundException(message, HttpStatusCode.BadGateway, "upstream", cause)

        internal fun tooLarge(limit: Long) =
            OutboundException("外部サイトの応答が大きすぎます（上限 ${limit / 1024 / 1024} MB）", HttpStatusCode.BadGateway, "too_large")
    }
}

/** 本文のコピー中に起きた [OutboundException] を、IOException として OkHttp や出力先の外へ運ぶための包み */
private class OutboundIOException(val outbound: OutboundException) : IOException(outbound.message, outbound)

/**
 * 外部サイトの応答の本文を [limit] バイトまでコピーする。超えたら too_large。
 * 読み込み（外部サイト側）の失敗は [OutboundIOException] にして、書き込み側の失敗と区別する
 */
internal fun InputStream.copyAtMost(out: OutputStream, limit: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = try {
            read(buffer)
        } catch (e: InterruptedIOException) {
            throw OutboundIOException(OutboundException("外部サイトの応答がタイムアウトしました", HttpStatusCode.GatewayTimeout, "timeout", e))
        } catch (e: IOException) {
            throw OutboundIOException(OutboundHttp.upstream("外部サイトからの読み込みに失敗しました（${e.message ?: e.javaClass.simpleName}）", e))
        }
        if (n < 0) return total
        total += n
        if (total > limit) throw OutboundIOException(OutboundHttp.tooLarge(limit))
        out.write(buffer, 0, n)
    }
}

/** 応答を待つ。コルーチンがキャンセルされたら（ブラウザが通信を切ったら）リクエストも中止する */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }
    })
}
