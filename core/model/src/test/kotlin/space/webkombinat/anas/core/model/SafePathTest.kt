package space.webkombinat.anas.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafePathTest {

    @Test
    fun normalizesSlashes() {
        assertEquals("/a/b", SafePath.parse("a//b/").toString())
        assertEquals("/", SafePath.parse("").toString())
        assertEquals("/", SafePath.parse(null).toString())
        assertTrue(SafePath.parse("///").isRoot)
    }

    @Test
    fun rejectsTraversal() {
        for (bad in listOf("..", "/a/../b", "./a", "a\\b", "a/\u0000b", "/..", "a/./b")) {
            try {
                SafePath.parse(bad)
                throw AssertionError("should reject: $bad")
            } catch (_: InvalidPathException) {
            }
        }
    }

    @Test
    fun allowsDotFoldersAndJapanese() {
        val p = SafePath.parse("/.jisui/data/本.pdf")
        assertEquals(listOf(".jisui", "data", "本.pdf"), p.segments)
        assertEquals("本.pdf", p.name)
        assertEquals("/.jisui/data", p.parent.toString())
    }

    @Test
    fun relativeTo() {
        val base = SafePath.parse("/.jisui/data")
        assertEquals("/a.pdf", SafePath.parse("/.jisui/data/a.pdf").relativeTo(base).toString())
        assertNull(SafePath.parse("/.jisui/app/x").relativeTo(base))
        assertFalse(SafePath.parse("/.jisuiX").startsWith(SafePath.parse("/.jisui")))
    }

    @Test
    fun fileNameRules() {
        assertNull(FileNames.problem("ok name (1).pdf"))
        assertNull(FileNames.problem(".hidden"))
        for (bad in listOf("", "a:b", "a?b", "a*b", "a|b", "a<b", "trail.", "trail ", "a/b", "..")) {
            assertTrue("should reject: $bad", FileNames.problem(bad) != null)
        }
        assertTrue(FileNames.problem("あ".repeat(86)) != null) // 258 bytes
    }
}
