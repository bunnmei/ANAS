package space.webkombinat.anas.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.io.File
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

class OutboundTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var disk: File
    private lateinit var backend: LocalFileBackend
    private lateinit var registry: ExtensionRegistry
    private lateinit var upstream: MockWebServer
    private lateinit var origin: String
    private var config = ServerConfig()
    private val assets = AssetSource { null }

    /** テストでは localhost のサーバーに繋ぐので、http とループバックを許す */
    private var outbound = OutboundHttp(allowHttp = true, isAllowedAddress = { true })

    @Before
    fun setUp() {
        disk = tmp.newFolder("disk1")
        backend = LocalFileBackend(mapOf("disk1" to disk))
        registry = ExtensionRegistry(backend)
        upstream = MockWebServer().also { it.start() }
        origin = "https://${upstream.hostName}:${upstream.port}"
    }

    @After
    fun tearDown() = upstream.close()

    private fun test(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { anasModule(backend, registry, assets, { config }, outbound = outbound) }
        block()
    }

    private fun install(network: List<String> = listOf(origin), granted: List<String> = network) {
        File(disk, ".jisui/app").mkdirs()
        File(disk, ".jisui/data").mkdirs()
        val list = network.joinToString(",") { "\"$it\"" }
        File(disk, ".jisui/anas.json").writeText("""{"anas":1,"id":"jisui","name":"自炊","network":[$list]}""")
        File(disk, ".jisui/app/index.html").writeText("<h1>jisui</h1>")
        runBlocking { registry.scan() }
        config = config.copy(networkGrants = mapOf("jisui" to granted.toSet()))
    }

    private fun url(path: String) = upstream.url(path).toString()

    private suspend fun ApplicationTestBuilder.post(op: String, body: String): HttpResponse =
        client.post("/x/jisui/api/$op") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun MockResponse.Builder.enqueue() = upstream.enqueue(build())

    @Test
    fun fetchNeedsDeclarationAndPermission() = test {
        install(granted = emptyList())
        val res = post("fetch", """{"url":"${url("/")}"}""")
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertTrue(res.bodyAsText().contains("\"code\":\"network\""))

        // 許可したあとに anas.json に増えたホストは、許可し直すまで使えない
        install(network = listOf(origin, "https://other.example"), granted = listOf("https://other.example"))
        assertEquals(HttpStatusCode.Forbidden, post("fetch", """{"url":"${url("/")}"}""").status)
        assertEquals(0, upstream.requestCount)
    }

    @Test
    fun fetchReturnsUpstreamBodyStatusAndHeaders() = test {
        install()
        MockResponse.Builder().code(404).addHeader("Content-Type", "text/html; charset=utf-8")
            .addHeader("X-Title", "a b").addHeader("Set-Cookie", "secret=1").body("<h1>nope</h1>").enqueue()
        val res = client.post("/x/jisui/api/fetch") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Cookie, "anas_session=browser")
            setBody("""{"url":"${url("/dp/123?x=1")}","headers":{"Accept-Language":"ja","Host":"evil"}}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("404", res.headers["X-Anas-Status"])
        assertEquals(url("/dp/123?x=1"), res.headers["X-Anas-Url"])
        assertEquals("sandbox", res.headers["Content-Security-Policy"])
        assertEquals("<h1>nope</h1>", res.bodyAsText())
        val headers = URLDecoder.decode(res.headers["X-Anas-Headers"]!!, "UTF-8")
        assertTrue(headers, headers.contains("\"x-title\":\"a b\""))
        assertFalse(headers, headers.contains("secret"))

        val recorded = upstream.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/dp/123?x=1", recorded.target)
        assertEquals("ja", recorded.headers["Accept-Language"])
        assertEquals("ANAS/dev", recorded.headers["User-Agent"])
        assertNull("ブラウザの Cookie は外部に送らない", recorded.headers["Cookie"])
        assertEquals("${upstream.hostName}:${upstream.port}", recorded.headers["Host"])
    }

    @Test
    fun fetchSendsBody() = test {
        install()
        MockResponse.Builder().body("ok").enqueue()
        MockResponse.Builder().body("ok").enqueue()
        post("fetch", """{"url":"${url("/a")}","method":"POST","headers":{"Content-Type":"application/json"},"body":"{\"q\":1}"}""")
        val first = upstream.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", first.method)
        assertEquals("{\"q\":1}", first.body?.utf8())
        assertTrue(first.headers["Content-Type"]!!.startsWith("application/json"))

        post("fetch", """{"url":"${url("/b")}","method":"PUT","bodyBase64":"AAEC"}""")
        assertEquals(listOf<Byte>(0, 1, 2), upstream.takeRequest(1, TimeUnit.SECONDS)!!.body?.toByteArray()?.toList())

        assertEquals(HttpStatusCode.BadRequest, post("fetch", """{"url":"${url("/")}","body":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("fetch", """{"url":"${url("/")}","method":"CONNECT"}""").status)
    }

    @Test
    fun rejectsOtherHostsAndRedirectsToThem() = test {
        install()
        assertEquals(HttpStatusCode.Forbidden, post("fetch", """{"url":"https://example.com/"}""").status)
        assertEquals(HttpStatusCode.Forbidden, post("fetch", """{"url":"http://user:pw@${upstream.hostName}:${upstream.port}/"}""").status)

        // 許可したホストの中のリダイレクトはたどる
        MockResponse.Builder().code(302).addHeader("Location", "/final").enqueue()
        MockResponse.Builder().body("done").enqueue()
        val ok = post("fetch", """{"url":"${url("/start")}"}""")
        assertEquals("done", ok.bodyAsText())
        assertEquals(url("/final"), ok.headers["X-Anas-Url"])

        // 許可していないホストへのリダイレクトは止める
        MockResponse.Builder().code(301).addHeader("Location", "https://evil.example/").enqueue()
        val res = post("fetch", """{"url":"${url("/go")}"}""")
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertTrue(res.bodyAsText().contains("evil.example"))
    }

    @Test
    fun blocksPrivateAddressesByDefault() {
        outbound = OutboundHttp(allowHttp = true) // 本番と同じアドレスの判定
        test {
            install()
            val res = post("fetch", """{"url":"${url("/")}"}""")
            assertEquals(HttpStatusCode.Forbidden, res.status)
            assertTrue(res.bodyAsText(), res.bodyAsText().contains("LAN"))
            assertEquals(0, upstream.requestCount)
        }
    }

    @Test
    fun requiresJsonContentType() = test {
        install()
        val res = client.post("/x/jisui/api/fetch") {
            contentType(ContentType.Text.Plain)
            setBody("""{"url":"${url("/")}"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun fetchToFileSavesIntoData() = test {
        install()
        MockResponse.Builder().addHeader("Content-Type", "image/jpeg").body("JPEGDATA").enqueue()
        val res = post("fetch-to-file", """{"url":"${url("/cover.jpg")}","path":"/.covers/123.jpg"}""")
        assertEquals(HttpStatusCode.Created, res.status)
        val text = res.bodyAsText()
        assertTrue(text, text.contains("\"path\":\"/.covers/123.jpg\"") && text.contains("\"contentType\":\"image/jpeg\""))
        assertEquals("JPEGDATA", File(disk, ".jisui/data/.covers/123.jpg").readText())

        // 同名があれば外部にアクセスする前に 409
        assertEquals(HttpStatusCode.Conflict, post("fetch-to-file", """{"url":"${url("/x")}","path":"/.covers/123.jpg"}""").status)
        assertEquals(1, upstream.requestCount)

        // 外部サイトのエラーは保存しない
        MockResponse.Builder().code(500).body("error").enqueue()
        val failed = post("fetch-to-file", """{"url":"${url("/x")}","path":"/b.jpg"}""")
        assertEquals(HttpStatusCode.BadGateway, failed.status)
        assertTrue(failed.bodyAsText().contains("upstream_status"))
        assertFalse(File(disk, ".jisui/data/b.jpg").exists())

        // data/ の外には書けない
        assertEquals(HttpStatusCode.BadRequest, post("fetch-to-file", """{"url":"${url("/x")}","path":"/../anas.json"}""").status)

        config = config.copy(readOnly = true)
        assertEquals(HttpStatusCode.Forbidden, post("fetch-to-file", """{"url":"${url("/x")}","path":"/c.jpg"}""").status)
    }

    @Test
    fun originMatching() {
        val url = "https://m.media-amazon.com/images/a.jpg".toHttpUrl()
        assertTrue(OutboundHttp.originMatches("https://m.media-amazon.com", url))
        assertTrue(OutboundHttp.originMatches("https://*.media-amazon.com", url))
        assertTrue(OutboundHttp.originMatches("https://M.Media-Amazon.com", url))
        assertFalse(OutboundHttp.originMatches("https://*.amazon.com", url))
        assertFalse(OutboundHttp.originMatches("https://m.media-amazon.com:8443", url))
        assertFalse(OutboundHttp.originMatches("https://media-amazon.com", url))
        assertFalse(OutboundHttp.originMatches("https://m.media-amazon.com", "http://m.media-amazon.com/".toHttpUrl()))
        assertFalse(OutboundHttp.originMatches("https://*.media-amazon.com", "https://evilmedia-amazon.com/".toHttpUrl()))
    }

    @Test
    fun publicAddresses() {
        fun pub(ip: String) = NetworkGuard.isPublicAddress(InetAddress.getByName(ip))
        assertTrue(pub("8.8.8.8"))
        assertTrue(pub("2001:4860:4860::8888"))
        for (ip in listOf(
            "127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "::1", "fe80::1", "fd00::1", "::ffff:192.168.1.1", "2002:c0a8:0101::1", "64:ff9b::a00:1", "224.0.0.1",
        )) {
            assertFalse(ip, pub(ip))
        }
    }
}
