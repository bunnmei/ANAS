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

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val port: Int = ServerConfig.DEFAULT_PORT,
    val password: String = "",
    val readOnly: Boolean = false,
    val disabledExtensions: Set<String> = emptySet(),
    val fullAccessExtensions: Set<String> = emptySet(),
) {
    fun toServerConfig(appVersion: String) = ServerConfig(
        port = port,
        password = password,
        readOnly = readOnly,
        disabledExtensions = disabledExtensions,
        fullAccessExtensions = fullAccessExtensions,
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
        val THEME = stringPreferencesKey("theme")
        val PORT = intPreferencesKey("port")
        val PASSWORD = stringPreferencesKey("password")
        val READ_ONLY = booleanPreferencesKey("read_only")
        val DISABLED = stringSetPreferencesKey("disabled_extensions")
        val FULL_ACCESS = stringSetPreferencesKey("full_access_extensions")
    }

    private val flow = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    override val settings: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    override suspend fun current(): AppSettings = flow.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.THEME] = next.theme.name
            prefs[Keys.PORT] = next.port
            prefs[Keys.PASSWORD] = next.password
            prefs[Keys.READ_ONLY] = next.readOnly
            prefs[Keys.DISABLED] = next.disabledExtensions
            prefs[Keys.FULL_ACCESS] = next.fullAccessExtensions
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        theme = this[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        port = this[Keys.PORT] ?: ServerConfig.DEFAULT_PORT,
        password = this[Keys.PASSWORD].orEmpty(),
        readOnly = this[Keys.READ_ONLY] ?: false,
        disabledExtensions = this[Keys.DISABLED].orEmpty(),
        fullAccessExtensions = this[Keys.FULL_ACCESS].orEmpty(),
    )
}
