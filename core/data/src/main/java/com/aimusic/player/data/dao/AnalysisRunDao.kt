package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.entity.AnalysisRunFileCrossRef
import com.aimusic.player.data.entity.ScanSourceEntity
import com.aimusic.player.data.model.RunFileRow
import com.aimusic.player.data.model.RunStatus
import com.aimusic.player.data.model.SourceKind
import kotlinx.coroutines.flow.Flow

@Dao
interface AnalysisRunDao {

    @Insert
    suspend fun createRun(run: AnalysisRunEntity): Long

    @Insert
    suspend fun linkFiles(rows: List<AnalysisRunFileCrossRef>)

    @Query("SELECT * FROM analysis_run ORDER BY started_at DESC LIMIT 1")
    suspend fun latest(): AnalysisRunEntity?

    /** 取消时把批次标成 `ABORTED`（`04 §4.9`）：已提交的批次保留，计数也保留。 */
    @Query("UPDATE analysis_run SET status = :status WHERE id = :runId")
    suspend fun setStatus(runId: Long, status: RunStatus)

    @Query(
        """SELECT f.*, r.id AS linkedEntityId FROM music_file f
           JOIN analysis_run_file arf ON arf.file_id = f.id
           LEFT JOIN song_entity r ON r.id = f.entity_id
           WHERE arf.run_id = :runId ORDER BY f.file_name""",
    )
    fun observeRunFiles(runId: Long): Flow<List<RunFileRow>>

    /** 「最近分析记录」页订阅（`09 §3.2.10`）。 */
    @Query("SELECT * FROM analysis_run ORDER BY started_at DESC LIMIT 1")
    fun observeLatest(): Flow<AnalysisRunEntity?>

    /** 逐文件累加（spec §10：一批里成功 18 失败 2 → 18/2）。 */
    @Query("UPDATE analysis_run SET analyzed_ok = analyzed_ok + 1 WHERE id = :runId")
    suspend fun bumpAnalyzedOk(runId: Long)

    @Query("UPDATE analysis_run SET failed_count = failed_count + 1 WHERE id = :runId")
    suspend fun bumpFailed(runId: Long)

    /** 结束：COMPLETED 或 ABORTED，两者都写 finished_at（`05 §4.9`）。 */
    @Query("UPDATE analysis_run SET status = :status, finished_at = :finishedAt WHERE id = :runId")
    suspend fun finish(runId: Long, status: RunStatus, finishedAt: Long)

    /** 「移除记录」：只解绑批次清单，**不删 music_file**。 */
    @Query("DELETE FROM analysis_run_file WHERE run_id = :runId AND file_id IN (:fileIds)")
    suspend fun unlinkFiles(runId: Long, fileIds: List<Long>): Int
}

@Dao
interface ScanSourceDao {

    @Query("SELECT * FROM scan_source WHERE kind = :kind ORDER BY path")
    fun observeSources(kind: SourceKind): Flow<List<ScanSourceEntity>>

    /** 启用中的来源（两个 kind 一起给）；调用方按 `kind` 分流（`04 §3.4`）。 */
    @Query("SELECT * FROM scan_source WHERE enabled = 1 ORDER BY path")
    suspend fun enabledSources(): List<ScanSourceEntity>

    @Query("SELECT * FROM scan_source WHERE kind = :kind AND path = :path LIMIT 1")
    suspend fun findByKindAndPath(kind: SourceKind, path: String): ScanSourceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSource(source: ScanSourceEntity): Long

    @Query("DELETE FROM scan_source WHERE id = :id")
    suspend fun removeById(id: Long): Int

    @Query("UPDATE scan_source SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    }
