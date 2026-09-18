package com.aimusic.player.library.artists

import androidx.compose.runtime.Immutable
import com.aimusic.player.data.model.ArtistListItem
import com.aimusic.player.ui.component.ListUiState

/** 歌手列表 UiState（`09 §3.2.2`）。纯导航入口，无多选、无 `⋯`。 */
@Immutable
data class LibraryArtistsUiState(
    val listState: ListUiState<ArtistListItem> = ListUiState.Loading,
)

/** 一次性事件（`09 §3.2.2`）。 */
sealed interface LibraryArtistsEvent {
    data class OpenArtist(val name: String) : LibraryArtistsEvent
}
