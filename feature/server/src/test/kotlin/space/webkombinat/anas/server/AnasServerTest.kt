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

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().decodeToString()
            }
        }
        return out
    }

    @Test
    fun downloadsFolderAsZip() = test {
        File(disk, "写真/2026/空").mkdirs()
        File(disk, "写真/a.txt").writeText("A")
        File(disk, "写真/2026/b.jpg").writeText("B")
        File(disk, "写真/.anas-upload-1.part").writeText("途中") // アップロード途中のファイルは入れない

        val res = client.get("/api/v1/fs/disk1/zip?path=/写真")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers[HttpHeaders.ContentDisposition]!!.contains("attachment"))
        val files = unzip(res.bodyAsBytes())
        assertEquals(
            mapOf("写真/2026/b.jpg" to "B", "写真/2026/空/" to "", "写真/a.txt" to "A"),
            files,
        )

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/fs/disk1/zip?path=/写真/a.txt").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/fs/disk1/zip?path=/ない").status)
        // ボリューム全体はボリューム ID の名前になる
        assertTrue(unzip(client.get("/api/v1/fs/disk1/zip").bodyAsBytes()).keys.all { it.startsWith("disk1/") })
    }

    @Test
    fun extensionCanZipItsDataOnly() = test {
        installJisui()
        val files = unzip(client.get("/x/jisui/api/zip?path=/books").bodyAsBytes())
        assertEquals(mapOf("books/a.pdf" to "PDFDATA"), files)
        val all = unzip(client.get("/x/jisui/api/zip").bodyAsBytes())
        assertTrue(all.keys.toString(), all.keys.all { it.startsWith("data/") } && "data/books/a.pdf" in all)
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
    fun streamsMediaByPathWithoutSandbox() = test {
        File(disk, "音楽/アルバム #1").mkdirs()
        File(disk, "音楽/アルバム #1/01 曲.mp3").writeText("0123456789")
        File(disk, "音楽/アルバム #1/note.txt").writeText("x")

        val full = client.get("/api/v1/fs/disk1/stream/%E9%9F%B3%E6%A5%BD/%E3%82%A2%E3%83%AB%E3%83%90%E3%83%A0%20%231/01%20%E6%9B%B2.mp3")
        assertEquals(HttpStatusCode.OK, full.status)
        assertEquals("audio/mpeg", full.headers[HttpHeaders.ContentType])
        // 音声・動画に sandbox を付けると、ブラウザ内蔵のプレーヤーが Range で読み直せない
        assertEquals(null, full.headers["Content-Security-Policy"])
        val part = client.get("/api/v1/fs/disk1/file?path=/音楽/アルバム %231/01 曲.mp3") { header(HttpHeaders.Range, "bytes=2-4") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals("234", part.bodyAsText())
        assertEquals(null, part.headers["Content-Security-Policy"])
        File(disk, "a.pdf").writeText("%PDF")
        assertEquals(null, client.get("/api/v1/fs/disk1/file?path=/a.pdf").headers["Content-Security-Policy"])
        // 音声・動画・PDF 以外は今まで通り sandbox
        assertEquals("sandbox", client.get("/api/v1/fs/disk1/stream/音楽/アルバム %231/note.txt").headers["Content-Security-Policy"])
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/fs/disk1/stream/%2E%2E/etc/passwd").status)
    }

    @Test
    fun playlistListsMediaWithAbsoluteStreamUrls() = test {
        File(disk, "音楽/b").mkdirs()
        File(disk, "音楽/02.mp3").writeText("x")
        File(disk, "音楽/01 #a.mp3").writeText("x")
        File(disk, "音楽/movie.mp4").writeText("x")
        File(disk, "音楽/cover.jpg").writeText("x")
        File(disk, "音楽/b/03.flac").writeText("x")

        val res = client.get("/api/v1/fs/disk1/playlist.m3u8?path=/音楽") { header(HttpHeaders.Host, "192.168.1.5:8080") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers[HttpHeaders.ContentType]!!.startsWith("audio/x-mpegurl"))
        val lines = res.bodyAsText().lines().filter { it.startsWith("http") }
        assertEquals(
            listOf(
                "http://192.168.1.5:8080/api/v1/fs/disk1/stream/%E9%9F%B3%E6%A5%BD/01%20%23a.mp3",
                "http://192.168.1.5:8080/api/v1/fs/disk1/stream/%E9%9F%B3%E6%A5%BD/02.mp3",
                "http://192.168.1.5:8080/api/v1/fs/disk1/stream/%E9%9F%B3%E6%A5%BD/movie.mp4",
            ),
            lines,
        )
        val audio = client.get("/api/v1/fs/disk1/playlist.m3u8?path=/音楽&recursive=1&type=audio").bodyAsText()
        assertTrue(audio, audio.contains("/b/03.flac") && !audio.contains("movie.mp4"))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/fs/disk1/playlist.m3u8?path=/音楽/02.mp3").status)
    }

    @Test
    fun extensionStreamsItsDataOnly() = test {
        installJisui()
        File(disk, ".jisui/data/a.mp3").writeText("AUDIO")
        File(disk, "secret.mp3").writeText("SECRET")
        assertEquals("AUDIO", client.get("/x/jisui/api/stream/a.mp3").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/x/jisui/api/stream/secret.mp3").status)
        val list = client.get("/x/jisui/api/playlist.m3u8") { header(HttpHeaders.Host, "nas:8080") }.bodyAsText()
        assertTrue(list, list.contains("http://nas:8080/x/jisui/api/stream/a.mp3") && !list.contains("books"))
    }

    @Test
    fun streamAcceptsBasicAuthForExternalPlayers() = test {
        config = ServerConfig(password = "pass")
        File(disk, "a.mp3").writeText("AUDIO")
        val noCookies = createClient { }
        val denied = noCookies.get("/api/v1/fs/disk1/stream/a.mp3")
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        assertTrue(denied.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Basic"))
        val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("any:pass".toByteArray())
        assertEquals("AUDIO", noCookies.get("/api/v1/fs/disk1/stream/a.mp3") { header(HttpHeaders.Authorization, basic) }.bodyAsText())
        assertEquals(HttpStatusCode.OK, noCookies.get("/api/v1/fs/disk1/playlist.m3u8?path=/") { header(HttpHeaders.Authorization, basic) }.status)
        // ほかの API は Basic では通さない（ブラウザにパスワードの入力欄を出さない）
        val other = noCookies.get("/api/v1/volumes") { header(HttpHeaders.Authorization, basic) }
        assertEquals(HttpStatusCode.Unauthorized, other.status)
        assertEquals(null, other.headers[HttpHeaders.WWWAuthenticate])
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String) = client.post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun movesAndCopiesWithinAndAcrossVolumes() {
        val disk2 = tmp.newFolder("disk2")
        backend = LocalFileBackend(mapOf("disk1" to disk, "disk2" to disk2))
        registry = ExtensionRegistry(backend)
        File(disk, "a/sub").mkdirs()
        File(disk, "a/sub/x.txt").writeText("X")
        File(disk, "a/y.txt").writeText("Y")
        File(disk, "b").mkdirs()
        test {
            // 同じボリュームの中で移動（フォルダごと）
            val moved = postJson("/api/v1/fs/disk1/move", """{"path":"/a/sub","toDir":"/b"}""")
            assertEquals(HttpStatusCode.OK, moved.status)
            assertTrue(moved.bodyAsText().contains("\"path\":\"/b/sub\""))
            assertEquals("X", File(disk, "b/sub/x.txt").readText())
            assertFalse(File(disk, "a/sub").exists())

            // コピー（元は残る）。同じ名前があれば 409
            assertEquals(HttpStatusCode.OK, postJson("/api/v1/fs/disk1/copy", """{"path":"/a/y.txt","toDir":"/b"}""").status)
            assertEquals("Y", File(disk, "a/y.txt").readText())
            assertEquals("Y", File(disk, "b/y.txt").readText())
            assertEquals(HttpStatusCode.Conflict, postJson("/api/v1/fs/disk1/copy", """{"path":"/a/y.txt","toDir":"/b"}""").status)

            // 自分の中へは移せない
            assertEquals(HttpStatusCode.BadRequest, postJson("/api/v1/fs/disk1/move", """{"path":"/b","toDir":"/b/sub"}""").status)
            assertEquals(HttpStatusCode.NotFound, postJson("/api/v1/fs/disk1/move", """{"path":"/a/y.txt","toDir":"/nothing"}""").status)

            // ほかのボリュームへ（コピーしてから消す）
            val across = postJson("/api/v1/fs/disk1/move", """{"path":"/b","toDir":"/","toVolume":"disk2"}""")
            assertEquals(HttpStatusCode.OK, across.status)
            assertEquals("X", File(disk2, "b/sub/x.txt").readText())
            assertEquals("Y", File(disk2, "b/y.txt").readText())
            assertFalse(File(disk, "b").exists())
        }
    }

    @Test
    fun extensionCannotMoveOutsideItsData() = test {
        installJisui()
        assertEquals(HttpStatusCode.OK, postJson("/x/jisui/api/copy", """{"path":"/books/a.pdf","toDir":"/"}""").status)
        assertEquals("PDFDATA", File(disk, ".jisui/data/a.pdf").readText())
        // パスはいつも data/ からなので、外へは出られない。ほかのボリュームも指定できない
        assertEquals(HttpStatusCode.BadRequest, postJson("/x/jisui/api/copy", """{"path":"/a.pdf","toDir":"/../.."}""").status)
        assertEquals(HttpStatusCode.BadRequest, postJson("/x/jisui/api/move", """{"path":"/a.pdf","toDir":"/","toVolume":"disk1"}""").status)
        assertTrue(File(disk, ".jisui/data/a.pdf").exists())
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
    fun homeExtensionReplacesRootOnlyWhenAllowed() = test {
        File(disk, ".home/app").mkdirs()
        File(disk, ".home/anas.json").writeText("""{"anas":1,"id":"home","name":"ホーム","scope":"all","home":true}""")
        File(disk, ".home/app/index.html").writeText("<h1>my home</h1>")
        // home: true なのに scope が self のものは読み込めない
        File(disk, ".bad/app").mkdirs()
        File(disk, ".bad/anas.json").writeText("""{"anas":1,"id":"bad","name":"x","home":true}""")
        registry.scan()
        val noRedirect = createClient { followRedirects = false }
        suspend fun root(query: String = "") = noRedirect.get("/$query")

        // 選んだだけ（NAS 全体へのアクセスを許可していない）では標準の画面
        config = ServerConfig(homeExtension = "home")
        assertEquals("<h1>NAS</h1>", root().bodyAsText())
        assertTrue(client.get("/api/v1/extensions").bodyAsText().contains("\"homeCapable\":true"))

        config = ServerConfig(homeExtension = "home", fullAccessExtensions = setOf("home"))
        val home = root()
        assertEquals(HttpStatusCode.Found, home.status)
        assertEquals("/x/home/", home.headers[HttpHeaders.Location])
        assertTrue(client.get("/api/v1/extensions").bodyAsText().contains("\"isHome\":true"))
        // 標準の画面はいつでも開ける
        assertEquals("<h1>NAS</h1>", root("?ui=default").bodyAsText())

        // 無効にすると標準の画面に戻る
        config = config.copy(disabledExtensions = setOf("home"))
        assertEquals("<h1>NAS</h1>", root().bodyAsText())

        // パスワードがあれば、ログインするまではログイン画面（標準の画面）
        config = ServerConfig(homeExtension = "home", fullAccessExtensions = setOf("home"), password = "pw")
        assertEquals("<h1>NAS</h1>", root().bodyAsText())

        // home: true でない拡張機能はホームにできない
        config = ServerConfig(homeExtension = "bad", fullAccessExtensions = setOf("bad"))
        assertEquals("<h1>NAS</h1>", root().bodyAsText())
        assertTrue(client.get("/api/v1/extensions").bodyAsText().contains("scope"))
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
