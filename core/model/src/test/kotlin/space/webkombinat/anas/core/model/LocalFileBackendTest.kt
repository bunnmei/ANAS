package space.webkombinat.anas.core.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileBackendTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun writeReadRenameDelete() = runTest {
        val backend = LocalFileBackend(mapOf("d" to tmp.root))
        backend.mkdirs("d", SafePath.parse("/a/b"))
        backend.write("d", SafePath.parse("/a/b"), "x.txt", overwrite = false) { it.write("hello".toByteArray()) }

        val handle = backend.openRead("d", SafePath.parse("/a/b/x.txt"))
        assertEquals(5, handle.entry.size)
        assertEquals("llo", handle.open(2).readBytes().decodeToString())

        try {
            backend.write("d", SafePath.parse("/a/b"), "x.txt", overwrite = false) { }
            throw AssertionError()
        } catch (_: StorageException.AlreadyExists) {
        }

        backend.rename("d", SafePath.parse("/a/b/x.txt"), "y.txt")
        assertEquals(listOf("y.txt"), backend.list("d", SafePath.parse("/a/b")).map { it.name })
        backend.delete("d", SafePath.parse("/a"))
        assertTrue(backend.list("d", SafePath.ROOT).isEmpty())
    }

    @Test
    fun failedWriteLeavesNoFile() = runTest {
        val backend = LocalFileBackend(mapOf("d" to tmp.root))
        try {
            backend.write("d", SafePath.ROOT, "x.bin", overwrite = false) {
                it.write(1)
                throw IllegalStateException("回線切断")
            }
        } catch (_: IllegalStateException) {
        }
        assertFalse(tmp.root.listFiles().orEmpty().any())
    }
}
