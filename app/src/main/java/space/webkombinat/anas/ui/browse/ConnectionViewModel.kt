package space.webkombinat.anas.ui.browse

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import space.webkombinat.anas.client.LanUrls
import space.webkombinat.anas.client.QrScanner
import space.webkombinat.anas.client.ServerProbe
import space.webkombinat.anas.data.AppSettings
import space.webkombinat.anas.data.SettingsRepository
import space.webkombinat.anas.service.ServerController
import space.webkombinat.anas.service.ServerState
import javax.inject.Inject

/** クライアントの接続先（QR・手入力で登録、削除） */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val repository: SettingsRepository,
    controller: ServerController,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
    val serverState: StateFlow<ServerState> = controller.state

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** QR の中身や手入力のアドレスを確かめて、接続先として保存する */
    fun connect(raw: String) {
        if (_connecting.value) return
        val url = LanUrls.normalizeServer(raw)
        if (url == null) {
            viewModelScope.launch { _messages.send("同じ LAN の ANAS のアドレスではありません: ${raw.take(80)}") }
            return
        }
        viewModelScope.launch {
            _connecting.value = true
            try {
                when (val result = ServerProbe.check(url)) {
                    is ServerProbe.Result.Ok -> {
                        repository.update { it.copy(serverUrl = url) }
                        _messages.send("$url に接続します")
                    }
                    ServerProbe.Result.NotAnas -> _messages.send("$url は ANAS のサーバーではないようです")
                    is ServerProbe.Result.Unreachable ->
                        _messages.send("$url に接続できません。サーバーが起動しているか、同じ Wi-Fi か確認してください")
                }
            } finally {
                _connecting.value = false
            }
        }
    }

    /** QR を読んで、そのまま [connect] する（[context] は読み取り画面を出すための Activity） */
    fun scan(context: Context) = QrScanner.scan(context) { result ->
        when (result) {
            is QrScanner.Result.Scanned -> connect(result.text)
            QrScanner.Result.Canceled -> Unit
            is QrScanner.Result.Unavailable -> message(result.message)
        }
    }

    /** サーバーの端末で、自分のサーバーを開く */
    fun useThisDevice(port: Int) = viewModelScope.launch {
        repository.update { it.copy(serverUrl = "http://127.0.0.1:$port/") }
    }

    fun forget() = viewModelScope.launch { repository.update { it.copy(serverUrl = "") } }

    fun message(text: String) = viewModelScope.launch { _messages.send(text) }
}
