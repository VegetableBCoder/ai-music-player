package com.aimusic.player.library.songs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.data.model.SongFilter
import com.aimusic.player.data.model.SongListItem
import com.aimusic.player.data.model.SongSort
import com.aimusic.player.library.LibraryDimension
import com.aimusic.player.library.LibraryDimensionTabs
import com.aimusic.player.ui.component.BatchAction
import com.aimusic.player.ui.component.CollectEvents
import com.aimusic.player.ui.component.ConfirmDialog
import com.aimusic.player.ui.component.ConfirmRequest
import com.aimusic.player.ui.component.ListSurface
import com.aimusic.player.ui.component.MultiBatchBar
import com.aimusic.player.ui.component.MultiSelectTopBar
import com.aimusic.player.ui.component.SongAction
import com.aimusic.player.ui.component.SongActionSheet
import com.aimusic.player.ui.component.SongRow
import com.aimusic.player.ui.component.SongScopeHeader

/**
 * 有状态外壳（`09 §8` 的落地补充）：取 ViewModel、收事件流、把一次性事件变成副作用。
 *
 * 屏幕不知道路由是谁 —— 导航以回调传入（`:feature:*` 不能依赖 `:app`，
 * 见 `AppNavHost` 的 KDoc）。渲染全部交给无状态的 [LibrarySongsContent]，测试也只测它。
 */
@Composable
fun LibrarySongsScreen(
    currentDimension: LibraryDimension = LibraryDimension.SONGS,
    snackbar: (String) -> Unit = {},
    onSelectDimension: (LibraryDimension) -> Unit = {},
    onOpenSongDetail: (Long) -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    vm: LibrarySongsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    CollectEvents(vm.events) { event ->
        when (event) {
            is LibrarySongsEvent.OpenSongDetail -> onOpenSongDetail(event.entityId)
            LibrarySongsEvent.NavigateToScan -> onNavigateToScan()
            is LibrarySongsEvent.ShowMessage -> snackbar(event.message.text)
            // 队列入口随 Phase 6 的 QueueScreen 落地
            LibrarySongsEvent.OpenQueue -> Unit
        }
    }

    LibrarySongsContent(
        state = state,
        currentDimension = currentDimension,
        onSelectDimension = onSelectDimension,
        onRowClick = vm::onRowClick,
        onPlayAll = vm::onPlayAll,
        onQuickAddToQueue = vm::onQuickAddToQueue,
        onOpenActionSheet = vm::onOpenActionSheet,
        onDismissActionSheet = vm::onDismissActionSheet,
        onAction = vm::onAction,
        onRowLongClick = { vm.enterMultiSelect(it) },
        onToggleSelect = vm::toggleSelect,
        onSelectAll = vm::selectAll,
        onExitMultiSelect = vm::exitMultiSelect,
        onBatchAddToQueue = vm::onBatchAddToQueue,
        onBatchDelete = vm::onBatchDelete,
        onSortChange = vm::onSortChange,
        onFilterChange = vm::onFilterChange,
        onClearFilter = { vm.onFilterChange(com.aimusic.player.data.model.SongFilter()) },
        onNavigateToScan = onNavigateToScan,
        onConfirm = vm::onConfirm,
        onDismissConfirm = vm::onDismissConfirm,
    )
}

/**
 * 无状态内容（测试只测它）：只吃 UiState 与回调，回调全给默认值以便用例只传要断言的那几个。
 *
 * 结构自上而下：多维切换 → 顶栏（多选态换成 [MultiSelectTopBar]）→ 表头
 * （[SongScopeHeader]，多选态隐藏）→ 列表（[ListSurface] + [SongRow]）→ 底部批量条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySongsContent(
    state: LibrarySongsUiState,
    currentDimension: LibraryDimension = LibraryDimension.SONGS,
    onSelectDimension: (LibraryDimension) -> Unit = {},
    onRowClick: (SongListItem) -> Unit = {},
    onPlayAll: () -> Unit = {},
    onQuickAddToQueue: (SongListItem) -> Unit = {},
    onOpenActionSheet: (SongListItem) -> Unit = {},
    onDismissActionSheet: () -> Unit = {},
    onAction: (SongListItem, SongAction) -> Unit = { _, _ -> },
    onRowLongClick: (Long) -> Unit = {},
    onToggleSelect: (Long) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onExitMultiSelect: () -> Unit = {},
    onBatchAddToQueue: () -> Unit = {},
    onBatchDelete: () -> Unit = {},
    onSortChange: (SongSort) -> Unit = {},
    onFilterChange: (com.aimusic.player.data.model.SongFilter) -> Unit = {},
    onClearFilter: () -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    onConfirm: (ConfirmRequest) -> Unit = {},
    onDismissConfirm: () -> Unit = {},
) {
    val selection = state.multiSelect
    val inMultiSelect = selection.isActive

    Column(Modifier.fillMaxSize()) {
        // 多选态把整个顶栏换成「取消 · 已选 N · 全选」（`09 §4.3.3`）
        if (inMultiSelect) {
            MultiSelectTopBar(
                state = selection,
                onCancel = onExitMultiSelect,
                onSelectAll = onSelectAll,
            )
        } else {
            TopAppBar(title = { Text(currentDimension.label) })
            // 维度切换只在歌曲库这一屏出现（子列表有自己的返回入口）
            LibraryDimensionTabs(current = currentDimension, onSelect = onSelectDimension)
            SongScopeHeader(
                totalCount = state.totalCount,
                sort = state.sort,
                filter = state.filter,
                onPlayAll = onPlayAll,
                onSortChange = onSortChange,
                onFilterChange = onFilterChange,
            )
        }

        ListSurface(
            state = state.listState,
            // key 用实体 id：排序 / 删除后下标会错位（`09 §4.5.3`）
            key = { it.entityId },
            contentType = { "song" },
            // 库空 → 引导去扫描（`09 §5.1`）
            emptyTitle = ErrorText.resolve("library.empty"),
            emptyAction = {
                TextButton(onClick = onNavigateToScan) { Text("扫描音乐") }
            },
            onClearFilter = onClearFilter,
            modifier = Modifier.weight(1f),
        ) { item ->
            SongRow(
                item = item,
                isPlaying = item.entityId == state.nowPlayingId,
                selection = selection.rowStateFor(item.entityId),
                // 点击的含义随多选态而变（`09 §3.2.1`）：多选态=勾选，否则=播放。
                // 在**界面侧**分流而不是全交给 VM —— 组件只表达「这一行被点了」，
                // 而「点了算勾选还是播放」是当前交互模式决定的，界面本来就知道。
                onRowClick = {
                    if (inMultiSelect) onToggleSelect(item.entityId) else onRowClick(item)
                },
                onQuickAddToQueue = { onQuickAddToQueue(item) },
                onOpenActionSheet = { onOpenActionSheet(item) },
                onLongClick = { onRowLongClick(item.entityId) },
            )
        }

        // 多选态底部换成批量条（`09 §4.3.3`）；动作集按列表裁剪
        if (inMultiSelect) {
            MultiBatchBar(
                state = selection,
                actions = batchActions(),
                onAction = { action ->
                    when (action.label) {
                        BATCH_ADD_TO_QUEUE -> onBatchAddToQueue()
                        BATCH_DELETE -> onBatchDelete()
                    }
                },
            )
        }
    }

    SongActionSheet(
        model = state.actionSheet,
        onDismiss = onDismissActionSheet,
        // 面板动作落到**打开面板的那一行**上（VM 在 onOpenActionSheet 时记下了它）
        onAction = { action -> state.currentSheetItem?.let { onAction(it, action) } },
    )

    ConfirmDialog(
        request = state.confirmation,
        onConfirm = onConfirm,
        onDismiss = onDismissConfirm,
    )
}

/** 歌曲列表的批量动作集（`09 §4.3.3`：加入队列 / 删除）。 */
internal const val BATCH_ADD_TO_QUEUE = "加入队列"
internal const val BATCH_DELETE = "删除"

internal fun batchActions(): List<BatchAction> = listOf(
    BatchAction(label = BATCH_ADD_TO_QUEUE, icon = Icons.Default.Add),
    BatchAction(label = BATCH_DELETE, icon = Icons.Default.Delete),
)
