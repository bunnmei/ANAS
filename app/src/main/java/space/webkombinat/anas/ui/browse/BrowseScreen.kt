package space.webkombinat.anas.ui.browse

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.webkombinat.anas.R
import space.webkombinat.anas.data.AppMode
import space.webkombinat.anas.service.ServerState
import space.webkombinat.anas.ui.LocalSnackbar
import space.webkombinat.anas.ui.theme.AnasTheme

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * 「ブラウズ」: 登録した NAS をアプリの中で開く。まだ登録していなければ QR を読む画面。
 * [onOpenSettings] が null でなければメニューに「設定」を出す（クライアントのみのとき）
 */
@Composable
fun BrowseScreen(onOpenSettings: (() -> Unit)?, vm: ConnectionViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val server by vm.serverState.collectAsStateWithLifecycle()
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Android 17+ は LAN の機器に接続するのに権限が要る
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetwork.launch(LOCAL_NETWORK)
        }
    }

    val scan = { vm.scan(context) }

    if (!settings.loaded) return
    if (settings.serverUrl.isEmpty()) {
        ConnectContent(
            connecting = connecting,
            ownServer = (server as? ServerState.Running)?.takeIf { settings.mode == AppMode.SERVER },
            onScan = scan,
            onConnect = vm::connect,
            onUseThisDevice = vm::useThisDevice,
        )
    } else {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        key(settings.serverUrl, dark) {
            WebContent(
                url = settings.serverUrl,
                dark = dark,
                onMessage = { vm.message(it) },
                onScan = scan,
                onForget = vm::forget,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebContent(
    url: String,
    dark: Boolean,
    onMessage: (String) -> Unit,
    onScan: () -> Unit,
    onForget: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    val context = LocalContext.current
    val state = remember { WebViewState() }
    var menu by remember { mutableStateOf(false) }
    // 画面いっぱいに描く設定（edge-to-edge）だと adjustResize が効かないので、
    // キーボードの分だけ WebView を縮める（ノートの入力中の行がキーボードに隠れないように）
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            windowInsets = WindowInsets(0),
            title = {
                Column {
                    Text(state.title.takeIf { state.error == null }?.ifBlank { null } ?: "ANAS", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(url.removePrefix("http://").removeSuffix("/"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                IconButton(onClick = state::reload) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reload)) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.client_scan_again)) },
                            leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                            onClick = { menu = false; onScan() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_in_browser)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                            onClick = {
                                menu = false
                                val current = state.view?.url ?: url
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, current.toUri())) }
                            },
                        )
                        if (onOpenSettings != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tab_settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = { menu = false; onOpenSettings() },
                            )
                        }
                    }
                }
            },
        )
        if (state.progress < 100) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        else HorizontalDivider()
        Box(Modifier.weight(1f)) {
            AnasWebView(url = url, dark = dark, state = state, onMessage = onMessage, modifier = Modifier.fillMaxSize())
            state.error?.let { error ->
                ConnectionError(error, url, onRetry = state::reload, onScan = onScan, onForget = onForget)
            }
        }
    }
}

@Composable
private fun ConnectionError(error: String, url: String, onRetry: () -> Unit, onScan: () -> Unit, onForget: () -> Unit) {
    // WebView 自身のエラーページを隠す
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.client_unreachable, url), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(stringResource(R.string.client_unreachable_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRetry) { Text(stringResource(R.string.reload)) }
            OutlinedButton(onClick = onScan) { Text(stringResource(R.string.client_scan_again)) }
            TextButton(onClick = onForget) { Text(stringResource(R.string.client_forget)) }
        }
    }
}

@Composable
private fun ConnectContent(
    connecting: Boolean,
    ownServer: ServerState.Running?,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onUseThisDevice: (Int) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    // キーボードを閉じないと、結果のスナックバーがキーボードに隠れる
    val submit = {
        keyboard?.hide()
        onConnect(address)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.client_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.client_desc), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onScan, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.client_scan))
        }
        if (connecting) CircularProgressIndicator()
        HorizontalDivider()
        Text(stringResource(R.string.client_manual), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text(stringResource(R.string.client_address)) },
                placeholder = { Text("192.168.1.10:8080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = submit, enabled = address.isNotBlank() && !connecting) {
                Text(stringResource(R.string.client_connect))
            }
        }
        if (ownServer != null) {
            HorizontalDivider()
            FilledTonalButton(onClick = { onUseThisDevice(ownServer.port) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.client_this_device))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConnectPreview() {
    AnasTheme {
        ConnectContent(connecting = false, ownServer = null, onScan = {}, onConnect = {}, onUseThisDevice = {})
    }
}
