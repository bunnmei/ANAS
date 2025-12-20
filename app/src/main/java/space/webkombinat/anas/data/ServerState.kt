package space.webkombinat.anas.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.NetworkInterface

class ServerState() {
    private val _serverState = MutableStateFlow(SERVER_STATUS.STOPPED)
    val serverStatus = _serverState.asStateFlow()

    private val _address = MutableStateFlow("")
    val address = _address.asStateFlow()


    fun setServerState(currentState: SERVER_STATUS) {
        _serverState.value = currentState
        if (currentState == SERVER_STATUS.IS_RUNNING) {
            _address.value = getLocalIpAddress()
        } else {
            _address.value = ""
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val net = NetworkInterface.getNetworkInterfaces()
            while (net.hasMoreElements()) {
                val intf = net.nextElement()
                val enumIp = intf.inetAddresses
                while (enumIp.hasMoreElements()) {
                    val inetAddress = enumIp.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress.isSiteLocalAddress) {
                        Log.d("ServerState", "getLocalIpAddress: ${inetAddress.hostAddress}")
                        return inetAddress.hostAddress.toString()
                    }
                }
            }
            return "Unknown IP"
        } catch (e: Exception) {
            e.printStackTrace()
            return "null"
        }
    }
}

enum class SERVER_STATUS {
    IS_RUNNING,
    STOPPED
}