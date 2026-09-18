package com.aimusic.player.search

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
fun SearchScreen(
    onOpenSongDetail: (Long) -> Unit = {},
    vm: SearchViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    SearchContent(
        state = state,
        onQueryChange = vm::onQueryChange,
        onClearQuery = vm::onClearQuery,
    )
}

/**
 * 无状态内容：只吃 UiState 与回调。
 *
 * 骨架阶段只出「历史区 / 结果四态」两类呈现；输入框自动聚焦、行渲染与多选随批次 3 落地。
 * 空状态文案按 `09 §5.1`：查询非空且无结果 →「未找到与「xxx」相关的内容」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit = {},
    onClearQuery: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("搜索") })

        if (state.showHistory) {
            // `09 §5.1`：查询为空 → 展示搜索历史（无历史则引导）；历史随批次 3 落库
            Text("搜索历史", modifier = Modifier.padding(16.dp))
        } else {
            when (val result = state.resultState) {
                is ListUiState.Loading -> Text("加载中", modifier = Modifier.padding(16.dp))
                is ListUiState.Empty -> Text(
                    "未找到与「${state.query}」相关的内容",
                    modifier = Modifier.padding(16.dp),
                )
                is ListUiState.EmptyFiltered -> Text("没有符合筛选条件的歌曲", modifier = Modifier.padding(16.dp))
                is ListUiState.Error -> Text("出错了，请查看详情", modifier = Modifier.padding(16.dp))
                is ListUiState.Content -> Text("共 ${result.items.size} 条结果", modifier = Modifier.padding(16.dp))
            }
        }
    }
}
