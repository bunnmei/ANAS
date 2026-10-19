package space.webkombinat.anas.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import space.webkombinat.anas.di.AppScope
import space.webkombinat.anas.di.SettingsStore
import space.webkombinat.anas.server.ServerConfig
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** この端末の使い方。SERVER はサーバー＋クライアント、CLIENT はほかの端末の NAS を見るだけ */
enum class AppMode { SERVER, CLIENT }

data class AppSettings(
    /** DataStore から読み終わったら true（読み込み前の既定値で画面を出さないため） */
    val loaded: Boolean = false,
    /** null なら初回起動で、まだ選んでいない */
    val mode: AppMode? = null,
    /** クライアントで開くサーバーのトップ（`http://192.168.1.108:8080/`）。空なら未設定 */
    val serverUrl: String = "",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val port: Int = ServerConfig.DEFAULT_PORT,
    val password: String = "",
    val readOnly: Boolean = false,
    val disabledExtensions: Set<String> = emptySet(),
    val fullAccessExtensions: Set<String> = emptySet(),
    /** 拡張機能 ID → 外部アクセスを許可したサイト（anas.json の `network`） */
    val networkGrants: Map<String, Set<String>> = emptyMap(),
    val webDavEnabled: Boolean = false,
    val webDavReadOnly: Boolean = false,
    /** `/` を開いたときに出す拡張機能（anas.json に `"home": true`）。null なら標準の画面 */
    val homeExtension: String? = null,
) {
    fun toServerConfig(appVersion: String) = ServerConfig(
        port = port,
        password = password,
        readOnly = readOnly,
        disabledExtensions = disabledExtensions,
        fullAccessExtensions = fullAccessExtensions,
        networkGrants = networkGrants,
        webDavEnabled = webDavEnabled,
        webDavReadOnly = webDavReadOnly,
        homeExtension = homeExtension,
        appVersion = appVersion,
    )
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @SettingsStore private val dataStore: DataStore<Preferences>,
    @AppScope scope: CoroutineScope,
) : SettingsRepository {

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val SERVER_URL = stringPreferencesKey("server_url")
        val THEME = stringPreferencesKey("theme")
        val PORT = intPreferencesKey("port")
        val PASSWORD = stringPreferencesKey("password")
        val READ_ONLY = booleanPreferencesKey("read_only")
        val DISABLED = stringSetPreferencesKey("disabled_extensions")
        val FULL_ACCESS = stringSetPreferencesKey("full_access_extensions")
        /** `"<id> <origin>"` の集合（ID とオリジンには空白が入らない） */
        val NETWORK = stringSetPreferencesKey("network_grants")
        val WEBDAV = booleanPreferencesKey("webdav_enabled")
        val WEBDAV_READ_ONLY = booleanPreferencesKey("webdav_read_only")
        val HOME = stringPreferencesKey("home_extension")
    }

    private val flow = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    override val settings: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    override suspend fun current(): AppSettings = flow.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            next.mode?.let { prefs[Keys.MODE] = it.name } ?: prefs.remove(Keys.MODE)
            prefs[Keys.SERVER_URL] = next.serverUrl
            prefs[Keys.THEME] = next.theme.name
            prefs[Keys.PORT] = next.port
            prefs[Keys.PASSWORD] = next.password
            prefs[Keys.READ_ONLY] = next.readOnly
            prefs[Keys.DISABLED] = next.disabledExtensions
            prefs[Keys.FULL_ACCESS] = next.fullAccessExtensions
            prefs[Keys.WEBDAV] = next.webDavEnabled
            prefs[Keys.WEBDAV_READ_ONLY] = next.webDavReadOnly
            next.homeExtension?.let { prefs[Keys.HOME] = it } ?: prefs.remove(Keys.HOME)
            prefs[Keys.NETWORK] = next.networkGrants.flatMap { (id, origins) -> origins.map { "$id $it" } }.toSet()
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        loaded = true,
        mode = this[Keys.MODE]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() },
        serverUrl = this[Keys.SERVER_URL].orEmpty(),
        theme = this[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        port = this[Keys.PORT] ?: ServerConfig.DEFAULT_PORT,
        password = this[Keys.PASSWORD].orEmpty(),
        readOnly = this[Keys.READ_ONLY] ?: false,
        disabledExtensions = this[Keys.DISABLED].orEmpty(),
        fullAccessExtensions = this[Keys.FULL_ACCESS].orEmpty(),
        networkGrants = this[Keys.NETWORK].orEmpty()
            .mapNotNull { entry -> entry.split(' ', limit = 2).takeIf { it.size == 2 } }
            .groupBy({ it[0] }, { it[1] })
            .mapValues { it.value.toSet() },
        webDavEnabled = this[Keys.WEBDAV] ?: false,
        webDavReadOnly = this[Keys.WEBDAV_READ_ONLY] ?: false,
        homeExtension = this[Keys.HOME],
    )
}
