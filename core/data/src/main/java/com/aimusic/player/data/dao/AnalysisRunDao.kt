package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.entity.AnalysisRunFileCrossRef
import com.aimusic.player.data.entity.ScanSourceEntity
import com.aimusic.player.data.model.RunFileRow
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
}
