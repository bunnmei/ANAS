package space.webkombinat.anas.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SearchQueryTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    /** 2026-10-18（日）15:00 */
    private val now = ZonedDateTime.of(2026, 10, 18, 15, 0, 0, 0, zone)
    private fun parse(q: String) = SearchQuery.parse(q, now.toInstant().toEpochMilli(), zone)
    private fun at(y: Int, m: Int, d: Int, h: Int = 0) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun uploadedWithinDays() {
        val q = parse("3日以内にuploadしたのですがどこかわかりますか")
        assertEquals(now.minusDays(3).toInstant().toEpochMilli(), q.since)
        assertNull(q.until)
        assertEquals(TimeField.ADDED, q.timeField)
        assertEquals(emptyList<String>(), q.keywords)
        assertEquals(listOf("追加: 3日以内"), q.summary)
    }

    @Test
    fun memoInMdOrTxt() {
        val q = parse("こんなメモを書いたmdかtxtはありますか？")
        assertEquals(setOf(FileKind.TEXT), q.kinds)
        assertTrue(q.extensions.containsAll(setOf("md", "txt")))
        assertEquals(emptyList<String>(), q.keywords)
        assertNull(q.since)
    }

    @Test
    fun keywordsAreSplitOnParticles() {
        assertEquals(listOf("仕事", "議事録"), parse("仕事の議事録を探して").keywords)
        val cat = parse("猫の写真")
        assertEquals(listOf("猫"), cat.keywords)
        assertEquals(setOf(FileKind.IMAGE), cat.kinds)
        assertEquals(listOf("ねこ"), parse("ねこ").keywords)
        assertEquals(listOf("見積書", "株式会社abc"), parse("「見積書」 株式会社ABCのファイル").keywords)
        // 「メモリ」は「メモ」ではない
        val mem = parse("メモリの資料")
        assertEquals(emptySet<FileKind>(), mem.kinds)
        assertEquals(listOf("メモリ", "資料"), mem.keywords)
    }

    @Test
    fun namedPeriods() {
        val y = parse("昨日編集したエクセル")
        assertEquals(at(2026, 10, 17), y.since)
        assertEquals(at(2026, 10, 18), y.until)
        assertEquals(TimeField.MODIFIED, y.timeField)
        assertEquals(setOf(FileKind.SPREADSHEET), y.kinds)

        val fromYesterday = parse("昨日から追加した")
        assertEquals(at(2026, 10, 17), fromYesterday.since)
        assertNull(fromYesterday.until)
        assertEquals(TimeField.ADDED, fromYesterday.timeField)

        val lastWeek = parse("先週のPDF")
        assertEquals(at(2026, 10, 5), lastWeek.since) // 月曜始まり
        assertEquals(at(2026, 10, 12), lastWeek.until)
        assertTrue("pdf" in lastWeek.allExtensions)

        val lastYear = parse("去年の確定申告")
        assertEquals(at(2025, 1, 1), lastYear.since)
        assertEquals(at(2026, 1, 1), lastYear.until)
        assertEquals(listOf("確定申告"), lastYear.keywords)
    }

    @Test
    fun datesAndAgo() {
        val d = parse("10月15日の写真")
        assertEquals(at(2026, 10, 15), d.since)
        assertEquals(at(2026, 10, 16), d.until)
        // 未来の日付は去年
        assertEquals(at(2025, 12, 24), parse("12/24").since)
        assertEquals(at(2026, 3, 1), parse("2026/3/1").since)
        assertEquals(at(2025, 11, 1), parse("11月の動画").since)
        assertEquals(at(2026, 10, 16), parse("2日前").since)
        assertEquals(now.minusWeeks(2).toInstant().toEpochMilli(), parse("過去二週間").since)
        assertEquals(now.minusDays(5).toInstant().toEpochMilli(), parse("files uploaded within 5 days").since)
        // 「15日」だけなら期間ではない
        assertNull(parse("15日").since)
    }

    @Test
    fun sizesAndSort() {
        val q = parse("1.5GB以上の動画 大きい順")
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), q.minSize)
        assertEquals(setOf(FileKind.VIDEO), q.kinds)
        assertEquals(SearchSort.LARGEST, q.sort)
        assertEquals(500L * 1024, parse("500KB未満").maxSize)
        assertEquals(SearchSort.NEWEST, parse("最新のスクショ").sort)
    }

    @Test
    fun englishAndExtensions() {
        val q = parse("photos of beach .heic")
        assertEquals(setOf(FileKind.IMAGE), q.kinds)
        assertTrue("heic" in q.extensions)
        assertEquals(listOf("beach"), q.keywords)
        assertEquals(listOf("output"), parse("output").keywords)
        assertEquals(listOf("readme"), parse("README").keywords)
    }

    @Test
    fun normalize() {
        assertEquals(normalizeForSearch("ネコ"), normalizeForSearch("ねこ"))
        assertEquals("abc123", normalizeForSearch("ＡＢＣ１２３"))
    }
}
