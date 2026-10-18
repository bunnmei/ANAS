package space.webkombinat.anas.server

import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.webkombinat.anas.core.model.LocalFileBackend
import java.io.File
import java.util.Base64

class WebDavTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var disk: File
    private lateinit var disk2: File
    private lateinit var backend: LocalFileBackend
    private var config = ServerConfig(webDavEnabled = true)

    @Before
    fun setUp() {
        disk = tmp.newFolder("disk1")
        disk2 = tmp.newFolder("disk2")
        backend = LocalFileBackend(mapOf("disk1" to disk, "disk2" to disk2))
    }

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { anasModule(backend, ExtensionRegistry(backend), { null }, { config }) }
        block()
    }

    private suspend fun ApplicationTestBuilder.dav(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse = client.request(path) {
        this.method = HttpMethod(method)
        for ((k, v) in headers) header(k, v)
        if (body != null) setBody(body)
    }

    @Test
    fun disabledByDefault() = test {
        config = ServerConfig()
        assertEquals(HttpStatusCode.NotFound, dav("PROPFIND", "/dav/").status)
    }

    @Test
    fun propfindListsVolumesAndFiles() = test {
        File(disk, "docs").mkdirs()
        File(disk, "docs/議事録 1.md").writeText("# hello")
        File(disk, "docs/.anas-upload-1.part").writeText("tmp")

        val root = dav("PROPFIND", "/dav/", headers = mapOf("Depth" to "1"))
        assertEquals(HttpStatusCode.MultiStatus, root.status)
        val rootXml = root.bodyAsText()
        assertTrue(rootXml, rootXml.contains("<D:href>/dav/disk1/</D:href>") && rootXml.contains("<D:href>/dav/disk2/</D:href>"))

        val docs = dav("PROPFIND", "/dav/disk1/docs", headers = mapOf("Depth" to "1")).bodyAsText()
        // 名前は URL エンコードされ、ファイルの大きさ・種類・更新日時が付く
        assertTrue(docs, docs.contains("<D:href>/dav/disk1/docs/</D:href>"))
        assertTrue(docs, docs.contains("/dav/disk1/docs/%E8%AD%B0%E4%BA%8B%E9%8C%B2%201.md</D:href>"))
        assertTrue(docs, docs.contains("<D:getcontentlength>7</D:getcontentlength>"))
        assertTrue(docs, docs.contains("<D:displayname>議事録 1.md</D:displayname>"))
        assertTrue(docs, docs.contains("<D:getlastmodified>"))
        assertFalse("書き込み中の一時ファイルは出さない", docs.contains("anas-upload"))

        val zero = dav("PROPFIND", "/dav/disk1/docs/", headers = mapOf("Depth" to "0")).bodyAsText()
        assertEquals(1, Regex("<D:response>").findAll(zero).count())
        assertEquals(HttpStatusCode.Forbidden, dav("PROPFIND", "/dav/disk1/", headers = mapOf("Depth" to "infinity")).status)
        assertEquals(HttpStatusCode.NotFound, dav("PROPFIND", "/dav/disk1/nothing", headers = mapOf("Depth" to "0")).status)
    }

    @Test
    fun putGetHeadDelete() = test {
        assertEquals(HttpStatusCode.Created, dav("PUT", "/dav/disk1/a.txt", "hello").status)
        assertEquals("hello", File(disk, "a.txt").readText())
        assertEquals(HttpStatusCode.NoContent, dav("PUT", "/dav/disk1/a.txt", "hello world").status)

        val get = dav("GET", "/dav/disk1/a.txt")
        assertEquals("hello world", get.bodyAsText())
        assertTrue(get.headers[HttpHeaders.ETag]!!.startsWith("\""))
        val range = dav("GET", "/dav/disk1/a.txt", headers = mapOf("Range" to "bytes=6-"))
        assertEquals(HttpStatusCode.PartialContent, range.status)
        assertEquals("world", range.bodyAsText())
        val head = dav("HEAD", "/dav/disk1/a.txt")
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals("11", head.headers[HttpHeaders.ContentLength])

        // 親フォルダがなければ 409
        assertEquals(HttpStatusCode.Conflict, dav("PUT", "/dav/disk1/no/b.txt", "x").status)
        assertEquals(HttpStatusCode.NoContent, dav("DELETE", "/dav/disk1/a.txt").status)
        assertFalse(File(disk, "a.txt").exists())
        assertEquals(HttpStatusCode.NotFound, dav("DELETE", "/dav/disk1/a.txt").status)
        assertEquals(HttpStatusCode.Forbidden, dav("DELETE", "/dav/disk1/").status)
    }

    @Test
    fun mkcolMoveCopy() = test {
        assertEquals(HttpStatusCode.Created, dav("MKCOL", "/dav/disk1/src").status)
        assertEquals(HttpStatusCode.MethodNotAllowed, dav("MKCOL", "/dav/disk1/src").status)
        assertEquals(HttpStatusCode.Conflict, dav("MKCOL", "/dav/disk1/x/y").status)
        dav("PUT", "/dav/disk1/src/a.txt", "A")
        dav("MKCOL", "/dav/disk1/src/sub")
        dav("PUT", "/dav/disk1/src/sub/b.txt", "B")

        // 同じフォルダ内の名前の変更
        val rename = dav("MOVE", "/dav/disk1/src/a.txt", headers = mapOf("Destination" to "http://h/dav/disk1/src/%E3%81%82.txt"))
        assertEquals(HttpStatusCode.Created, rename.status)
        assertEquals("A", File(disk, "src/あ.txt").readText())

        // フォルダごとコピー（別のボリュームへも）
        assertEquals(HttpStatusCode.Created, dav("COPY", "/dav/disk1/src/", headers = mapOf("Destination" to "http://h/dav/disk2/copy/")).status)
        assertEquals("B", File(disk2, "copy/sub/b.txt").readText())
        assertTrue(File(disk, "src/sub/b.txt").exists())

        // 別のフォルダへ移動。Overwrite: F で既存なら 412、T なら置き換え
        dav("MKCOL", "/dav/disk1/dst")
        dav("PUT", "/dav/disk1/dst/b.txt", "old")
        val noOverwrite = dav("MOVE", "/dav/disk1/src/sub/b.txt", headers = mapOf("Destination" to "/dav/disk1/dst/b.txt", "Overwrite" to "F"))
        assertEquals(HttpStatusCode.PreconditionFailed, noOverwrite.status)
        val overwrite = dav("MOVE", "/dav/disk1/src/sub/b.txt", headers = mapOf("Destination" to "/dav/disk1/dst/b.txt"))
        assertEquals(HttpStatusCode.NoContent, overwrite.status)
        assertEquals("B", File(disk, "dst/b.txt").readText())
        assertFalse(File(disk, "src/sub/b.txt").exists())

        // 自分の中へは移動できない
        assertEquals(HttpStatusCode.Forbidden, dav("MOVE", "/dav/disk1/src", headers = mapOf("Destination" to "/dav/disk1/src/sub/src")).status)
    }

    @Test
    fun lockAndProppatchForWindowsAndMac() = test {
        val options = dav("OPTIONS", "/dav/disk1/")
        assertEquals("1, 2", options.headers["DAV"])
        assertTrue(options.headers[HttpHeaders.Allow]!!.contains("PROPFIND"))

        // まだないファイルのロックは空のファイルを作る
        val lock = dav(
            "LOCK", "/dav/disk1/new.docx",
            """<?xml version="1.0"?><D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype><D:owner><D:href>me</D:href></D:owner></D:lockinfo>""",
        )
        assertEquals(HttpStatusCode.Created, lock.status)
        assertTrue(lock.headers["Lock-Token"]!!.startsWith("<opaquelocktoken:"))
        assertTrue(lock.bodyAsText().contains("<D:owner><D:href>me</D:href></D:owner>"))
        assertTrue(File(disk, "new.docx").exists())
        assertEquals(HttpStatusCode.NoContent, dav("UNLOCK", "/dav/disk1/new.docx", headers = mapOf("Lock-Token" to lock.headers["Lock-Token"]!!)).status)

        val patch = dav(
            "PROPPATCH", "/dav/disk1/new.docx",
            """<?xml version="1.0"?><D:propertyupdate xmlns:D="DAV:" xmlns:Z="urn:schemas-microsoft-com:"><D:set><D:prop><Z:Win32LastModifiedTime>Sun, 18 Oct 2026 07:00:00 GMT</Z:Win32LastModifiedTime></D:prop></D:set></D:propertyupdate>""",
        )
        assertEquals(HttpStatusCode.MultiStatus, patch.status)
        assertTrue(patch.bodyAsText(), patch.bodyAsText().contains("Win32LastModifiedTime") && patch.bodyAsText().contains("200 OK"))
    }

    @Test
    fun readOnlyAndAuth() = test {
        config = ServerConfig(webDavEnabled = true, webDavReadOnly = true, password = "secret")
        val noAuth = dav("PROPFIND", "/dav/", headers = mapOf("Depth" to "0"))
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)
        assertTrue(noAuth.headers[HttpHeaders.WWWAuthenticate]!!.startsWith("Basic"))

        fun basic(pw: String) = "Basic " + Base64.getEncoder().encodeToString("anyone:$pw".toByteArray())
        assertEquals(HttpStatusCode.Unauthorized, dav("PROPFIND", "/dav/", headers = mapOf("Depth" to "0", "Authorization" to basic("wrong"))).status)
        val ok = mapOf("Authorization" to basic("secret"))
        assertEquals(HttpStatusCode.MultiStatus, dav("PROPFIND", "/dav/", headers = ok + ("Depth" to "0")).status)

        // WebDAV だけ読み取り専用
        assertEquals(HttpStatusCode.Forbidden, dav("PUT", "/dav/disk1/a.txt", "x", ok).status)
        assertEquals(HttpStatusCode.Forbidden, dav("MKCOL", "/dav/disk1/d", headers = ok).status)
        assertFalse(File(disk, "a.txt").exists())
    }
}
