package space.webkombinat.anas.ui.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import space.webkombinat.anas.BuildConfig
import space.webkombinat.anas.core.model.SafePath
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.model.VolumeInfo
import space.webkombinat.anas.core.model.mkdirs
import space.webkombinat.anas.data.ExtensionTemplate
import space.webkombinat.anas.data.SettingsRepository
import space.webkombinat.anas.server.ExtensionInfo
import space.webkombinat.anas.server.ExtensionRegistry
import space.webkombinat.anas.service.ServerController
import space.webkombinat.anas.service.ServerState
import javax.inject.Inject

@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    private val registry: ExtensionRegistry,
    private val settings: SettingsRepository,
    private val backend: StorageBackend,
    controller: ServerController,
) : ViewModel() {

    val extensions: StateFlow<List<ExtensionInfo>> =
        combine(registry.extensions, settings.settings) { list, s ->
            val config = s.toServerConfig(BuildConfig.VERSION_NAME)
            list.map { it.toInfo(config) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** ホーム画面に選んでいる拡張機能の ID */
    val homeId: StateFlow<String?> = settings.settings.map { it.homeExtension }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 拡張機能を開くときのサーバーの URL（停止中は null） */
    val baseUrl: StateFlow<String?> = controller.state
        .map { (it as? ServerState.Running)?.urls?.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val writableVolumes: StateFlow<List<VolumeInfo>> = _volumes.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        rescan()
    }

    fun rescan() = viewModelScope.launch {
        _scanning.value = true
        runCatching { registry.scan() }.onFailure { _messages.send(it.message ?: it.toString()) }
        _volumes.value = runCatching { backend.volumes().filter { it.online && it.writable } }.getOrDefault(emptyList())
        _scanning.value = false
    }

    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        settings.update { s ->
            s.copy(disabledExtensions = if (enabled) s.disabledExtensions - id else s.disabledExtensions + id)
        }
    }

    fun setFullAccess(id: String, allowed: Boolean) = viewModelScope.launch {
        settings.update { s ->
            s.copy(fullAccessExtensions = if (allowed) s.fullAccessExtensions + id else s.fullAccessExtensions - id)
        }
    }

    /** [id] をホーム画面にする（ほかに選んでいたものは外れる）。[on] が false なら標準の画面に戻す */
    fun setHome(id: String, on: Boolean) = viewModelScope.launch {
        settings.update { s ->
            s.copy(homeExtension = if (on) id else s.homeExtension.takeIf { it != id })
        }
    }

    /** anas.json の `network` に書かれたサイトへのアクセスを許可する（[origins] が空なら取り消す） */
    fun setNetwork(id: String, origins: List<String>) = viewModelScope.launch {
        settings.update { s ->
            s.copy(networkGrants = if (origins.isEmpty()) s.networkGrants - id else s.networkGrants + (id to origins.toSet()))
        }
    }

    /** `/.<id>/` にひな形を作る。既存のファイルは上書きしない */
    fun create(volume: String, id: String, name: String) = viewModelScope.launch {
        val result = runCatching {
            val root = SafePath.ROOT.child(".$id")
            for ((path, text) in ExtensionTemplate.files(id, name)) {
                val target = root.resolve(path)
                backend.mkdirs(volume, target.parent)
                if (backend.stat(volume, target) == null) {
                    backend.write(volume, target.parent, target.name, overwrite = false) { it.write(text.toByteArray()) }
                }
            }
            registry.scan()
        }
        _messages.send(result.fold({ "/.$id を作りました" }, { "作成できませんでした: ${it.message}" }))
    }
}
