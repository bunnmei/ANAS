package space.webkombinat.anas.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.webkombinat.anas.core.model.Entry
import space.webkombinat.anas.core.model.SafePath
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.model.StorageException

/** ファイル・フォルダの移動とコピー（NAS の API と WebDAV で共通） */
internal object FileOps {
    /** 書き込み途中の一時ファイル（[StorageBackend.write] が作る） */
    const val TEMP_PREFIX = ".anas-upload-"

    /**
     * [src] を [dstVolume] の [dst]（コピー後のパス）へ、フォルダなら中身ごとコピーする。
     * [dst] にすでに何かあれば [StorageException.AlreadyExists]（中途半端に混ざらないよう、最初に確かめる）
     */
    suspend fun copy(backend: StorageBackend, srcVolume: String, src: SafePath, dstVolume: String, dst: SafePath): Entry {
        val entry = backend.stat(srcVolume, src) ?: throw StorageException.NotFound(src.toString())
        checkTarget(backend, srcVolume, src, dstVolume, dst, "コピー")
        copyTree(backend, srcVolume, entry, dstVolume, dst)
        return backend.stat(dstVolume, dst) ?: throw StorageException.Io("コピーしたものが見つかりません: $dst")
    }

    /**
     * [src] を [dstVolume] の [dst]（移動後のパス）へ移す。
     * 同じボリュームならストレージの移動（大きなファイルでもすぐ終わる）を使い、できなければコピーしてから消す。
     * コピーの途中で失敗したときは元を消さない
     */
    suspend fun move(backend: StorageBackend, srcVolume: String, src: SafePath, dstVolume: String, dst: SafePath): Entry {
        if (src.isRoot) throw IllegalArgumentException("ストレージの一番上は移動できません")
        val entry = backend.stat(srcVolume, src) ?: throw StorageException.NotFound(src.toString())
        checkTarget(backend, srcVolume, src, dstVolume, dst, "移動")
        if (srcVolume == dstVolume) {
            if (src.parent == dst.parent) return backend.rename(srcVolume, src, dst.name)
            if (src.name == dst.name) backend.move(srcVolume, src, dst.parent)?.let { return it }
        }
        copyTree(backend, srcVolume, entry, dstVolume, dst)
        backend.delete(srcVolume, src)
        return backend.stat(dstVolume, dst) ?: throw StorageException.Io("移動したものが見つかりません: $dst")
    }

    private suspend fun checkTarget(
        backend: StorageBackend,
        srcVolume: String,
        src: SafePath,
        dstVolume: String,
        dst: SafePath,
        what: String,
    ) {
        if (dst.isRoot) throw IllegalArgumentException("${what}先が不正です")
        if (srcVolume == dstVolume && dst.relativeTo(src) != null) {
            throw IllegalArgumentException(if (dst == src) "同じ場所には${what}できません" else "フォルダをその中には${what}できません")
        }
        if (backend.stat(dstVolume, dst.parent)?.isDir != true) throw StorageException.NotFound("${what}先のフォルダ ${dst.parent}")
        if (backend.stat(dstVolume, dst) != null) throw StorageException.AlreadyExists(dst.toString())
    }

    /** 1 つずつ読み書きする（ボリュームをまたぐときや、ストレージが移動に対応していないとき） */
    private suspend fun copyTree(backend: StorageBackend, srcVolume: String, src: Entry, dstVolume: String, dst: SafePath) {
        if (src.isDir) {
            backend.mkdir(dstVolume, dst.parent, dst.name)
            for (child in backend.list(srcVolume, SafePath.parse(src.path))) {
                if (child.name.startsWith(TEMP_PREFIX)) continue
                copyTree(backend, srcVolume, child, dstVolume, dst.child(child.name))
            }
        } else {
            val handle = backend.openRead(srcVolume, SafePath.parse(src.path))
            backend.write(dstVolume, dst.parent, dst.name, overwrite = false) { out ->
                withContext(Dispatchers.IO) { handle.open(0).use { it.copyTo(out, 256 * 1024) } }
            }
        }
    }
}
