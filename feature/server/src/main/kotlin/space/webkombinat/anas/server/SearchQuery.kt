package space.webkombinat.anas.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** ファイルの種類（「メモ」「写真」などの言葉から決まる） */
@Serializable
enum class FileKind(val label: String, val extensions: Set<String>) {
    @SerialName("text") TEXT("テキスト・メモ", setOf("md", "markdown", "txt", "text")),
    @SerialName("pdf") PDF("PDF", setOf("pdf")),
    @SerialName("image") IMAGE("画像", setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tif", "tiff")),
    @SerialName("video") VIDEO("動画", setOf("mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp")),
    @SerialName("audio") AUDIO("音声・音楽", setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus")),
    @SerialName("archive") ARCHIVE("圧縮ファイル", setOf("zip", "7z", "rar", "tar", "gz", "tgz", "xz")),
    @SerialName("document") DOCUMENT("文書", setOf("doc", "docx", "odt", "rtf", "pages")),
    @SerialName("spreadsheet") SPREADSHEET("表計算", setOf("xls", "xlsx", "ods", "csv", "numbers")),
    @SerialName("presentation") PRESENTATION("スライド", setOf("ppt", "pptx", "odp", "key")),
    @SerialName("folder") FOLDER("フォルダ", emptySet()),
}

/** 日時の条件をどの日時で見るか */
@Serializable
enum class TimeField {
    /** 更新日時か、ANAS に追加された日時のどちらか */
    @SerialName("any") ANY,

    /** ANAS に追加（アップロード・保存）された日時 */
    @SerialName("added") ADDED,

    /** ファイルの更新日時 */
    @SerialName("modified") MODIFIED,
}

@Serializable
enum class SearchSort {
    @SerialName("relevance") RELEVANCE,
    @SerialName("newest") NEWEST,
    @SerialName("oldest") OLDEST,
    @SerialName("largest") LARGEST,
}

/**
 * 「3日以内にアップロードした md か txt」のような文章を、検索の条件にしたもの。
 * 機械学習は使わず、日時・種類・サイズの言い方をルールで読み取り、残りをキーワードにする。
 */
@Serializable
data class SearchQuery(
    /** すべてを含むものを探す（ファイル名とフォルダ名から） */
    val keywords: List<String> = emptyList(),
    val kinds: Set<FileKind> = emptySet(),
    /** 拡張子（小文字、`.` なし）。[kinds] とはどちらかに当てはまればよい */
    val extensions: Set<String> = emptySet(),
    /** UNIX ミリ秒。この日時以降 */
    val since: Long? = null,
    /** UNIX ミリ秒。この日時より前 */
    val until: Long? = null,
    val timeField: TimeField = TimeField.ANY,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val sort: SearchSort = SearchSort.RELEVANCE,
    /** 読み取った条件を人が読める形にしたもの（画面に出して、どう解釈したかを伝える） */
    val summary: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = keywords.isEmpty() && kinds.isEmpty() && extensions.isEmpty() && since == null && until == null &&
            minSize == null && maxSize == null

    /** [kinds] と [extensions] を合わせた拡張子（フォルダと MIME で決める種類は別） */
    val allExtensions: Set<String> get() = extensions + kinds.flatMap { it.extensions }

    companion object {
        fun parse(input: String, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): SearchQuery =
            Parser(input, ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)).parse()
    }
}

/** 検索の照合に使う形。全角・半角、大文字・小文字、ひらがな・カタカナを区別しない */
fun normalizeForSearch(text: String): String {
    val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
    val sb = StringBuilder(nfkc.length)
    for (c in nfkc) sb.append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
    return sb.toString()
}

private class Parser(input: String, private val now: ZonedDateTime) {
    /** 読み取った部分は空白に置き換えていく */
    private var text = " " + Normalizer.normalize(input, Normalizer.Form.NFKC).lowercase() + " "
    private val keywords = mutableListOf<String>()
    private val kinds = linkedSetOf<FileKind>()
    private val extensions = linkedSetOf<String>()
    private var since: Long? = null
    private var until: Long? = null
    private var timeLabel: String? = null
    private var timeField = TimeField.ANY
    private var minSize: Long? = null
    private var maxSize: Long? = null
    private var sort = SearchSort.RELEVANCE

    fun parse(): SearchQuery {
        quoted()
        relativeTime()
        namedTime()
        dates()
        timeVerbs()
        sizes()
        sortWords()
        explicitExtensions()
        kindWords()
        stopWords()
        remainingKeywords()

        val summary = buildList {
            timeLabel?.let {
                val field = when (timeField) {
                    TimeField.ADDED -> "追加"
                    TimeField.MODIFIED -> "更新"
                    TimeField.ANY -> "日時"
                }
                add("$field: $it")
            }
            val types = kinds.map { it.label } + extensions.filter { ext -> kinds.none { ext in it.extensions } }.map { ".$it" }
            if (types.isNotEmpty()) add("種類: " + types.joinToString("・"))
            minSize?.let { add("サイズ: ${formatSize(it)} 以上") }
            maxSize?.let { add("サイズ: ${formatSize(it)} 以下") }
            when (sort) {
                SearchSort.NEWEST -> add("新しい順")
                SearchSort.OLDEST -> add("古い順")
                SearchSort.LARGEST -> add("大きい順")
                SearchSort.RELEVANCE -> Unit
            }
            if (keywords.isNotEmpty()) add("キーワード: " + keywords.joinToString("・") { "「$it」" })
        }
        return SearchQuery(keywords.toList(), kinds.toSet(), extensions.toSet(), since, until, timeField, minSize, maxSize, sort, summary)
    }

    // ---- キーワードの引用 ----

    private fun quoted() {
        text = QUOTED.replace(text) { m ->
            val k = (m.groups[1] ?: m.groups[2] ?: m.groups[3])!!.value.trim()
            if (k.isNotEmpty()) keywords += k
            " "
        }
    }

    // ---- 日時 ----

    private fun setTime(from: ZonedDateTime?, to: ZonedDateTime?, label: String) {
        if (timeLabel != null) return // 最初に見つかったものだけ使う
        since = from?.toInstant()?.toEpochMilli()
        until = to?.toInstant()?.toEpochMilli()
        timeLabel = label
    }

    private fun startOfDay(d: ZonedDateTime) = d.toLocalDate().atStartOfDay(now.zone)

    /** 「3日以内」「過去2週間」「5日前」「within 3 days」「2 weeks ago」 */
    private fun relativeTime() {
        text = RELATIVE_JA.replace(text) { m ->
            val n = parseNumber(m.groupValues[2]) ?: return@replace m.value
            val unit = unitOf(m.groupValues[3]) ?: return@replace m.value
            val suffix = m.groupValues[4]
            // 「15日」だけでは日付の一部かもしれないので、「以内」「前」「過去」などがあるときだけ
            if (suffix.isEmpty() && m.groupValues[1].isEmpty()) return@replace m.value
            applyRelative(n, unit, ago = suffix.startsWith("前") && !suffix.startsWith("前から") && !suffix.startsWith("前以降"), m.value.trim())
            " "
        }
        text = RELATIVE_EN.replace(text) { m ->
            val n = m.groupValues[2].toLongOrNull() ?: return@replace m.value
            val unit = unitOf(m.groupValues[3]) ?: return@replace m.value
            applyRelative(n, unit, ago = m.groupValues[4].isNotBlank(), m.value.trim())
            " "
        }
    }

    private fun applyRelative(n: Long, unit: TimeUnitWord, ago: Boolean, label: String) {
        if (ago && unit == TimeUnitWord.DAY) {
            val day = startOfDay(now.minusDays(n))
            setTime(day, day.plusDays(1), label)
        } else if (ago) {
            // 「2週間前」は、その前後1単位ずつ
            setTime(unit.minus(now, n + 1), unit.minus(now, n - 1), label)
        } else {
            setTime(unit.minus(now, n), null, label)
        }
    }

    private fun namedTime() {
        for ((pattern, compute) in namedTimes()) {
            val m = pattern.find(text) ?: continue
            val (from, to) = compute()
            // 「昨日から」「先週以降」は、その日から今まで
            val open = OPEN_END.find(text, m.range.last + 1)?.takeIf { it.range.first == m.range.last + 1 }
            val label = m.value.trim() + (open?.value ?: "")
            setTime(from, if (open != null) null else to, label)
            text = text.replaceRange(m.range.first, (open?.range?.last ?: m.range.last) + 1, " ")
            return
        }
    }

    private fun namedTimes(): List<Pair<Regex, () -> Pair<ZonedDateTime?, ZonedDateTime?>>> {
        val today = startOfDay(now)
        val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val month = today.withDayOfMonth(1)
        val year = today.withDayOfYear(1)
        return listOf(
            Regex("一昨日|おととい|day before yesterday") to { today.minusDays(2) to today.minusDays(1) },
            Regex("今日|きょう|本日|today") to { today to null },
            Regex("昨日|きのう|yesterday") to { today.minusDays(1) to today },
            Regex("さっき|先ほど|先程|さきほど|just now") to { now.minusHours(3) to null },
            Regex("今週|this week") to { week to null },
            Regex("先週|last week") to { week.minusWeeks(1) to week },
            Regex("今月|this month") to { month to null },
            Regex("先月|last month") to { month.minusMonths(1) to month },
            Regex("今年|this year") to { year to null },
            Regex("去年|昨年|last year") to { year.minusYears(1) to year },
            Regex("最近|このあいだ|この間|recent(ly)?") to { now.minusDays(7) to null },
        )
    }

    /** 「2026/10/15」「10月15日」「10/15」「10月」 */
    private fun dates() {
        val full = FULL_DATE.find(text)
        if (full != null) {
            val d = runCatching { LocalDate.of(full.groupValues[1].toInt(), full.groupValues[2].toInt(), full.groupValues[3].toInt()) }.getOrNull()
            if (d != null) {
                val start = d.atStartOfDay(now.zone)
                setTime(start, start.plusDays(1), full.value.trim())
                text = text.replaceRange(full.range, " ")
                return
            }
        }
        val md = MONTH_DAY.find(text)
        if (md != null) {
            val month = md.groupValues[1].ifEmpty { md.groupValues[3] }.toInt()
            val day = md.groupValues[2].ifEmpty { md.groupValues[4] }.toInt()
            val d = runCatching { pastDate(month, day) }.getOrNull()
            if (d != null) {
                val start = d.atStartOfDay(now.zone)
                setTime(start, start.plusDays(1), md.value.trim())
                text = text.replaceRange(md.range, " ")
                return
            }
        }
        val mo = MONTH_ONLY.find(text)
        if (mo != null) {
            val month = mo.groupValues[1].toInt()
            if (month in 1..12) {
                var start = LocalDate.of(now.year, month, 1)
                if (start.isAfter(now.toLocalDate())) start = start.minusYears(1)
                val s = start.atStartOfDay(now.zone)
                setTime(s, s.plusMonths(1), mo.value.trim())
                text = text.replaceRange(mo.range, " ")
            }
        }
    }

    /** 年を書かない日付は、未来にならないよう去年のものとみなす */
    private fun pastDate(month: Int, day: Int): LocalDate {
        val d = LocalDate.of(now.year, month, day)
        return if (d.isAfter(now.toLocalDate())) d.minusYears(1) else d
    }

    /** 「アップロードした」「編集した」で、どの日時を見るかを決める */
    private fun timeVerbs() {
        val added = ADDED_WORDS.containsMatchIn(text)
        val modified = MODIFIED_WORDS.containsMatchIn(text)
        if (timeLabel != null) {
            timeField = when {
                added && !modified -> TimeField.ADDED
                modified && !added -> TimeField.MODIFIED
                else -> TimeField.ANY
            }
        }
        text = ADDED_WORDS.replace(text, " ")
        text = MODIFIED_WORDS.replace(text, " ")
    }

    // ---- サイズ・並び順 ----

    private fun sizes() {
        text = SIZE.replace(text) { m ->
            val value = m.groupValues[1].toDoubleOrNull() ?: return@replace m.value
            val bytes = (value * unitBytes(m.groupValues[2])).toLong()
            val cmp = m.groupValues[3]
            if (cmp.contains("以下") || cmp.contains("未満") || cmp.contains("小さ") || cmp.contains("under") || cmp.contains("less")) {
                maxSize = bytes
            } else {
                minSize = bytes
            }
            " "
        }
    }

    private fun sortWords() {
        val rules = listOf(
            Regex("大きい順|サイズ順|大きい|でかい|重い|" + en("largest|biggest|large|big")) to SearchSort.LARGEST,
            Regex("新しい順|新しい|最新|" + en("newest|latest")) to SearchSort.NEWEST,
            Regex("古い順|古い|" + en("oldest")) to SearchSort.OLDEST,
        )
        for ((pattern, s) in rules) {
            if (pattern.containsMatchIn(text)) {
                sort = s
                text = pattern.replace(text, " ")
                return
            }
        }
    }

    // ---- 種類 ----

    /** 「.md」「ext:md」と、拡張子そのものの単語（「mdかtxt」） */
    private fun explicitExtensions() {
        text = EXT_SYNTAX.replace(text) { m ->
            extensions += m.groupValues[1].ifEmpty { m.groupValues[2] }
            " "
        }
        text = BARE_EXT.replace(text) { m ->
            val word = m.groupValues[1]
            if (KNOWN_EXTENSIONS.contains(word)) {
                extensions += if (word == "jpeg") "jpg" else word
                " "
            } else {
                m.value
            }
        }
        if ("jpg" in extensions) extensions += "jpeg"
        if ("md" in extensions) extensions += "markdown"
    }

    private fun kindWords() {
        for ((kind, pattern) in KIND_WORDS) {
            if (pattern.containsMatchIn(text)) {
                kinds += kind
                text = pattern.replace(text, " ")
            }
        }
    }

    // ---- キーワード ----

    private fun stopWords() {
        text = STOP_PHRASES.replace(text, " ")
    }

    /**
     * 残った文字からキーワードを取り出す。日本語は分かち書きしないので、
     * ひらがなの並び（助詞・送り仮名）を区切りとみなし、漢字・カタカナ・英数字の並びをキーワードにする
     */
    private fun remainingKeywords() {
        for (chunk in text.split(SEPARATORS)) {
            if (chunk.isBlank()) continue
            val hasNonKana = chunk.any { !isHiragana(it) }
            if (!hasNonKana) {
                // ひらがなだけの言葉（「ねこ」）
                if (chunk.length >= 2 && chunk !in HIRAGANA_STOP) keywords += chunk
                continue
            }
            var i = 0
            while (i < chunk.length) {
                if (isHiragana(chunk[i])) {
                    i++
                    continue
                }
                val start = i
                while (i < chunk.length && !isHiragana(chunk[i])) i++
                val run = chunk.substring(start, i)
                val followedByKana = i < chunk.length
                // 「書いた」の「書」のような、動詞の漢字1文字は捨てる（「猫の」「犬と」のように助詞が続くなら残す）
                val verbStem = followedByKana && chunk[i] !in PARTICLES
                if (run.length == 1 && (verbStem || !run[0].isLetterOrDigit() || run[0] in 'a'..'z')) continue
                if (run in STOP_RUNS) continue
                keywords += run
            }
        }
        val seen = mutableSetOf<String>()
        keywords.retainAll { seen.add(normalizeForSearch(it)) }
    }

    private fun isHiragana(c: Char) = c in 'ぁ'..'ゟ'

    companion object {
        private const val PARTICLES = "のをがはにでともへやかよ"

        /** 英単語。Java の `\b` は日本語の文字も単語の一部とみなすので、英数字だけで区切りを判定する */
        private fun en(words: String) = "(?<![a-z0-9])(?:$words)(?![a-z0-9])"

        private val QUOTED = Regex("\"([^\"]+)\"|「([^」]+)」|『([^』]+)』")
        private val SEPARATORS = Regex("[\\s、。,.!?！？・/／()（）\\[\\]【】:;：；~〜\\-_+*'`]+")

        private const val NUM = "\\d+|[一二三四五六七八九十百]+"
        private val RELATIVE_JA = Regex(
            "(ここ|過去|直近|最近の?)?\\s*($NUM)\\s*(分|時間|日|週間|週|か月|ヶ月|ヵ月|カ月|ケ月|箇月|年)\\s*(以内|前から|前以降|前|ぐらい|くらい|ほど)?",
        )
        private val RELATIVE_EN = Regex(
            "(within|in the last|last|past)?\\s*(\\d+)\\s*(minutes?|mins?|hours?|days?|weeks?|months?|years?)(?![a-z])\\s*(ago)?",
        )
        private val OPEN_END = Regex("\\s*(から|以降|以後|since)")
        private val FULL_DATE = Regex("(\\d{4})\\s*[/年.-]\\s*(\\d{1,2})\\s*[/月.-]\\s*(\\d{1,2})\\s*日?")
        private val MONTH_DAY = Regex("(\\d{1,2})月(\\d{1,2})日|(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?![\\d/])")
        private val MONTH_ONLY = Regex("(?<![\\dか])(\\d{1,2})月(?!\\d)")

        private val ADDED_WORDS = Regex(
            "アップロード|アップ|追加|入れ|いれ|置い|おい(た|て)|保存|コピー|送っ|" + en("upload(ed)?|added|saved|copied|put"),
        )
        private val MODIFIED_WORDS = Regex("更新|編集|変更|修正|書い|作っ|作成|いじっ|" + en("modified|edited|updated|changed|wrote|created"))

        private val SIZE = Regex(
            "(\\d+(?:\\.\\d+)?)\\s*(kb|mb|gb|tb|キロ|メガ|ギガ|テラ|k|m|g)(?:バイト|b)?\\s*(以上|超え|超|より大きい|以下|未満|より小さい|over|above|more than|under|less than|below)?",
        )

        private val EXT_SYNTAX = Regex("(?<![a-z0-9])\\.([a-z0-9]{1,8})(?![a-z0-9])|ext:([a-z0-9]{1,8})")
        private val BARE_EXT = Regex("(?<![a-z0-9])([a-z0-9]{2,8})(?![a-z0-9])")
        private val KNOWN_EXTENSIONS = FileKind.entries.flatMap { it.extensions }.toSet() - setOf("text", "key", "pages", "numbers") +
            setOf("json", "html", "xml", "yaml", "yml", "kt", "ts", "js", "py", "apk", "iso")

        /** カタカナ語は、前後にカタカナが続くときは別の言葉（「メモリ」の「メモ」）なので除く */
        private fun katakana(word: String) = "(?<![\\p{InKatakana}])$word(?![\\p{InKatakana}])"

        private val KIND_WORDS: List<Pair<FileKind, Regex>> = listOf(
            FileKind.TEXT to Regex(listOf(katakana("メモ"), katakana("ノート"), katakana("テキスト"), katakana("マークダウン"), en("markdown|notes?|text")).joinToString("|")),
            FileKind.PDF to Regex(en("pdf")),
            FileKind.IMAGE to Regex(listOf("画像", "写真", katakana("イメージ"), katakana("スクショ"), katakana("スクリーンショット"), katakana("フォト"), en("images?|photos?|pictures?|screenshots?")).joinToString("|")),
            FileKind.VIDEO to Regex(listOf("動画", katakana("ビデオ"), katakana("ムービー"), en("videos?|movies?")).joinToString("|")),
            FileKind.AUDIO to Regex(listOf("音楽", "音声", "録音", katakana("オーディオ"), en("audio|music|songs?")).joinToString("|")),
            FileKind.ARCHIVE to Regex(listOf("圧縮", katakana("アーカイブ"), en("archives?")).joinToString("|")),
            FileKind.DOCUMENT to Regex(listOf(katakana("ワード"), "文書", katakana("ドキュメント"), en("word|documents?")).joinToString("|")),
            FileKind.SPREADSHEET to Regex(listOf(katakana("エクセル"), "表計算", katakana("スプレッドシート"), en("excel|spreadsheets?")).joinToString("|")),
            FileKind.PRESENTATION to Regex(listOf(katakana("パワポ"), katakana("スライド"), katakana("プレゼン"), en("powerpoint|slides?")).joinToString("|")),
            FileKind.FOLDER to Regex(listOf(katakana("フォルダー?"), katakana("ディレクトリ"), en("folders?|director(y|ies)")).joinToString("|")),
        )

        private val STOP_PHRASES = Regex(
            listOf(
                "ありますか", "ありませんか", "ありました", "あります", "あったっけ", "あった", "ある",
                "わかりますか", "分かりますか", "わかる", "分かる", "知りませんか", "知ってる",
                "どこにある", "どこか", "どこ", "どれ", "どの", "教えてください", "教えて", "おしえて",
                "探してください", "探して", "さがして", "探す", "見つけて", "みつけて", "出して", "見せて", "ください",
                "したのですが", "したんですが", "したけど", "したやつ", "したもの", "した", "して", "しました",
                "のですが", "んですが", "ですが", "ですか", "です", "けど", "だっけ", "かな",
                "こんな", "あんな", "そんな", "みたいな", "ような", "っぽい", "らしい", "について", "に関する", "関係",
                katakana("ファイル"), katakana("データ"), "もの", "やつ", "たしか", "確か", "なんか", "何か", "なにか", "前に", "以前",
                en("find|search|show|where|which|what|is|are|was|were|the|a|an|my|me|i|did|do|any|there|that|with|of|in|on|for|from|to|files?"),
            ).joinToString("|"),
        )
        private val HIRAGANA_STOP = setOf("これ", "それ", "あれ", "ここ", "そこ", "あそこ", "なに", "なん", "とか", "など", "まで", "から", "より")
        private val STOP_RUNS = setOf("私", "僕", "俺", "自分", "前", "中", "以内")

        private fun parseNumber(s: String): Long? = s.toLongOrNull() ?: kanjiNumber(s)

        private fun kanjiNumber(s: String): Long? {
            val digits = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
            var total = 0L
            var current = 0L
            for (c in s) {
                when (c) {
                    in digits -> current = digits[c]!!.toLong()
                    '十' -> { total += (if (current == 0L) 1 else current) * 10; current = 0 }
                    '百' -> { total += (if (current == 0L) 1 else current) * 100; current = 0 }
                    else -> return null
                }
            }
            return (total + current).takeIf { it > 0 }
        }

        private fun unitOf(s: String): TimeUnitWord? = when {
            s == "分" || s.startsWith("min") -> TimeUnitWord.MINUTE
            s == "時間" || s.startsWith("hour") -> TimeUnitWord.HOUR
            s == "日" || s.startsWith("day") -> TimeUnitWord.DAY
            s.startsWith("週") || s.startsWith("week") -> TimeUnitWord.WEEK
            s.endsWith("月") || s.startsWith("month") -> TimeUnitWord.MONTH
            s == "年" || s.startsWith("year") -> TimeUnitWord.YEAR
            else -> null
        }

        private fun unitBytes(s: String): Double = when (s) {
            "kb", "k", "キロ" -> 1024.0
            "mb", "m", "メガ" -> 1024.0 * 1024
            "gb", "g", "ギガ" -> 1024.0 * 1024 * 1024
            "tb", "テラ" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }

        fun formatSize(bytes: Long): String = when {
            bytes >= 1L shl 40 -> "%.0fTB".format(bytes / (1L shl 40).toDouble())
            bytes >= 1L shl 30 -> "%.0fGB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "%.0fMB".format(bytes / (1L shl 20).toDouble())
            else -> "%.0fKB".format(bytes / 1024.0)
        }
    }
}

private enum class TimeUnitWord {
    MINUTE, HOUR, DAY, WEEK, MONTH, YEAR;

    fun minus(t: ZonedDateTime, n: Long): ZonedDateTime = when (this) {
        MINUTE -> t.minusMinutes(n)
        HOUR -> t.minusHours(n)
        DAY -> t.minusDays(n)
        WEEK -> t.minusWeeks(n)
        MONTH -> t.minusMonths(n)
        YEAR -> t.minusYears(n)
    }
}
