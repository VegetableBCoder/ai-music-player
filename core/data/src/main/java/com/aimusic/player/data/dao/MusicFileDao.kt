package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aimusic.player.data.entity.MusicFileEntity
import com.aimusic.player.data.model.AnalysisStatus
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MusicFileDao {

    @Query("SELECT * FROM music_file WHERE entity_id = :entityId ORDER BY size DESC")
    abstract fun observeFiles(entityId: Long): Flow<List<MusicFileEntity>>

    @Query("SELECT * FROM music_file WHERE entity_id = :entityId ORDER BY size DESC LIMIT 1")
    abstract suspend fun representativeOf(entityId: Long): MusicFileEntity?

    /** 可播性判定：`03 §5` 规定由 LINKED 记录数派生，不落库。 */
    @Query("SELECT COUNT(*) FROM music_file WHERE entity_id IN (:ids) AND analysis_status = 'LINKED'")
    abstract suspend fun playableCount(ids: List<Long>): Int

    /** 存在可用文件的实体 id 子集（`06 §3.3`：入队过滤的唯一判定入口）。 */
    @Query(
        "SELECT DISTINCT entity_id FROM music_file " +
            "WHERE entity_id IN (:ids) AND analysis_status = 'LINKED'",
    )
    abstract suspend fun playableIds(ids: List<Long>): List<Long>

    @Query("SELECT * FROM music_file WHERE analysis_status IN ('UNANALYZED','FAILED')")
    abstract suspend fun pendingForAnalysis(): List<MusicFileEntity>

    // —— 扫描差异比对与提交（04 §4.6 / §4.8） ——

    /** 「库中已存在」集合：**一次取全量**，不逐文件查询（`04 §7` 的数据量假设：百~千级）。 */
    @Query("SELECT path FROM music_file")
    abstract suspend fun allPaths(): List<String>

    @Query("SELECT * FROM music_file WHERE path = :path LIMIT 1")
    abstract suspend fun findByPath(path: String): MusicFileEntity?

    /**
     * 幂等批量插入（`04 §4.8` 步骤 ②）。
     *
     * 返回每行的 rowId，其中 **`-1` 表示这一行被 `path` 唯一索引忽略了**。调用方**必须**
     * 把 `-1` 过滤掉再写 `analysis_run_file` —— 否则批次清单会指向一条不存在的文件行。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnoreAll(files: List<MusicFileEntity>): List<Long>

    @Query(
        "UPDATE music_file SET entity_id = :entityId, analysis_status = 'LINKED', analyzed_at = :now " +
            "WHERE id = :fileId",
    )
    abstract suspend fun link(fileId: Long, entityId: Long, now: Long)

    @Query(
        "UPDATE music_file SET analysis_status = :status, analysis_error = :error, error_kind = :kind " +
            "WHERE id = :fileId",
    )
    abstract suspend fun setStatus(
        fileId: Long,
        status: AnalysisStatus,
        error: String?,
        kind: String?,
    )

    // —— 删除序列（03 §4.4）所需 ——

    @Query("SELECT entity_id FROM music_file WHERE id = :fileId")
    abstract suspend fun entityIdOf(fileId: Long): Long?

    @Query("SELECT path FROM music_file WHERE id = :fileId")
    abstract suspend fun pathOf(fileId: Long): String?

    @Query("DELETE FROM music_file WHERE id = :fileId")
    abstract suspend fun deleteById(fileId: Long)

    @Query("SELECT COUNT(*) FROM music_file WHERE entity_id = :entityId")
    abstract suspend fun countForEntity(entityId: Long): Int

    /** 按分析状态取文件（测试与「最近分析记录」页用）。 */
    @Query("SELECT * FROM music_file WHERE analysis_status = :status")
    abstract suspend fun allByStatus(status: AnalysisStatus): List<MusicFileEntity>

}
