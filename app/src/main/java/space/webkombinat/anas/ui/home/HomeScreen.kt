package space.webkombinat.anas.ui.home

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import space.webkombinat.anas.R
import space.webkombinat.anas.core.model.VolumeInfo
import space.webkombinat.anas.service.LocalAddress
import space.webkombinat.anas.service.ServerState
import space.webkombinat.anas.ui.LocalSnackbar
import space.webkombinat.anas.ui.components.Section
import space.webkombinat.anas.ui.components.formatBytes
import space.webkombinat.anas.ui.theme.AnasTheme

@Composable
fun HomeScreen(vm: HomeViewModel = hiltViewModel()) {
    val server by vm.serverState.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addVolume(uri)
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.start() // 通知が拒否されてもサーバーは動く
    }
    val requestStart = {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        if (needed.isEmpty()) vm.start() else permissions.launch(needed.toTypedArray())
    }

    HomeContent(
        server = server,
        volumes = volumes,
        onStart = requestStart,
        onStop = vm::stop,
        onAddVolume = { pickTree.launch(null) },
        onRemoveVolume = vm::removeVolume,
    )
}

@Composable
private fun HomeContent(
    server: ServerState,
    volumes: List<VolumeInfo>?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddVolume: () -> Unit,
    onRemoveVolume: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.server)) {
            ServerCard(server, canStart = !volumes.isNullOrEmpty(), onStart, onStop)
        }
        Section(stringResource(R.string.storage)) {
            when {
                volumes == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                volumes.isEmpty() -> Text(stringResource(R.string.storage_empty), style = MaterialTheme.typography.bodyMedium)
                else -> volumes.forEach { VolumeRow(it, onRemoveVolume) }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAddVolume) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.storage_add))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.storage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServerCard(server: ServerState, canStart: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.copied)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, color) = when (server) {
                    is ServerState.Running -> R.string.server_running to MaterialTheme.colorScheme.primary
                    ServerState.Starting -> R.string.server_starting to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> R.string.server_stopped to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(stringResource(label), style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.weight(1f))
                when (server) {
                    is ServerState.Running -> Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.server_stop))
                    }
                    ServerState.Starting -> CircularProgressIndicator(Modifier.size(32.dp))
                    else -> Button(onClick = onStart, enabled = canStart) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.server_start))
                    }
                }
            }
            when (server) {
                is ServerState.Running -> {
                    if (server.urls.isEmpty()) {
                        Text(stringResource(R.string.server_no_address), color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(stringResource(R.string.server_open_hint), style = MaterialTheme.typography.bodySmall)
                        server.addresses.zip(server.urls).forEach { (address, url) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(address.label, style = MaterialTheme.typography.labelSmall)
                                    SelectionContainer {
                                        Text(url, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                    }
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("url", url)))
                                        snackbar.showSnackbar(copiedText)
                                    }
                                }) { Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy)) }
                                IconButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.open_in_browser)) }
                            }
                        }
                    }
                }
                is ServerState.Failed -> Text(server.message, color = MaterialTheme.colorScheme.error)
                else -> if (!canStart) Text(stringResource(R.string.server_no_storage), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun VolumeRow(volume: VolumeInfo, onRemove: (String) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (volume.name.contains("USB", ignoreCase = true)) Icons.Default.Usb else Icons.Default.SdStorage,
            contentDescription = null,
            tint = if (volume.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(volume.name, style = MaterialTheme.typography.bodyLarge)
            val detail = buildList {
                add(volume.id)
                if (!volume.online) add(stringResource(R.string.storage_offline))
                else if (!volume.writable) add(stringResource(R.string.storage_readonly))
                if (volume.freeBytes != null && volume.totalBytes != null) {
                    add(stringResource(R.string.storage_free, formatBytes(volume.freeBytes!!), formatBytes(volume.totalBytes!!)))
                }
            }.joinToString(" · ")
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (volume.online) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        IconButton(onClick = { confirm = true }) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.storage_remove))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.storage_remove)) },
            text = { Text(stringResource(R.string.storage_remove_confirm, volume.name)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onRemove(volume.id) }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    AnasTheme {
        HomeContent(
            server = ServerState.Running(8080, listOf(LocalAddress("Wi-Fi", "192.168.1.108"))),
            volumes = listOf(
                VolumeInfo("disk1", "USB ドライブ", online = true, writable = true, totalBytes = 500L shl 30, freeBytes = 320L shl 30),
                VolumeInfo("disk2", "SD カード / NAS", online = false, writable = false),
            ),
            onStart = {}, onStop = {}, onAddVolume = {}, onRemoveVolume = {},
        )
    }
}
