package space.webkombinat.core.data

import java.io.InputStream

sealed class ReadResult {
    data class Success(
        val inputStream: InputStream,
        val contentType: String,
        val size: Long
    ) : ReadResult()
    object NotFound : ReadResult()
    data class Error(val message: String) : ReadResult()
}