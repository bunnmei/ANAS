package space.webkombinat.anas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.webkombinat.anas.client.LanUrls

class LanUrlsTest {

    @Test
    fun normalizesServerUrls() {
        assertEquals("http://192.168.1.108:8080/", LanUrls.normalizeServer("http://192.168.1.108:8080"))
        assertEquals("http://192.168.1.108:8080/", LanUrls.normalizeServer(" 192.168.1.108 "))
        assertEquals("http://10.0.0.5:9000/", LanUrls.normalizeServer("10.0.0.5:9000/x/jisui/"))
        assertEquals("http://127.0.0.1:8080/", LanUrls.normalizeServer("http://127.0.0.1:8080/"))
        assertEquals("http://192.168.1.2:8080/", LanUrls.normalizeServer("１９２．１６８．１．２：８０８０"))
    }

    @Test
    fun rejectsNonLanServers() {
        assertNull(LanUrls.normalizeServer(""))
        assertNull(LanUrls.normalizeServer("https://example.com/"))
        assertNull(LanUrls.normalizeServer("8.8.8.8"))
        assertNull(LanUrls.normalizeServer("ftp://192.168.1.2/"))
        assertNull(LanUrls.normalizeServer("javascript:alert(1)"))
    }

    @Test
    fun allowsOnlyLanPages() {
        assertTrue(LanUrls.isLan("http://192.168.1.108:8080/x/notes/#/a.md"))
        assertTrue(LanUrls.isLan("http://172.16.0.1/"))
        assertFalse(LanUrls.isLan("https://cdn.jsdelivr.net/npm/x.js"))
        assertFalse(LanUrls.isLan("http://nas.example.com/"))
        assertFalse(LanUrls.isLan("file:///sdcard/a.html"))
        assertFalse(LanUrls.isLan("data:text/html,hi"))
    }

    @Test
    fun readsFileNames() {
        assertEquals(
            "本 1.pdf",
            LanUrls.fileName("attachment; filename=\"a.pdf\"; filename*=UTF-8''%E6%9C%AC%201.pdf", "http://192.168.1.2/f"),
        )
        assertEquals("rp2040.zip", LanUrls.fileName("attachment; filename=\"rp2040.zip\"", "http://192.168.1.2/f"))
        assertEquals("a+b.txt", LanUrls.fileName("attachment; filename*=utf-8''a+b.txt", "http://192.168.1.2/f"))
        assertEquals("file", LanUrls.fileName(null, "http://192.168.1.2/api/v1/fs/disk1/file"))
        assertEquals("_etc_passwd", LanUrls.fileName("attachment; filename=\"..\\..\\_etc_passwd\"", "http://h/"))
    }
}
