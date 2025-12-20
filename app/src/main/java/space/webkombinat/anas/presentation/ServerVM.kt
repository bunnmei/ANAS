package space.webkombinat.anas.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.documentfile.provider.DocumentFile
import space.webkombinat.anas.data.DirectoryManager
import space.webkombinat.anas.data.ExFolder

class ServerVM(
   private val directoryManager: DirectoryManager
): ViewModel() {


    val folders = directoryManager.folders

    fun setUri(uri: Uri, context: Context) {

        val rootDir = DocumentFile.fromTreeUri(context, uri) ?: return
        // folderPermissionのチェック
        val newExFolder = ExFolder(
            folderName = rootDir.name ?: "null",
            readable = rootDir.canRead(),
            writable = rootDir.canWrite()
        )

        directoryManager.addNewFolder(newExFolder)
    }



}

