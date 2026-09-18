package com.aimusic.player.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * 一次性提示（`09 §4.6`）。
 *
 * **`text` 是已解析好的文案**：由 ViewModel 在发事件前调 `common/error/ErrorText.resolve`
 * 得到 —— 与既有 `ScanViewModel` / `AnalysisRunViewModel` 的做法一致，文案来源仍唯一。
 *
 * 包一层而不是直接传 `String`，是为了以后要加「带动作按钮的提示」时不必改所有事件的签名。
 */
@Immutable
data class UiMessage(val text: String)

/**
 * 收集一次性事件（`09 §4.6`）。
 *
 * 用 `repeatOnLifecycle(STARTED)` 而不是直接 `LaunchedEffect { flow.collect(...) }`：
 * 退到后台时暂停收集，避免在不可见状态下弹 snackbar 或触发导航。
 *
 * 一次性事件必须走 `Channel`，**禁止用 `StateFlow`** —— 后者会重放，导致旋转后重复提示
 * 或重复导航（`09 §4.6`）。
 */
@Composable
fun <T> CollectEvents(flow: Flow<T>, onEvent: (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(flow, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            flow.collect(onEvent)
        }
    }
}
