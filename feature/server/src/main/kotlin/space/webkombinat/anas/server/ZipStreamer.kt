package space.webkombinat.anas.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.webkombinat.anas.core.model.SafePath
import space.webkombinat.anas.core.model.StorageBackend
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * フォルダを zip にしながら [out] へ書き出す（フォルダごとダウンロード用）。
 * 一時ファイルを作らないので、大きなフォルダでも端末の空き容量やメモリを使わない。
 * 4GB を超えるファイルや 65535 個を超えるファイルは Zip64 になる。
 */
object ZipStreamer {
    suspend fun write(backend: StorageBackend, volume: String, dir: SafePath, rootName: String, out: OutputStream) {
        val zip = ZipOutputStream(out.buffered(64 * 1024))
        // エントリーごとに setLevel を切り替えると、Android の Deflater では切り替え直後のデータが壊れる
        // （実機で確認）。速さ優先の 1 つのレベルに固定する。スマホでも Wi-Fi の速度より速い
        zip.setLevel(Deflater.BEST_SPEED)
        addDir(backend, volume, dir, "$rootName/", zip)
        withContext(Dispatchers.IO) {
            zip.finish()
            zip.flush()
        }
    }

    private suspend fun addDir(backend: StorageBackend, volume: String, dir: SafePath, prefix: String, zip: ZipOutputStream) {
        val entries = backend.list(volume, dir).filterNot { it.name.startsWith(".anas-upload-") }
        if (entries.isEmpty()) {
            // 空のフォルダも zip の中に残す
            withContext(Dispatchers.IO) {
                zip.putNextEntry(ZipEntry(prefix))
                zip.closeEntry()
            }
            return
        }
        for (entry in entries.sortedBy { it.name }) {
            // FAT で作れない名前（本体ストレージの `a:b` など）は API で扱えないので飛ばす
            val path = runCatching { dir.child(entry.name) }.getOrNull() ?: continue
            val name = prefix + entry.name
            if (entry.isDir) {
                addDir(backend, volume, path, "$name/", zip)
                continue
            }
            val handle = backend.openRead(volume, path)
            withContext(Dispatchers.IO) {
                zip.putNextEntry(ZipEntry(name).apply { if (entry.modified > 0) time = entry.modified })
                handle.open().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
        }
    }
}
