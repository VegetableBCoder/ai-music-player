package com.aimusic.player.library.detail

import androidx.compose.runtime.Immutable
import com.aimusic.player.ui.component.ConfirmRequest
import com.aimusic.player.ui.component.ListUiState
import com.aimusic.player.ui.component.MultiSelectState
import com.aimusic.player.ui.component.UiMessage

/**
 * 歌曲详情页里「这首歌本身」的展示模型（`09 §3.2.17` 的 `song: SongDetailUi`）。
 *
 * `09` 只点了名、没给字段。骨架期按「详情页要显示什么」定最小集合：
 * 封面 / 名称 / 歌手 / 专辑（`06 §4.5`）。完整标签单独放
 * [SongDetailUiState.tags]，因为它们随标签增删独立变化。
 */
@Immutable
data class SongDetailUi(
    val entityId: Long,
    val title: String,
    val displayArtists: String,
    val albumName: String?,
    val coverCachePath: String?,
)

/** 文件列表行（`09 §3.2.17` 的 `fileListState: ListUiState<MusicFileUi>`）。 */
@Immutable
data class MusicFileUi(
    val fileId: Long,
    val fileName: String,
    val path: String,
    val size: Long,
    val format: String,
    val durationMs: Long?,
    val isRepresentative: Boolean,
    val analysisStatus: String,
)

/**
 * 详情页的歌词状态（`09 §3.2.17` 的 `lyricStatus: LyricStatus`，`05 §5`）。
 *
 * 区分「未关联」与「文件已失效」两态：前者给不了动作，后者的出口是「移除关联」。
 */
@Immutable
sealed interface LyricStatus {
    data object Linked : LyricStatus
    data object NotLinked : LyricStatus
    data object FileMissing : LyricStatus
}

/** 标签编辑面板状态（`09 §3.2.17` 的 `tagEditor: TagEditorState?`）。批次 4 用。 */
@Immutable
data class TagEditorState(
    val selectedTagNames: Set<String> = emptySet(),
    /** 撞名时提示「已存在同名标签，是否直接复用？」（`06 §3.4`）。 */
    val reusePrompt: String? = null,
)

/**
 * 歌曲详情 UiState（`09 §3.2.17`）。
 *
 * 覆盖页：从任意列表推入，返回即回到来源（`09 §4.1.4` 选独立路由而非 sheet 的原因之一
 * 就是它的 ViewModel 能挂 `NavBackStackEntry` 生命周期）。
 */
@Immutable
data class SongDetailUiState(
    val song: SongDetailUi? = null,
    /** **完整标签**，不按主要分类折叠（`06 §4.5`）。 */
    val tags: List<String> = emptyList(),
    val fileListState: ListUiState<MusicFileUi> = ListUiState.Loading,
    val lyricStatus: LyricStatus = LyricStatus.NotLinked,
    /** 勾选 = `fileId`（文件列表的多选，`09 §3.2.17`）。 */
    val multiSelect: MultiSelectState<Long> = MultiSelectState(),
    val tagEditor: TagEditorState? = null,
    val confirmation: ConfirmRequest? = null,
)

/** 一次性事件（`09 §3.2.17`）。 */
sealed interface SongDetailEvent {

    data object OpenQueue : SongDetailEvent

    data class ShowMessage(val message: UiMessage) : SongDetailEvent

    /** 标签撞名，询问是否复用（`06 §3.4`）。 */
    data class ReuseExistingTagPrompt(val tagName: String) : SongDetailEvent
}
