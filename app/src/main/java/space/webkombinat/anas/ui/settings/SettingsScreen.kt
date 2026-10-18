package space.webkombinat.anas.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.webkombinat.anas.BuildConfig
import space.webkombinat.anas.R
import space.webkombinat.anas.data.AppMode
import space.webkombinat.anas.data.AppSettings
import space.webkombinat.anas.data.ThemeMode
import space.webkombinat.anas.service.NetworkAddresses
import space.webkombinat.anas.ui.LocalSnackbar
import space.webkombinat.anas.ui.browse.ConnectionViewModel
import space.webkombinat.anas.ui.components.Section
import space.webkombinat.anas.ui.theme.AnasTheme

private const val DOCS_URL = "https://bunnmei.github.io/ANAS/"

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel(), connection: ConnectionViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val connecting by connection.connecting.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { connection.messages.collect { snackbar.showSnackbar(it) } }
    SettingsContent(
        settings = settings,
        connecting = connecting,
        onMode = { vm.setMode(it) },
        onScan = { connection.scan(context) },
        onForget = { connection.forget() },
        onTheme = { vm.setTheme(it) },
        onPort = vm::setPort,
        onPassword = { vm.setPassword(it) },
        onReadOnly = { vm.setReadOnly(it) },
        onWebDav = { vm.setWebDavEnabled(it) },
        onWebDavReadOnly = { vm.setWebDavReadOnly(it) },
    )
}

@Composable
private fun SettingsContent(
    settings: AppSettings,
    connecting: Boolean,
    onMode: (AppMode) -> Unit,
    onScan: () -> Unit,
    onForget: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onPort: (String) -> Boolean,
    onPassword: (String) -> Unit,
    onReadOnly: (Boolean) -> Unit,
    onWebDav: (Boolean) -> Unit,
    onWebDavReadOnly: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Section(stringResource(R.string.settings_mode)) {
            Column(Modifier.selectableGroup()) {
                listOf(
                    AppMode.SERVER to (R.string.mode_server to R.string.mode_server_desc),
                    AppMode.CLIENT to (R.string.mode_client to R.string.mode_client_desc),
                ).forEach { (mode, labels) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = settings.mode == mode, role = Role.RadioButton, onClick = { onMode(mode) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.mode == mode, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(labels.first), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(labels.second), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Section(stringResource(R.string.settings_client)) {
            Text(
                settings.serverUrl.ifEmpty { stringResource(R.string.client_none) },
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (settings.serverUrl.isEmpty()) null else FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onScan, enabled = !connecting) {
                    Text(stringResource(if (settings.serverUrl.isEmpty()) R.string.client_scan else R.string.client_scan_again))
                }
                if (settings.serverUrl.isNotEmpty()) {
                    TextButton(onClick = onForget) { Text(stringResource(R.string.client_forget)) }
                }
                if (connecting) CircularProgressIndicator(Modifier.size(24.dp))
            }
            Text(stringResource(R.string.client_lan_only), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Section(stringResource(R.string.settings_appearance)) {
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyLarge)
            Column(Modifier.selectableGroup()) {
                listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                ).forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = settings.theme == mode, role = Role.RadioButton, onClick = { onTheme(mode) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.theme == mode, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(label))
                    }
                }
            }
        }
        if (settings.mode != AppMode.CLIENT) {
            HorizontalDivider()
            Section(stringResource(R.string.settings_server)) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PortField(settings.port, onPort)
                    PasswordField(settings.password, onPassword)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.read_only), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.read_only_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = settings.readOnly, onCheckedChange = onReadOnly)
                    }
                }
            }
            HorizontalDivider()
            WebDavSection(settings, onWebDav, onWebDavReadOnly)
        }
        HorizontalDivider()
        Section(stringResource(R.string.settings_about)) {
            Text("${stringResource(R.string.version)}: ${BuildConfig.VERSION_NAME}")
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, DOCS_URL.toUri())) }) {
                Text(stringResource(R.string.docs))
            }
        }
    }
}

/** PC にネットワークドライブとしてつなぐ（WebDAV） */
@Composable
private fun WebDavSection(settings: AppSettings, onWebDav: (Boolean) -> Unit, onWebDavReadOnly: (Boolean) -> Unit) {
    Section(stringResource(R.string.settings_webdav)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.webdav_enable), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.webdav_enable_desc), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = settings.webDavEnabled, onCheckedChange = onWebDav)
            }
            if (settings.webDavEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.webdav_read_only), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.webdav_read_only_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = settings.webDavReadOnly, onCheckedChange = onWebDavReadOnly)
                }
                val ip = remember { NetworkAddresses.list().firstOrNull()?.ip ?: "<IP>" }
                Text(stringResource(R.string.webdav_url), style = MaterialTheme.typography.bodyMedium)
                Text("http://$ip:${settings.port}/dav/", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (settings.password.isEmpty()) R.string.webdav_auth_none else R.string.webdav_auth),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (settings.password.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PortField(port: Int, onPort: (String) -> Boolean) {
    var text by rememberSaveable(port) { mutableStateOf(port.toString()) }
    val valid = SettingsViewModel.parsePort(text) != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.port)) },
            isError = !valid,
            supportingText = { Text(stringResource(if (valid) R.string.restart_needed else R.string.port_invalid)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onPort(text) }, enabled = valid && text != port.toString()) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun PasswordField(password: String, onPassword: (String) -> Unit) {
    var text by rememberSaveable(password) { mutableStateOf(password) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = {
                    Text(
                        stringResource(R.string.password) + "（" +
                            stringResource(if (password.isEmpty()) R.string.password_none else R.string.password_set) + "）",
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onPassword(text) }, enabled = text != password) {
                Text(stringResource(R.string.save))
            }
        }
        Text(stringResource(R.string.password_desc), style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    AnasTheme {
        SettingsContent(
            AppSettings(loaded = true, mode = AppMode.CLIENT, serverUrl = "http://192.168.1.108:8080/"),
            connecting = false, onMode = {}, onScan = {}, onForget = {},
            onTheme = {}, onPort = { true }, onPassword = {}, onReadOnly = {}, onWebDav = {}, onWebDavReadOnly = {})
    }
}
