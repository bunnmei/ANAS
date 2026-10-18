package space.webkombinat.anas.client

import space.webkombinat.anas.server.NetworkGuard
import space.webkombinat.anas.server.ServerConfig
import java.net.URI
import java.net.URLDecoder

/** クライアント（WebView）で開いてよい URL の判定。同じ LAN（プライベート IP）だけ */
object LanUrls {

    /**
     * QR や手入力の文字列を、サーバーのトップの URL（`http://192.168.1.108:8080/`）にする。
     * `192.168.1.108` だけならポートは 8080。プライベート IP でなければ null
     */
    fun normalizeServer(raw: String): String? {
        val text = toHalfWidth(raw).trim()
        if (text.isEmpty()) return null
        val withScheme = if (text.contains("://")) text else "http://$text"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (!NetworkGuard.isPrivate(host)) return null
        val port = if (uri.port == -1) ServerConfig.DEFAULT_PORT else uri.port
        return "$scheme://$host:$port/"
    }

    /** 日本語キーボードで打った `１９２．１６８．１．２：８０８０` を半角にする */
    private fun toHalfWidth(text: String): String = buildString {
        for (c in text) {
            append(
                when (c) {
                    in '\uFF01'..'\uFF5E' -> c - 0xFEE0
                    '。' -> '.'
                    '\u3000' -> ' '
                    else -> c
                },
            )
        }
    }

    /** WebView で読み込んでよいか（http / https で、ホストがプライベート IP） */
    fun isLan(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        return uri.host?.let(NetworkGuard::isPrivate) ?: false
    }

    /**
     * Content-Disposition からファイル名を取り出す。
     * `filename*=UTF-8''%E6%9C%AC.pdf`（日本語名）を `filename="..."` より優先する
     */
    fun fileName(contentDisposition: String?, url: String): String {
        val header = contentDisposition.orEmpty()
        Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            runCatching { URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
                ?.let(::safeFileName)?.let { return it }
        }
        Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            safeFileName(m.groupValues[1])?.let { return it }
        }
        Regex("""filename\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let { m ->
            safeFileName(m.groupValues[1].trim())?.let { return it }
        }
        val last = runCatching { URI(url).path }.getOrNull()?.substringAfterLast('/')
        return last?.let(::safeFileName) ?: "download"
    }

    private fun safeFileName(name: String): String? =
        name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[:*?"<>|\u0000-\u001f]"""), "_")
            .trim().takeIf { it.isNotEmpty() && it != "." && it != ".." }
}
