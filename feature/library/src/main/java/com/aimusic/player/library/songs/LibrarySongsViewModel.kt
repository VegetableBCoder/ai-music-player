package com.aimusic.player.library.songs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.data.deletion.DeletionService
import com.aimusic.player.data.model.SongFilter
import com.aimusic.player.data.model.SongListItem
import com.aimusic.player.data.model.SongScope
import com.aimusic.player.data.model.SongSort
import com.aimusic.player.data.repository.LibraryRepository
import com.aimusic.player.data.repository.TagRepository
import com.aimusic.player.data.settings.SettingsRepository
import com.aimusic.player.playback.PlaybackController
import com.aimusic.player.playback.model.PlaybackUiState
import com.aimusic.player.ui.component.ActionSheetModel
import com.aimusic.player.ui.component.ConfirmKind
import com.aimusic.player.ui.component.ConfirmRequest
import com.aimusic.player.ui.component.ListUiState
import com.aimusic.player.ui.component.MultiSelectState
import com.aimusic.player.ui.component.SongAction
import com.aimusic.player.ui.component.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 歌曲库（`09 §3.3.1`）。
 *
 * 与契约示例的三处出入都是照实际类型修正的（`09` 已同步）：
 * 1. `SongFilter` 是 `titleQuery` / `hasLyricOnly`（`06 §3.2` 的定义），不是 `nameQuery` / `lyricsOnly`。
 * 2. 可选集来自 `SongListItem.isPlayable` —— 该投影**没有** `isSelectable` 字段。
 * 3. 取当前列表要用 `state.value`，`Flow` 没有 `value()` 方法。
 *
 * `tagRepository` 本批尚未用到（标签编辑随批次 4 接线），但保留注入 ——
 * `09 §3.3.1` 把它列为本 VM 的协作者，批 4 会直接用上。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("unused")
@HiltViewModel
class LibrarySongsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val tagRepository: TagRepository,
    private val playbackController: PlaybackController,
    private val deletionService: DeletionService,
) : ViewModel() {

    private val sort = MutableStateFlow(SongSort.NAME)
    private val filter = MutableStateFlow(SongFilter())
    private val multi = MutableStateFlow(MultiSelectState<Long>())
    private val sheet = MutableStateFlow<ActionSheetModel?>(null)
    private val confirm = MutableStateFlow<ConfirmRequest?>(null)

    private val _events = Channel<LibrarySongsEvent>(Channel.BUFFERED)
    val events: Flow<LibrarySongsEvent> = _events.receiveAsFlow()

    init {
        observeSortPreference()
    }

    /** 排序偏好来自 DataStore（`09 §3.2.1` 的数据来源）。 */
    private fun observeSortPreference() {
        viewModelScope.launch {
            settingsRepository.sortPreference.collect { sort.value = it }
        }
    }

    private val songs: Flow<List<SongListItem>> =
        combine(sort, filter, ::Pair).flatMapLatest { (currentSort, currentFilter) ->
            libraryRepository.observeSongs(SongScope.Songs, currentSort, currentFilter)
        }

    /**
     * 瞬时交互状态（多选 / 面板 / 确认）聚成一个类型。
     *
     * 这样最终 `state` 只需两路 `combine` —— 七路 `combine` 只能走 vararg 重载，
     * 参数类型退化成 `Array<Any?>`、每项都得强转，既啰嗦又丢类型安全。
     */
    private data class Transient(
        val selection: MultiSelectState<Long>,
        val sheet: ActionSheetModel?,
        val confirm: ConfirmRequest?,
    )

    private val transient: Flow<Transient> =
        combine(multi, sheet, confirm) { selection, actionSheet, confirmation ->
            Transient(selection, actionSheet, confirmation)
        }

    val state: StateFlow<LibrarySongsUiState> = combine(
        songs,
        combine(sort, filter, ::Pair),
        transient,
        playbackController.state,
    ) { items, (currentSort, currentFilter), pending, playback: PlaybackUiState ->
        LibrarySongsUiState(
            listState = when {
                items.isEmpty() && currentFilter.isEmpty -> ListUiState.Empty
                items.isEmpty() -> ListUiState.EmptyFiltered
                else -> ListUiState.Content(items)
            },
            totalCount = items.size,
            sort = currentSort,
            filter = currentFilter,
            // 可选集 = 当前结果里可播的那些：不可用（I3）与受保护项（I12）天然排除在外
            multiSelect = pending.selection.copy(
                selectableIds = items.filter { it.isPlayable }.map { it.entityId }.toSet(),
            ),
            actionSheet = pending.sheet,
            confirmation = pending.confirm,
            nowPlayingId = playback.currentEntityId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySongsUiState.initial())

    private fun currentItems(): List<SongListItem> =
        (state.value.listState as? ListUiState.Content)?.items.orEmpty()

    private fun show(message: String) {
        _events.trySend(LibrarySongsEvent.ShowMessage(UiMessage(message)))
    }

    // —— 排序 / 筛选：变更即清空多选（`页面设计 §9.3`）————

    fun onSortChange(next: SongSort) {
        sort.value = next
        multi.value = MultiSelectState<Long>()
    }

    fun onFilterChange(next: SongFilter) {
        filter.value = next
        multi.value = MultiSelectState<Long>()
    }

    // —— 行点击：多选态=勾选；否则=播放（不可用只提示，I3）————

    fun onRowClick(item: SongListItem) {
        if (multi.value.isActive) {
            toggleSelect(item.entityId)
            return
        }
        if (!item.isPlayable) {
            show(ErrorText.resolve("song.unavailable"))
            return
        }
        val items = currentItems()
        playbackController.playFromList(items.map { it.entityId }, items.indexOf(item))
    }

    fun onPlayAll() {
        val ids = currentItems().map { it.entityId }
        if (ids.isEmpty()) return
        playbackController.playFromList(ids, 0)
    }

    fun onQuickAddToQueue(item: SongListItem) {
        if (!item.isPlayable) {
            show(ErrorText.resolve("song.unavailable"))
            return
        }
        playbackController.appendToQueue(item.entityId)
    }

    // —— 操作面板 ————

    fun onOpenActionSheet(item: SongListItem) {
        sheet.value = ActionSheetModel.forSong(item)
    }

    fun onDismissActionSheet() {
        sheet.value = null
    }

    fun onAction(item: SongListItem, action: SongAction) {
        sheet.value = null
        when (action) {
            SongAction.PLAY -> onRowClick(item)
            SongAction.PLAY_NEXT -> playbackController.insertNext(item.entityId)
            SongAction.ADD_TO_QUEUE -> onQuickAddToQueue(item)
            // 标签编辑（批 4）与「从标签移除」（标签维度屏）各自接线
            SongAction.ADD_TO_TAG -> Unit
            SongAction.REMOVE_FROM_TAG -> Unit
            SongAction.VIEW_DETAIL -> _events.trySend(LibrarySongsEvent.OpenSongDetail(item.entityId))
            SongAction.MULTI_SELECT -> enterMultiSelect(item.entityId)
            SongAction.DELETE -> confirm.value = ConfirmRequest.deleteSongs(listOf(item.entityId))
        }
    }

    // —— 多选（`09 §4.3.3`）————

    fun enterMultiSelect(seed: Long? = null) {
        multi.value = MultiSelectState<Long>(isActive = true, selectedIds = setOfNotNull(seed))
    }

    fun toggleSelect(id: Long) {
        multi.value = multi.value.toggle(id)
    }

    fun selectAll() {
        multi.value = multi.value.selectAll()
    }

    fun exitMultiSelect() {
        multi.value = MultiSelectState<Long>()
    }

    fun onBatchAddToQueue() {
        multi.value.selectedIds.forEach(playbackController::appendToQueue)
        exitMultiSelect()
    }

    fun onBatchDelete() {
        confirm.value = ConfirmRequest.deleteSongs(multi.value.selectedIds.toList())
    }

    // —— 二次确认（`09 §4.6`）————

    fun onConfirm(request: ConfirmRequest) {
        viewModelScope.launch {
            // 本批只接线「删除歌曲」；其余种类随批次 4
            if (request.kind == ConfirmKind.DELETE_SONGS) {
                request.ids.forEach { deletionService.deleteSong(it) }
            }
            confirm.value = null
            exitMultiSelect()
        }
    }

    fun onDismissConfirm() {
        confirm.value = null
    }
}
