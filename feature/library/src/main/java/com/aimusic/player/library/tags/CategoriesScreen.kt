package com.aimusic.player.library.tags

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
import com.aimusic.player.ui.component.ConfirmDialog

/** 有状态外壳（`09 §8`）。 */
@Composable
fun CategoriesScreen(
    onOpenCategory: (Long) -> Unit = {},
    onOpenTag: (String) -> Unit = {},
    vm: CategoriesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    CategoriesContent(state = state, onOpenCategory = onOpenCategory, onOpenTag = onOpenTag)
}

/**
 * 无状态内容：只吃 UiState 与回调。
 *
 * 骨架阶段只出分类计数与空状态；行尾 `⋯`（设为/取消主要分类、删除分类）随批次 4 落地。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesContent(
    state: CategoriesUiState,
    onOpenCategory: (Long) -> Unit = {},
    onOpenTag: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("分类与标签") })

        if (state.isEmpty) {
            Text("该分类还没有标签", modifier = Modifier.padding(16.dp))
        } else {
            Text("共 ${state.categories.size} 个分类", modifier = Modifier.padding(16.dp))
        }
    }

    ConfirmDialog(
        request = state.confirmation,
        onConfirm = {},
        onDismiss = {},
    )
}
