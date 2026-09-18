package com.aimusic.player.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aimusic.player.common.error.AppError
import com.aimusic.player.common.error.ErrorText

/**
 * 列表容器（`09 §4.5.2`）：把 [ListUiState] 四态渲染成占位或 `LazyColumn`。
 *
 * 为什么把四态收在一处 —— 四态的**动作不同**（`09 §5.1`）：库空引导去扫描、筛选无命中引导清除筛选、
 * 出错可重试。散在每个列表页里复制一遍，迟早有一页漏掉「清除筛选」。
 *
 * 与契约示例的补全（示例里是 `...`）：文案、动作、重试回调都要参数化，否则各屏无法表达
 * 自己的空态措辞（如标签歌曲列表要「该标签下还没有歌曲」）。默认值取自 `ErrorText`，符合
 * 「文案只在 ErrorText 取」的约定。
 *
 * **key 必须稳定且唯一**（`09 §4.5.3`）：歌曲用 `entityId`，队列项必须用 `queueItemId`
 * （队列允许重复）。**禁止用下标** —— 排序 / 筛选后下标整体错位。
 */
@Composable
fun <T> ListSurface(
    state: ListUiState<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    contentType: (T) -> Any? = { null },
    emptyTitle: String = ErrorText.resolve("library.empty"),
    emptyAction: (@Composable () -> Unit)? = null,
    emptyFilteredTitle: String = ErrorText.resolve("library.filter.empty"),
    onClearFilter: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    when (state) {
        is ListUiState.Loading -> LoadingPlaceholder(modifier)

        is ListUiState.Empty -> PlaceholderMessage(
            title = emptyTitle,
            action = emptyAction,
            modifier = modifier,
        )

        is ListUiState.EmptyFiltered -> PlaceholderMessage(
            title = emptyFilteredTitle,
            actionLabel = "清除筛选",
            onAction = onClearFilter,
            modifier = modifier,
        )

        is ListUiState.Error -> PlaceholderMessage(
            title = ErrorText.resolve(state.error.userMessageKey),
            actionLabel = "重试",
            onAction = onRetry,
            modifier = modifier,
        )

        is ListUiState.Content -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(items = state.items, key = key, contentType = contentType) { item ->
                itemContent(item)
            }
        }
    }
}

/** 加载中占位（`09 §4.5.2`）。 */
@Composable
fun LoadingPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 读屏可感知：「加载中」不只是一段动画
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "加载中" })
    }
}

/** 空态 / 错误态的通用占位：一句说明 + 至多一个动作按钮。 */
@Composable
private fun PlaceholderMessage(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        // 两种动作形态：现成按钮（给了标签与回调）或调用方自带的 Composable
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
        action?.invoke()
    }
}

/** 空态占位（库空 / 无标签等），由调用方给文案与动作。 */
@Composable
fun EmptyPlaceholder(
    title: String,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) = PlaceholderMessage(title = title, action = action, modifier = modifier)

/** 筛选无命中占位（`09 §5.1`）：出口是「清除筛选」。 */
@Composable
fun FilterEmptyPlaceholder(
    onClear: () -> Unit,
    title: String = ErrorText.resolve("library.filter.empty"),
    modifier: Modifier = Modifier,
) = PlaceholderMessage(
    title = title,
    actionLabel = "清除筛选",
    onAction = onClear,
    modifier = modifier,
)

/** 错误占位（`09 §4.5.2`）：文案取自 [AppError.userMessageKey]，出口是重试。 */
@Composable
fun ErrorPlaceholder(
    error: AppError,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) = PlaceholderMessage(
    title = ErrorText.resolve(error.userMessageKey),
    actionLabel = if (onRetry != null) "重试" else null,
    onAction = onRetry,
    modifier = modifier,
)
