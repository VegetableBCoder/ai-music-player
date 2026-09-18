package com.aimusic.player.navigation

import kotlinx.serialization.Serializable

/**
 * 类型安全路由（`09 §4.1.1`）。
 *
 * 只声明**真正会被导航到**的目的地：先铺一堆没人能到的路由，会让「哪些屏已交付」看不出来。
 * 本文件在 Phase 3e 只有「我的」那几条；Phase 5 补上音乐库、搜索与歌曲详情覆盖页。
 *
 * **偏离 `09 §4.1.1` 一处（已记入文档）**：`ArtistSongsRoute` 带的是 `artistName`
 * 而不是文档写的 `artistId` —— `SongScope.Artist` 按**名称**过滤（`06 §4.2.1` 的
 * `EXISTS ... artist_name = :name`），且 `ArtistListItem` 只有 `name` 没有 id，
 * 文档的 `artistId` 映射不到任何实际查询。
 */
@Serializable
data object MineRoute

@Serializable
data object ScanRoute

@Serializable
data object AnalysisRoute

@Serializable
data object SettingsRoute

// —— 音乐库（Phase 5）——

/** 音乐库图。各维度（歌曲 / 歌手 / 专辑 / 标签）是它的子目的地。 */
@Serializable
data object SongsRoute

@Serializable
data object ArtistsRoute

@Serializable
data object AlbumsRoute

@Serializable
data object TagsHubRoute

/** 歌手歌曲列表。用**歌手名**，理由见文件头。 */
@Serializable
data class ArtistSongsRoute(val artistName: String)

/** 专辑歌曲列表。专辑由「专辑名 + 专辑歌手」共同确定（`06 §4.2.1`）。 */
@Serializable
data class AlbumSongsRoute(val albumName: String, val albumArtist: String?)

/** 某分类下的标签列表。 */
@Serializable
data class CategoryTagsRoute(val categoryId: Long)

/** 标签歌曲列表。 */
@Serializable
data class TagSongsRoute(val tagName: String)

// —— 搜索（Phase 5）——

@Serializable
data object SearchRoute

// —— 覆盖页（Phase 5 交付歌曲详情）——

/** 歌曲详情覆盖页（`09 §3.2.17`）。从任意列表推入，返回即回来源。 */
@Serializable
data class SongDetailRoute(val entityId: Long)
