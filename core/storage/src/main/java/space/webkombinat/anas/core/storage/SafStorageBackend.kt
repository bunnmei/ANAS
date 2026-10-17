package space.webkombinat.anas.core.storage

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.net.toUri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.webkombinat.anas.core.model.Entry
import space.webkombinat.anas.core.model.FileNames
import space.webkombinat.anas.core.model.MimeTypes
import space.webkombinat.anas.core.model.ReadHandle
import space.webkombinat.anas.core.model.SafePath
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.model.StorageException
import space.webkombinat.anas.core.model.VolumeInfo
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * SAF (DocumentsContract) で読み書きする [StorageBackend]。
 *
 * DocumentFile は1階層ごとに全件を検索するので遅い。ここでは
 * - 本体 / SD / USB（`com.android.externalstorage.documents`）では、ドキュメント ID が
 *   `<ボリューム>:<パス>` という形なので、パスから ID を直接組み立てる
 * - それ以外のプロバイダでは、子を検索して ID を探し、結果をキャッシュする
 */
class SafStorageBackend(
    private val context: Context,
    private val store: VolumeStore,
) : StorageBackend {
    private val resolver: ContentResolver get() = context.contentResolver
    private val idCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2048
    }

    private data class Row(val docId: String, val name: String, val mime: String, val size: Long, val modified: Long, val flags: Int) {
        val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
    }

    private class Vol(val registered: RegisteredVolume) {
        val treeUri: Uri = registered.treeUri.toUri()
        val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)
        val isExternalStorage: Boolean = treeUri.authority == EXTERNAL_STORAGE_AUTHORITY
        fun uri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    private suspend fun vol(id: String): Vol {
        val registered = store.current().firstOrNull { it.id == id } ?: throw StorageException.NotFound(id)
        return Vol(registered)
    }

    override suspend fun volumes(): List<VolumeInfo> = withContext(Dispatchers.IO) {
        store.current().map { registered ->
            val vol = Vol(registered)
            val root = if (store.hasPermission(registered)) query(vol.uri(vol.rootId)) else null
            val space = if (root != null) space(vol) else null
            VolumeInfo(
                id = registered.id,
                name = registered.label,
                online = root != null,
                writable = root != null && root.flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0,
                totalBytes = space?.first,
                freeBytes = space?.second,
            )
        }
    }

    override suspend fun stat(volume: String, path: SafePath): Entry? = withContext(Dispatchers.IO) {
        val vol = vol(volume)
        resolve(vol, path)?.toEntry(path.toString())
    }

    override suspend fun list(volume: String, path: SafePath): List<Entry> = withContext(Dispatchers.IO) {
        val vol = vol(volume)
        val dir = requireDir(vol, path)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vol.treeUri, dir.docId)
        val prefix = if (path.isRoot) "" else path.toString()
        val rows = try {
            resolver.query(childrenUri, COLUMNS, null, null, null)?.use { c -> buildList<Row> { while (c.moveToNext()) add(c.toRow()) } }
        } catch (e: Exception) {
            throw StorageException.Io("一覧を取得できません: $path", e)
        } ?: emptyList()
        rows.map { row ->
            cachePut(vol, "$prefix/${row.name}", row.docId)
            row.toEntry("$prefix/${row.name}")
        }
    }

    override suspend fun openRead(volume: String, path: SafePath): ReadHandle = withContext(Dispatchers.IO) {
        val vol = vol(volume)
        val row = resolve(vol, path) ?: throw notFoundOrOffline(vol, path)
        if (row.isDir) throw StorageException.IsDirectory(path.toString())
        val uri = vol.uri(row.docId)
        ReadHandle(row.toEntry(path.toString())) { offset -> open(uri, offset) }
    }

    private fun open(uri: Uri, offset: Long): InputStream {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw StorageException.Io("開けません: $uri")
        val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (offset > 0) {
            // 本体・SD・USB は実ファイルの fd なのでシークできる。できなければ読み飛ばす
            val sought = runCatching { (stream as FileInputStream).channel.position(offset) }.isSuccess
            if (!sought) stream.skipFully(offset)
        }
        return stream
    }

    override suspend fun write(
        volume: String,
        dir: SafePath,
        name: String,
        overwrite: Boolean,
        writer: suspend (OutputStream) -> Unit,
    ): Entry {
        FileNames.requireValid(name)
        val target = dir.child(name)
        val (vol, parentUri) = withContext(Dispatchers.IO) {
            val vol = vol(volume)
            val parent = requireDir(vol, dir)
            checkWritable(vol, parent)
            resolve(vol, target)?.let { existing ->
                if (existing.isDir) throw StorageException.IsDirectory(target.toString())
                if (!overwrite) throw StorageException.AlreadyExists(target.toString())
            }
            vol to vol.uri(parent.docId)
        }
        val tempName = ".anas-upload-${System.nanoTime()}.part"
        val tempUri = withContext(Dispatchers.IO) {
            DocumentsContract.createDocument(resolver, parentUri, MimeTypes.OCTET_STREAM, tempName)
        } ?: throw StorageException.Io("ファイルを作れません: $target")
        try {
            @Suppress("Recycle") // 下の out.use で閉じる
            val out = withContext(Dispatchers.IO) { resolver.openOutputStream(tempUri, "w") }
                ?: throw StorageException.Io("書き込めません: $target")
            out.use { writer(it) }
            withContext(Dispatchers.IO) {
                resolve(vol, target)?.let { existing ->
                    if (!overwrite) throw StorageException.AlreadyExists(target.toString())
                    DocumentsContract.deleteDocument(resolver, vol.uri(existing.docId))
                    invalidate(vol, target)
                }
                DocumentsContract.renameDocument(resolver, tempUri, name)
                    ?: throw StorageException.Io("名前を変更できません: $target")
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, tempUri) } }
            throw if (e is StorageException || e !is java.io.IOException) e else StorageException.Io("書き込みに失敗しました: ${e.message}", e)
        }
        return withContext(Dispatchers.IO) {
            invalidate(vol, target)
            resolve(vol, target)?.toEntry(target.toString()) ?: throw StorageException.Io("書き込んだファイルが見つかりません: $target")
        }
    }

    override suspend fun mkdir(volume: String, dir: SafePath, name: String): Entry = withContext(Dispatchers.IO) {
        val target = dir.child(name)
        val vol = vol(volume)
        resolve(vol, target)?.let { existing ->
            if (existing.isDir) return@withContext existing.toEntry(target.toString())
            throw StorageException.AlreadyExists(target.toString())
        }
        val parent = requireDir(vol, dir)
        checkWritable(vol, parent)
        DocumentsContract.createDocument(resolver, vol.uri(parent.docId), Document.MIME_TYPE_DIR, name)
            ?: throw StorageException.Io("フォルダを作れません: $target")
        invalidate(vol, target)
        resolve(vol, target)?.toEntry(target.toString()) ?: throw StorageException.Io("フォルダを作れません: $target")
    }

    override suspend fun delete(volume: String, path: SafePath) = withContext(Dispatchers.IO) {
        if (path.isRoot) throw StorageException.Io("ルートは削除できません")
        val vol = vol(volume)
        val row = resolve(vol, path) ?: throw notFoundOrOffline(vol, path)
        checkWritable(vol, null)
        try {
            if (!DocumentsContract.deleteDocument(resolver, vol.uri(row.docId))) throw StorageException.Io("削除できません: $path")
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException.Io("削除できません: $path", e)
        } finally {
            invalidate(vol, path)
        }
    }

    override suspend fun rename(volume: String, path: SafePath, newName: String): Entry = withContext(Dispatchers.IO) {
        if (path.isRoot) throw StorageException.Io("ルートは名前を変えられません")
        val target = path.parent.child(newName)
        val vol = vol(volume)
        val row = resolve(vol, path) ?: throw notFoundOrOffline(vol, path)
        if (resolve(vol, target) != null) throw StorageException.AlreadyExists(target.toString())
        checkWritable(vol, null)
        try {
            DocumentsContract.renameDocument(resolver, vol.uri(row.docId), newName)
                ?: throw StorageException.Io("名前を変更できません: $path")
        } catch (e: StorageException) {
            throw e
        } catch (e: Exception) {
            throw StorageException.Io("名前を変更できません: $path", e)
        }
        invalidate(vol, path)
        resolve(vol, target)?.toEntry(target.toString()) ?: throw StorageException.Io("名前を変更できません: $path")
    }

    // ---- パス → ドキュメント ----

    private fun resolve(vol: Vol, path: SafePath): Row? {
        if (path.isRoot) return query(vol.uri(vol.rootId))
        if (vol.isExternalStorage) {
            val sep = if (vol.rootId.endsWith(":")) "" else "/"
            return query(vol.uri(vol.rootId + sep + path.segments.joinToString("/")))
                // FAT は大文字小文字を区別しないが、API としては名前が完全に一致するものだけを返す
                ?.takeIf { it.name == path.name }
        }
        return walk(vol, path)
    }

    /** 子を1階層ずつ検索して探す（キャッシュがあれば使う） */
    private fun walk(vol: Vol, path: SafePath): Row? {
        cacheGet(vol, path.toString())?.let { id -> query(vol.uri(id))?.let { return it } }
        var current = query(vol.uri(vol.rootId)) ?: return null
        var currentPath = SafePath.ROOT
        for (segment in path.segments) {
            if (!current.isDir) return null
            currentPath = currentPath.child(segment)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vol.treeUri, current.docId)
            current = runCatching {
                resolver.query(childrenUri, COLUMNS, null, null, null)?.use { c ->
                    var found: Row? = null
                    while (c.moveToNext()) {
                        val row = c.toRow()
                        if (row.name == segment) { found = row; break }
                    }
                    found
                }
            }.getOrNull() ?: return null
            cachePut(vol, currentPath.toString(), current.docId)
        }
        return current
    }

    private fun query(uri: Uri): Row? = try {
        resolver.query(uri, COLUMNS, null, null, null)?.use { c -> if (c.moveToFirst()) c.toRow() else null }
    } catch (_: Exception) {
        // 存在しない ID・取り外されたストレージ・権限切れはいずれも例外になる
        null
    }

    private fun requireDir(vol: Vol, path: SafePath): Row {
        val row = resolve(vol, path) ?: throw notFoundOrOffline(vol, path)
        if (!row.isDir) throw StorageException.NotDirectory(path.toString())
        return row
    }

    private fun notFoundOrOffline(vol: Vol, path: SafePath): StorageException =
        if (query(vol.uri(vol.rootId)) == null) StorageException.Offline(vol.registered.id)
        else StorageException.NotFound(path.toString())

    private fun checkWritable(vol: Vol, dir: Row?) {
        val target = dir ?: query(vol.uri(vol.rootId)) ?: throw StorageException.Offline(vol.registered.id)
        if (target.isDir && target.flags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
            throw StorageException.ReadOnly(vol.registered.id)
        }
    }

    private fun cacheKey(vol: Vol, path: String) = "${vol.registered.id}\u0000$path"
    private fun cacheGet(vol: Vol, path: String): String? = synchronized(idCache) { idCache[cacheKey(vol, path)] }
    private fun cachePut(vol: Vol, path: String, docId: String) {
        if (vol.isExternalStorage) return
        synchronized(idCache) { idCache[cacheKey(vol, path)] = docId }
    }

    private fun invalidate(vol: Vol, path: SafePath) = synchronized(idCache) {
        val key = cacheKey(vol, path.toString())
        idCache.keys.removeAll { it == key || it.startsWith("$key/") }
    }

    // ---- 容量 ----

    private val spaceCache = mutableMapOf<String, Pair<Long, Pair<Long, Long>>>()

    /**
     * (全体, 空き)。アプリの権限では /storage/<UUID> を statfs できないので、
     * ルート直下（なければ1階層下）のファイルを SAF で開いて fstatvfs する。30 秒キャッシュ。
     */
    private fun space(vol: Vol): Pair<Long, Long>? {
        val key = vol.registered.id
        synchronized(spaceCache) {
            spaceCache[key]?.let { (at, value) -> if (System.currentTimeMillis() - at < 30_000) return value }
        }
        val file = findAnyFile(vol, vol.rootId, depth = 0) ?: return null
        val value = runCatching {
            resolver.openFileDescriptor(vol.uri(file), "r")?.use { pfd ->
                val st = android.system.Os.fstatvfs(pfd.fileDescriptor)
                st.f_blocks * st.f_frsize to st.f_bavail * st.f_frsize
            }
        }.getOrNull() ?: return null
        synchronized(spaceCache) { spaceCache[key] = System.currentTimeMillis() to value }
        return value
    }

    private fun findAnyFile(vol: Vol, dirId: String, depth: Int): String? {
        val rows = runCatching {
            resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(vol.treeUri, dirId), COLUMNS, null, null, null)
                ?.use { c -> buildList<Row> { while (c.moveToNext() && size < 50) add(c.toRow()) } }
        }.getOrNull().orEmpty()
        rows.firstOrNull { !it.isDir }?.let { return it.docId }
        if (depth >= 1) return null
        return rows.asSequence().filter { it.isDir }.take(5).firstNotNullOfOrNull { findAnyFile(vol, it.docId, depth + 1) }
    }

    private fun Row.toEntry(path: String) = Entry(
        name = name,
        path = path.ifEmpty { "/" },
        isDir = isDir,
        size = if (isDir) 0 else size,
        modified = modified,
        mime = if (isDir) MimeTypes.DIRECTORY else MimeTypes.of(name),
    )

    private fun Cursor.toRow() = Row(
        docId = getString(0),
        name = getString(1) ?: "",
        mime = getString(2) ?: MimeTypes.OCTET_STREAM,
        size = if (isNull(3)) 0 else getLong(3),
        modified = if (isNull(4)) 0 else getLong(4),
        flags = if (isNull(5)) 0 else getInt(5),
    )

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}

private fun InputStream.skipFully(n: Long) {
    var remaining = n
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped <= 0) {
            if (read() < 0) return
            remaining--
        } else {
            remaining -= skipped
        }
    }
}
