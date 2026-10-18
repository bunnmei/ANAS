package space.webkombinat.anas.server

import java.io.InputStream

/** サーバーの設定。リクエストのたびに [AnasServer] へ渡した関数から最新の値を読む */
data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    /** 空ならパスワードなし */
    val password: String = "",
    /** true なら書き込み系の API をすべて拒否する */
    val readOnly: Boolean = false,
    /** 無効にした拡張機能の ID */
    val disabledExtensions: Set<String> = emptySet(),
    /** scope: "all" を許可した拡張機能の ID */
    val fullAccessExtensions: Set<String> = emptySet(),
    /** 拡張機能 ID → アプリで許可した外部サイト（anas.json の `network` のうち許可したもの） */
    val networkGrants: Map<String, Set<String>> = emptyMap(),
    /** WebDAV（/dav/）を使えるようにする */
    val webDavEnabled: Boolean = false,
    /** WebDAV からは読むだけにする（[readOnly] が true なら、これに関係なく読むだけ） */
    val webDavReadOnly: Boolean = false,
    val appVersion: String = "dev",
) {
    companion object {
        const val DEFAULT_PORT = 8080
    }
}

/** Web UI のファイル（APK の assets/web）を読む。テストでは Map などで差し替える */
fun interface AssetSource {
    /** [path] は `index.html` や `sdk.js` のような assets/web からの相対パス。なければ null */
    fun open(path: String): InputStream?
}
