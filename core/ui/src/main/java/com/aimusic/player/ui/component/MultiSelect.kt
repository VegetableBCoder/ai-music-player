package com.aimusic.player.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 行在多选态的呈现（`09 §4.3.1`）。sealed 的嵌套 data class 要经外层限定名构造。 */
@Immutable
sealed interface RowSelectionState {

    data object Hidden : RowSelectionState

    /** 显示勾选圈。`enabled = false` 用于不可用歌曲（I3）与受保护项（I12）。 */
    data class Visible(val selected: Boolean, val enabled: Boolean) : RowSelectionState
}

/**
 * 多选态状态模型（`09 §4.3.3`）。**无状态组件只读它，状态由 ViewModel 持有。**
 *
 * `selectableIds` 是「当前筛选结果中的可选集」—— 不可用歌曲（I3）与受保护项（I12）已被剔除，
 * `toggle` 与 `selectAll` 都以它为准，故这两类项天然不可勾选。
 *
 * **`T` 为何是泛型**：各列表的「行的标识」类型并不统一 —— 歌曲 / 文件 / 队列项用
 * 实体 id（`Long`），而某分类下的标签列表按 `09 §3.2.5` 是**勾选=标签名**（`String`）。
 * 契约 `§4.3.3` 的示例只写了 `Set<Long>`，此处放宽为泛型以让同一个组件服务两类列表；
 * 否则标签列表得另造一套多选逻辑，与「统一交互三件套」的初衷相悖。
 *
 * **注意：Kotlin 没有「类型参数默认值」**（那是 C++ / TypeScript 的规则），所以各处
 * 必须显式写出 `MultiSelectState<Long>` / `MultiSelectState<String>`，写裸的
 * `MultiSelectState` 会因 `T` 无从推断而编译失败。
 */
@Immutable
data class MultiSelectState<T>(
    val isActive: Boolean = false,
    val selectedIds: Set<T> = emptySet(),
    val selectableIds: Set<T> = emptySet(),
) {
    val selectedCount: Int get() = selectedIds.size

    val allSelected: Boolean
        get() = selectableIds.isNotEmpty() && selectedIds.containsAll(selectableIds)

    val canBatch: Boolean get() = selectedIds.isNotEmpty()

    fun toggle(id: T) = if (id in selectableIds) {
        copy(selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id)
    } else {
        this
    }

    fun selectAll() = copy(selectedIds = selectedIds + selectableIds)

    fun clearSelection() = copy(selectedIds = emptySet())

    /** 行渲染用：隐藏 / 显示（选中与否 / 是否可选）。 */
    fun rowStateFor(id: T): RowSelectionState =
        if (!isActive) {
            RowSelectionState.Hidden
        } else {
            RowSelectionState.Visible(selected = id in selectedIds, enabled = id in selectableIds)
        }
}

/**
 * 多选态顶栏（`09 §4.3.3`）：`取消 · 已选 N · 全选`。
 *
 * 无状态：只吃 `state` 与回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiSelectTopBar(
    state: MultiSelectState<*>,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        title = { Text("已选 ${state.selectedCount}") },
        navigationIcon = {
            IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = "取消")
            }
        },
        actions = {
            TextButton(onClick = onSelectAll, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (state.allSelected) "取消全选" else "全选")
            }
        },
    )
}

/** 批量条上的一个动作（`09 §4.3.3`）。各列表传入自己的子集与顺序。 */
@Immutable
data class BatchAction(
    val label: String,
    val icon: ImageVector,
)

/**
 * 底部批量条（`09 §4.3.3`）：多选态出现于底部导航之**下**。
 *
 * 无状态：动作列表由各列表页决定（加入队列 / 添加到标签 / 从标签移除 / 删除 / 移除队列 /
 * 重试分析 / 物理删除），这里只负责渲染与禁用态。
 */
@Composable
fun MultiBatchBar(
    state: MultiSelectState<*>,
    actions: List<BatchAction>,
    onAction: (BatchAction) -> Unit,
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            actions.forEach { action ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .clickable(enabled = state.canBatch) { onAction(action) }
                        .semantics {
                            contentDescription = "${action.label}，已选 ${state.selectedCount}"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(action.icon, contentDescription = null)
                    Text(action.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
