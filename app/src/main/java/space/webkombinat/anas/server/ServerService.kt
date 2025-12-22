package space.webkombinat.anas.server

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.koin.android.ext.android.inject
import space.webkombinat.anas.data.SERVER_STATUS
import space.webkombinat.anas.data.ServerState
import space.webkombinat.server.KtorWebServer
import space.webkombinat.storage.DirectoryManager

class ServerService: Service() {
    private lateinit var wakeLock: PowerManager.WakeLock
    private val serverState: ServerState by inject()
    private val directoryManager: DirectoryManager by inject()
    private var webServer: KtorWebServer? = null

    override fun onCreate() {
        super.onCreate()
        if (webServer == null) {
            val uris = directoryManager.folders.value.map {
                "${it.folderName.uri.path}?${it.alias}"
            }
            webServer = KtorWebServer(port = 8080, uris = uris, fileAccess = directoryManager)
            webServer!!.startServer()
        }
        serverState.setServerState(SERVER_STATUS.IS_RUNNING)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            /* levelAndFlags = */ PowerManager.PARTIAL_WAKE_LOCK,
            /* tag = */ "Service::WebServerWakeLock"
        )
        try {
            //意図してWakeLockのタイムアウトを指定していないため
            //Warnを出さないように設定
            @SuppressLint("WakelockTimeout")
            wakeLock.acquire() // CPUスリープ防止
        } finally {
            //異常終了した場合にロックを解放
            wakeLock.release()
        }
        Log.d("ServerService", "called onCreate()")

    }

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        webServer?.stopServer()
        webServer = null
        serverState.setServerState(SERVER_STATUS.STOPPED)
        try {
            wakeLock.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Log.d("ServerService", "called onDestroy()")
    }

}