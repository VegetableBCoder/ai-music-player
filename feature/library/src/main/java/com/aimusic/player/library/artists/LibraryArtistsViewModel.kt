package com.aimusic.player.library.artists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aimusic.player.data.repository.LibraryRepository
import com.aimusic.player.ui.component.ListUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 歌手列表（`09 §3.2.2`）。
 *
 * 列表本身**固定按名称排序**，不受 `SongSort` 影响（`06 §4.3`：排序只作用于歌曲列表）。
 * 行尾无 `⋯`、不支持多选 —— 它是纯导航入口。
 */
@HiltViewModel
class LibraryArtistsViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
) : ViewModel() {

    val state: StateFlow<LibraryArtistsUiState> = libraryRepository.observeArtists()
        .map { artists ->
            LibraryArtistsUiState(
                listState = if (artists.isEmpty()) {
                    ListUiState.Empty
                } else {
                    ListUiState.Content(artists)
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryArtistsUiState())
}
