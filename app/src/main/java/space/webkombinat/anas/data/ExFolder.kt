package space.webkombinat.anas.data

data class ExFolder(
    val folderName: String,
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