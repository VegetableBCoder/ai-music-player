package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.aimusic.player.data.entity.SongEntity
import com.aimusic.player.data.model.AlbumListItem
import com.aimusic.player.data.model.EntityArtistRow
import com.aimusic.player.data.model.EntityPlayableRow
import com.aimusic.player.data.model.MusicFileItem
import kotlinx.coroutines.flow.Flow

/**
 * 列表装配用的查询（`06 §3.3`）。
 *
 * `@RawQuery` 的 `observedEntities` 是关键：动态 SQL Room 无法静态分析，必须显式告诉它
 * 这条查询依赖 `song_entity`，否则表变了 Flow 不会重新发射。
 */
@Dao
interface LibraryDao {

    @RawQuery(observedEntities = [SongEntity::class])
    fun observeEntities(query: SupportSQLiteQuery): Flow<List<SongEntity>>

    /** 可播性：按实体聚合可用文件数（`03 §5` 要求派生，不落库）。 */
    @Query(
        """SELECT f.entity_id AS entityId, COUNT(*) AS playableCount FROM music_file f
           WHERE f.entity_id IN (:ids) AND f.analysis_status = 'LINKED'
           GROUP BY f.entity_id""",
    )
    fun observePlayableCounts(ids: List<Long>): Flow<List<EntityPlayableRow>>

    /** 批量取演唱者名（按 position），避免逐首查询。 */
    @Query(
        """SELECT a.entity_id AS entityId, a.artist_name AS name FROM song_artist a
           WHERE a.entity_id IN (:ids) ORDER BY a.position""",
    )
    fun observeArtistNames(ids: List<Long>): Flow<List<EntityArtistRow>>

    @Query(
        """SELECT s.album_name AS albumName, s.album_artist AS albumArtist,
                  COUNT(*) AS songCount, MIN(s.album_release_date) AS releaseDate,
                  MAX(s.cover_cache_path) AS coverCachePath
           FROM song_entity s
           WHERE s.album_name IS NOT NULL
           GROUP BY s.album_name, s.album_artist
           ORDER BY s.album_name COLLATE NOCASE ASC, s.album_artist COLLATE NOCASE ASC""",
    )
    fun observeAlbumList(): Flow<List<AlbumListItem>>

    /** 代表文件置顶（`is_representative` 为 1 时 `IS NULL` 为 0，排在前面），其余按体积降序。 */
    @Query(
        """SELECT f.id AS fileId, f.path AS path, f.file_name AS fileName, f.size AS size,
                  f.format AS format, f.duration_ms AS durationMs,
                  f.is_representative = 1 AS isRepresentative,
                  f.analysis_status AS analysisStatus
           FROM music_file f WHERE f.entity_id = :entityId
           ORDER BY f.is_representative IS NULL, f.size DESC, f.id""",
    )
    fun observeFilesOf(entityId: Long): Flow<List<MusicFileItem>>
}
