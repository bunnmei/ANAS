package space.webkombinat.anas.ui.extensions

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.webkombinat.anas.R
import space.webkombinat.anas.core.model.ExtensionManifest
import space.webkombinat.anas.core.model.VolumeInfo
import space.webkombinat.anas.data.ExtensionTemplate
import space.webkombinat.anas.server.ExtensionInfo
import space.webkombinat.anas.ui.LocalSnackbar
import space.webkombinat.anas.ui.components.Section
import space.webkombinat.anas.ui.theme.AnasTheme

@Composable
fun ExtensionsScreen(vm: ExtensionsViewModel = hiltViewModel()) {
    val extensions by vm.extensions.collectAsStateWithLifecycle()
    val baseUrl by vm.baseUrl.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val volumes by vm.writableVolumes.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val open = { path: String ->
        baseUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, (it + path).toUri())) }
        Unit
    }

    ExtensionsContent(
        extensions = extensions,
        serverRunning = baseUrl != null,
        scanning = scanning,
        onRescan = { vm.rescan() },
        onCreate = { creating = true },
        onOpen = open,
        onOpenSpec = { open("/_anas/spec.md") },
        onEnabledChange = { id, v -> vm.setEnabled(id, v) },
        onFullAccessChange = { id, v -> vm.setFullAccess(id, v) },
        onNetworkChange = { id, origins -> vm.setNetwork(id, origins) },
    )

    if (creating) {
        CreateDialog(
            volumes = volumes,
            onDismiss = { creating = false },
            onCreate = { volume, id, name -> creating = false; vm.create(volume, id, name) },
        )
    }
}

@Composable
private fun ExtensionsContent(
    extensions: List<ExtensionInfo>,
    serverRunning: Boolean,
    scanning: Boolean,
    onRescan: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenSpec: () -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    onFullAccessChange: (String, Boolean) -> Unit,
    onNetworkChange: (String, List<String>) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.extensions_title)) {
            Text(stringResource(R.string.extensions_desc), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRescan, enabled = !scanning) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.extensions_rescan))
                }
                OutlinedButton(onClick = onCreate) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.extensions_create))
                }
            }
            TextButton(onClick = onOpenSpec, enabled = serverRunning) {
                Icon(Icons.Default.Description, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.extensions_spec))
            }
            if (!serverRunning) {
                Text(
                    stringResource(R.string.extension_server_needed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (extensions.isEmpty() && !scanning) {
                Text(stringResource(R.string.extensions_empty), style = MaterialTheme.typography.bodyMedium)
            }
            extensions.forEach { ext ->
                ExtensionCard(ext, serverRunning, onOpen, onEnabledChange, onFullAccessChange, onNetworkChange)
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun ExtensionCard(
    ext: ExtensionInfo,
    serverRunning: Boolean,
    onOpen: (String) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    onFullAccessChange: (String, Boolean) -> Unit,
    onNetworkChange: (String, List<String>) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ext.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${ext.volume}:${ext.folder}" + if (ext.version.isNotEmpty()) "  v${ext.version}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ext.error == null) {
                    Switch(checked = ext.enabled, onCheckedChange = { onEnabledChange(ext.id, it) })
                }
            }
            if (ext.description.isNotEmpty()) Text(ext.description, style = MaterialTheme.typography.bodyMedium)
            ext.error?.let {
                Text(stringResource(R.string.extension_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (ext.error == null && ext.scope == ExtensionManifest.Scope.ALL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.extension_full_access), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.extension_full_access_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = ext.fullAccess, onCheckedChange = { onFullAccessChange(ext.id, it) })
                }
            }
            if (ext.error == null && ext.network.isNotEmpty()) {
                // 許可したあとで anas.json にサイトが増えたら、増えた分は許可し直すまで使えない
                val allGranted = ext.networkAllowed.containsAll(ext.network)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.extension_network), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.extension_network_desc), style = MaterialTheme.typography.bodySmall)
                        Text(
                            ext.network.joinToString("\n") { it.removePrefix("https://") },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!allGranted && ext.networkAllowed.isNotEmpty()) {
                            Text(stringResource(R.string.extension_network_added), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Switch(checked = allGranted, onCheckedChange = { onNetworkChange(ext.id, if (it) ext.network else emptyList()) })
                }
            }
            if (ext.error == null && ext.enabled) {
                TextButton(onClick = { onOpen(ext.url) }, enabled = serverRunning) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.extension_open))
                }
            }
        }
    }
}

@Composable
private fun CreateDialog(
    volumes: List<VolumeInfo>,
    onDismiss: () -> Unit,
    onCreate: (volume: String, id: String, name: String) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf(volumes.firstOrNull()?.id) }
    val idValid = ExtensionTemplate.isValidId(id)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_extension_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it.trim() },
                    label = { Text(stringResource(R.string.create_extension_id)) },
                    prefix = { Text("/.") },
                    isError = id.isNotEmpty() && !idValid,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.create_extension_name)) },
                    singleLine = true,
                )
                Text(stringResource(R.string.create_extension_volume), style = MaterialTheme.typography.labelMedium)
                volumes.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = volume == v.id, role = Role.RadioButton, onClick = { volume = v.id }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = volume == v.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(v.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { volume?.let { onCreate(it, id, name) } }, enabled = idValid && volume != null) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun ExtensionsPreview() {
    AnasTheme {
        ExtensionsContent(
            extensions = listOf(
                ExtensionInfo("jisui", "自炊本棚", "1.0.0", "スキャンした本を読む", "disk1", "/.jisui", "/x/jisui/",
                    scope = ExtensionManifest.Scope.SELF, enabled = true, fullAccess = false,
                    network = listOf("https://www.amazon.co.jp", "https://*.media-amazon.com")),
                ExtensionInfo("broken", ".broken", "", "", "disk1", "/.broken", "/x/broken/",
                    scope = ExtensionManifest.Scope.SELF, enabled = true, fullAccess = false, error = "anas.json を読めません"),
            ),
            serverRunning = true, scanning = false,
            onRescan = {}, onCreate = {}, onOpen = {}, onOpenSpec = {}, onEnabledChange = { _, _ -> }, onFullAccessChange = { _, _ -> }, onNetworkChange = { _, _ -> },
        )
    }
}
