package space.webkombinat.anas.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * java.io.File で読み書きする [StorageBackend]。サーバーのテストと、PC 上での動作確認に使う。
 * [roots] は ボリューム ID → ルートフォルダ。
 */
class LocalFileBackend(
    private val roots: Map<String, File>,
    private val readOnly: Set<String> = emptySet(),
) : StorageBackend {

    override suspend fun volumes(): List<VolumeInfo> = roots.map { (id, dir) ->
        VolumeInfo(
            id = id,
            name = dir.name,
            online = dir.isDirectory,
            writable = id !in readOnly,
            totalBytes = dir.totalSpace,
            freeBytes = dir.usableSpace,
        )
    }

    private fun root(volume: String): File {
        val dir = roots[volume] ?: throw StorageException.NotFound(volume)
        if (!dir.isDirectory) throw StorageException.Offline(volume)
        return dir
    }

    private fun file(volume: String, path: SafePath): File =
        path.segments.fold(root(volume)) { acc, s -> File(acc, s) }

    private fun writableFile(volume: String, path: SafePath): File {
        if (volume in readOnly) throw StorageException.ReadOnly(volume)
        return file(volume, path)
    }

    private fun File.toEntry(path: SafePath) = Entry(
        name = name,
        path = path.toString(),
        isDir = isDirectory,
        size = if (isDirectory) 0 else length(),
        modified = lastModified(),
        mime = if (isDirectory) MimeTypes.DIRECTORY else MimeTypes.of(name),
    )

    override suspend fun stat(volume: String, path: SafePath): Entry? = withContext(Dispatchers.IO) {
        val f = file(volume, path)
        if (f.exists()) f.toEntry(path) else null
    }

    override suspend fun list(volume: String, path: SafePath): List<Entry> = withContext(Dispatchers.IO) {
        val dir = file(volume, path)
        if (!dir.exists()) throw StorageException.NotFound(path.toString())
        if (!dir.isDirectory) throw StorageException.NotDirectory(path.toString())
        dir.listFiles().orEmpty().map { it.toEntry(path.child(it.name)) }
    }

    override suspend fun openRead(volume: String, path: SafePath): ReadHandle = withContext(Dispatchers.IO) {
        val f = file(volume, path)
        if (!f.exists()) throw StorageException.NotFound(path.toString())
        if (f.isDirectory) throw StorageException.IsDirectory(path.toString())
        ReadHandle(f.toEntry(path)) { offset ->
            FileInputStream(f).also { it.channel.position(offset) }
        }
    }

    override suspend fun write(
        volume: String,
        dir: SafePath,
        name: String,
        overwrite: Boolean,
        writer: suspend (java.io.OutputStream) -> Unit,
    ): Entry {
        val target = dir.child(name)
        val parent = writableFile(volume, dir)
        val dest = File(parent, name)
        withContext(Dispatchers.IO) {
            if (!parent.isDirectory) throw StorageException.NotFound(dir.toString())
            if (dest.isDirectory) throw StorageException.IsDirectory(target.toString())
            if (dest.exists() && !overwrite) throw StorageException.AlreadyExists(target.toString())
        }
        val temp = File(parent, ".anas-upload-${System.nanoTime()}.part")
        try {
            temp.outputStream().use { writer(it) }
            withContext(Dispatchers.IO) {
                if (dest.exists() && !overwrite) throw StorageException.AlreadyExists(target.toString())
                if (dest.exists()) dest.delete()
                if (!temp.renameTo(dest)) throw StorageException.Io("名前を変更できません: $target")
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        return dest.toEntry(target)
    }

    override suspend fun mkdir(volume: String, dir: SafePath, name: String): Entry = withContext(Dispatchers.IO) {
        val target = dir.child(name)
        val f = writableFile(volume, target)
        if (f.isFile) throw StorageException.AlreadyExists(target.toString())
        if (!f.isDirectory && !f.mkdir()) {
            if (!f.parentFile.isDirectory) throw StorageException.NotFound(dir.toString())
            throw StorageException.Io("フォルダを作れません: $target")
        }
        f.toEntry(target)
    }

    override suspend fun delete(volume: String, path: SafePath) = withContext(Dispatchers.IO) {
        if (path.isRoot) throw StorageException.Io("ルートは削除できません")
        val f = writableFile(volume, path)
        if (!f.exists()) throw StorageException.NotFound(path.toString())
        if (!f.deleteRecursively()) throw StorageException.Io("削除できません: $path")
    }

    override suspend fun rename(volume: String, path: SafePath, newName: String): Entry = withContext(Dispatchers.IO) {
        if (path.isRoot) throw StorageException.Io("ルートは名前を変えられません")
        val target = path.parent.child(newName)
        val f = writableFile(volume, path)
        val dest = file(volume, target)
        if (!f.exists()) throw StorageException.NotFound(path.toString())
        if (dest.exists()) throw StorageException.AlreadyExists(target.toString())
        try {
            if (!f.renameTo(dest)) throw IOException()
        } catch (e: IOException) {
            throw StorageException.Io("名前を変更できません: $path", e)
        }
        dest.toEntry(target)
    }
}
