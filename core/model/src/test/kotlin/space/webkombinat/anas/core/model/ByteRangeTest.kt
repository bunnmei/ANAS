package space.webkombinat.anas.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteRangeTest {
    private fun partial(start: Long, end: Long) = ByteRange.Result.Partial(ByteRange(start, end))

    @Test
    fun parsesSingleRanges() {
        assertEquals(partial(0, 99), ByteRange.parse("bytes=0-99", 1000))
        assertEquals(partial(100, 999), ByteRange.parse("bytes=100-", 1000))
        assertEquals(partial(500, 999), ByteRange.parse("bytes=-500", 1000))
        assertEquals(partial(0, 999), ByteRange.parse("bytes=-5000", 1000))
        assertEquals(partial(990, 999), ByteRange.parse("bytes=990-2000", 1000))
    }

    @Test
    fun fallsBackOrRejects() {
        assertEquals(ByteRange.Result.Full, ByteRange.parse(null, 10))
        assertEquals(ByteRange.Result.Full, ByteRange.parse("items=0-1", 10))
        assertEquals(ByteRange.Result.Full, ByteRange.parse("bytes=0-1,4-5", 10))
        assertEquals(ByteRange.Result.Full, ByteRange.parse("bytes=x-y", 10))
        assertEquals(ByteRange.Result.Unsatisfiable, ByteRange.parse("bytes=10-", 10))
        assertEquals(ByteRange.Result.Unsatisfiable, ByteRange.parse("bytes=5-2", 10))
        assertEquals(ByteRange.Result.Unsatisfiable, ByteRange.parse("bytes=-0", 10))
    }
}
