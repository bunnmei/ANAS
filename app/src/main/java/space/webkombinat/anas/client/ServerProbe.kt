package space.webkombinat.anas.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 接続先が本当に ANAS のサーバーかを `/api/v1/info` で確かめる */
object ServerProbe {

    sealed interface Result {
        data class Ok(val version: String) : Result
        /** つながったが ANAS ではない */
        data object NotAnas : Result
        data class Unreachable(val message: String) : Result
    }

    suspend fun check(serverUrl: String): Result = withContext(Dispatchers.IO) {
        val conn = try {
            URL("${serverUrl}api/v1/info").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return@withContext Result.Unreachable(e.message ?: e.javaClass.simpleName)
        }
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = false
            if (conn.responseCode != 200) return@withContext Result.NotAnas
            val json = JSONObject(conn.inputStream.use { it.readBytes().decodeToString() })
            if (json.optString("app") == "ANAS") Result.Ok(json.optString("version")) else Result.NotAnas
        } catch (e: org.json.JSONException) {
            Result.NotAnas
        } catch (e: Exception) {
            Result.Unreachable(e.message ?: e.javaClass.simpleName)
        } finally {
            conn.disconnect()
        }
    }
}
