package com.aimusic.player.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * 二次确认的种类（`09 §4.6`）。决定确认后走哪个执行分支。
 *
 * 目前只列出 `09 §4.6` 表格点名的操作；新增操作时同步补在这里，
 * 避免各处用字符串表达种类。
 */
enum class ConfirmKind {
    DELETE_SONGS,
    DELETE_FILE_PHYSICALLY,
    REMOVE_FILE,
    CLEAR_QUEUE,
    DELETE_TAG,
    DELETE_CATEGORY,
}

/**
 * 二次确认请求（`09 §4.6`）。
 *
 * 作为 **`UiState` 字段**下发，而不是走 `Channel` 事件 —— 这样旋转 / 重组后对话框不丢，
 * 且与触发它的选中态同源（`09 §4.6` 明文：`Channel` 只承载导航与 Toast/Snackbar）。
 *
 * `title` / `message` / `confirmLabel` 都是**已备好的文案**：字面来自需求文档
 * （`00-总览 §5` 的确认级别），由调用方在此处构造时写定，界面层不再查表。
 */
@Immutable
data class ConfirmRequest(
    val kind: ConfirmKind,
    val ids: List<Long>,
    val title: String,
    val message: String,
    val confirmLabel: String,
    val destructive: Boolean = true,
) {
    companion object {

        /** 删除歌曲实体：**不触碰磁盘**，提示里要把这点讲清楚（`00-总览 §5`）。 */
        fun deleteSongs(ids: List<Long>) = ConfirmRequest(
            kind = ConfirmKind.DELETE_SONGS,
            ids = ids,
            title = if (ids.size > 1) "删除 ${ids.size} 首歌曲？" else "删除这首歌？",
            message = "将移除歌曲及其标签、歌词关联与播放记录；磁盘上的音乐文件不会被删除。",
            confirmLabel = "删除",
        )

        /** 物理删除文件：破坏性，二次确认（`09 §4.6` 表格）。 */
        fun deleteFilePhysically(fileId: Long) = ConfirmRequest(
            kind = ConfirmKind.DELETE_FILE_PHYSICALLY,
            ids = listOf(fileId),
            title = "物理删除该文件？",
            message = "将从磁盘永久删除，不可恢复。",
            confirmLabel = "永久删除",
        )
    }
}

/**
 * 统一二次确认对话框（`09 §4.6`）。**无状态**：只接收请求与回调，状态由 ViewModel 持有。
 *
 * `request == null` 时不渲染 —— 调用方直接把 `UiState` 里的字段传进来即可。
 */
@Composable
fun ConfirmDialog(
    request: ConfirmRequest?,
    onConfirm: (ConfirmRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    if (request == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(request) }) { Text(request.confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
