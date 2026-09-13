package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aimusic.player.data.entity.SongArtistEntity
import com.aimusic.player.data.model.ArtistListItem
import kotlinx.coroutines.flow.Flow

@Dao
interface SongArtistDao {

    @Query(
        "SELECT artist_name AS name, COUNT(*) AS songCount FROM song_artist " +
            "GROUP BY artist_name ORDER BY artist_name",
    )
    fun observeArtistList(): Flow<List<ArtistListItem>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<SongArtistEntity>)
}
