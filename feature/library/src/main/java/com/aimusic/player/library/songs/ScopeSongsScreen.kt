package com.aimusic.player.library.songs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aimusic.player.data.model.SongScope
import com.aimusic.player.ui.component.CollectEvents
import com.aimusic.player.ui.component.ConfirmDialog
import com.aimusic.player.ui.component.ListUiState

/**
 * 歌手 / 专辑 / 标签歌曲列表（`09 §3.2.6`）—— **三屏共用这一个实现**。
 *
 * 三者差异只有 `scope` 与面板裁剪；复制三份会让排序 / 筛选 / 多选逻辑各自漂移。
 * 调用方（`AppNavHost`）按路由构造对应的 `SongScope` 传进来。
 */
@Composable
fun ScopeSongsScreen(
    scope: SongScope,
    onOpenSongDetail: (Long) -> Unit = {},
    vm: ScopeSongsViewModel = hiltViewModel(),
) {
    LaunchedEffect(scope) { vm.setScope(scope) }

    val state by vm.state.collectAsStateWithLifecycle()

    CollectEvents(vm.events) { event ->
        when (event) {
            is ScopeSongsEvent.OpenSongDetail -> onOpenSongDetail(event.entityId)
            // 提示与队列入口随各自外壳接线
            is ScopeSongsEvent.ShowMessage -> Unit
            ScopeSongsEvent.OpenQueue -> Unit
        }
    }

    ScopeSongsContent(
        state = state,
        onPlayAll = vm::onPlayAll,
        onSortChange = vm::onSortChange,
        onConfirm = vm::onConfirm,
        onDismissConfirm = vm::onDismissConfirm,
    )
}

/**
 * 无状态内容：只吃 UiState 与回调。
 *
 * 骨架阶段用 [ScopeSongsUiState.scopeLabel] 显示来源名；`全部播放(N)`、行渲染与面板随批次 2 落地。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopeSongsContent(
    state: ScopeSongsUiState,
    onPlayAll: () -> Unit = {},
    onSortChange: (com.aimusic.player.data.model.SongSort) -> Unit = {},
    onConfirm: (com.aimusic.player.ui.component.ConfirmRequest) -> Unit = {},
    onDismissConfirm: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(state.scopeLabel.ifBlank { "歌曲" }) })

        when (val list = state.listState) {
            is ListUiState.Loading -> Text("加载中", modifier = Modifier.padding(16.dp))
            // `09 §5.1`：标签下无歌 →「该标签下还没有歌曲」；其余维度沿用库空文案
            is ListUiState.Empty -> Text(
                if (state.showRemoveFromTag) "该标签下还没有歌曲" else "音乐库还是空的",
                modifier = Modifier.padding(16.dp),
            )
            is ListUiState.EmptyFiltered -> Text("没有符合筛选条件的歌曲", modifier = Modifier.padding(16.dp))
            is ListUiState.Error -> Text("出错了，请查看详情", modifier = Modifier.padding(16.dp))
            is ListUiState.Content -> Text("共 ${list.items.size} 首", modifier = Modifier.padding(16.dp))
        }
    }

    ConfirmDialog(
        request = state.confirmation,
        onConfirm = onConfirm,
        onDismiss = onDismissConfirm,
    )
}
