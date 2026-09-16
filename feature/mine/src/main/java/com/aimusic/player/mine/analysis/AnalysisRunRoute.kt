package com.aimusic.player.mine.analysis

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 有状态外壳（`09 §8`）：收集 VM、把一次性事件转成 snackbar / 导航，渲染交给无状态 Content。
 *
 * 事件不放进 state —— 放进 state 的话旋转屏幕会重放（"已移除记录"又弹一次）。
 */
@Composable
fun AnalysisRunScreen(
    onOpenSettings: () -> Unit,
    onOpenSong: (Long) -> Unit,
    vm: AnalysisRunViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is AnalysisRunEvent.ShowMessage -> snackbar.showSnackbar(event.text)
                is AnalysisRunEvent.OpenSongDetail -> onOpenSong(event.entityId)
                AnalysisRunEvent.OpenSettings -> onOpenSettings()
            }
        }
    }

    AnalysisRunScreenContent(
        state = state,
        onRetry = vm::onRetry,
        onRemoveRecord = vm::onRemoveRecord,
        onOpenSettings = onOpenSettings,
        onOpenSong = onOpenSong,
    )
}
