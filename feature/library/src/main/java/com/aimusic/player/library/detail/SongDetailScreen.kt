package com.aimusic.player.library.detail

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
import com.aimusic.player.ui.component.ConfirmDialog
import com.aimusic.player.ui.component.ListUiState

/** 有状态外壳（`09 §8`）：注入 `entityId`，收状态。 */
@Composable
fun SongDetailScreen(
    entityId: Long,
    vm: SongDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(entityId) { vm.setEntityId(entityId) }

    val state by vm.state.collectAsStateWithLifecycle()

    SongDetailContent(state = state)
}

/**
 * 无状态内容：只吃 UiState 与回调。
 *
 * 骨架阶段只出「这首歌是什么 + 标签 + 文件数 + 歌词状态」四块；
 * 文件列表行与它们的 `⋯`（移除 / 物理删除）、标签增删随批次 3/4 落地。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailContent(
    state: SongDetailUiState,
    onToggleFileSelect: (Long) -> Unit = {},
    onDismissConfirm: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(state.song?.title ?: "歌曲详情") })

        state.song?.let { song ->
            Text(song.displayArtists, modifier = Modifier.padding(horizontal = 16.dp))
            song.albumName?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
        }

        // `05 §4` / `09 §5.1`：分析成功但 0 标签显示「暂无标签」，**不算失败**
        Text(
            text = if (state.tags.isEmpty()) "暂无标签" else state.tags.joinToString("、"),
            modifier = Modifier.padding(16.dp),
        )

        when (val files = state.fileListState) {
            is ListUiState.Loading -> Text("加载中", modifier = Modifier.padding(16.dp))
            is ListUiState.Empty -> Text("没有关联的文件", modifier = Modifier.padding(16.dp))
            is ListUiState.EmptyFiltered -> Text("没有符合筛选条件的歌曲", modifier = Modifier.padding(16.dp))
            is ListUiState.Error -> Text("出错了，请查看详情", modifier = Modifier.padding(16.dp))
            is ListUiState.Content -> Text("管理的文件：${files.items.size} 个", modifier = Modifier.padding(16.dp))
        }

        // `05 §5`：未关联 / 已失效 是两态，出口不同（后者可「移除关联」）
        val lyricText = when (state.lyricStatus) {
            LyricStatus.Linked -> "已关联歌词"
            LyricStatus.NotLinked -> "未关联歌词"
            LyricStatus.FileMissing -> "歌词文件已失效"
        }
        Text(lyricText, modifier = Modifier.padding(16.dp))
    }

    ConfirmDialog(
        request = state.confirmation,
        onConfirm = {},
        onDismiss = onDismissConfirm,
    )
}
