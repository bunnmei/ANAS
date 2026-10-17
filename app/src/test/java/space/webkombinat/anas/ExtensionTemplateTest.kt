package space.webkombinat.anas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.webkombinat.anas.core.model.ExtensionManifest
import space.webkombinat.anas.data.ExtensionTemplate

class ExtensionTemplateTest {
    @Test
    fun templateHasValidManifest() {
        val files = ExtensionTemplate.files("my_ext", "マイ拡張")
        val manifest = ExtensionManifest.parse(files.getValue("anas.json"), ".my_ext").getOrThrow()
        assertEquals("マイ拡張", manifest.name)
        assertTrue(files.getValue("app/index.html").contains("/_anas/sdk.js"))
        assertTrue("data/.keep" in files)
    }

    @Test
    fun validatesId() {
        assertTrue(ExtensionTemplate.isValidId("kicad_symbol_footprint"))
        assertFalse(ExtensionTemplate.isValidId("Bad"))
        assertFalse(ExtensionTemplate.isValidId(".jisui"))
        assertFalse(ExtensionTemplate.isValidId(""))
    }
}
