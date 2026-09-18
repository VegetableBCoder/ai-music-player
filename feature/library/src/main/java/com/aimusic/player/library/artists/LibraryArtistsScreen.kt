package com.aimusic.player.library.artists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aimusic.player.ui.component.CollectEvents
import com.aimusic.player.ui.component.ListUiState

/** 有状态外壳（`09 §8`）：收 VM 与事件，渲染交给无状态 Content。 */
@Composable
fun LibraryArtistsScreen(
    onOpenArtist: (String) -> Unit = {},
    vm: LibraryArtistsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LibraryArtistsContent(state = state, onOpenArtist = onOpenArtist)
}

/**
 * 无状态内容：只吃 UiState 与回调。
 *
 * 本批是骨架 —— 行内不出 `⋯`（`09 §3.2.2`：歌手列表是纯导航入口），批次 2 补真实行渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryArtistsContent(
    state: LibraryArtistsUiState,
    onOpenArtist: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("歌手") })

        when (val list = state.listState) {
            is ListUiState.Loading -> Text("加载中", modifier = Modifier.padding(16.dp))
            is ListUiState.Empty -> Text("音乐库还是空的", modifier = Modifier.padding(16.dp))
            is ListUiState.EmptyFiltered -> Text("没有符合筛选条件的歌曲", modifier = Modifier.padding(16.dp))
            is ListUiState.Error -> Text("出错了，请查看详情", modifier = Modifier.padding(16.dp))
            is ListUiState.Content -> Text("共 ${list.items.size} 位歌手", modifier = Modifier.padding(16.dp))
        }
    }
}
