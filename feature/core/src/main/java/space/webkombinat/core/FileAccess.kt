package space.webkombinat.core

import space.webkombinat.core.data.ANAS_Folder

interface FileAccess {
    suspend fun fileRead(path: String)
    suspend fun fileDelete(path: String)
    suspend fun readDirectory(path: String): ANAS_Folder
    suspend fun makeDirectory(path: String)
    suspend fun deleteDirectory(path: String)
}