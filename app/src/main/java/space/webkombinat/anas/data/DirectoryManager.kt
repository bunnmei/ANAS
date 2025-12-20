package space.webkombinat.anas.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DirectoryManager {
    private val _folders = MutableStateFlow<List<ExFolder>>(emptyList())
    val folders = _folders.asStateFlow()

    fun addNewFolder(newFolder: ExFolder) {
        _folders.value = _folders.value + newFolder
    }
}