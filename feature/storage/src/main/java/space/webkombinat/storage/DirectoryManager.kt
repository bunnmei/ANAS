package space.webkombinat.storage

import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import space.webkombinat.core.FileAccess
import space.webkombinat.core.data.ANAS_File
import space.webkombinat.core.data.ANAS_Folder
import space.webkombinat.core.data.ANAS_FolderOrFile
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

    override suspend fun readDirectory(disk: String, path: String): List<ANAS_FolderOrFile> {

        val select_disk = _folders.value.find { folder -> folder.alias == disk }
        val returnArray = mutableListOf<ANAS_FolderOrFile>()

        val pathPrefix = if(path == "/") "" else path

        if (select_disk != null) {
           if (select_disk.folderName.isDirectory){
               val targetDir = select_disk.folderName.resolvePath(path) ?: return emptyList()
               val fileList = targetDir.listFiles()

               fileList.forEach { item ->
                   if(item.isDirectory) {
                       returnArray.add(
                           ANAS_Folder(
                               absolutePath = "${pathPrefix}/${item.name}",
                               name = item.name ?: "",
                               open = false,
                               children = null
                           )
                       )
                   } else {
                       returnArray.add(
                           ANAS_File(
                               absolutePath = path,
                               name = item.name ?: "",
                               size = item.length(),
                               lastModified = item.lastModified()
                           )
                       )
                   }
               }
           } else {
               println("not directory")
           }
        } else {
            println("検索ストレージが見つからない")
        }
        return returnArray
    }

    override suspend fun makeDirectory(path: String) {

    }

    override suspend fun deleteDirectory(path: String) {

    }

    fun DocumentFile.resolvePath(path: String): DocumentFile? {
        if (path == "/" || path.isBlank()) return this

        val segments = path
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }

        var current: DocumentFile? = this

        for (segment in segments) {
            current = current?.findFile(segment)
            if (current == null) return null
        }

        return current
    }
}