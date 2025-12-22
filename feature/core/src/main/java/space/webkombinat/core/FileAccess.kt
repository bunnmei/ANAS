package space.webkombinat.core

import space.webkombinat.core.data.ANAS_Folder
import space.webkombinat.core.data.ANAS_FolderOrFile

interface FileAccess {
    suspend fun fileRead(path: String)
    suspend fun fileDelete(path: String)
    suspend fun readDirectory(disk: String, path: String): List<ANAS_FolderOrFile>
    suspend fun makeDirectory(path: String)
    suspend fun deleteDirectory(path: String)
}