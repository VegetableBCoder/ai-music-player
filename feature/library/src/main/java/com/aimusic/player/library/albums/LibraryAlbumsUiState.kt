package com.aimusic.player.library.albums

import androidx.compose.runtime.Immutable
import com.aimusic.player.data.model.AlbumListItem
import com.aimusic.player.ui.component.ListUiState

/**
 * 专辑列表 UiState（`09 §3.2.3`）。纯导航入口，无多选、无 `⋯`。
 *
 * 专辑由「专辑名 + 专辑歌手」共同确定（`06 §4.2.1`）—— 同名不同歌手的专辑是两张，
 * 故事件里两者都要带。
 */
@Immutable
data class LibraryAlbumsUiState(
    val listState: ListUiState<AlbumListItem> = ListUiState.Loading,
)

/** 一次性事件（`09 §3.2.3`）。 */
sealed interface LibraryAlbumsEvent {
    data class OpenAlbum(val albumName: String, val albumArtist: String?) : LibraryAlbumsEvent
}
