package space.webkombinat.anas.core.model

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/** 登録されたストレージ（本体のフォルダ / SD カード / USB ドライブ） */
@Serializable
data class VolumeInfo(
    /** URL に使う ID（例: `disk1`） */
    val id: String,
    /** 表示名（例: `USB ドライブ / NAS`） */
    val name: String,
    val online: Boolean,
    val writable: Boolean,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
)

/** フォルダの中の1項目 */
@Serializable
data class Entry(
    val name: String,
    /** ボリュームのルートからのパス（`/` 始まり） */
    val path: String,
    val isDir: Boolean,
    val size: Long,
    /** 更新日時（UNIX ミリ秒）。不明なら 0 */
    val modified: Long,
    val mime: String,
)

/** 読み出し用のハンドル。[open] は指定位置から読み始めるストリームを返す */
class ReadHandle(
    val entry: Entry,
    private val opener: (offset: Long) -> InputStream,
) {
    fun open(offset: Long = 0): InputStream = opener(offset)
}

/**
 * ファイルの読み書き。Android では SAF、テストでは java.io.File で実装する。
 * 失敗はすべて [StorageException] で通知する。
 */
interface StorageBackend {
    suspend fun volumes(): List<VolumeInfo>

    /** 項目の情報。存在しなければ null */
    suspend fun stat(volume: String, path: SafePath): Entry?

    suspend fun list(volume: String, path: SafePath): List<Entry>

    suspend fun openRead(volume: String, path: SafePath): ReadHandle

    /**
     * [dir] の中に [name] というファイルを書き込む。
     * 書き込みは一時ファイルに行い、成功してから名前を変えるので、途中で失敗しても壊れたファイルは残らない。
     * [overwrite] が false で同名のものがあれば [StorageException.AlreadyExists]。
     */
    suspend fun write(
        volume: String,
        dir: SafePath,
        name: String,
        overwrite: Boolean,
        writer: suspend (OutputStream) -> Unit,
    ): Entry

    /** フォルダを作る。すでにフォルダがあればそれを返す */
    suspend fun mkdir(volume: String, dir: SafePath, name: String): Entry

    /** ファイルまたはフォルダ（中身ごと）を削除する */
    suspend fun delete(volume: String, path: SafePath)

    suspend fun rename(volume: String, path: SafePath, newName: String): Entry

    /**
     * [path] を同じボリュームの [toDir] の中へ、同じ名前のまま移す。[toDir] に同じ名前のものがあれば [StorageException.AlreadyExists]。
     * ストレージがその場での移動に対応していなければ null を返す（呼ぶ側でコピーしてから消す）
     */
    suspend fun move(volume: String, path: SafePath, toDir: SafePath): Entry?
}

/** [mkdir] を1階層ずつ繰り返して、[path] までのフォルダをすべて作る */
suspend fun StorageBackend.mkdirs(volume: String, path: SafePath) {
    var current = SafePath.ROOT
    for (segment in path.segments) {
        mkdir(volume, current, segment)
        current = current.child(segment)
    }
}

sealed class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotFound(what: String) : StorageException("見つかりません: $what")
    class AlreadyExists(what: String) : StorageException("同じ名前のものがあります: $what")
    class NotDirectory(what: String) : StorageException("フォルダではありません: $what")
    class IsDirectory(what: String) : StorageException("フォルダです: $what")
    class Offline(volume: String) : StorageException("ストレージに接続できません（取り外されていませんか？）: $volume")
    class ReadOnly(volume: String) : StorageException("読み取り専用です: $volume")
    class Io(message: String, cause: Throwable? = null) : StorageException(message, cause)
}
