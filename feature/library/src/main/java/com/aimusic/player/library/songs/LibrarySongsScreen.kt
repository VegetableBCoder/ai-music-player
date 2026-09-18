package com.aimusic.player.library.songs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aimusic.player.ui.component.CollectEvents
import com.aimusic.player.ui.component.ConfirmDialog
import com.aimusic.player.ui.component.ListUiState

/**
 * 有状态外壳（`09 §8` 的落地补充）：取 ViewModel、收事件流、把一次性事件变成副作用。
 *
 * 屏幕不知道路由是谁 —— 导航以回调传入（`:feature:*` 不能依赖 `:app`，
 * 见 `AppNavHost` 的 KDoc）。渲染全部交给无状态的 [LibrarySongsContent]，测试也只测它。
 */
@Composable
fun LibrarySongsScreen(
    onOpenSongDetail: (Long) -> Unit = {},
    onNavigateToScan: () -> Unit = {},
    vm: LibrarySongsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    CollectEvents(vm.events) { event ->
        when (event) {
            is LibrarySongsEvent.OpenSongDetail -> onOpenSongDetail(event.entityId)
            LibrarySongsEvent.NavigateToScan -> onNavigateToScan()
            // 提示（含「无可用文件」）与队列入口随各自外壳接线
            is LibrarySongsEvent.ShowMessage -> Unit
            LibrarySongsEvent.OpenQueue -> Unit
        }
    }

    LibrarySongsContent(
        state = state,
        onRowClick = vm::onRowClick,
        onPlayAll = vm::onPlayAll,
        onQuickAddToQueue = vm::onQuickAddToQueue,
        onOpenActionSheet = vm::onOpenActionSheet,
        onRowLongClick = { vm.enterMultiSelect(it) },
        onSortChange = vm::onSortChange,
        onConfirm = vm::onConfirm,
        onDismissConfirm = vm::onDismissConfirm,
    )
}

/**
 * 无状态内容（测试只测它）：只吃 UiState 与回调，回调全给默认值以便用例只传要断言的那几个。
 *
 * 本批是**骨架**：列表区只表达四态与条数，`SongRow` 与排序控件随批次 2 落地
 * （见 `09 §4.3.1` / `§4.5.1`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibrarySongsContent(
    state: LibrarySongsUiState,
    onRowClick: (com.aimusic.player.data.model.SongListItem) -> Unit = {},
    onPlayAll: () -> Unit = {},
    onQuickAddToQueue: (com.aimusic.player.data.model.SongListItem) -> Unit = {},
    onOpenActionSheet: (com.aimusic.player.data.model.SongListItem) -> Unit = {},
    onRowLongClick: (Long) -> Unit = {},
    onSortChange: (com.aimusic.player.data.model.SongSort) -> Unit = {},
    onConfirm: (com.aimusic.player.ui.component.ConfirmRequest) -> Unit = {},
    onDismissConfirm: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("歌曲") })

        when (val list = state.listState) {
            is ListUiState.Loading -> Placeholder("加载中")

            // 库空 → 引导去扫描（`09 §5.1`）。本批只出文案，动作随批次 2 接线。
            is ListUiState.Empty -> Placeholder("音乐库还是空的")

            is ListUiState.EmptyFiltered -> Placeholder("没有符合筛选条件的歌曲")

            is ListUiState.Error -> Placeholder("出错了，请查看详情")

            is ListUiState.Content -> Placeholder("共 ${list.items.size} 首")
        }
    }

    ConfirmDialog(
        request = state.confirmation,
        onConfirm = onConfirm,
        onDismiss = onDismissConfirm,
    )
}

@Composable
private fun Placeholder(text: String) {
    Text(text = text, modifier = Modifier.padding(16.dp))
}
