package space.webkombinat.anas.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.model.VolumeInfo
import space.webkombinat.anas.core.storage.VolumeStore
import space.webkombinat.anas.service.ServerController
import space.webkombinat.anas.service.ServerState
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val controller: ServerController,
    private val store: VolumeStore,
    private val backend: StorageBackend,
) : ViewModel() {

    val serverState: StateFlow<ServerState> = controller.state

    private val _volumes = MutableStateFlow<List<VolumeInfo>?>(null)
    /** null は読み込み中 */
    val volumes: StateFlow<List<VolumeInfo>?> = _volumes.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { store.volumes.collect { refresh() } }
    }

    fun refresh() {
        controller.refreshAddresses()
        viewModelScope.launch { _volumes.value = runCatching { backend.volumes() }.getOrDefault(emptyList()) }
    }

    fun addVolume(uri: Uri) = viewModelScope.launch {
        runCatching { store.add(uri) }.onFailure { _messages.send("追加できませんでした: ${it.message}") }
    }

    fun removeVolume(id: String) = viewModelScope.launch { store.remove(id) }

    fun start() = controller.requestStart()

    fun stop() = controller.requestStop()
}
