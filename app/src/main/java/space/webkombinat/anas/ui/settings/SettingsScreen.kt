package space.webkombinat.anas.ui.settings

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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.webkombinat.anas.BuildConfig
import space.webkombinat.anas.R
import space.webkombinat.anas.data.AppSettings
import space.webkombinat.anas.data.ThemeMode
import space.webkombinat.anas.ui.components.Section
import space.webkombinat.anas.ui.theme.AnasTheme

private const val DOCS_URL = "https://bunnmei.github.io/ANAS/"

@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        onTheme = { vm.setTheme(it) },
        onPort = vm::setPort,
        onPassword = { vm.setPassword(it) },
        onReadOnly = { vm.setReadOnly(it) },
    )
}

@Composable
private fun SettingsContent(
    settings: AppSettings,
    onTheme: (ThemeMode) -> Unit,
    onPort: (String) -> Boolean,
    onPassword: (String) -> Unit,
    onReadOnly: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
        Section(stringResource(R.string.settings_about)) {
            Text("${stringResource(R.string.version)}: ${BuildConfig.VERSION_NAME}")
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, DOCS_URL.toUri())) }) {
                Text(stringResource(R.string.docs))
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
        SettingsContent(AppSettings(), onTheme = {}, onPort = { true }, onPassword = {}, onReadOnly = {})
    }
}
