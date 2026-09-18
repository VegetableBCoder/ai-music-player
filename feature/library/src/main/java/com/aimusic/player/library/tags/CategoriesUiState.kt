package com.aimusic.player.library.tags

import androidx.compose.runtime.Immutable
import com.aimusic.player.data.model.CategoryListItem
import com.aimusic.player.data.model.TagListItem
import com.aimusic.player.ui.component.ConfirmRequest
import com.aimusic.player.ui.component.UiMessage

/**
 * 分类 & 标签页 UiState（`09 §3.2.4`）。
 *
 * 标签搜索**只在本页可用**（`06 §4.2.2`：搜索标签不进全局搜索页）。
 */
@Immutable
data class CategoriesUiState(
    val categories: List<CategoryListItem> = emptyList(),
    val searchQuery: String = "",
    val tagResults: List<TagListItem> = emptyList(),
    val isSearching: Boolean = false,
    val newCategoryDialog: Boolean = false,
    val confirmation: ConfirmRequest? = null,
) {
    val isEmpty: Boolean get() = categories.isEmpty() && !isSearching
}

/** 一次性事件（`09 §3.2.4`）。 */
sealed interface CategoriesEvent {

    data class OpenCategory(val categoryId: Long) : CategoriesEvent

    /** 点标签搜索结果 → 进该标签的歌曲列表。 */
    data class OpenTagSearchResult(val tagName: String) : CategoriesEvent

    data class ShowMessage(val message: UiMessage) : CategoriesEvent

    /** 新建标签撞名 → 提示「已存在同名标签，是否直接复用？」（`06 §3.4`）。 */
    data class ReuseExistingTagPrompt(val categoryId: Long) : CategoriesEvent
}
