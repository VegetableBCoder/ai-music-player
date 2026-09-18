package com.aimusic.player.library.songs

import androidx.compose.runtime.Immutable
import com.aimusic.player.data.model.SongFilter
import com.aimusic.player.data.model.SongListItem
import com.aimusic.player.data.model.SongSort
import com.aimusic.player.ui.component.ActionSheetModel
import com.aimusic.player.ui.component.ConfirmRequest
import com.aimusic.player.ui.component.ListUiState
import com.aimusic.player.ui.component.MultiSelectState
import com.aimusic.player.ui.component.UiMessage

/**
 * 歌手 / 专辑 / 标签歌曲列表的 UiState（`09 §3.2.6`）。
 *
 * 三屏**共用本类型与同一个 ViewModel**：差异只有 `scope` 与面板裁剪，
 * 复制三份会让排序 / 多选 / 筛选逻辑各自漂移（`09 §3.2.6` 把三者列为同级）。
 *
 * 与歌曲库的差别：多一个 [scopeLabel]（来源名称，用于 `SongScopeHeader`）。
 */
@Immutable
data class ScopeSongsUiState(
    val scopeLabel: String = "",
    val listState: ListUiState<SongListItem> = ListUiState.Loading,
    val totalCount: Int = 0,
    val sort: SongSort = SongSort.NAME,
    val filter: SongFilter = SongFilter(),
    /** 勾选 = 歌曲实体 `entityId`（`09 §3.2.6`）。 */
    val multiSelect: MultiSelectState<Long> = MultiSelectState(),
    val actionSheet: ActionSheetModel? = null,
    val confirmation: ConfirmRequest? = null,
    val nowPlayingId: Long? = null,
    /** 标签维度才给「从『标签』移除」；歌手 / 专辑不给（`09 §3.2.6`）。 */
    val showRemoveFromTag: Boolean = false,
)

/** 一次性事件（`09 §3.2.6`）。 */
sealed interface ScopeSongsEvent {

    data class OpenSongDetail(val entityId: Long) : ScopeSongsEvent

    data object OpenQueue : ScopeSongsEvent

    data class ShowMessage(val message: UiMessage) : ScopeSongsEvent
}
