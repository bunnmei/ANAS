package space.webkombinat.anas.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import space.webkombinat.anas.core.model.Entry
import space.webkombinat.anas.core.model.ExtensionManifest

/** [status] と [code] がそのまま API のエラー（`{"error", "code"}`）になる例外 */
open class ApiException(
    message: String,
    val status: HttpStatusCode,
    val code: String,
    cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
data class InfoResponse(
    val app: String = "ANAS",
    val version: String,
    val authRequired: Boolean,
    val authenticated: Boolean,
    val readOnly: Boolean,
)

@Serializable
data class LoginRequest(val password: String)

@Serializable
data class PathRequest(val path: String)

@Serializable
data class RenameRequest(val path: String, val newName: String)

/** 移動・コピー。[toDir] の中へ同じ名前で入れる。[toVolume] はほかのストレージへ（NAS の API だけ） */
@Serializable
data class TransferRequest(val path: String, val toDir: String, val toVolume: String? = null)

@Serializable
data class ListResponse(
    val volume: String,
    val path: String,
    val entries: List<Entry>,
    /** このフォルダが拡張機能の中なら、その拡張機能 */
    val extension: ExtensionInfo? = null,
)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class ErrorResponse(val error: String, val code: String)

/** 拡張機能の状態（Web UI とアプリの両方で使う） */
@Serializable
data class ExtensionInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val volume: String,
    /** ボリュームのルートからのフォルダのパス（`/.jisui`） */
    val folder: String,
    /** 専用 UI の URL（`/x/jisui/`） */
    val url: String,
    val iconUrl: String? = null,
    val scope: ExtensionManifest.Scope,
    val enabled: Boolean,
    /** scope: "all" をアプリで許可しているか */
    val fullAccess: Boolean,
    /** anas.json の `network`（サーバー経由でアクセスしたい外部サイト） */
    val network: List<String> = emptyList(),
    /** [network] のうち、アプリで許可しているもの。これだけに /x/<id>/api/fetch でアクセスできる */
    val networkAllowed: List<String> = emptyList(),
    /** anas.json に `"home": true`（NAS の最初の画面の代わりに使える） */
    val homeCapable: Boolean = false,
    /** アプリで「ホーム画面にする」を選んでいて、今 `/` を開くとこの拡張機能になる */
    val isHome: Boolean = false,
    /** anas.json の `opens`（この拡張機能で開けるファイルの拡張子） */
    val opens: List<String> = emptyList(),
    /** [opens] のファイルを今開ける（有効・NAS 全体へのアクセスを許可済み）。NAS の画面はこれが true のものだけ使う */
    val canOpen: Boolean = false,
    /** anas.json の誤りなど。null なら正常 */
    val error: String? = null,
)

/** 拡張機能の API（/x/<id>/api/info）が返す情報 */
@Serializable
data class ExtensionSelfInfo(
    val extension: ExtensionInfo,
    val readOnly: Boolean,
)

/** /x/<id>/api/fetch の本文 */
@Serializable
data class FetchRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    /** テキストの本文。バイナリは [bodyBase64] */
    val body: String? = null,
    val bodyBase64: String? = null,
)

/** /x/<id>/api/fetch-to-file の本文。[url] の中身を data/ の [path] に保存する */
@Serializable
data class FetchToFileRequest(
    val url: String,
    val path: String,
    val overwrite: Boolean = false,
    val mkdirs: Boolean = true,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val bodyBase64: String? = null,
) {
    fun toFetch() = FetchRequest(url, method, headers, body, bodyBase64)
}

@Serializable
data class FetchToFileResponse(
    val entry: Entry,
    /** リダイレクトをたどったあとの URL */
    val url: String,
    val status: Int,
    val contentType: String? = null,
)
