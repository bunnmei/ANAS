package space.webkombinat.anas.core.model

/** 拡張子から MIME タイプを決める。Android の MimeTypeMap に頼らず、Web 配信に必要なものを確実に返す */
object MimeTypes {
    const val DIRECTORY = "inode/directory"
    const val OCTET_STREAM = "application/octet-stream"

    private val TABLE = mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "map" to "application/json",
        "json" to "application/json",
        "txt" to "text/plain; charset=utf-8",
        "md" to "text/markdown; charset=utf-8",
        "ts" to "text/plain; charset=utf-8",
        "csv" to "text/csv; charset=utf-8",
        "xml" to "application/xml",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "wasm" to "application/wasm",
    )

    fun of(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return TABLE[ext] ?: OCTET_STREAM
    }
}
