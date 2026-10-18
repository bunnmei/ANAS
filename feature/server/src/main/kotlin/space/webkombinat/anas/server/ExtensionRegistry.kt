package space.webkombinat.anas.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.webkombinat.anas.core.model.ExtensionManifest
import space.webkombinat.anas.core.model.SafePath
import space.webkombinat.anas.core.model.StorageBackend

/** ボリューム直下で見つかった拡張機能。[manifest] が null なら [error] に理由が入る */
data class InstalledExtension(
    val id: String,
    val volume: String,
    val folder: SafePath,
    val manifest: ExtensionManifest?,
    val error: String?,
) {
    val appDir: SafePath get() = folder.child(ExtensionManifest.APP_DIR)
    val dataDir: SafePath get() = folder.child(ExtensionManifest.DATA_DIR)
    val usable: Boolean get() = manifest != null && error == null

    fun toInfo(config: ServerConfig): ExtensionInfo = ExtensionInfo(
        id = id,
        name = manifest?.name ?: folder.name,
        version = manifest?.version ?: "",
        description = manifest?.description ?: "",
        volume = volume,
        folder = folder.toString(),
        url = "/x/$id/",
        iconUrl = manifest?.icon?.let { "/x/$id/" + SafePath.parse(it).segments.joinToString("/") },
        scope = manifest?.scope ?: ExtensionManifest.Scope.SELF,
        enabled = id !in config.disabledExtensions,
        fullAccess = manifest?.scope == ExtensionManifest.Scope.ALL && id in config.fullAccessExtensions,
        network = manifest?.network.orEmpty(),
        networkAllowed = manifest?.network.orEmpty().filter { it in config.networkGrants[id].orEmpty() },
        error = error,
    )
}

/**
 * 各ボリュームの直下にある `.<id>/anas.json` を探して、拡張機能の一覧を持つ。
 * 探すのはサーバー起動時・[scan] を呼んだとき（Web UI やアプリの「再スキャン」、zip インストール後）。
 */
class ExtensionRegistry(private val backend: StorageBackend) {
    private val mutex = Mutex()
    private val _extensions = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val extensions: StateFlow<List<InstalledExtension>> = _extensions.asStateFlow()

    fun find(id: String): InstalledExtension? = _extensions.value.firstOrNull { it.id == id }

    /** [volume] の [path] を含む拡張機能 */
    fun containing(volume: String, path: SafePath): InstalledExtension? =
        _extensions.value.firstOrNull { it.volume == volume && path.startsWith(it.folder) }

    suspend fun scan(): List<InstalledExtension> = mutex.withLock {
        val found = mutableListOf<InstalledExtension>()
        for (volume in backend.volumes().filter { it.online }) {
            val folders = runCatching { backend.list(volume.id, SafePath.ROOT) }.getOrDefault(emptyList())
                .filter { it.isDir && it.name.startsWith(".") }
                .sortedBy { it.name }
            for (folder in folders) {
                val dir = SafePath.ROOT.child(folder.name)
                val manifestPath = dir.child(ExtensionManifest.FILE_NAME)
                val stat = runCatching { backend.stat(volume.id, manifestPath) }.getOrNull() ?: continue
                found += load(volume.id, dir, stat.size, found)
            }
        }
        _extensions.value = found
        found
    }

    private suspend fun load(
        volume: String,
        dir: SafePath,
        size: Long,
        already: List<InstalledExtension>,
    ): InstalledExtension {
        val fallbackId = ExtensionManifest.idFromFolderName(dir.name) ?: dir.name
        fun broken(message: String) = InstalledExtension(fallbackId, volume, dir, null, message)

        if (size > MAX_MANIFEST_BYTES) return broken("anas.json が大きすぎます")
        val text = try {
            backend.openRead(volume, dir.child(ExtensionManifest.FILE_NAME)).open().use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            return broken("anas.json を読めません: ${e.message}")
        }
        val manifest = ExtensionManifest.parse(text, dir.name).getOrElse { return broken(it.message ?: "anas.json が不正です") }
        if (already.any { it.id == manifest.id }) {
            return InstalledExtension(manifest.id, volume, dir, null, "同じ id の拡張機能がほかのストレージにあります")
        }
        return InstalledExtension(manifest.id, volume, dir, manifest, null)
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}
