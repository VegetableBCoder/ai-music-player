package com.aimusic.player.library.songs

import androidx.compose.runtime.Composable
import com.aimusic.player.data.model.SongScope
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * 歌手歌曲列表（`09 §3.1` 第 5 行、`§3.2.6`）。
 *
 * 三个维度（歌手 / 专辑 / 标签）的**差异只有 `SongScope` 与面板裁剪**，故共用
 * [ScopeSongsViewModel] 与 [ScopeSongsContent]。这里各留一个按契约命名的薄入口 ——
 * `09 §2` / `§3.1` 的标识符是锁定的（`02 §3.2` 要求不得自创命名），
 * 但实现不必复制三份，否则排序 / 筛选 / 多选逻辑会各自漂移。
 */
@Composable
fun ArtistSongsScreen(
    artistName: String,
    onOpenSongDetail: (Long) -> Unit = {},
    vm: ScopeSongsViewModel = hiltViewModel(),
) {
    ScopeSongsScreen(
        scope = SongScope.Artist(artistName),
        onOpenSongDetail = onOpenSongDetail,
        vm = vm,
    )
}

/** 专辑歌曲列表（`09 §3.1` 第 6 行）。专辑由「专辑名 + 专辑歌手」共同确定（`06 §4.2.1`）。 */
@Composable
fun AlbumSongsScreen(
    albumName: String,
    albumArtist: String?,
    onOpenSongDetail: (Long) -> Unit = {},
    vm: ScopeSongsViewModel = hiltViewModel(),
) {
    ScopeSongsScreen(
        scope = SongScope.Album(albumName, albumArtist),
        onOpenSongDetail = onOpenSongDetail,
        vm = vm,
    )
}

/** 标签歌曲列表（`09 §3.1` 第 8 行）。面板用 `forTagSongs`（无「删除歌曲」）。 */
@Composable
fun TagSongsScreen(
    tagName: String,
    onOpenSongDetail: (Long) -> Unit = {},
    vm: ScopeSongsViewModel = hiltViewModel(),
) {
    ScopeSongsScreen(
        scope = SongScope.Tag(tagName),
        onOpenSongDetail = onOpenSongDetail,
        vm = vm,
    )
}
