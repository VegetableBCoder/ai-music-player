package com.aimusic.player.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars

/**
 * 底部操作面板（`09 §4.3.2`）—— 行级操作的**唯一**入口（`09 §4.7`：不用左滑，
 * 正因左滑对读屏与手部不便用户不可达）。
 *
 * 无状态：`model` 为 null 即不显示；`onAction` 把选中的项交回调用方（ViewModel 负责分发）。
 * 面板项的裁剪已由 [ActionSheetModel] 的工厂按能力做好，这里只负责渲染与危险项配色。
 *
 * > 本文件与 `09 §2` 清单的对应关系：清单写 `ActionSheet.kt = SongActionSheet + ActionSheetModel
 * > + SongAction`，实际把模型与枚举按关注点拆在 `SongAction.kt`，`BatchAction` 拆在
 * > `MultiSelect.kt`（它本就属于多选那一套）。类型齐全、仅文件归属不同，已记入 `09 §2`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionSheet(
    model: ActionSheetModel?,
    onDismiss: () -> Unit,
    onAction: (SongAction) -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    if (model == null) return

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            text = model.title.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        model.actions.forEach { action ->
            ListItem(
                headlineContent = { Text(action.label()) },
                leadingContent = { Icon(action.icon(), contentDescription = null) },
                // 删除类用错误色，与面板里其它项区分（`09 §4.3.2` 的破坏性提示）
                colors = if (action == SongAction.DELETE) {
                    ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.error)
                } else {
                    ListItemDefaults.colors()
                },
                modifier = Modifier
                    .clickable { onAction(action) }
                    .fillMaxWidth()
                    .heightIn(min = 48.dp), // 触控目标 ≥48dp（`09 §4.7`）
            )
        }
        // 手势区避让：面板自身要补底部 inset，否则最后一项被系统导航条压住
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
