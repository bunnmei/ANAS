package space.webkombinat.anas.server

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.koin.android.ext.android.inject
import space.webkombinat.anas.data.SERVER_STATUS
import space.webkombinat.anas.data.ServerState

class ServerService: Service() {
    private lateinit var wakeLock: PowerManager.WakeLock
    private val serverState: ServerState by inject()

    override fun onCreate() {
        super.onCreate()
        serverState.setServerState(SERVER_STATUS.IS_RUNNING)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            /* levelAndFlags = */ PowerManager.PARTIAL_WAKE_LOCK,
            /* tag = */ "Service::WebServerWakeLock"
        )
        wakeLock.acquire() // CPUスリープ防止
        Log.d("ServerService", "called onCreate()")
    }

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serverState.setServerState(SERVER_STATUS.STOPPED)
        wakeLock.release()
        Log.d("ServerService", "called onDestroy()")
    }


}