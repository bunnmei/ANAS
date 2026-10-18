package space.webkombinat.anas.server

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import space.webkombinat.anas.core.model.ExtensionManifest
import space.webkombinat.anas.core.model.InvalidPathException
import space.webkombinat.anas.core.model.MimeTypes
import space.webkombinat.anas.core.model.SafePath
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.model.StorageException
import java.io.File
import java.net.URI

/**
 * ANAS の HTTP サーバー。
 *
 * | URL | 中身 |
 * |---|---|
 * | `/` | NAS の Web UI（assets/web） |
 * | `/_anas/sdk.js` ほか | 拡張機能用の SDK・共通 CSS・仕様書 |
 * | `/api/v1/...` | NAS のファイル API |
 * | `/x/<id>/` | 拡張機能の UI（`/.<id>/app/`） |
 * | `/x/<id>/api/...` | 拡張機能のファイル API（`/.<id>/data/` の中だけ）と外部サイトへのアクセス（[OutboundHttp]） |
 */
class AnasServer(
    private val backend: StorageBackend,
    private val registry: ExtensionRegistry,
    private val assets: AssetSource,
    private val outbound: OutboundHttp = OutboundHttp(),
    /** ファイル検索。null なら検索の API は 503 */
    private val search: SearchService? = null,
    private val config: () -> ServerConfig,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val sessions = Sessions()

    val isRunning: Boolean get() = server != null

    /** ポートが使用中などで起動できなければ例外を投げる */
    fun start(port: Int) {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            anasModule(backend, registry, assets, config, sessions, outbound, search)
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 300, timeoutMillis = 1500)
        server = null
    }
}

internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun Application.anasModule(
    backend: StorageBackend,
    registry: ExtensionRegistry,
    assets: AssetSource,
    config: () -> ServerConfig,
    sessions: Sessions = Sessions(),
    outbound: OutboundHttp = OutboundHttp(),
    search: SearchService? = null,
) {
    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        exception<Throwable> { call, cause -> call.respondError(cause) }
    }

    fun isAuthorized(call: ApplicationCall): Boolean {
        val password = config().password
        return password.isEmpty() || sessions.isValid(call.request.cookies[Sessions.COOKIE], password)
    }

    /** `Authorization: Basic`。ユーザー名は何でもよく、パスワードがアクセスパスワードと合えば通す */
    suspend fun basicAuthorized(call: ApplicationCall): Boolean {
        val header = call.request.headers[HttpHeaders.Authorization] ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching { String(java.util.Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8) }
            .getOrNull() ?: return false
        val ok = sessions.checkPassword(decoded.substringAfter(':', ""), config().password)
        if (!ok) delay(500) // 総当たりを遅くする
        return ok
    }

    // LAN 以外からの接続の拒否・ログインの確認・拡張機能からの NAS API 呼び出しの制限
    intercept(ApplicationCallPipeline.Plugins) {
        val call = context
        val remote = call.request.local.remoteAddress
        if (!NetworkGuard.isPrivate(remote)) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("同じネットワークからだけ接続できます", "forbidden"))
            return@intercept finish()
        }
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "same-origin")

        val path = call.request.path()

        // WebDAV: 設定で無効なら無いことにする。認証は Cookie か Basic（WebDAV のクライアントは Cookie を使えない）
        if (isWebDavPath(path)) {
            if (!config().webDavEnabled) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("WebDAV は無効です（アプリの設定で有効にできます）", "not_found"))
                return@intercept finish()
            }
            if (!isAuthorized(call) && !basicAuthorized(call)) {
                call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"ANAS\", charset=\"UTF-8\"")
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("ログインしてください", "unauthorized"))
                return@intercept finish()
            }
            return@intercept
        }

        val isPublicApi = path == "/api/v1/info" || path == "/api/v1/login" || path == "/api/v1/logout"
        val needsAuth = (path.startsWith("/api/") && !isPublicApi) || path.startsWith("/x/")
        if (needsAuth && !isAuthorized(call)) {
            if (path.startsWith("/api/") || path.contains("/api/")) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("ログインしてください", "unauthorized"))
            } else {
                call.respondRedirect("/?next=" + java.net.URLEncoder.encode(path, "UTF-8"))
            }
            return@intercept finish()
        }

        // scope: "self" の拡張機能のページからは NAS 全体の API を呼べないようにする
        if (path.startsWith("/api/v1/") && !isPublicApi) {
            val refererId = refererExtensionId(call.request.headers[HttpHeaders.Referrer])
            if (refererId != null) {
                val ext = registry.find(refererId)?.toInfo(config())
                if (ext == null || !ext.fullAccess) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("拡張機能 \"$refererId\" は NAS 全体へのアクセスを許可されていません", "scope"),
                    )
                    return@intercept finish()
                }
            }
        }
    }

    routing {
        apiRoutes(backend, registry, config, sessions, ::isAuthorized, search)
        extensionRoutes(backend, registry, config, outbound, search)
        webDavRoutes(backend, config)
        staticRoutes(assets)
    }
}

private fun refererExtensionId(referer: String?): String? {
    val path = referer?.let { runCatching { URI(it).rawPath }.getOrNull() } ?: return null
    if (!path.startsWith("/x/")) return null
    return path.removePrefix("/x/").substringBefore('/').takeIf { it.isNotEmpty() }
}

private fun Route.apiRoutes(
    backend: StorageBackend,
    registry: ExtensionRegistry,
    config: () -> ServerConfig,
    sessions: Sessions,
    isAuthorized: (ApplicationCall) -> Boolean,
    search: SearchService?,
) = route("/api/v1") {
    get("info") {
        val c = config()
        call.respond(
            InfoResponse(
                version = c.appVersion,
                authRequired = c.password.isNotEmpty(),
                authenticated = isAuthorized(call),
                readOnly = c.readOnly,
            ),
        )
    }

    post("login") {
        val password = config().password
        val input = call.receiveNullable<LoginRequest>()?.password.orEmpty()
        if (password.isNotEmpty() && !sessions.checkPassword(input, password)) {
            delay(500) // 総当たりを遅くする
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("パスワードが違います", "unauthorized"))
            return@post
        }
        if (password.isNotEmpty()) {
            call.response.cookies.append(
                Cookie(
                    name = Sessions.COOKIE,
                    value = sessions.issue(password),
                    httpOnly = true,
                    path = "/",
                    maxAge = 60 * 60 * 24 * 30,
                    extensions = mapOf("SameSite" to "Strict"),
                ),
            )
        }
        call.respond(OkResponse())
    }

    post("logout") {
        sessions.revoke(call.request.cookies[Sessions.COOKIE])
        call.response.cookies.append(Cookie(Sessions.COOKIE, "", path = "/", maxAge = 0))
        call.respond(OkResponse())
    }

    get("volumes") { call.respond(backend.volumes()) }

    route("fs/{volume}") {
        fsRoutes(
            backend = backend,
            scopeOf = { call ->
                val volume = call.parameters["volume"]!!
                val info = backend.volumes().firstOrNull { it.id == volume } ?: throw StorageException.NotFound(volume)
                FsScope(volume, SafePath.ROOT, writable = info.writable && !config().readOnly)
            },
            listExtra = { scope, path -> registry.containing(scope.volume, path)?.toInfo(config()) },
            search = search,
        )
    }

    // すべてのボリュームから探す。?q=3日以内に追加したPDF&limit=50&hidden=1
    get("search") { call.respond(searchIn(search, call, volume = null)) }

    get("search/status") {
        val s = search ?: throw ApiException("検索は使えません", HttpStatusCode.ServiceUnavailable, "search_unavailable")
        call.respond(s.currentStatus())
    }

    // 索引を作り直す（終わるのを待たずに返す）
    post("search/rescan") {
        val s = search ?: throw ApiException("検索は使えません", HttpStatusCode.ServiceUnavailable, "search_unavailable")
        call.application.launch { s.rescan() }
        call.respond(HttpStatusCode.Accepted, s.currentStatus().copy(running = true))
    }

    get("extensions") { call.respond(registry.extensions.value.map { it.toInfo(config()) }) }

    post("extensions/rescan") { call.respond(registry.scan().map { it.toInfo(config()) }) }

    post("extensions/install") {
        if (config().readOnly) throw StorageException.ReadOnly("NAS")
        val volume = call.request.queryParameters["volume"]
            ?: backend.volumes().firstOrNull { it.online && it.writable }?.id
            ?: throw IllegalArgumentException("書き込めるストレージがありません")
        val temp = withContext(Dispatchers.IO) { File.createTempFile("anas-ext", ".zip") }
        try {
            val channel = call.receiveChannel()
            withContext(Dispatchers.IO) { temp.outputStream().use { channel.copyTo(it) } }
            val manifest = ZipInstaller(backend).install(volume, temp)
            registry.scan()
            val info = registry.find(manifest.id)?.toInfo(config()) ?: throw StorageException.Io("インストール後に見つかりません")
            call.respond(HttpStatusCode.Created, info)
        } finally {
            temp.delete()
        }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.receiveNullable(): T? =
    runCatching { json.decodeFromString<T>(receiveText()) }.getOrNull()

private fun Route.extensionRoutes(
    backend: StorageBackend,
    registry: ExtensionRegistry,
    config: () -> ServerConfig,
    outbound: OutboundHttp,
    search: SearchService?,
) = route("/x/{id}") {

    suspend fun usable(call: ApplicationCall): InstalledExtension {
        val id = call.parameters["id"]!!
        val ext = registry.find(id)
        if (ext == null) throw StorageException.NotFound("拡張機能 $id")
        if (!ext.usable) throw StorageException.Io("拡張機能 $id は読み込めません: ${ext.error}")
        if (id in config().disabledExtensions) throw StorageException.NotFound("拡張機能 $id（無効になっています）")
        return ext
    }

    suspend fun dataScope(ext: InstalledExtension): FsScope {
        val writable = backend.volumes().firstOrNull { it.id == ext.volume }?.writable == true && !config().readOnly
        return FsScope(ext.volume, ext.dataDir, writable)
    }

    route("api") {
        get("info") {
            val ext = usable(call)
            call.respond(ExtensionSelfInfo(ext.toInfo(config()), config().readOnly))
        }
        fsRoutes(backend = backend, scopeOf = { call -> dataScope(usable(call)) }, search = search)
        outboundRoutes(outbound, backend) { call ->
            val ext = usable(call)
            val c = config()
            OutboundContext(dataScope(ext), ext.toInfo(c).networkAllowed, "ANAS/${c.appVersion}")
        }
    }

    get("{path...}") {
        val ext = usable(call)
        val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
        if (segments.isEmpty() && !call.request.path().endsWith("/")) {
            // index.html の相対パスが正しく解決されるよう、末尾に / を付ける
            call.respondRedirect("/x/${ext.id}/")
            return@get
        }
        val rel = if (segments.isEmpty()) SafePath.parse(ExtensionManifest.ENTRY)
        else SafePath.parse(segments.joinToString("/"))
        val handle = backend.openRead(ext.volume, ext.appDir.resolve(rel))
        call.response.header("Content-Security-Policy", extensionCsp(call, ext, ext.toInfo(config()).fullAccess))
        call.respondFile(handle, download = false, sandbox = false)
    }
}

/**
 * 拡張機能のページの CSP。通信先を自分の `/x/<id>/` と SDK、宣言した外部オリジンに限る。
 * （同じオリジンなので完全な隔離ではない。仕様書にもそう書いている）
 */
private fun extensionCsp(call: ApplicationCall, ext: InstalledExtension, fullAccess: Boolean): String {
    val manifest = ext.manifest!!
    val external = manifest.externalOrigins.joinToString(" ")
    val host = call.request.headers[HttpHeaders.Host]?.takeIf { HOST_PATTERN.matches(it) }
    val own = if (host != null) "http://$host/x/${ext.id}/" else "'self'"
    val connect = if (fullAccess) "'self'" else own
    return listOf(
        "default-src 'self' $external",
        "script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval' $external",
        "style-src 'self' 'unsafe-inline' $external",
        "img-src 'self' data: blob: $external",
        "media-src 'self' blob: $external",
        "font-src 'self' data: $external",
        "connect-src $connect $external",
        "frame-src $own blob:",
        "object-src $own",
        "worker-src 'self' blob: $external",
        "base-uri 'self'",
        "form-action 'self'",
    ).joinToString("; ") { it.trim() }
}

private val HOST_PATTERN = Regex("^[A-Za-z0-9.\\-\\[\\]:]+$")

private fun Route.staticRoutes(assets: AssetSource) {
    get("/") { call.respondAsset(assets, "index.html") }
    get("/_anas/{file}") {
        val file = call.parameters["file"]!!
        call.respondAsset(assets, file)
    }
    get("/{path...}") {
        val path = call.parameters.getAll("path").orEmpty().joinToString("/")
        call.respondAsset(assets, path)
    }
}

private suspend fun ApplicationCall.respondAsset(assets: AssetSource, path: String) {
    val safe = SafePath.parse(path)
    val stream = if (safe.isRoot) null else assets.open(safe.segments.joinToString("/"))
    if (stream == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("見つかりません: /$path", "not_found"))
        return
    }
    response.header(HttpHeaders.CacheControl, "no-cache")
    respondOutputStream(ContentType.parse(MimeTypes.of(safe.name)), HttpStatusCode.OK) {
        withContext(Dispatchers.IO) { stream.use { it.copyTo(this@respondOutputStream) } }
    }
}

private suspend fun ApplicationCall.respondError(cause: Throwable) {
    val (status, code) = when (cause) {
        is InvalidPathException -> HttpStatusCode.BadRequest to "invalid_path"
        is StorageException.NotFound -> HttpStatusCode.NotFound to "not_found"
        is StorageException.AlreadyExists -> HttpStatusCode.Conflict to "exists"
        is StorageException.NotDirectory, is StorageException.IsDirectory -> HttpStatusCode.BadRequest to "type"
        is StorageException.Offline -> HttpStatusCode.ServiceUnavailable to "offline"
        is StorageException.ReadOnly -> HttpStatusCode.Forbidden to "read_only"
        is StorageException.Io -> HttpStatusCode.InternalServerError to "io"
        is ApiException -> cause.status to cause.code
        is BadRequestException, is SerializationException, is IllegalArgumentException -> HttpStatusCode.BadRequest to "bad_request"
        else -> HttpStatusCode.InternalServerError to "internal"
    }
    val message = (cause as? BadRequestException)?.let { it.cause?.message ?: it.message } ?: cause.message ?: cause.toString()
    if (request.path().startsWith("/x/") && !request.path().contains("/api/") && request.httpMethod.value == "GET") {
        // 拡張機能のページは人が見るので HTML で返す
        respond(status, TextContent(errorPage(status, message), ContentType.Text.Html.withParameter("charset", "utf-8")))
    } else {
        respond(status, ErrorResponse(message, code))
    }
}

private fun errorPage(status: HttpStatusCode, message: String): String {
    val escaped = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
        |<link rel="stylesheet" href="/_anas/base.css"><title>${status.value}</title>
        |<main class="anas-container"><h1>${status.value}</h1><p>$escaped</p><p><a href="/">NAS に戻る</a></p></main>""".trimMargin()
}
