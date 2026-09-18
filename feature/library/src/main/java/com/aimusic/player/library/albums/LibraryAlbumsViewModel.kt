package com.aimusic.player.library.albums

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
 * 专辑列表（`09 §3.2.3`）。
 *
 * 与歌手列表同理：固定按名称排序（`06 §4.3`），纯导航入口。
 * 聚合查询在 DAO 侧完成（`06 §3.7` 的 `observeAlbumList`），`releaseDate` 取代表文件内嵌。
 */
@HiltViewModel
class LibraryAlbumsViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
) : ViewModel() {

    val state: StateFlow<LibraryAlbumsUiState> = libraryRepository.observeAlbums()
        .map { albums ->
            LibraryAlbumsUiState(
                listState = if (albums.isEmpty()) {
                    ListUiState.Empty
                } else {
                    ListUiState.Content(albums)
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryAlbumsUiState())
}
