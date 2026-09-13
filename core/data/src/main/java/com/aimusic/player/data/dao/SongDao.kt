package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.text.TextNormalizer
import com.aimusic.player.data.entity.EntityTagCrossRef
import com.aimusic.player.data.entity.SongArtistEntity
import com.aimusic.player.data.entity.SongEntity
import com.aimusic.player.data.entity.TagEntity
import com.aimusic.player.data.model.AnalysisStatus

@Dao
abstract class SongDao {

    @Query("SELECT * FROM song_entity WHERE canonical_title = :title AND artists_key = :key LIMIT 1")
    abstract suspend fun findByIdentity(title: String, key: String): SongEntity?

    @Query("SELECT * FROM song_entity WHERE id = :id LIMIT 1")
    abstract suspend fun byId(id: Long): SongEntity?

    @Insert
    abstract suspend fun insertSong(entity: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertArtists(rows: List<SongArtistEntity>)

    @Query("DELETE FROM song_entity WHERE id = :entityId")
    abstract suspend fun deleteSongById(entityId: Long)

    /**
     * 展示字段刷新（`03 §4.3`）：回填专辑信息并**作废封面缓存路径**（由封面仓库下次访问时重建）。
     *
     * 取值（代表文件的内嵌元数据）由调用方传入，本方法只负责确定性写入。
     * 单条 `UPDATE` 本身就是原子的，不需要额外的 `@Transaction` 包裹。
     */
    @Query(
        "UPDATE song_entity SET album_name = :albumName, album_artist = :albumArtist, " +
            "album_release_date = :albumReleaseDate, cover_cache_path = NULL WHERE id = :entityId",
    )
    abstract suspend fun refreshDisplayFields(
        entityId: Long,
        albumName: String?,
        albumArtist: String?,
        albumReleaseDate: String?,
    )

    @Query("UPDATE song_entity SET play_count = play_count + 1, last_played_at = :now WHERE id = :id")
    abstract suspend fun bumpPlayCount(id: Long, now: Long)

    @Query(
        """UPDATE song_entity SET complete_count = complete_count + :complete,
           skip_count = skip_count + :skip, interrupt_count = interrupt_count + :interrupt
           WHERE id = :id""",
    )
    abstract suspend fun bumpSessionResult(id: Long, complete: Int, skip: Int, interrupt: Int)

    // —— 挂靠文件需要的其余表语句（跨表，故声明在本 DAO 内） ——

    @Query("SELECT analysis_status FROM music_file WHERE id = :fileId")
    abstract suspend fun analysisStatusOf(fileId: Long): String?

    @Query(
        "UPDATE music_file SET entity_id = :entityId, analysis_status = 'LINKED', analyzed_at = :now " +
            "WHERE id = :fileId",
    )
    abstract suspend fun linkFile(fileId: Long, entityId: Long, now: Long)

    @Query("SELECT id FROM music_file WHERE entity_id = :entityId ORDER BY size DESC, id ASC LIMIT 1")
    abstract suspend fun representativeCandidate(entityId: Long): Long?

    /**
     * 代表文件选举（`03 §4.2`）。非代表一律置 `NULL` 而非 `0` —— `is_representative`
     * 为 `NULL` 时彼此不冲突，才容得下同一实体的多条非代表文件（见 `03 §2.4`）。
     */
    @Query(
        "UPDATE music_file SET is_representative = CASE WHEN id = :winner THEN 1 ELSE NULL END " +
            "WHERE entity_id = :entityId",
    )
    abstract suspend fun setRepresentative(entityId: Long, winner: Long)

    @Query("SELECT id FROM category WHERE name = :name")
    abstract suspend fun categoryIdByName(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTag(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEntityTag(row: EntityTagCrossRef)

    /** 代表文件选举：取体积最大者，并列时取 id 较小者，保证确定性。 */
    @Transaction
    open suspend fun reelectRepresentative(entityId: Long) {
        val winner = representativeCandidate(entityId) ?: return
        setRepresentative(entityId, winner)
    }

    /**
     * 挂靠文件（`03 §4.1` 步骤 1–7）。
     *
     * 步骤 8（刷新展示字段）刻意**不在这里**：它要以代表文件的内嵌元数据回填，
     * 而 DAO 由 Room 实例化、拿不到 `MetadataReader`；且 `03 §1` 给 `:core:data`
     * 的存储职责只有「物理删除文件与读取封面缓存路径」，不含读元数据。
     * 因此元数据取值由调用方完成，数据层只负责确定性写入。
     */
    @Transaction
    open suspend fun attachAnalysisResult(fileId: Long, result: NormalizeResult, now: Long) {
        // 1. I4：已 LINKED 的文件直接返回（幂等）
        if (analysisStatusOf(fileId) == AnalysisStatus.LINKED.name) return

        // 2. 身份键走唯一实现（08 §4.2）
        val normalizedArtists = TextNormalizer.normalizeArtists(result.artists)
        val artistsKey = normalizedArtists.joinToString("\u001F")

        // 3/4. 按身份键复用或新建实体，并写演唱者行（position 按归一化后顺序）
        val entityId = findByIdentity(result.canonicalTitle, artistsKey)?.id
            ?: insertSong(
                SongEntity(
                    canonicalTitle = result.canonicalTitle,
                    artistsKey = artistsKey,
                    displayArtists = result.artists.joinToString(" / "),
                    createdAt = now,
                ),
            ).also { newId ->
                insertArtists(
                    normalizedArtists.mapIndexed { position, name ->
                        SongArtistEntity(entityId = newId, artistName = name, position = position)
                    },
                )
            }

        // 5. 挂靠
        linkFile(fileId, entityId, now)

        // 6. 代表文件选举
        reelectRepresentative(entityId)

        // 7. 标签写入：分类不在当前有效集合中 → 丢弃该条（AI 不得新建分类）
        result.tagAssignments.forEach { assignment ->
            val categoryId = categoryIdByName(assignment.category) ?: return@forEach
            insertTag(TagEntity(name = assignment.name, categoryId = categoryId, createdAt = now))
            insertEntityTag(EntityTagCrossRef(entityId, assignment.name, now))
        }
    }
}
