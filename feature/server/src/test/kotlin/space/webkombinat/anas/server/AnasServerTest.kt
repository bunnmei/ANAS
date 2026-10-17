package space.webkombinat.anas.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.webkombinat.anas.core.model.LocalFileBackend
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AnasServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var disk: File
    private lateinit var backend: LocalFileBackend
    private lateinit var registry: ExtensionRegistry
    private var config = ServerConfig()
    private val assets = AssetSource { path ->
        mapOf("index.html" to "<h1>NAS</h1>", "sdk.js" to "export const anas = {}")[path]?.byteInputStream()
    }

    @Before
    fun setUp() {
        disk = tmp.newFolder("disk1")
        backend = LocalFileBackend(mapOf("disk1" to disk))
        registry = ExtensionRegistry(backend)
    }

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { anasModule(backend, registry, assets, { config }) }
        block()
    }

    private fun installJisui(scope: String = "self") {
        File(disk, ".jisui/app").mkdirs()
        File(disk, ".jisui/data/books").mkdirs()
        File(disk, ".jisui/anas.json").writeText("""{"anas":1,"id":"jisui","name":"自炊","scope":"$scope"}""")
        File(disk, ".jisui/app/index.html").writeText("<h1>jisui</h1>")
        File(disk, ".jisui/data/books/a.pdf").writeText("PDFDATA")
        runBlocking { registry.scan() }
    }

    @Test
    fun servesWebUiAndSdk() = test {
        assertEquals("<h1>NAS</h1>", client.get("/").bodyAsText())
        assertEquals("export const anas = {}", client.get("/_anas/sdk.js").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/nothing.js").status)
    }

    @Test
    fun uploadListDownloadWithRange() = test {
        val up = client.put("/api/v1/fs/disk1/file?path=/docs/本.txt&mkdirs=1") { setBody("0123456789") }
        assertEquals(HttpStatusCode.Created, up.status)

        val list = client.get("/api/v1/fs/disk1/list?path=/docs").bodyAsText()
        assertTrue(list, list.contains("\"name\":\"本.txt\"") && list.contains("\"path\":\"/docs/本.txt\""))

        val full = client.get("/api/v1/fs/disk1/file?path=/docs/本.txt")
        assertEquals("0123456789", full.bodyAsText())
        assertEquals("sandbox", full.headers["Content-Security-Policy"])

        val part = client.get("/api/v1/fs/disk1/file?path=/docs/本.txt") { header(HttpHeaders.Range, "bytes=2-4") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals("234", part.bodyAsText())
        assertEquals("bytes 2-4/10", part.headers[HttpHeaders.ContentRange])

        val bad = client.get("/api/v1/fs/disk1/file?path=/docs/本.txt") { header(HttpHeaders.Range, "bytes=50-") }
        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, bad.status)

        val dup = client.put("/api/v1/fs/disk1/file?path=/docs/本.txt") { setBody("x") }
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertEquals(HttpStatusCode.Created, client.put("/api/v1/fs/disk1/file?path=/docs/本.txt&overwrite=1") { setBody("x") }.status)
    }

    @Test
    fun mkdirRenameDelete() = test {
        suspend fun post(path: String, body: String) = client.post(path) {
            contentType(ContentType.Application.Json); setBody(body)
        }
        assertEquals(HttpStatusCode.OK, post("/api/v1/fs/disk1/mkdir", """{"path":"/a/b"}""").status)
        assertTrue(File(disk, "a/b").isDirectory)
        assertEquals(HttpStatusCode.OK, post("/api/v1/fs/disk1/rename", """{"path":"/a/b","newName":"c"}""").status)
        assertTrue(File(disk, "a/c").isDirectory)
        assertEquals(HttpStatusCode.BadRequest, post("/api/v1/fs/disk1/rename", """{"path":"/a/c","newName":"x:y"}""").status)
        assertEquals(HttpStatusCode.OK, post("/api/v1/fs/disk1/delete", """{"path":"/a"}""").status)
        assertFalse(File(disk, "a").exists())
    }

    @Test
    fun rejectsTraversal() = test {
        File(tmp.root, "secret.txt").writeText("secret")
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/fs/disk1/file?path=/../secret.txt").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/fs/disk1/file?path=..%2Fsecret.txt").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/fs/nodisk/list").status)
    }

    @Test
    fun readOnlyModeBlocksWrites() = test {
        config = ServerConfig(readOnly = true)
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/fs/disk1/file?path=/x.txt") { setBody("x") }.status)
        assertFalse(File(disk, "x.txt").exists())
    }

    @Test
    fun passwordProtectsApiAndExtensions() = test {
        installJisui()
        config = ServerConfig(password = "pass")
        val noCookies = createClient { }
        assertEquals(HttpStatusCode.Unauthorized, noCookies.get("/api/v1/volumes").status)
        assertTrue(noCookies.get("/api/v1/info").bodyAsText().contains("\"authenticated\":false"))
        assertEquals(HttpStatusCode.OK, noCookies.get("/").status)

        val noRedirect = createClient { followRedirects = false }
        val page = noRedirect.get("/x/jisui/")
        assertEquals(HttpStatusCode.Found, page.status)
        assertTrue(page.headers[HttpHeaders.Location]!!.startsWith("/?next="))

        val wrong = noCookies.post("/api/v1/login") { contentType(ContentType.Application.Json); setBody("""{"password":"no"}""") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        val ok = noCookies.post("/api/v1/login") { contentType(ContentType.Application.Json); setBody("""{"password":"pass"}""") }
        val cookie = ok.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
        assertEquals(HttpStatusCode.OK, noCookies.get("/api/v1/volumes") { header(HttpHeaders.Cookie, cookie) }.status)

        // パスワードを変えると古いセッションは無効
        config = ServerConfig(password = "new")
        assertEquals(HttpStatusCode.Unauthorized, noCookies.get("/api/v1/volumes") { header(HttpHeaders.Cookie, cookie) }.status)
    }

    @Test
    fun extensionServesUiWithCspAndScopedApi() = test {
        installJisui()
        val noRedirect = createClient { followRedirects = false }
        assertEquals("/x/jisui/", noRedirect.get("/x/jisui").headers[HttpHeaders.Location])

        val page = client.get("/x/jisui/") { header(HttpHeaders.Host, "192.168.1.5:8080") }
        assertEquals("<h1>jisui</h1>", page.bodyAsText())
        val csp = page.headers["Content-Security-Policy"]!!
        assertTrue(csp, csp.contains("connect-src http://192.168.1.5:8080/x/jisui/"))

        // data/ が API のルートになる
        val list = client.get("/x/jisui/api/list?path=/books").bodyAsText()
        assertTrue(list, list.contains("\"path\":\"/books/a.pdf\""))
        assertEquals("PDFDATA", client.get("/x/jisui/api/file?path=/books/a.pdf").bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.get("/x/jisui/api/file?path=/../anas.json").status)

        assertEquals(HttpStatusCode.Created, client.put("/x/jisui/api/file?path=/books/b.pdf") { setBody("B") }.status)
        assertTrue(File(disk, ".jisui/data/books/b.pdf").exists())

        // NAS の一覧には拡張機能の情報が付く
        val nas = client.get("/api/v1/fs/disk1/list?path=/.jisui").bodyAsText()
        assertTrue(nas, nas.contains("\"url\":\"/x/jisui/\""))
    }

    @Test
    fun selfScopedExtensionCannotCallNasApi() = test {
        installJisui()
        val fromExt = client.get("/api/v1/volumes") { header(HttpHeaders.Referrer, "http://h/x/jisui/index.html") }
        assertEquals(HttpStatusCode.Forbidden, fromExt.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/volumes") { header(HttpHeaders.Referrer, "http://h/") }.status)
    }

    @Test
    fun fullAccessNeedsScopeAllAndPermission() = test {
        installJisui(scope = "all")
        val req: suspend HttpClient.() -> HttpStatusCode = {
            get("/api/v1/volumes") { header(HttpHeaders.Referrer, "http://h/x/jisui/") }.status
        }
        assertEquals(HttpStatusCode.Forbidden, client.req())
        config = ServerConfig(fullAccessExtensions = setOf("jisui"))
        assertEquals(HttpStatusCode.OK, client.req())
    }

    @Test
    fun disabledAndBrokenExtensions() = test {
        installJisui()
        File(disk, ".broken").mkdirs()
        File(disk, ".broken/anas.json").writeText("""{"anas":1,"id":"other","name":"x"}""")
        File(disk, ".Trash-1000").mkdirs() // anas.json がないので無視される
        registry.scan()
        val list = client.get("/api/v1/extensions").bodyAsText()
        assertTrue(list, list.contains("\"id\":\"broken\"") && list.contains("フォルダ名は"))
        assertFalse(list.contains("Trash"))
        assertEquals(HttpStatusCode.InternalServerError, client.get("/x/broken/").status)

        config = ServerConfig(disabledExtensions = setOf("jisui"))
        assertEquals(HttpStatusCode.NotFound, client.get("/x/jisui/").status)
    }

    @Test
    fun installsExtensionFromZip() = test {
        File(disk, ".kicad/data").mkdirs()
        File(disk, ".kicad/data/keep.kicad_sym").writeText("keep")
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                fun add(name: String, text: String) {
                    z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
                }
                add("kicad/anas.json", """{"anas":1,"id":"kicad","name":"KiCad"}""")
                add("kicad/app/index.html", "<h1>kicad</h1>")
                add("__MACOSX/kicad/._anas.json", "junk")
            }
        }.toByteArray()
        val res = client.post("/api/v1/extensions/install?volume=disk1") { setBody(zip) }
        assertEquals(res.bodyAsText(), HttpStatusCode.Created, res.status)
        assertEquals("<h1>kicad</h1>", client.get("/x/kicad/").bodyAsText())
        assertEquals("keep", File(disk, ".kicad/data/keep.kicad_sym").readText())
        assertFalse(File(disk, ".kicad/__MACOSX").exists())
    }

    @Test
    fun rejectsZipSlip() = test {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("anas.json")); z.write("""{"anas":1,"id":"evil","name":"x"}""".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("../../escape.txt")); z.write("x".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val res = client.post("/api/v1/extensions/install?volume=disk1") { setBody(zip) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertFalse(File(tmp.root, "escape.txt").exists())
        assertTrue(res.bodyAsBytes().isNotEmpty())
    }
}
