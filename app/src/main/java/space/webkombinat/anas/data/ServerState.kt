package space.webkombinat.anas.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServerState() {
    private val _serverState = MutableStateFlow(SERVER_STATUS.STOPPED)
    val serverStatus = _serverState.asStateFlow()

    fun setServerState(currentState: SERVER_STATUS) {
        _serverState.value = currentState
    }
}

enum class SERVER_STATUS {
    IS_RUNNING,
    STOPPED
}