package space.webkombinat.storage.data

import androidx.documentfile.provider.DocumentFile

data class ExFolder(
    val folderName: DocumentFile,
    val alias: String,
    val readable: Boolean,
    val writable: Boolean
) {
    fun checkRead(): Boolean {
        return readable
    }

    fun checkWrite(): Boolean {
        return writable
    }
}