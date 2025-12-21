package space.webkombinat.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.webkombinat.core.FileAccess
import space.webkombinat.core.data.ANAS_Folder
import space.webkombinat.storage.data.ExFolder

class DirectoryManager: FileAccess {
    private val _folders = MutableStateFlow<List<ExFolder>>(emptyList())
    val folders = _folders.asStateFlow()

    fun addNewFolder(newFolder: ExFolder) {
        _folders.value = _folders.value + newFolder
    }

    override suspend fun fileRead(path: String) {

    }

    override suspend fun fileDelete(path: String) {

    }

    override suspend fun readDirectory(path: String): ANAS_Folder {
        return ANAS_Folder(
            absolutePath = "path",
            name = "name",
            open = false,
            children = null
        )
    }

    override suspend fun makeDirectory(path: String) {

    }

    override suspend fun deleteDirectory(path: String) {

    }
}