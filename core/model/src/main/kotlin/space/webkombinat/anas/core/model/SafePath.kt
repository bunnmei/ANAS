package space.webkombinat.anas.core.model

/**
 * ボリュームのルートからの相対パス。`..` や NUL 文字などを含むパスは作れないので、
 * これを受け取った時点でルートの外に出ないことが保証される。
 *
 * 文字列表現は常に `/` 始まり（ルートは `/`）。
 */
class SafePath private constructor(val segments: List<String>) {

    val isRoot: Boolean get() = segments.isEmpty()

    /** 最後の要素の名前。ルートでは空文字 */
    val name: String get() = segments.lastOrNull() ?: ""

    /** 親のパス。ルートの親はルート */
    val parent: SafePath get() = if (isRoot) this else SafePath(segments.dropLast(1))

    /** 名前を1つ足す。名前は [FileNames.requireValid] で検証する */
    fun child(name: String): SafePath = SafePath(segments + FileNames.requireValid(name))

    /** 相対パス（`a/b` のような文字列）を足す */
    fun resolve(relative: String): SafePath = SafePath(segments + parse(relative).segments)

    fun resolve(other: SafePath): SafePath = SafePath(segments + other.segments)

    fun startsWith(prefix: SafePath): Boolean =
        segments.size >= prefix.segments.size && segments.subList(0, prefix.segments.size) == prefix.segments

    /** [prefix] からの相対パス。[prefix] の下にないときは null */
    fun relativeTo(prefix: SafePath): SafePath? =
        if (startsWith(prefix)) SafePath(segments.drop(prefix.segments.size)) else null

    override fun toString(): String = "/" + segments.joinToString("/")
    override fun equals(other: Any?): Boolean = other is SafePath && other.segments == segments
    override fun hashCode(): Int = segments.hashCode()

    companion object {
        val ROOT = SafePath(emptyList())

        /**
         * `/a/b`、`a/b/`、`a//b` などを受け付けて正規化する。
         * `.` `..` 、バックスラッシュ、制御文字を含むものは [InvalidPathException]。
         */
        fun parse(raw: String?): SafePath {
            if (raw == null) return ROOT
            if (raw.contains('\\')) throw InvalidPathException("バックスラッシュは使えません: $raw")
            val segments = raw.split('/').filter { it.isNotEmpty() }
            for (s in segments) {
                if (s == "." || s == "..") throw InvalidPathException("`.` や `..` は使えません: $raw")
                if (s.any { it.code < 0x20 || it.code == 0x7f }) throw InvalidPathException("制御文字は使えません")
            }
            return SafePath(segments)
        }
    }
}

class InvalidPathException(message: String) : IllegalArgumentException(message)

/** ファイル名の検証。FAT32 / exFAT の SD カードや USB メモリでも作れる名前だけを許す */
object FileNames {
    private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    private const val MAX_BYTES = 255

    fun problem(name: String): String? = when {
        name.isEmpty() -> "名前が空です"
        name == "." || name == ".." -> "`.` や `..` は使えません"
        name.any { it.code < 0x20 || it.code == 0x7f } -> "制御文字は使えません"
        name.any { it in FORBIDDEN } -> "次の文字は使えません: / \\ : * ? \" < > |"
        name.endsWith(' ') || name.endsWith('.') -> "末尾に空白や `.` は使えません"
        name.toByteArray(Charsets.UTF_8).size > MAX_BYTES -> "名前が長すぎます（UTF-8 で $MAX_BYTES バイトまで）"
        else -> null
    }

    fun requireValid(name: String): String {
        problem(name)?.let { throw InvalidPathException("$it: $name") }
        return name
    }
}
