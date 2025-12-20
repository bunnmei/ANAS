package space.webkombinat.anas.presentation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import space.webkombinat.anas.R
import space.webkombinat.anas.data.ExFolder
import space.webkombinat.anas.data.PreviewSize
import space.webkombinat.anas.data.SERVER_STATUS

@Composable
fun ServerScreen(
    modifier: Modifier = Modifier,
    vm: ServerVM
) {
    val context = LocalContext.current
    val folderState by vm.folders.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val address by vm.address.collectAsState()


    ServerScreen(
        setUri = { uri ->
            vm.setUri(uri = uri, context = context)
        },
        folderList = folderState,
        serverState = serverState,
        serviceIntent = { vm.startOrStopServer(context = context) },
        address = address
    )
}

@Composable
private fun ServerScreen(
    modifier: Modifier = Modifier,
    setUri: (Uri) -> Unit,
    folderList: List<ExFolder>,
    serverState: SERVER_STATUS,
    serviceIntent: () -> Unit,
    address: String
) {
    val context = LocalContext.current
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            setUri(uri)
        } else {
            println("uri is null")
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        //選択したフォルダやServerのStatus
        Column {
            Row(
                modifier = modifier
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = stringResource(R.string.selectedFolderStatus),
                    fontSize = 12.sp
                )
                Spacer(modifier = modifier.weight(1f))

                Text(
                    text = "W",
                    modifier = modifier
                        .width(30.dp),
                )
                Text(
                    text = "/",
                    modifier = modifier.width(10.dp),
                )
                Text(
                    text = "R",
                    modifier = modifier.width(30.dp),
                )
            }
            HorizontalDivider(
                modifier.padding(horizontal = 16.dp)
            )

            folderList.forEach { folder ->
                Row(
                    modifier = modifier
                        .padding(horizontal = 16.dp),
                ){
                    Text(text = folder.folderName)
                    Spacer(modifier = modifier.weight(1f))


                    Text(
                        text = if (folder.writable) "✓" else "☓",
                        color = if (folder.writable) Color.Green else Color.Red,
                        modifier = modifier
                            .width(30.dp),
                    )

                    Text(
                        text = "/",
                        modifier = modifier.width(10.dp),
                    )

                    Text(
                        text = if (folder.readable)  "✓" else "☓",
                        color = if (folder.readable) Color.Green else Color.Red,
                        modifier = modifier
                            .width(30.dp),
                    )
                }
            }
        }


        // 操作ボタン
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Bottom
        ) {

            Row {
                Text("IP Address: ${address}")
                Spacer(modifier = modifier.weight(1f))
            }
            Spacer(modifier = modifier.height(20.dp))
            Button(
                onClick = {
                    openDocLauncher.launch(null)
                }
            ) {
                Row {
                    Text(stringResource(R.string.selectFolder))
                    Spacer(modifier = modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null
                    )
                }
            }

            Button(
                onClick = {
                    serviceIntent()
                },
                enabled = folderList.isNotEmpty()
            ) {
                Row {
                    if (serverState == SERVER_STATUS.STOPPED) {
                        Text(stringResource(R.string.startServer))
                        Spacer(modifier = modifier.width(10.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                            contentDescription = null
                        )
                    } else {
                        Text(stringResource(R.string.stopServer))
                        Spacer(modifier = modifier.width(10.dp))
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}

@Preview(
    showBackground = true,
    widthDp = PreviewSize.WIDTH,
    heightDp = PreviewSize.HEIGHT,
)
@Composable
fun ServerScreenPreview() {
    ServerScreen(
        setUri = {},
        folderList = listOf(
            ExFolder(
                folderName = "Download",
                readable = true,
                writable = true
            ),
            ExFolder(
                folderName = "FilePicker",
                readable = true,
                writable = false
            )
        ),
        serverState = SERVER_STATUS.STOPPED,
        serviceIntent = {},
        address = "192.168.1.1:8080"
    )
}
