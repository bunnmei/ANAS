package space.webkombinat.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ANAS_FolderOrFile

@Serializable
@SerialName("file")
data class ANAS_File(
//    val type: String,
    val absolutePath: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
): ANAS_FolderOrFile

@Serializable
@SerialName("folder")
data class ANAS_Folder(
//    val type: String,
    val absolutePath: String,
    val name: String,
    val open: Boolean,
    val children: List<ANAS_FolderOrFile>?
): ANAS_FolderOrFile