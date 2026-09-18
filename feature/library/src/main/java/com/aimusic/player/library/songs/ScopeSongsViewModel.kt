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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 歌手 / 专辑 / 标签歌曲列表（`09 §3.2.6`）—— 三屏共用同一个 VM。
 *
 * 差异只有 `scope` 与面板裁剪，复制三份会让排序 / 筛选 / 多选逻辑各自漂移。
 *
 * `scope` 由界面在进入时通过 [setScope] 显式注入，不走带参导航（理由见
 * [com.aimusic.player.library.tags.CategoryTagsViewModel] 的 KDoc）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScopeSongsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val playbackController: PlaybackController,
    private val deletionService: DeletionService,
) : ViewModel() {

    private val scope = MutableStateFlow<SongScope?>(null)
    private val sort = MutableStateFlow(SongSort.NAME)
    private val filter = MutableStateFlow(SongFilter())
    private val multi = MutableStateFlow(MultiSelectState<Long>())
    private val sheet = MutableStateFlow<ActionSheetModel?>(null)
    private val confirm = MutableStateFlow<ConfirmRequest?>(null)

    private val _events = Channel<ScopeSongsEvent>(Channel.BUFFERED)
    val events: Flow<ScopeSongsEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsRepository.sortPreference.collect { sort.value = it }
        }
    }

    private val songs: Flow<List<SongListItem>> =
        combine(scope, sort, filter, ::Triple).flatMapLatest { (currentScope, currentSort, currentFilter) ->
            if (currentScope == null) {
                // scope 未注入前不查库 —— 用空列表而非 null，省得下游再判一次空
                flowOf(emptyList())
            } else {
                libraryRepository.observeSongs(currentScope, currentSort, currentFilter)
            }
        }

    /** 查询参数（scope + 排序 + 筛选）。 */
    private data class Query(
        val scope: SongScope?,
        val sort: SongSort,
        val filter: SongFilter,
    )

    /** 瞬时交互状态（多选 + 面板 + 确认）。 */
    private data class Pending(
        val selection: MultiSelectState<Long>,
        val sheet: ActionSheetModel?,
        val confirm: ConfirmRequest?,
    )

    // 拆成两组再合并：`combine` 只有 2~5 个 flow 的显式重载，六路会退化成 `Array<Any?>`
    private val query: Flow<Query> = combine(scope, sort, filter, ::Query)

    private val pending: Flow<Pending> = combine(multi, sheet, confirm, ::Pending)

    val state: StateFlow<ScopeSongsUiState> = combine(
        query,
        pending,
        songs,
        playbackController.state,
    ) { currentQuery, currentPending, items, playback: PlaybackUiState ->
        ScopeSongsUiState(
            scopeLabel = labelOf(currentQuery.scope),
            listState = when {
                items.isEmpty() && currentQuery.filter.isEmpty -> ListUiState.Empty
                items.isEmpty() -> ListUiState.EmptyFiltered
                else -> ListUiState.Content(items)
            },
            totalCount = items.size,
            sort = currentQuery.sort,
            filter = currentQuery.filter,
            // 可选集 = 当前结果里可播的那些（I3 / I12 的项天然排除）
            multiSelect = currentPending.selection.copy(
                selectableIds = items.filter { it.isPlayable }.map { it.entityId }.toSet(),
            ),
            actionSheet = currentPending.sheet,
            confirmation = currentPending.confirm,
            nowPlayingId = playback.currentEntityId,
            showRemoveFromTag = currentQuery.scope is SongScope.Tag,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScopeSongsUiState())

    /** 界面在进入时调用（幂等）。换 scope 要清空多选 —— 选择是「针对当前列表」的。 */
    fun setScope(next: SongScope) {
        if (scope.value != next) {
            scope.value = next
            multi.value = MultiSelectState<Long>()
        }
    }

    private fun labelOf(current: SongScope?): String = when (current) {
        null -> ""
        SongScope.Songs -> "歌曲"
        is SongScope.Artist -> current.name
        is SongScope.Album -> current.albumName
        is SongScope.Tag -> current.name
        is SongScope.SearchResult -> current.query
    }

    private fun currentItems(): List<SongListItem> =
        (state.value.listState as? ListUiState.Content)?.items.orEmpty()

    private fun show(message: String) {
        _events.trySend(ScopeSongsEvent.ShowMessage(UiMessage(message)))
    }

    fun onRowClick(item: SongListItem) {
        if (multi.value.isActive) {
            multi.value = multi.value.toggle(item.entityId)
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

    fun onSortChange(next: SongSort) {
        sort.value = next
        multi.value = MultiSelectState<Long>()
    }

    /** 标签维度裁掉「删除歌曲」、补「从『标签』移除」（`09 §3.2.6`）。 */
    fun onOpenActionSheet(item: SongListItem) {
        sheet.value = if (scope.value is SongScope.Tag) {
            ActionSheetModel.forTagSongs(item)
        } else {
            ActionSheetModel.forSong(item)
        }
    }

    fun onDismissActionSheet() {
        sheet.value = null
    }

    fun onAction(item: SongListItem, action: SongAction) {
        sheet.value = null
        when (action) {
            SongAction.PLAY -> onRowClick(item)
            SongAction.PLAY_NEXT -> playbackController.insertNext(item.entityId)
            SongAction.ADD_TO_QUEUE -> playbackController.appendToQueue(item.entityId)
            SongAction.VIEW_DETAIL -> _events.trySend(ScopeSongsEvent.OpenSongDetail(item.entityId))
            SongAction.MULTI_SELECT -> {
                multi.value = MultiSelectState<Long>(isActive = true, selectedIds = setOf(item.entityId))
            }
            SongAction.DELETE -> confirm.value = ConfirmRequest.deleteSongs(listOf(item.entityId))
            // 标签编辑（批 4）与「从标签移除」（批 4 接线 detachTags）
            SongAction.ADD_TO_TAG, SongAction.REMOVE_FROM_TAG -> Unit
        }
    }

    fun onToggleSelect(id: Long) {
        multi.value = multi.value.toggle(id)
    }

    fun exitMultiSelect() {
        multi.value = MultiSelectState<Long>()
    }

    fun onBatchDelete() {
        confirm.value = ConfirmRequest.deleteSongs(multi.value.selectedIds.toList())
    }

    fun onConfirm(request: ConfirmRequest) {
        viewModelScope.launch {
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
