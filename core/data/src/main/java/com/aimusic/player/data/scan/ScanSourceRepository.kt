package com.aimusic.player.data.scan

import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.ScanSourceEntity
import com.aimusic.player.data.model.SourceKind
import com.aimusic.player.storage.StorageSource
import kotlinx.coroutines.flow.Flow

/** 添加来源的结果（`04 §3.4`）；重名不是错误，是要引导复用的既有来源。 */
sealed interface AddSourceResult {
    data class Added(val id: Long) : AddSourceResult

    data class AlreadyExists(val existingId: Long) : AddSourceResult

    data class Invalid(val reason: String) : AddSourceResult
}

/** 扫描来源（音乐目录 / 外部歌词目录）的增删启停（`04 §3.4`）。 */
interface ScanSourceRepository {

    fun observeSources(kind: SourceKind): Flow<List<ScanSourceEntity>>

    /** 无参是 `04 §3.4` 的签名；调用方按 `kind` 分流（§4.1 守卫要 MUSIC，§4.7 要 LYRICS）。 */
    suspend fun enabledSources(): List<ScanSourceEntity>

    suspend fun add(kind: SourceKind, rawPath: String): AddSourceResult

    /** 只删来源记录，**不清理 `music_file`** —— 扫描不重建实体（`04 §5.2`）。 */
    suspend fun remove(id: Long)

    suspend fun setEnabled(id: Long, enabled: Boolean)
}

class ScanSourceRepositoryImpl(
    private val db: MusicDatabase,
    private val storage: StorageSource,
    private val now: () -> Long = System::currentTimeMillis,
) : ScanSourceRepository {

    private val dao = db.scanSourceDao()

    override fun observeSources(kind: SourceKind): Flow<List<ScanSourceEntity>> =
        dao.observeSources(kind)

    override suspend fun enabledSources(): List<ScanSourceEntity> = dao.enabledSources()

    override suspend fun add(kind: SourceKind, rawPath: String): AddSourceResult {
        val path = rawPath.trim()
        if (path.isEmpty()) return AddSourceResult.Invalid("路径为空")
        if (!path.startsWith("/")) return AddSourceResult.Invalid("必须是绝对路径")
        if (!storage.exists(path)) return AddSourceResult.Invalid("路径不存在")
        if (!storage.isDirectory(path)) return AddSourceResult.Invalid("不是目录")

        // 先插再判，靠唯一索引判冲突（不在插入前额外查一次）
        val rowId = dao.insertSource(ScanSourceEntity(kind = kind, path = path, createdAt = now()))
        if (rowId != -1L) return AddSourceResult.Added(rowId)

        // 命中 UNIQUE(kind, path)：不是失败，把原 id 交回去让 UI 引导复用
        val existing = dao.findByKindAndPath(kind, path)
            ?: return AddSourceResult.Invalid("路径冲突")
        return AddSourceResult.AlreadyExists(existing.id)
    }

    override suspend fun remove(id: Long) {
        dao.removeById(id)
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.setEnabled(id, enabled)
    }
}
