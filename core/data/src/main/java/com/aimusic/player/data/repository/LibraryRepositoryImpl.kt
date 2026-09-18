package com.aimusic.player.data.repository

import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.SongEntity
import com.aimusic.player.data.model.AlbumListItem
import com.aimusic.player.data.model.ArtistListItem
import com.aimusic.player.data.model.SongDetail
import com.aimusic.player.data.model.SongFilter
import com.aimusic.player.data.model.SongListItem
import com.aimusic.player.data.model.SongScope
import com.aimusic.player.data.model.SongSort
import com.aimusic.player.data.model.TagProjection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * 列表装配：**三步查询、两次批量、零 N+1**（`06 §4.1`）。
 *
 * Step 1 取实体行（带排序 / 筛选 / 维度）→ Step 2 批量取可播性 → Step 3 批量取标签，
 * 在 `combine` 里合并成 `SongListItem`。标签用 `onlyMain = true`（I6）。
 *
 * 标签投影是 `TagProjection`（`:core:data` 自己的三字段类型），**不是** `:core:llm` 契约里
 * 那个两字段 `TagRef` —— 两者同名不同形，详见 `TagProjection` 的 KDoc。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepositoryImpl(private val db: MusicDatabase) : LibraryRepository {

    private val libraryDao = db.libraryDao()
    private val songDao = db.songDao()
    private val tagDao = db.tagDao()

    override fun observeSongs(scope: SongScope, sort: SongSort): Flow<List<SongListItem>> =
        observeSongs(scope, sort, SongFilter())

    override fun observeSongs(
        scope: SongScope,
        sort: SongSort,
        filter: SongFilter,
    ): Flow<List<SongListItem>> =
        libraryDao.observeEntities(SongQueryBuilder.build(scope, sort, filter))
            .distinctUntilChanged()
            .flatMapLatest(::assemble)

    override fun observeSearch(query: String): Flow<List<SongListItem>> =
        libraryDao.observeEntities(SongQueryBuilder.search(query))
            .distinctUntilChanged()
            .flatMapLatest(::assemble)

    override fun observeArtists(): Flow<List<ArtistListItem>> = db.songArtistDao().observeArtistList()

    override fun observeAlbums(): Flow<List<AlbumListItem>> = libraryDao.observeAlbumList()

    override suspend fun getAvailableFileIds(entityIds: List<Long>): Set<Long> =
        if (entityIds.isEmpty()) emptySet() else db.musicFileDao().playableIds(entityIds).toSet()

    override suspend fun getSongDetail(entityId: Long): SongDetail =
        observeSongDetail(entityId).firstOrNull()
            ?: error("歌曲实体不存在：$entityId（调用方只应对存在的实体取详情）")

    override fun observeSongDetail(entityId: Long): Flow<SongDetail> =
        songDao.observeById(entityId)
            .filterNotNull()
            .flatMapLatest { entity ->
                combine(
                    libraryDao.observeArtistNames(listOf(entityId)),
                    tagDao.observeTagsOfSongs(listOf(entityId), onlyMain = false),
                    libraryDao.observeFilesOf(entityId),
                    db.lyricDao().observeLyric(entityId),
                ) { artists, tags, files, lyric ->
                    SongDetail(
                        entity = entity,
                        artists = artists.map { it.name },
                        // 详情要**完整标签**，不按主要分类折叠
                        tags = tags.map { TagProjection(it.name, it.categoryId, it.categoryName) },
                        lyric = lyric,
                        files = files,
                        representativeFileId = files.firstOrNull { it.isRepresentative }?.fileId,
                    )
                }
            }

    /** Step 2 + Step 3 的批量装配；行集未变时不重订阅。 */
    private fun assemble(entities: List<SongEntity>): Flow<List<SongListItem>> {
        if (entities.isEmpty()) return flowOf(emptyList())

        val ids = entities.map { it.id }
        return combine(
            libraryDao.observePlayableCounts(ids),
            tagDao.observeTagsOfSongs(ids, onlyMain = true),
            libraryDao.observeArtistNames(ids),
        ) { playable, tagRows, artistRows ->
            val playableIds = playable.mapTo(HashSet()) { it.entityId }
            val tagsByEntity = tagRows.groupBy({ it.entityId }) {
                TagProjection(it.name, it.categoryId, it.categoryName)
            }
            val artistsByEntity = artistRows.groupBy({ it.entityId }, { it.name })

            entities.map { entity ->
                SongListItem(
                    entityId = entity.id,
                    title = entity.canonicalTitle,
                    displayArtists = entity.displayArtists,
                    artistNames = artistsByEntity[entity.id].orEmpty(),
                    albumName = entity.albumName,
                    albumArtist = entity.albumArtist,
                    coverCachePath = entity.coverCachePath,
                    tags = tagsByEntity[entity.id].orEmpty(),
                    // 派生，不落库
                    isPlayable = entity.id in playableIds,
                )
            }
        }
    }
}
