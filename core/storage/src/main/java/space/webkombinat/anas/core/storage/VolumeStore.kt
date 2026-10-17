package space.webkombinat.anas.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** ユーザーが SAF で選んだフォルダ。再起動しても使えるよう DataStore に保存する */
@Serializable
data class RegisteredVolume(
    /** URL に使う ID（`disk1` など）。一度決めたら変えない */
    val id: String,
    /** OpenDocumentTree で得た tree URI */
    val treeUri: String,
    /** 表示名（`USB ドライブ / NAS` など） */
    val label: String,
)

/** 登録したストレージの一覧。追加すると URI の権限を永続化し、削除すると手放す */
class VolumeStore(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val key = stringPreferencesKey("volumes")
    private val json = Json { ignoreUnknownKeys = true }

    val volumes: StateFlow<List<RegisteredVolume>> = dataStore.data
        .map { prefs -> decode(prefs[key]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** DataStore からの最初の読み込みを待ってから一覧を返す */
    suspend fun current(): List<RegisteredVolume> = decode(dataStore.data.first()[key])

    private fun decode(text: String?): List<RegisteredVolume> =
        text?.let { runCatching { json.decodeFromString<List<RegisteredVolume>>(it) }.getOrNull() }.orEmpty()

    /** 同じフォルダがすでに登録されていればそれを返す */
    suspend fun add(treeUri: Uri): RegisteredVolume {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        var added: RegisteredVolume? = null
        dataStore.edit { prefs ->
            val list = decode(prefs[key])
            added = list.firstOrNull { it.treeUri == treeUri.toString() } ?: run {
                val used = list.map { it.id }.toSet()
                val id = generateSequence(1) { it + 1 }.map { "disk$it" }.first { it !in used }
                RegisteredVolume(id, treeUri.toString(), labelFor(treeUri)).also {
                    prefs[key] = json.encodeToString(list + it)
                }
            }
        }
        return added!!
    }

    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            val list = decode(prefs[key])
            list.firstOrNull { it.id == id }?.let { volume ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        volume.treeUri.toUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            prefs[key] = json.encodeToString(list.filterNot { it.id == id })
        }
    }

    /** まだ URI の権限を持っているか（端末の初期化などで失われることがある） */
    fun hasPermission(volume: RegisteredVolume): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri.toString() == volume.treeUri && it.isReadPermission }

    /** `C00A-9FBB:NAS` → `USB ドライブ / NAS` のような表示名 */
    private fun labelFor(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return treeUri.toString()
        val volumeId = docId.substringBefore(':')
        val path = docId.substringAfter(':', "")
        val sm = context.getSystemService(StorageManager::class.java)
        val description = sm.storageVolumes.firstOrNull { v ->
            if (volumeId == "primary") v.isPrimary else v.uuid.equals(volumeId, ignoreCase = true)
        }?.getDescription(context) ?: volumeId
        return if (path.isEmpty()) description else "$description / $path"
    }
}
