package com.aimusic.player.library.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
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
import com.aimusic.player.ui.component.ConfirmDialog

/** 有状态外壳（`09 §8`）。 */
@Composable
fun CategoriesScreen(
    onSelectDimension: (LibraryDimension) -> Unit = {},
    onOpenCategory: (Long) -> Unit = {},
    onOpenTag: (String) -> Unit = {},
    vm: CategoriesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    CategoriesContent(
        state = state,
        onSelectDimension = onSelectDimension,
        onSearchQueryChange = vm::onSearchQueryChange,
        onOpenCategory = onOpenCategory,
        onOpenTag = onOpenTag,
    )
}

/**
 * 无状态内容：只吃 UiState 与回调。
 *
 * 「标签搜索**只在本页可用**」（`06 §4.2.2`：不进全局搜索页），故搜索框属于本屏。
 * 分类行尾的 `⋯`（设为/取消主要分类、删除分类，有标签则灰置 I12）随**批次 4** 接线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesContent(
    state: CategoriesUiState,
    onSelectDimension: (LibraryDimension) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onOpenCategory: (Long) -> Unit = {},
    onOpenTag: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(LibraryDimension.TAGS.label) })
        LibraryDimensionTabs(
            current = LibraryDimension.TAGS,
            onSelect = onSelectDimension,
        )

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text("搜索标签") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (state.isSearching) {
            // 搜索模式：结果是标签（点进去是标签歌曲列表），不是分类
            if (state.tagResults.isEmpty()) {
                Text(
                    text = ErrorText.resolve("search.empty", mapOf("query" to state.searchQuery)),
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items = state.tagResults, key = { it.name }) { tag ->
                        ListItem(
                            headlineContent = { Text(tag.name) },
                            supportingContent = { Text("${tag.songCount} 首") },
                            modifier = Modifier
                                .clickable { onOpenTag(tag.name) }
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        )
                    }
                }
            }
        } else {
            // 浏览模式：分类列表
            if (state.categories.isEmpty()) {
                Text(
                    text = ErrorText.resolve("tag.category.empty"),
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items = state.categories, key = { it.id }) { category ->
                        ListItem(
                            headlineContent = { Text(category.name) },
                            supportingContent = {
                                // 主要分类给个可见标记（`06 §3.4` 的多选主要分类）
                                Text(
                                    if (category.isMain) {
                                        "主要 · ${category.tagCount} 个标签"
                                    } else {
                                        "${category.tagCount} 个标签"
                                    },
                                )
                            },
                            modifier = Modifier
                                .clickable { onOpenCategory(category.id) }
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        )
                    }
                }
            }
        }
    }

    ConfirmDialog(
        request = state.confirmation,
        onConfirm = {},
        onDismiss = {},
    )
}
