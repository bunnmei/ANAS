package space.webkombinat.anas.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 特殊フォルダ（拡張機能）の `anas.json`。仕様は spec/EXTENSION_SPEC.md。
 *
 * ```
 * /.<id>/
 *   anas.json   ← これ
 *   app/        ← UI（index.html が入口）。/x/<id>/ で配信
 *   data/       ← 拡張のデータ。/x/<id>/api/ はここより上に出られない
 * ```
 */
@Serializable
data class ExtensionManifest(
    /** 仕様のバージョン。今は 1 だけ */
    val anas: Int,
    /** フォルダ名から `.` を除いたもの。英小文字・数字・`_`・`-` */
    val id: String,
    val name: String,
    val version: String = "0.0.0",
    val description: String = "",
    /** app/ からの相対パス */
    val icon: String? = null,
    val scope: Scope = Scope.SELF,
    /** CSP で許可する外部オリジン（例: `https://cdn.jsdelivr.net`）。既定は外部通信なし */
    val externalOrigins: List<String> = emptyList(),
    /**
     * サーバー経由（`/x/<id>/api/fetch`）でアクセスする外部サイト（例: `https://www.amazon.co.jp`。`*.` から始めるとサブドメインすべて）。
     * アプリで許可したものだけ有効。既定はなし
     */
    val network: List<String> = emptyList(),
    /**
     * NAS の最初の画面（`/`）の代わりに使える拡張機能なら true。`scope: "all"` が必要。
     * アプリで「ホーム画面にする」を選んだときだけ、`/` を開くとこの拡張機能の画面になる
     */
    val home: Boolean = false,
    /**
     * この拡張機能で開けるファイルの拡張子（例: `".csv"`, `".kicad_sym"`）。`scope: "all"` が必要。
     * NAS の画面で該当するファイルを開くと `/x/<id>/?volume=<ボリューム>&path=<パス>` が開く
     */
    val opens: List<String> = emptyList(),
) {
    @Serializable
    enum class Scope {
        /** data/ の中だけ読み書きできる */
        @SerialName("self") SELF,

        /** NAS 全体を読み書きできる（アプリで許可したときだけ） */
        @SerialName("all") ALL,
    }

    companion object {
        const val FILE_NAME = "anas.json"
        const val APP_DIR = "app"
        const val DATA_DIR = "data"
        const val ENTRY = "index.html"
        const val SPEC_VERSION = 1

        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
        private val ORIGIN_PATTERN = Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$")
        // `*.` は「そのドメインのサブドメインすべて」。`*.com` のような広すぎるものは書けない
        private val NETWORK_PATTERN =
            Regex("^https://(\\*\\.[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+|[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*)(:[0-9]{1,5})?$")
        private val EXTENSION_PATTERN = Regex("^\\.[a-z0-9][a-z0-9_+-]{0,31}$")
        private val json = Json { ignoreUnknownKeys = true }

        /** フォルダ名（`.jisui`）から ID（`jisui`）を取り出す。拡張機能のフォルダ名でなければ null */
        fun idFromFolderName(folderName: String): String? =
            folderName.takeIf { it.startsWith(".") }?.drop(1)?.takeIf { ID_PATTERN.matches(it) }

        /** `anas.json` の中身を読み、検証する。[folderName] はそのフォルダ名（`.jisui` など） */
        fun parse(text: String, folderName: String): Result<ExtensionManifest> = runCatching {
            val manifest = try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("anas.json を読めません: ${e.message?.lineSequence()?.firstOrNull()}")
            }
            manifest.validate(folderName)
            manifest
        }
    }

    fun validate(folderName: String) {
        require(anas == SPEC_VERSION) { "\"anas\" は $SPEC_VERSION にしてください（$anas になっています）" }
        require(ID_PATTERN.matches(id)) { "\"id\" は英小文字・数字・_・- で 64 文字までです: $id" }
        require(folderName == ".$id") { "フォルダ名は \".$id\" にしてください（$folderName になっています）" }
        require(name.isNotBlank()) { "\"name\" が空です" }
        icon?.let { SafePath.parse(it) }
        for (origin in externalOrigins) {
            require(ORIGIN_PATTERN.matches(origin)) { "\"externalOrigins\" は https://host[:port] の形で書いてください: $origin" }
        }
        require(!home || scope == Scope.ALL) { "\"home\": true にするには \"scope\": \"all\" も必要です（NAS 全体のファイルを扱うため）" }
        require(opens.isEmpty() || scope == Scope.ALL) { "\"opens\" を使うには \"scope\": \"all\" も必要です（NAS のファイルを読むため）" }
        for (ext in opens) {
            require(EXTENSION_PATTERN.matches(ext)) { "\"opens\" は \".csv\" のように . で始まる英小文字の拡張子で書いてください: $ext" }
        }
        for (origin in network) {
            require(NETWORK_PATTERN.matches(origin)) { "\"network\" は https://host[:port] か https://*.host の形で書いてください: $origin" }
        }
    }
}
