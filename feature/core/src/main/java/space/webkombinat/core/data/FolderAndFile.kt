package space.webkombinat.core.data

import kotlinx.serialization.Serializable

@Serializable
sealed interface ANAS_FolderOrFile

@Serializable
data class ANAS_File(
    val absolutePath: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
): ANAS_FolderOrFile

@Serializable
data class ANAS_Folder(
    val absolutePath: String,
    val name: String,
    val open: Boolean,
    val children: List<ANAS_FolderOrFile>? = null
): ANAS_FolderOrFile