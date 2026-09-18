package com.aimusic.player.library.tags

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aimusic.player.ui.component.ConfirmDialog
import com.aimusic.player.ui.component.ListUiState

/** 有状态外壳（`09 §8`）：注入 `categoryId`，收状态，渲染交给无状态 Content。 */
@Composable
fun CategoryTagsScreen(
    categoryId: Long,
    onOpenTag: (String) -> Unit = {},
    vm: CategoryTagsViewModel = hiltViewModel(),
) {
    LaunchedEffect(categoryId) { vm.setCategoryId(categoryId) }

    val state by vm.state.collectAsStateWithLifecycle()

    CategoryTagsContent(state = state, onOpenTag = onOpenTag)
}

/**
 * 无状态内容：只吃 UiState 与回调。
 *
 * 骨架阶段只出四态与计数；多选删除、行尾 `⋯` 随批次 4 落地（I12 的可选集已在 VM 里算好）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryTagsContent(
    state: CategoryTagsUiState,
    onOpenTag: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(state.categoryName.ifBlank { "标签" }) })

        when (val list = state.listState) {
            is ListUiState.Loading -> Text("加载中", modifier = Modifier.padding(16.dp))
            // `09 §5.1`：分类无标签 → 「该分类还没有标签」+ 新建入口
            is ListUiState.Empty -> Text("该分类还没有标签", modifier = Modifier.padding(16.dp))
            is ListUiState.EmptyFiltered -> Text("没有符合筛选条件的歌曲", modifier = Modifier.padding(16.dp))
            is ListUiState.Error -> Text("出错了，请查看详情", modifier = Modifier.padding(16.dp))
            is ListUiState.Content -> Text("共 ${list.items.size} 个标签", modifier = Modifier.padding(16.dp))
        }
    }

    ConfirmDialog(
        request = state.confirmation,
        onConfirm = {},
        onDismiss = {},
    )
}
