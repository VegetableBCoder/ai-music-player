package com.aimusic.player.data.dao

import androidx.room.Dao
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

    @Query("SELECT * FROM music_file WHERE analysis_status IN ('UNANALYZED','FAILED')")
    abstract suspend fun pendingForAnalysis(): List<MusicFileEntity>

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
}
