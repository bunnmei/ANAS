package space.webkombinat.anas.core.model

/** HTTP の Range ヘッダーで指定された範囲（両端を含む） */
data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1

    sealed interface Result {
        /** Range なし、または解釈できない → ファイル全体を返す */
        data object Full : Result
        data class Partial(val range: ByteRange) : Result
        /** 範囲がファイルの外 → 416 */
        data object Unsatisfiable : Result
    }

    companion object {
        /**
         * `bytes=0-99`、`bytes=100-`、`bytes=-500` の単一範囲だけに対応する。
         * 複数範囲（`bytes=0-1,5-9`）はファイル全体を返す（RFC 9110 で許されている）。
         */
        fun parse(header: String?, size: Long): Result {
            if (header == null) return Result.Full
            val spec = header.trim()
            if (!spec.startsWith("bytes=")) return Result.Full
            val value = spec.removePrefix("bytes=").trim()
            if (value.contains(',')) return Result.Full
            val dash = value.indexOf('-')
            if (dash < 0) return Result.Full
            val first = value.substring(0, dash).trim()
            val last = value.substring(dash + 1).trim()
            return try {
                when {
                    first.isEmpty() -> {
                        val suffix = last.toLong()
                        if (suffix <= 0 || size == 0L) Result.Unsatisfiable
                        else Result.Partial(ByteRange(maxOf(0, size - suffix), size - 1))
                    }
                    else -> {
                        val start = first.toLong()
                        val end = if (last.isEmpty()) size - 1 else minOf(last.toLong(), size - 1)
                        if (start < 0 || start >= size || end < start) Result.Unsatisfiable
                        else Result.Partial(ByteRange(start, end))
                    }
                }
            } catch (_: NumberFormatException) {
                Result.Full
            }
        }
    }
}
