package space.webkombinat.anas.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import space.webkombinat.anas.core.model.Entry
import space.webkombinat.anas.core.model.SafePath
import space.webkombinat.anas.core.model.StorageBackend
import space.webkombinat.anas.core.model.StorageException
import space.webkombinat.anas.core.model.VolumeInfo
import java.io.OutputStream

/** 索引の状態（Web UI に出す） */
@Serializable
data class SearchStatus(
    /** 走査中なら true */
    val running: Boolean = false,
    /** 走査中のボリューム */
    val volume: String? = null,
    /** 今回の走査で見た項目の数 */
    val scanned: Long = 0,
    val volumes: List<VolumeIndexInfo> = emptyList(),
)

@Serializable
data class SearchResponse(
    val q: String,
    /** 文章をどう読み取ったか */
    val query: SearchQuery,
    val hits: List<SearchHit>,
    /** 条件に合う件数（hits は先頭の limit 件だけ） */
    val total: Int,
    /** 完全に一致するものがなく、似た名前を返したとき true */
    val fuzzy: Boolean,
    val status: SearchStatus,
)

/**
 * ファイル検索。ボリュームを定期的に走査して [SearchIndex] を作り、文章の検索に答える。
 * ANAS 経由の書き込みは [IndexingStorageBackend] がその場で索引に入れるので、走査は PC から直接コピーしたものなどを拾うためのもの。
 *
 * [backend] は索引を更新しない元のバックエンドを渡す。
 */
class SearchService(
    private val backend: StorageBackend,
    private val index: SearchIndex,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(SearchStatus())
    val status: StateFlow<SearchStatus> = _status.asStateFlow()
    private val scanning = Mutex()

    suspend fun search(text: String, scope: SearchScope, limit: Int = DEFAULT_LIMIT): SearchResponse {
        val query = SearchQuery.parse(text, clock())
        val result = if (query.isEmpty) SearchIndex.Result(emptyList(), 0, false) else index.search(query, scope, limit.coerceIn(1, MAX_LIMIT))
        return SearchResponse(text, query, result.hits, result.total, result.fuzzy, currentStatus())
    }

    suspend fun currentStatus(): SearchStatus = _status.value.copy(volumes = index.volumeInfo())

    suspend fun onlineVolumes(): Set<String> = backend.volumes().filter { it.online }.map { it.id }.toSet()

    /**
     * 接続されているボリュームをすべて走査する。[onlyDue] なら、前回から [RESCAN_INTERVAL_MS] たったものだけ。
     * すでに走査中なら何もしない。
     */
    suspend fun rescan(onlyDue: Boolean = false) {
        if (!scanning.tryLock()) return
        try {
            val volumes = backend.volumes()
            index.retainVolumes(volumes.map { it.id }.toSet())
            val last = index.volumeInfo().associate { it.volume to it.lastScan }
            for (v in volumes.filter(VolumeInfo::online)) {
                val lastScan = last[v.id]
                if (onlyDue && lastScan != null && clock() - lastScan < RESCAN_INTERVAL_MS) continue
                scanVolume(v.id, firstScan = lastScan == null)
            }
        } finally {
            _status.update { it.copy(running = false, volume = null) }
            scanning.unlock()
        }
    }

    private suspend fun scanVolume(volume: String, firstScan: Boolean) {
        val scanId = clock()
        _status.update { it.copy(running = true, volume = volume, scanned = 0) }
        val queue = ArrayDeque<SafePath>().apply { add(SafePath.ROOT) }
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val entries = try {
                backend.list(volume, dir)
            } catch (e: StorageException.Offline) {
                return // 取り外された。索引は消さずに次の機会に
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                index.touchTree(volume, dir, scanId) // 読めなかったフォルダの中身は残す
                continue
            }.filterNot { it.name.startsWith(".anas-upload-") }
            index.upsertScanned(volume, entries, scanId, added = if (firstScan) null else scanId)
            for (e in entries) if (e.isDir) queue.add(SafePath.parse(e.path))
            _status.update { it.copy(scanned = it.scanned + entries.size) }
            yield()
        }
        index.purgeUnseen(volume, scanId)
        index.markScanned(volume, clock())
    }

    /** サーバーが動いているあいだ、ときどき走査する（最初は起動の少しあと） */
    fun startAuto(scope: CoroutineScope): Job = scope.launch {
        delay(START_DELAY_MS)
        while (isActive) {
            runCatching { rescan(onlyDue = true) }
            delay(CHECK_INTERVAL_MS)
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
        const val RESCAN_INTERVAL_MS = 12L * 60 * 60 * 1000
        private const val CHECK_INTERVAL_MS = 30L * 60 * 1000
        private const val START_DELAY_MS = 20_000L
    }
}

/**
 * 書き込み・フォルダ作成・名前の変更・削除を、その場で索引にも反映するバックエンド。
 * 索引の更新に失敗しても、ファイルの操作そのものは成功として返す（次の走査で直る）。
 */
class IndexingStorageBackend(
    private val delegate: StorageBackend,
    private val index: SearchIndex,
    private val clock: () -> Long = System::currentTimeMillis,
) : StorageBackend by delegate {

    override suspend fun write(
        volume: String,
        dir: SafePath,
        name: String,
        overwrite: Boolean,
        writer: suspend (OutputStream) -> Unit,
    ): Entry {
        val entry = delegate.write(volume, dir, name, overwrite, writer)
        quietly { index.recordWrite(volume, entry, clock()) }
        return entry
    }

    override suspend fun mkdir(volume: String, dir: SafePath, name: String): Entry {
        val entry = delegate.mkdir(volume, dir, name)
        quietly { index.recordMkdir(volume, entry, clock()) }
        return entry
    }

    override suspend fun delete(volume: String, path: SafePath) {
        delegate.delete(volume, path)
        quietly { index.remove(volume, path) }
    }

    override suspend fun rename(volume: String, path: SafePath, newName: String): Entry {
        val entry = delegate.rename(volume, path, newName)
        quietly { index.move(volume, path, SafePath.parse(entry.path)) }
        return entry
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }
}
