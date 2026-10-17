package space.webkombinat.anas.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import space.webkombinat.anas.BuildConfig
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.data.SettingsRepository
import space.webkombinat.anas.di.AppScope
import space.webkombinat.anas.server.AnasServer
import space.webkombinat.anas.server.ExtensionRegistry
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(val port: Int, val addresses: List<LocalAddress>) : ServerState {
        val urls: List<String> get() = addresses.map { "http://${it.ip}:$port" }
    }
    data class Failed(val message: String) : ServerState
}

/**
 * サーバーの開始・停止。UI は [requestStart] / [requestStop] で前景サービスを動かし、
 * 実際の起動・停止は [NasService] が [startServer] / [stopServer] で行う。
 */
@Singleton
class ServerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backend: StorageBackend,
    private val registry: ExtensionRegistry,
    private val settings: SettingsRepository,
    private val assets: AndroidAssetSource,
    @AppScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private var server: AnasServer? = null

    fun requestStart() {
        _state.value = ServerState.Starting
        ContextCompat.startForegroundService(context, Intent(context, NasService::class.java))
    }

    fun requestStop() {
        context.stopService(Intent(context, NasService::class.java))
    }

    /** ネットワークが変わったときなどに、表示するアドレスを取り直す */
    fun refreshAddresses() {
        val current = _state.value
        if (current is ServerState.Running) _state.value = current.copy(addresses = NetworkAddresses.list())
    }

    internal suspend fun startServer(): ServerState {
        if (server != null) return _state.value
        _state.value = ServerState.Starting
        val port = settings.current().port
        val next = AnasServer(backend, registry, assets) {
            settings.settings.value.toServerConfig(BuildConfig.VERSION_NAME)
        }
        val result = try {
            withContext(Dispatchers.IO) { next.start(port) }
            server = next
            scope.launch { runCatching { registry.scan() } }
            ServerState.Running(port, NetworkAddresses.list())
        } catch (e: Exception) {
            next.stop()
            ServerState.Failed(
                if (e is java.net.BindException) "ポート $port は使用中です。設定で別のポートにしてください"
                else e.message ?: e.toString(),
            )
        }
        _state.value = result
        return result
    }

    internal fun stopServer() {
        server?.stop()
        server = null
        if (_state.value !is ServerState.Failed) _state.value = ServerState.Stopped
    }
}
