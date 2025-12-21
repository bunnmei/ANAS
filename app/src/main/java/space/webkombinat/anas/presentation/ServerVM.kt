package space.webkombinat.anas.presentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.documentfile.provider.DocumentFile
import space.webkombinat.anas.data.SERVER_STATUS
import space.webkombinat.anas.data.ServerState
import space.webkombinat.anas.server.ServerService
import space.webkombinat.storage.DirectoryManager
import space.webkombinat.storage.data.ExFolder


class ServerVM(
    private val directoryManager: DirectoryManager,
    private val serverStateClass: ServerState
): ViewModel() {
    val folders = directoryManager.folders
    val serverState = serverStateClass.serverStatus
    val address = serverStateClass.address

    fun setUri(uri: Uri, context: Context) {

        val rootDir = DocumentFile.fromTreeUri(context, uri) ?: return
        // folderPermissionのチェック
        val newExFolder = ExFolder(
            folderName = rootDir,
            alias = "disk${folders.value.size + 1}",
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

