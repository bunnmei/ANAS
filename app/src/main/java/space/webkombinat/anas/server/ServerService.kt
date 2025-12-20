package space.webkombinat.anas.server

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.koin.android.ext.android.inject
import space.webkombinat.anas.data.DirectoryManager
import space.webkombinat.anas.data.SERVER_STATUS
import space.webkombinat.anas.data.ServerState
import space.webkombinat.server.KtorWebServer

class ServerService: Service() {
    private lateinit var wakeLock: PowerManager.WakeLock
    private val serverState: ServerState by inject()
    private val directoryManager: DirectoryManager by inject()
    private val webServer: KtorWebServer = KtorWebServer(port = 8080)

    override fun onCreate() {
        super.onCreate()
        webServer.startServer()
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
        webServer.stopServer()
        serverState.setServerState(SERVER_STATUS.STOPPED)
        wakeLock.release()
        Log.d("ServerService", "called onDestroy()")
    }


}