package com.aimusic.player.library.albums

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
import com.aimusic.player.ui.component.ListUiState

/** 有状态外壳（`09 §8`）。 */
@Composable
fun LibraryAlbumsScreen(
    onOpenAlbum: (String, String?) -> Unit = { _, _ -> },
    vm: LibraryAlbumsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LibraryAlbumsContent(state = state, onOpenAlbum = onOpenAlbum)
}

/** 无状态内容：只吃 UiState 与回调。骨架阶段行内不出 `⋯`（`09 §3.2.3`）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryAlbumsContent(
    state: LibraryAlbumsUiState,
    onOpenAlbum: (String, String?) -> Unit = { _, _ -> },
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("专辑") })

        when (val list = state.listState) {
            is ListUiState.Loading -> Text("加载中", modifier = Modifier.padding(16.dp))
            is ListUiState.Empty -> Text("音乐库还是空的", modifier = Modifier.padding(16.dp))
            is ListUiState.EmptyFiltered -> Text("没有符合筛选条件的歌曲", modifier = Modifier.padding(16.dp))
            is ListUiState.Error -> Text("出错了，请查看详情", modifier = Modifier.padding(16.dp))
            is ListUiState.Content -> Text("共 ${list.items.size} 张专辑", modifier = Modifier.padding(16.dp))
        }
    }
}
