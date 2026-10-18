package space.webkombinat.anas.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionManifestTest {

    @Test
    fun parsesMinimalManifest() {
        val m = ExtensionManifest.parse("""{"anas":1,"id":"jisui","name":"自炊本棚"}""", ".jisui").getOrThrow()
        assertEquals("jisui", m.id)
        assertEquals(ExtensionManifest.Scope.SELF, m.scope)
        assertTrue(m.externalOrigins.isEmpty())
    }

    @Test
    fun parsesFullManifest() {
        val text = """
            {"anas":1,"id":"kicad_symbol_footprint","name":"KiCad","version":"1.2.0","icon":"icon.svg",
             "scope":"all","externalOrigins":["https://cdn.jsdelivr.net"],
             "network":["https://www.amazon.co.jp","https://*.media-amazon.com:8443"],"unknownKey":true}
        """
        val m = ExtensionManifest.parse(text, ".kicad_symbol_footprint").getOrThrow()
        assertEquals(ExtensionManifest.Scope.ALL, m.scope)
        assertEquals("icon.svg", m.icon)
        assertEquals(listOf("https://www.amazon.co.jp", "https://*.media-amazon.com:8443"), m.network)
    }

    @Test
    fun rejectsInvalidManifests() {
        val cases = mapOf(
            """{"anas":2,"id":"a","name":"x"}""" to ".a",
            """{"anas":1,"id":"A","name":"x"}""" to ".A",
            """{"anas":1,"id":"a","name":"x"}""" to ".b",
            """{"anas":1,"id":"a","name":" "}""" to ".a",
            """{"anas":1,"id":"a","name":"x","icon":"../x"}""" to ".a",
            """{"anas":1,"id":"a","name":"x","externalOrigins":["http://evil"]}""" to ".a",
            """{"anas":1,"id":"a","name":"x","network":["http://example.com"]}""" to ".a",
            """{"anas":1,"id":"a","name":"x","network":["https://*.com"]}""" to ".a",
            """{"anas":1,"id":"a","name":"x","network":["https://a.*.com"]}""" to ".a",
            """{"anas":1,"id":"a","name":"x","network":["https://example.com/path"]}""" to ".a",
            """{"anas":1,"id":"a","name":"x","scope":"root"}""" to ".a",
            """not json""" to ".a",
        )
        for ((text, folder) in cases) {
            assertTrue("should reject: $text", ExtensionManifest.parse(text, folder).isFailure)
        }
    }

    @Test
    fun idFromFolderName() {
        assertEquals("jisui", ExtensionManifest.idFromFolderName(".jisui"))
        assertNull(ExtensionManifest.idFromFolderName("jisui"))
        assertNull(ExtensionManifest.idFromFolderName(".Trash-1000"))
        assertNull(ExtensionManifest.idFromFolderName("."))
    }
}
