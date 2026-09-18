package com.aimusic.player.search

import androidx.compose.runtime.Immutable
import com.aimusic.player.data.model.SongListItem
import com.aimusic.player.ui.component.ActionSheetModel
import com.aimusic.player.ui.component.ListUiState
import com.aimusic.player.ui.component.MultiSelectState
import com.aimusic.player.ui.component.UiMessage

/**
 * 搜索 UiState（`09 §3.2.7`）。
 *
 * 结果**不分组**：歌名与歌手命中的合并在同一个列表里（`06 §4.4`，锁定决策）。
 */
@Immutable
data class SearchUiState(
    val query: String = "",
    val history: List<String> = emptyList(),
    val resultState: ListUiState<SongListItem> = ListUiState.Empty,
    /** 查询为空时展示历史（`09 §3.2.7`）。 */
    val showHistory: Boolean = true,
    /** 勾选 = 歌曲实体 `entityId`（`09 §3.2.7`）。 */
    val multiSelect: MultiSelectState<Long> = MultiSelectState(),
    val actionSheet: ActionSheetModel? = null,
)

/** 一次性事件（`09 §3.2.7`）。 */
sealed interface SearchEvent {

    data class OpenSongDetail(val entityId: Long) : SearchEvent

    data class ShowMessage(val message: UiMessage) : SearchEvent
}
