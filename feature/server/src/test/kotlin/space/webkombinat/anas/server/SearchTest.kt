package space.webkombinat.anas.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.webkombinat.anas.core.model.LocalFileBackend
import space.webkombinat.anas.core.model.SafePath
import java.io.File
import java.net.URLEncoder

class SearchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var disk: File
    private lateinit var raw: LocalFileBackend
    private lateinit var index: SearchIndex
    private lateinit var search: SearchService
    private lateinit var backend: IndexingStorageBackend
    private lateinit var registry: ExtensionRegistry
    private var now = DAY * 20_000 // 2024 年ごろ
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        disk = tmp.newFolder("disk1")
        raw = LocalFileBackend(mapOf("disk1" to disk))
        index = SearchIndex(File(tmp.root, "search.db").path)
        search = SearchService(raw, index) { now }
        backend = IndexingStorageBackend(raw, index) { now }
        registry = ExtensionRegistry(backend)
    }

    @After
    fun tearDown() = index.close()

    private fun file(path: String, text: String = "x", daysAgo: Int = 30): File =
        File(disk, path).apply {
            parentFile.mkdirs()
            writeText(text)
            setLastModified(now - daysAgo * DAY)
        }

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { anasModule(backend, registry, { null }, { ServerConfig() }, search = search) }
        block()
    }

    private suspend fun ApplicationTestBuilder.find(q: String, base: String = "/api/v1/search", extra: String = ""): SearchResponse {
        val res = client.get("$base?q=" + URLEncoder.encode(q, "UTF-8") + extra)
        assertEquals(res.bodyAsText(), HttpStatusCode.OK, res.status)
        return json.decodeFromString(res.bodyAsText())
    }

    private fun SearchResponse.paths() = hits.map { it.path }

    @Test
    fun scanFindsFilesByKeywordKindAndDate() = test {
        file("仕事/2026-議事録.md", daysAgo = 40)
        file("仕事/見積書.pdf", daysAgo = 2)
        file("写真/猫の写真.jpg", daysAgo = 1)
        file("メモリ増設.txt", daysAgo = 5)
        search.rescan()

        assertEquals(listOf("/仕事/2026-議事録.md"), find("仕事の議事録を探して").paths())
        // 初回の走査で見つけたものは、いつ追加されたか分からない
        assertNull(find("議事録").hits.single().added)

        val memo = find("こんなメモを書いたmdかtxtはありますか")
        assertEquals(setOf("/仕事/2026-議事録.md", "/メモリ増設.txt"), memo.paths().toSet())
        assertTrue(memo.query.summary.any { it.startsWith("種類") })

        assertEquals(listOf("/写真/猫の写真.jpg", "/仕事/見積書.pdf"), find("3日以内のファイル").paths())
        assertEquals(listOf("/写真/猫の写真.jpg"), find("猫の写真").paths())
        assertEquals(listOf("/仕事"), find("仕事フォルダ").paths())
        // フォルダ名に一致するものより、名前に一致するものが先
        file("仕事.txt", daysAgo = 100)
        search.rescan()
        assertEquals(setOf("/仕事", "/仕事.txt"), find("仕事").paths().take(2).toSet())
    }

    @Test
    fun uploadsAreRecordedAndFoundAsAdded() = test {
        file("old/report.pdf", daysAgo = 1) // 最近更新されたが、ANAS で追加したものではない
        search.rescan()
        now += DAY
        val put = client.put("/api/v1/fs/disk1/file?path=/inbox/scan.pdf&mkdirs=1") { setBody("PDF") }
        assertEquals(HttpStatusCode.Created, put.status)

        val res = find("3日以内にuploadしたのですがどこかわかりますか")
        assertEquals(TimeField.ADDED, res.query.timeField)
        assertEquals(listOf("/inbox/scan.pdf"), res.paths())
        assertEquals(now, res.hits.first().added)
        // 「3日以内」だけなら更新日時でも当たる
        assertEquals(setOf("/inbox/scan.pdf", "/old/report.pdf"), find("3日以内").paths().toSet())
        // フォルダと言えばフォルダも
        assertEquals(listOf("/inbox"), find("3日以内に追加したフォルダ").paths())
    }

    @Test
    fun renameAndDeleteUpdateTheIndex() = test {
        file("docs/a/設計メモ.md")
        search.rescan()
        client.post("/api/v1/fs/disk1/rename") {
            contentType(ContentType.Application.Json)
            setBody("""{"path":"/docs","newName":"資料"}""")
        }
        assertEquals(listOf("/資料/a/設計メモ.md"), find("設計").paths())
        assertEquals("/資料", find("資料フォルダ").paths().first())

        client.post("/api/v1/fs/disk1/delete") {
            contentType(ContentType.Application.Json)
            setBody("""{"path":"/資料/a"}""")
        }
        assertEquals(emptyList<String>(), find("設計").paths())
    }

    @Test
    fun rescanPicksUpDirectChanges() = test {
        file("keep.txt")
        file("gone.txt")
        search.rescan()
        File(disk, "gone.txt").delete()
        now += DAY
        file("copied/新しい資料.docx", daysAgo = 300) // PC から直接コピー（更新日時は古いまま）
        search.rescan()
        assertEquals(emptyList<String>(), find("gone").paths())
        val added = find("昨日から追加した文書")
        assertEquals(listOf("/copied/新しい資料.docx"), added.paths())
        assertEquals(now, added.hits.single().added)
        val status = json.decodeFromString<SearchStatus>(client.get("/api/v1/search/status").bodyAsText())
        assertEquals(3L, status.volumes.single().files) // keep.txt・copied・copied/新しい資料.docx
    }

    @Test
    fun fuzzyMatchWhenNothingMatchesExactly() = test {
        file("2026-議事録.md")
        file("請求書_株式会社サンプル.pdf")
        search.rescan()
        val typo = find("議事禄")
        assertTrue(typo.fuzzy)
        assertEquals(listOf("/2026-議事録.md"), typo.paths())
        assertEquals(listOf("/請求書_株式会社サンプル.pdf"), find("株式会社サンプリ").paths())
        assertEquals(emptyList<String>(), find("まったく関係ない").paths())
    }

    @Test
    fun hiddenFilesAndExtensionScope() = test {
        File(disk, ".jisui/app").mkdirs()
        File(disk, ".jisui/anas.json").writeText("""{"anas":1,"id":"jisui","name":"自炊"}""")
        File(disk, ".jisui/app/index.html").writeText("x")
        file(".jisui/data/books/吾輩は猫である.pdf")
        file(".jisui/data/.covers/吾輩は猫である.jpg")
        file("猫.pdf")
        registry.scan()
        search.rescan()

        assertEquals(listOf("/猫.pdf"), find("猫").paths())
        assertEquals(3, find("猫", extra = "&hidden=1").hits.size)
        // 拡張機能からは自分の data/ の中だけ、data/ からのパスで
        val ext = find("猫", base = "/x/jisui/api/search")
        assertEquals(listOf("/books/吾輩は猫である.pdf"), ext.paths())
        assertEquals(2, find("猫", base = "/x/jisui/api/search", extra = "&hidden=1").hits.size)
        // ボリュームごと
        assertEquals(listOf("/猫.pdf"), find("猫pdf", base = "/api/v1/fs/disk1/search").paths())
    }

    @Test
    fun indexMoveKeepsAddedTime() = runBlocking {
        val entry = raw.write("disk1", SafePath.ROOT, "a.txt", false) { it.write(1) }
        index.recordWrite("disk1", entry, 123)
        index.move("disk1", SafePath.parse("/a.txt"), SafePath.parse("/b.txt"))
        val hit = index.search(SearchQuery.parse("b.txt"), SearchScope(onlineVolumes = setOf("disk1")), 10).hits.single()
        assertEquals("/b.txt", hit.path)
        assertEquals(123L, hit.added)
        assertFalse(index.search(SearchQuery.parse("a.txt"), SearchScope(onlineVolumes = setOf("disk1")), 10).hits.any { it.path == "/a.txt" })
    }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
    }
}
