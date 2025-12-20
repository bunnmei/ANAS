package space.webkombinat.anas.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.documentfile.provider.DocumentFile
import space.webkombinat.anas.data.DirectoryManager
import space.webkombinat.anas.data.ExFolder
import space.webkombinat.anas.data.SERVER_STATUS
import space.webkombinat.anas.data.ServerState
import space.webkombinat.anas.server.ServerService

class ServerVM(
    private val directoryManager: DirectoryManager,
    private val serverStateClass: ServerState
): ViewModel() {


    val folders = directoryManager.folders
    val serverState = serverStateClass.serverStatus

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

    fun startOrStopServer(context: Context) {
        val intent = Intent(context, ServerService::class.java)
        if(serverState.value == SERVER_STATUS.STOPPED) {
            context.startService(intent)
        } else {
            context.stopService(intent)
        }
    }

}

