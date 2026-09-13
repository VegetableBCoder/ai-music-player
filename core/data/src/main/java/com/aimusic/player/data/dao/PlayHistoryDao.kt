package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aimusic.player.data.entity.PlayHistoryEntity

@Dao
interface PlayHistoryDao {

    @Insert
    suspend fun addHistory(row: PlayHistoryEntity)

    @Query(
        "DELETE FROM play_history WHERE id NOT IN " +
            "(SELECT id FROM play_history ORDER BY played_at DESC LIMIT :keep)",
    )
    suspend fun pruneHistory(keep: Int = 500)

    @Query("SELECT entity_id FROM play_history ORDER BY played_at DESC LIMIT :limit")
    suspend fun recentSongs(limit: Int): List<Long>
}
