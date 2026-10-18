package space.webkombinat.anas.server

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import space.webkombinat.anas.core.model.Entry
import space.webkombinat.anas.core.model.SafePath
import java.io.Closeable

/** 検索結果の1件。[path] はボリュームのルートから（拡張機能の API では data/ から） */
@Serializable
data class SearchHit(
    val volume: String,
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String,
    /** ANAS に追加された日時（UNIX ミリ秒）。最初の索引作りの前からあったものは null */
    val added: Long? = null,
)

/** どこを探すか */
class SearchScope(
    /** null ならすべてのボリューム */
    val volume: String? = null,
    /** この中だけを探す（拡張機能なら `/.<id>/data`） */
    val base: SafePath = SafePath.ROOT,
    /** `.` で始まるファイル・フォルダ（[base] より下のもの）も含める */
    val includeHidden: Boolean = false,
    /** 接続されているボリューム。これ以外の結果は返さない */
    val onlineVolumes: Set<String>,
)

/** ボリュームごとの索引の状態 */
@Serializable
data class VolumeIndexInfo(
    val volume: String,
    /** 最後に走査し終えた日時。まだなら null */
    val lastScan: Long? = null,
    val files: Long = 0,
)

/**
 * ファイル名の索引（SQLite）。中身ではなく、名前・フォルダ・種類・サイズ・日時だけを持つ。
 * 名前とフォルダ名は FTS5 の trigram で引くので、日本語の部分一致も速い（2 文字以下は LIKE）。
 *
 * 接続は1つだけで、[Mutex] で順番に使う。走査は1フォルダずつのトランザクションにして、検索を長く待たせない。
 */
class SearchIndex(path: String) : Closeable {
    private val conn: SQLiteConnection = BundledSQLiteDriver().open(path)
    private val mutex = Mutex()

    init {
        conn.execSQL("PRAGMA journal_mode=WAL")
        conn.execSQL("PRAGMA synchronous=NORMAL")
        val version = conn.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
        if (version != SCHEMA_VERSION) {
            for (sql in DROP) conn.execSQL(sql)
            for (sql in SCHEMA) conn.execSQL(sql)
            conn.execSQL("PRAGMA user_version=$SCHEMA_VERSION")
        }
    }

    override fun close() = conn.close()

    private suspend fun <T> db(block: SQLiteConnection.() -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { conn.block() } }

    private fun <T> SQLiteConnection.transaction(block: SQLiteConnection.() -> T): T {
        execSQL("BEGIN IMMEDIATE")
        try {
            val result = block()
            execSQL("COMMIT")
            return result
        } catch (e: Throwable) {
            execSQL("ROLLBACK")
            throw e
        }
    }

    // ---- 書き込み ----

    /**
     * 走査で見つけた項目を入れる。[added] は新しく見つかったものにだけ付ける日時
     * （最初の走査では null。あとから見つかったものは、だいたいその頃に追加されたとみなす）
     */
    suspend fun upsertScanned(volume: String, entries: List<Entry>, seen: Long, added: Long?) = db {
        transaction {
            prepare(UPSERT_KEEP_ADDED).use { st -> for (e in entries) st.bindEntry(volume, e, added, seen) }
        }
    }

    /** ANAS が書き込んだ（アップロード・保存した）ファイル。既にあっても追加日時を今にする */
    suspend fun recordWrite(volume: String, entry: Entry, at: Long) = db {
        prepare(UPSERT_SET_ADDED).use { it.bindEntry(volume, entry, at, at) }
    }

    /** ANAS が作ったフォルダ。既にあれば追加日時は変えない */
    suspend fun recordMkdir(volume: String, entry: Entry, at: Long) = db {
        prepare(UPSERT_KEEP_ADDED).use { it.bindEntry(volume, entry, at, at) }
    }

    /** [path] とその中身を消す */
    suspend fun remove(volume: String, path: SafePath) = db {
        prepare("DELETE FROM files WHERE volume = ? AND ${TREE_WHERE}").use { st ->
            st.bindText(1, volume)
            st.bindTree(2, path)
            st.step()
        }
    }

    /** 名前の変更・移動。中身のパスも付け替える */
    suspend fun move(volume: String, from: SafePath, to: SafePath) = db {
        transaction {
            val rows = prepare("SELECT id, path, is_dir FROM files WHERE volume = ? AND $TREE_WHERE").use { st ->
                st.bindText(1, volume)
                st.bindTree(2, from)
                buildList { while (st.step()) add(Triple(st.getLong(0), st.getText(1), st.getLong(2) != 0L)) }
            }
            // 移動先に古い行が残っていたら先に消す（UNIQUE 制約のため）
            prepare("DELETE FROM files WHERE volume = ? AND $TREE_WHERE").use { st ->
                st.bindText(1, volume)
                st.bindTree(2, to)
                st.step()
            }
            prepare("UPDATE files SET path = ?, name = ?, norm = ?, dir_norm = ?, ext = ?, hidden = ? WHERE id = ?").use { st ->
                for ((id, oldPath, isDir) in rows) {
                    val newPath = SafePath.parse(to.toString() + oldPath.removePrefix(from.toString()))
                    st.bindText(1, newPath.toString())
                    st.bindText(2, newPath.name)
                    st.bindText(3, normalizeForSearch(newPath.name))
                    st.bindText(4, normalizeForSearch(newPath.parent.toString()))
                    st.bindText(5, extensionOf(newPath.name, isDir))
                    st.bindLong(6, if (isHidden(newPath)) 1 else 0)
                    st.bindLong(7, id)
                    st.step()
                    st.reset()
                }
            }
        }
    }

    /** 走査で見つからなかったもの（[seen] より前）を消す */
    suspend fun purgeUnseen(volume: String, seen: Long) = db {
        prepare("DELETE FROM files WHERE volume = ? AND seen < ?").use { st ->
            st.bindText(1, volume)
            st.bindLong(2, seen)
            st.step()
        }
    }

    /** 読めなかったフォルダの中身は、消さずに残す */
    suspend fun touchTree(volume: String, path: SafePath, seen: Long) = db {
        prepare("UPDATE files SET seen = ? WHERE volume = ? AND $TREE_WHERE").use { st ->
            st.bindLong(1, seen)
            st.bindText(2, volume)
            st.bindTree(3, path)
            st.step()
        }
    }

    /** 登録を解除したボリュームの索引を消す */
    suspend fun retainVolumes(volumes: Set<String>) = db {
        val known = prepare("SELECT volume FROM volumes").use { st -> buildList { while (st.step()) add(st.getText(0)) } }
        for (v in known - volumes) {
            for (sql in listOf("DELETE FROM files WHERE volume = ?", "DELETE FROM volumes WHERE volume = ?")) {
                prepare(sql).use { it.bindText(1, v); it.step() }
            }
        }
    }

    suspend fun markScanned(volume: String, at: Long) = db {
        val count = prepare("SELECT count(*) FROM files WHERE volume = ?").use { it.bindText(1, volume); it.step(); it.getLong(0) }
        prepare("INSERT INTO volumes(volume, last_scan, files) VALUES (?, ?, ?) ON CONFLICT(volume) DO UPDATE SET last_scan = excluded.last_scan, files = excluded.files").use { st ->
            st.bindText(1, volume)
            st.bindLong(2, at)
            st.bindLong(3, count)
            st.step()
        }
    }

    suspend fun volumeInfo(): List<VolumeIndexInfo> = db {
        prepare("SELECT volume, last_scan, files FROM volumes ORDER BY volume").use { st ->
            buildList { while (st.step()) add(VolumeIndexInfo(st.getText(0), if (st.isNull(1)) null else st.getLong(1), st.getLong(2))) }
        }
    }

    // ---- 検索 ----

    class Result(val hits: List<SearchHit>, val total: Int, val fuzzy: Boolean)

    suspend fun search(query: SearchQuery, scope: SearchScope, limit: Int): Result = db {
        val exact = find(query, scope, limit, fuzzy = false)
        // 完全に一致するものがなければ、似た名前（2文字・3文字の並びが多く重なるもの）を探す
        if (exact.hits.isEmpty() && query.keywords.any { normalizeForSearch(it).length >= 2 }) {
            val fuzzy = find(query, scope, limit, fuzzy = true)
            if (fuzzy.hits.isNotEmpty()) return@db fuzzy
        }
        exact
    }

    private fun SQLiteConnection.find(query: SearchQuery, scope: SearchScope, limit: Int, fuzzy: Boolean): Result {
        if (scope.onlineVolumes.isEmpty()) return Result(emptyList(), 0, fuzzy)
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        where += "volume IN (${scope.onlineVolumes.joinToString(",") { "?" }})"
        args.addAll(scope.onlineVolumes)
        scope.volume?.let { where += "volume = ?"; args += it }
        if (!scope.base.isRoot) {
            where += "path >= ? AND path < ?"
            args += scope.base.toString() + "/"
            args += scope.base.toString() + "0" // '/' の次の文字
        } else if (!scope.includeHidden) {
            where += "hidden = 0"
        }

        // 種類（どれかに当てはまればよい）
        val kindOr = mutableListOf<String>()
        val exts = query.allExtensions
        if (exts.isNotEmpty()) {
            kindOr += "(is_dir = 0 AND ext IN (${exts.joinToString(",") { "?" }}))"
            args.addAll(exts)
        }
        // MIME は拡張子から決めているので、拡張子だけで絞る（ext の索引が使える）
        if (FileKind.FOLDER in query.kinds) kindOr += "is_dir = 1"
        if (kindOr.isNotEmpty()) where += "(" + kindOr.joinToString(" OR ") + ")"

        // 日時
        val timeExpr = when (query.timeField) {
            TimeField.ADDED -> "added"
            TimeField.MODIFIED -> "modified"
            TimeField.ANY -> "recent"
        }
        query.since?.let { where += "$timeExpr >= ?"; args += it }
        query.until?.let { where += "$timeExpr < ?"; args += it }
        // フォルダの更新日時は中身が変わるたびに変わるので、日時で探すときはファイルだけ（「フォルダ」と言われたときを除く）
        if ((query.since != null || query.until != null) && FileKind.FOLDER !in query.kinds) where += "is_dir = 0"

        // サイズ
        if (query.minSize != null || query.maxSize != null) where += "is_dir = 0"
        query.minSize?.let { where += "size >= ?"; args += it }
        query.maxSize?.let { where += "size <= ?"; args += it }

        // キーワード
        val keywords = query.keywords.map(::normalizeForSearch).filter { it.isNotEmpty() }
        if (!fuzzy) {
            val long = keywords.filter { it.length >= 3 }
            if (long.isNotEmpty()) {
                where += "id IN (SELECT rowid FROM files_fts WHERE files_fts MATCH ?)"
                args += long.joinToString(" AND ") { "\"" + it.replace("\"", "\"\"") + "\"" }
            }
            // 2 文字以下は trigram で引けないので LIKE（全件を見る）
            for (k in keywords.filter { it.length < 3 }) {
                where += "(norm LIKE ? ESCAPE '\\' OR dir_norm LIKE ? ESCAPE '\\')"
                args += likePattern(k)
                args += likePattern(k)
            }
        } else {
            // 長い言葉は trigram のどれか、短い言葉は2文字の並びのどれかを含む候補を集め、あとで重なり具合を見る
            val or = mutableListOf<String>()
            val tris = keywords.filter { it.length >= 4 }.flatMap { ngrams(it, 3) }.distinct()
            if (tris.isNotEmpty()) {
                or += "id IN (SELECT rowid FROM files_fts WHERE files_fts MATCH ?)"
                args += tris.joinToString(" OR ") { "\"" + it.replace("\"", "\"\"") + "\"" }
            }
            val bigrams = keywords.filter { it.length in 2..3 }.flatMap { ngrams(it, 2) }.distinct()
            if (bigrams.isNotEmpty()) {
                or += "(" + bigrams.joinToString(" OR ") { "norm LIKE ? ESCAPE '\\'" } + ")"
                bigrams.forEach { args += likePattern(it) }
            }
            if (or.isEmpty()) return Result(emptyList(), 0, true)
            where += "(" + or.joinToString(" OR ") + ")"
        }

        val whereSql = where.joinToString(" AND ")
        val order = when (query.sort) {
            SearchSort.LARGEST -> "size DESC"
            SearchSort.OLDEST -> "$timeExpr ASC"
            else -> "$timeExpr DESC, is_dir ASC"
        }
        // キーワードがあるときは多めに取って、名前の一致具合で並べ直す
        val rerank = keywords.isNotEmpty() && query.sort == SearchSort.RELEVANCE
        val hiddenFilter = !scope.base.isRoot && !scope.includeHidden
        val fetch = if (rerank || hiddenFilter || fuzzy) CANDIDATES else limit
        val rows = prepare(
            "SELECT volume, path, name, is_dir, size, modified, mime, added, norm, dir_norm FROM files WHERE $whereSql ORDER BY $order LIMIT ?",
        ).use { st ->
            bindAll(st, args + fetch.toLong())
            buildList {
                while (st.step()) {
                    add(
                        Row(
                            SearchHit(
                                st.getText(0), st.getText(1), st.getText(2), st.getLong(3) != 0L, st.getLong(4), st.getLong(5),
                                st.getText(6), if (st.isNull(7)) null else st.getLong(7),
                            ),
                            st.getText(8), st.getText(9),
                        ),
                    )
                }
            }
        }

        var candidates = rows
        if (hiddenFilter) {
            candidates = candidates.filter { r -> !isHidden(SafePath.parse(r.hit.path).relativeTo(scope.base) ?: SafePath.ROOT) }
        }
        if (fuzzy) {
            candidates = candidates
                .map { it to fuzzyScore(keywords, it) }
                .filter { (_, s) -> s >= FUZZY_THRESHOLD }
                .sortedByDescending { (_, s) -> s }
                .map { it.first }
        } else if (rerank) {
            candidates = candidates.sortedByDescending { exactScore(keywords, it) } // 同点なら元の順（新しい順）
        }
        val total = when {
            fuzzy || hiddenFilter -> candidates.size
            rows.size < fetch -> rows.size // 全部取れている
            else -> countWhere(whereSql, args)
        }
        return Result(candidates.take(limit).map { it.hit }, total, fuzzy)
    }

    private fun SQLiteConnection.countWhere(whereSql: String, args: List<Any>): Int =
        // 多すぎるときは数えきらない（「1000 件以上」と出す）
        prepare("SELECT count(*) FROM (SELECT 1 FROM files WHERE $whereSql LIMIT ?)").use { st ->
            bindAll(st, args + (MAX_COUNT + 1).toLong())
            st.step()
            st.getLong(0).toInt()
        }

    private class Row(val hit: SearchHit, val norm: String, val dirNorm: String)

    /** 名前に含まれるほど、名前の先頭にあるほど高い。フォルダ名だけに含まれるものは低い */
    private fun exactScore(keywords: List<String>, r: Row): Int = keywords.sumOf { k ->
        when {
            r.norm == k || r.norm.substringBeforeLast('.') == k -> 6
            r.norm.startsWith(k) -> 4
            r.norm.contains(k) -> 3
            else -> 1
        }.toInt()
    }

    /** 言葉ごとの、2文字の並びが名前（とフォルダ名）に含まれる割合の平均 */
    private fun fuzzyScore(keywords: List<String>, r: Row): Double = keywords.map { k ->
        val grams = ngrams(k, 2).ifEmpty { listOf(k) }
        val inName = grams.count { r.norm.contains(it) }.toDouble() / grams.size
        val inDir = grams.count { r.dirNorm.contains(it) }.toDouble() / grams.size
        maxOf(inName, inDir * 0.8)
    }.average()

    private fun SQLiteStatement.bindEntry(volume: String, e: Entry, added: Long?, seen: Long) {
        val path = SafePath.parse(e.path)
        bindText(1, volume)
        bindText(2, path.toString())
        bindText(3, e.name)
        bindText(4, normalizeForSearch(e.name))
        bindText(5, normalizeForSearch(path.parent.toString()))
        bindText(6, extensionOf(e.name, e.isDir))
        bindLong(7, if (e.isDir) 1 else 0)
        bindLong(8, e.size)
        bindLong(9, e.modified)
        bindText(10, e.mime)
        bindLong(11, if (isHidden(path)) 1 else 0)
        if (added == null) bindNull(12) else bindLong(12, added)
        bindLong(13, maxOf(e.modified, added ?: 0))
        bindLong(14, seen)
        step()
        reset()
    }

    /** `?` 2 つに、[path] とその中身を表す条件（[TREE_WHERE]）の値を入れる */
    private fun SQLiteStatement.bindTree(index: Int, path: SafePath) {
        bindText(index, path.toString())
        bindText(index + 1, path.toString().trimEnd('/') + "/")
        bindText(index + 2, path.toString().trimEnd('/') + "0")
    }

    private fun bindAll(st: SQLiteStatement, args: List<Any>) {
        args.forEachIndexed { i, a ->
            when (a) {
                is String -> st.bindText(i + 1, a)
                is Long -> st.bindLong(i + 1, a)
                is Int -> st.bindLong(i + 1, a.toLong())
                else -> error("bind できない値: $a")
            }
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 2L

        /** キーワードがあるとき、並べ直すために取り出す件数 */
        private const val CANDIDATES = 500

        /** total はここまでしか数えない（超えたら MAX_COUNT + 1） */
        const val MAX_COUNT = 1000
        private const val FUZZY_THRESHOLD = 0.5

        /** [path] 自身か、その中身。`?` を 3 つ使う */
        private const val TREE_WHERE = "(path = ? OR (path >= ? AND path < ?))"

        private val DROP = listOf(
            "DROP TRIGGER IF EXISTS files_ai", "DROP TRIGGER IF EXISTS files_ad", "DROP TRIGGER IF EXISTS files_au",
            "DROP TABLE IF EXISTS files_fts", "DROP TABLE IF EXISTS files", "DROP TABLE IF EXISTS volumes",
        )

        private val SCHEMA = listOf(
            """CREATE TABLE files(
                id INTEGER PRIMARY KEY,
                volume TEXT NOT NULL,
                path TEXT NOT NULL,
                name TEXT NOT NULL,
                norm TEXT NOT NULL,
                dir_norm TEXT NOT NULL,
                ext TEXT NOT NULL,
                is_dir INTEGER NOT NULL,
                size INTEGER NOT NULL,
                modified INTEGER NOT NULL,
                mime TEXT NOT NULL,
                hidden INTEGER NOT NULL,
                added INTEGER,
                -- modified と added の新しいほう（「いつ頃のファイル」で探すとき用）
                recent INTEGER NOT NULL,
                seen INTEGER NOT NULL,
                UNIQUE(volume, path)
            )""",
            "CREATE INDEX files_modified ON files(modified)",
            "CREATE INDEX files_added ON files(added)",
            "CREATE INDEX files_recent ON files(recent)",
            "CREATE INDEX files_ext ON files(ext)",
            "CREATE INDEX files_seen ON files(volume, seen)",
            "CREATE VIRTUAL TABLE files_fts USING fts5(norm, dir_norm, content='files', content_rowid='id', tokenize='trigram')",
            """CREATE TRIGGER files_ai AFTER INSERT ON files BEGIN
                INSERT INTO files_fts(rowid, norm, dir_norm) VALUES (new.id, new.norm, new.dir_norm);
            END""",
            """CREATE TRIGGER files_ad AFTER DELETE ON files BEGIN
                INSERT INTO files_fts(files_fts, rowid, norm, dir_norm) VALUES ('delete', old.id, old.norm, old.dir_norm);
            END""",
            """CREATE TRIGGER files_au AFTER UPDATE OF norm, dir_norm ON files
                WHEN old.norm IS NOT new.norm OR old.dir_norm IS NOT new.dir_norm BEGIN
                INSERT INTO files_fts(files_fts, rowid, norm, dir_norm) VALUES ('delete', old.id, old.norm, old.dir_norm);
                INSERT INTO files_fts(rowid, norm, dir_norm) VALUES (new.id, new.norm, new.dir_norm);
            END""",
            "CREATE TABLE volumes(volume TEXT PRIMARY KEY, last_scan INTEGER, files INTEGER NOT NULL DEFAULT 0)",
        )

        private const val INSERT =
            "INSERT INTO files(volume, path, name, norm, dir_norm, ext, is_dir, size, modified, mime, hidden, added, recent, seen) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(volume, path) DO UPDATE SET " +
                "name = excluded.name, norm = excluded.norm, dir_norm = excluded.dir_norm, ext = excluded.ext, " +
                "is_dir = excluded.is_dir, size = excluded.size, modified = excluded.modified, mime = excluded.mime, " +
                "hidden = excluded.hidden, seen = excluded.seen"
        private const val UPSERT_KEEP_ADDED = "$INSERT, recent = max(excluded.modified, coalesce(files.added, 0))"
        private const val UPSERT_SET_ADDED = "$INSERT, added = excluded.added, recent = excluded.recent"

        fun extensionOf(name: String, isDir: Boolean): String =
            if (isDir || !name.contains('.') || name.lastIndexOf('.') == 0) "" else name.substringAfterLast('.').lowercase()

        fun isHidden(path: SafePath): Boolean = path.segments.any { it.startsWith(".") }

        private fun likePattern(s: String) = "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

        private fun ngrams(s: String, n: Int): List<String> =
            if (s.length < n) emptyList() else (0..s.length - n).map { s.substring(it, it + n) }
    }
}
