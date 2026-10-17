package space.webkombinat.anas.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import space.webkombinat.anas.core.model.ExtensionManifest
import space.webkombinat.anas.core.model.InvalidPathException
import space.webkombinat.anas.core.model.SafePath
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.model.mkdirs
import java.io.File
import java.util.zip.ZipFile

/**
 * 拡張機能の zip をボリュームに展開する。
 *
 * - zip の中の一番浅い `anas.json` があるフォルダを拡張機能のルートとみなす
 *   （`anas.json` が zip 直下でも、`.jisui/anas.json` でも、`jisui/anas.json` でもよい）
 * - 展開先は常に `/.<id>/`。既存のファイルは上書きし、zip にないファイル（data/ の中身など）は消さない
 */
class ZipInstaller(private val backend: StorageBackend) {

    suspend fun install(volume: String, zip: File): ExtensionManifest {
        val (manifest, entries) = withContext(Dispatchers.IO) { inspect(zip) }
        val root = SafePath.ROOT.child(".${manifest.id}")
        backend.mkdirs(volume, root.child(ExtensionManifest.DATA_DIR))
        ZipFile(zip).use { file ->
            for ((entryName, rel) in entries) {
                val target = root.resolve(rel)
                backend.mkdirs(volume, target.parent)
                backend.write(volume, target.parent, target.name, overwrite = true) { out ->
                    withContext(Dispatchers.IO) {
                        file.getInputStream(file.getEntry(entryName)).use { it.copyTo(out) }
                    }
                }
            }
        }
        return manifest
    }

    /** マニフェストを検証し、展開するファイル（zip 内の名前 → 拡張機能ルートからの相対パス）を返す */
    private fun inspect(zip: File): Pair<ExtensionManifest, List<Pair<String, SafePath>>> = ZipFile(zip).use { file ->
        val names = file.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
        val manifestName = names
            .filter { it == ExtensionManifest.FILE_NAME || it.endsWith("/" + ExtensionManifest.FILE_NAME) }
            .filter { !it.startsWith("__MACOSX/") }
            .minByOrNull { it.count { c -> c == '/' } }
            ?: throw IllegalArgumentException("zip の中に anas.json がありません")
        val prefix = manifestName.removeSuffix(ExtensionManifest.FILE_NAME)

        val text = file.getInputStream(file.getEntry(manifestName)).use { it.readBytes().decodeToString() }
        val id = runCatching { Json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
            ?: throw IllegalArgumentException("anas.json に \"id\" がありません")
        val manifest = ExtensionManifest.parse(text, ".$id").getOrThrow()

        val entries = names
            .filter { it.startsWith(prefix) && !it.startsWith("__MACOSX/") && !it.endsWith(".DS_Store") }
            .map { name ->
                val rel = try {
                    SafePath.parse(name.removePrefix(prefix))
                } catch (e: InvalidPathException) {
                    throw IllegalArgumentException("zip に不正なパスがあります: $name")
                }
                rel.segments.forEach { SafePath.ROOT.child(it) } // ファイル名の検証
                name to rel
            }
        manifest to entries
    }
}
