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
 * 歌曲库 UiState（`09 §3.2.1` / `§3.3.1`）。
 *
 * 四个维度列表（歌手 / 专辑 / 标签歌曲）共用同一投影 [SongListItem]，
 * 因此各屏的 UiState 形状也基本一致，差别只在 `scope` 与面板裁剪。
 */
@Immutable
data class LibrarySongsUiState(
    val listState: ListUiState<SongListItem> = ListUiState.Loading,
    /** `全部播放(N)` 里的 N。 */
    val totalCount: Int = 0,
    val sort: SongSort = SongSort.NAME,
    val filter: SongFilter = SongFilter(),
    /** 勾选 = 歌曲实体 `entityId`（`09 §3.3.1`）。 */
    val multiSelect: MultiSelectState<Long> = MultiSelectState(),
    val actionSheet: ActionSheetModel? = null,
    /**
     * 打开面板的那一行。
     *
     * `ActionSheetModel` 只有标题文本，而面板动作要落到**具体某首**上。放在 UiState 里
     * 而不是让界面按标题反查 —— 同名歌曲（不同 albumArtist）反查会认错项。
     */
    val currentSheetItem: SongListItem? = null,
    val confirmation: ConfirmRequest? = null,
    /** 当前播放的实体，用于行高亮（`09 §3.2.1` 数据来源里的 `PlaybackController.state`）。 */
    val nowPlayingId: Long? = null,
) {
    companion object {
        fun initial() = LibrarySongsUiState()
    }
}

/** 一次性事件（`09 §3.3.1`）。导航与提示走 `Channel`，禁止用 `StateFlow`（会重放）。 */
sealed interface LibrarySongsEvent {

    data class OpenSongDetail(val entityId: Long) : LibrarySongsEvent

    data object OpenQueue : LibrarySongsEvent

    data object NavigateToScan : LibrarySongsEvent

    /** 提示文案已由 VM 解析好（`UiMessage.text`）。 */
    data class ShowMessage(val message: UiMessage) : LibrarySongsEvent
}
