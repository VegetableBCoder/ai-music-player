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
