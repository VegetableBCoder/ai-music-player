package com.aimusic.player.library.tags

import androidx.compose.runtime.Immutable
import com.aimusic.player.data.model.TagListItem
import com.aimusic.player.ui.component.ConfirmRequest
import com.aimusic.player.ui.component.ListUiState
import com.aimusic.player.ui.component.MultiSelectState
import com.aimusic.player.ui.component.UiMessage

/**
 * 某分类下标签列表 UiState（`09 §3.2.5`）。
 *
 * 支持多选删除标签，但**有歌的标签不可勾选**（I12）—— 由 `selectableIds` 排除
 * `songCount > 0` 的项来保证。
 */
@Immutable
data class CategoryTagsUiState(
    val categoryName: String = "",
    val listState: ListUiState<TagListItem> = ListUiState.Loading,
    /** 勾选 = **标签名**（`09 §3.2.5`），故泛型参数是 `String` 而非实体 id。 */
    val multiSelect: MultiSelectState<String> = MultiSelectState(),
    val newTagDialog: Boolean = false,
    val confirmation: ConfirmRequest? = null,
)

/** 一次性事件（`09 §3.2.5`）。 */
sealed interface CategoryTagsEvent {

    data class OpenTag(val tagName: String) : CategoryTagsEvent

    data class ShowMessage(val message: UiMessage) : CategoryTagsEvent
}
