package com.aimusic.player.library.artists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.library.LibraryDimension
import com.aimusic.player.library.LibraryDimensionTabs
import com.aimusic.player.ui.component.ListSurface

/** 有状态外壳（`09 §8`）：收 VM 与事件，渲染交给无状态 Content。 */
@Composable
fun LibraryArtistsScreen(
    onSelectDimension: (LibraryDimension) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    vm: LibraryArtistsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LibraryArtistsContent(
        state = state,
        onSelectDimension = onSelectDimension,
        onOpenArtist = onOpenArtist,
    )
}

/**
 * 无状态内容：只吃 UiState 与回调。
 *
 * 行内**不出 `⋯`、不支持多选**（`09 §3.2.2` / `§4.3.3` 清单：歌手列表是纯导航入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryArtistsContent(
    state: LibraryArtistsUiState,
    onSelectDimension: (LibraryDimension) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(LibraryDimension.ARTISTS.label) })
        LibraryDimensionTabs(
            current = LibraryDimension.ARTISTS,
            onSelect = onSelectDimension,
        )

        ListSurface(
            state = state.listState,
            // 名称即唯一键（`09 §4.5.3`）
            key = { it.name },
            contentType = { "artist" },
            emptyTitle = ErrorText.resolve("library.empty"),
            modifier = Modifier.weight(1f),
        ) { artist ->
            // 纯导航行：整行可点、无行尾动作（`09 §3.2.2`）。
            // 不造 `ArtistRow` 组件 —— `09 §2` 的组件清单里没有它，这类「图标 + 两行文本」
            // 的导航行用 Material3 的 `ListItem` 就够，为每屏造一个专用组件只会让清单膨胀。
            ListItem(
                headlineContent = { Text(artist.name) },
                supportingContent = { Text("${artist.songCount} 首") },
                modifier = Modifier
                    .clickable { onOpenArtist(artist.name) }
                    .fillMaxWidth()
                    .heightIn(min = 48.dp), // 触控目标 ≥48dp（`09 §4.7`）
            )
        }
    }
}
