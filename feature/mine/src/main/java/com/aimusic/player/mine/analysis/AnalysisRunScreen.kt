package com.aimusic.player.mine.analysis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.data.model.AnalysisStatus

/**
 * 分析记录页（`09 §3.2.10`）。
 *
 * `09 §8` 的拆法：本函数是**无状态 Content**，只吃 [AnalysisRunUiState] 与回调 ——
 * 于是它能在真机上被 `createComposeRule` 直接渲染与断言，不需要 ViewModel / Hilt / 设备存储。
 * 有状态的外壳（收集 VM、把 events 转 snackbar 与导航）留在 `AnalysisRunScreen`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnalysisRunScreenContent(
    state: AnalysisRunUiState,
    onRetry: (Long) -> Unit = {},
    onRemoveRecord: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSong: (Long) -> Unit = {},
) {
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("最近分析记录") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            state.progressFraction?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }

            Text(
                text = if (state.running) {
                    ErrorText.resolve("analysis.running", mapOf("done" to state.done, "total" to state.total))
                } else {
                    summaryText(state)
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            // 整批失败时，若首个失败原因能被识别（PARSE / AUTH），文案用具体的那条（09 §6）
            if (state.allFailed) {
                specificFailureText(state)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (state.rows.any { it.errorKind == FailureKind.AUTH.name }) {
                TextButton(onClick = onOpenSettings) { Text("去设置") }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.rows, key = { it.fileId }) { row ->
                    FileRow(row = row, onRetry = onRetry, onRemoveRecord = onRemoveRecord, onOpenSong = onOpenSong)
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    row: AnalysisFileUi,
    onRetry: (Long) -> Unit,
    onRemoveRecord: (Long) -> Unit,
    onOpenSong: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = row.fileName, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = statusLabel(row),
            style = MaterialTheme.typography.labelMedium,
        )
        if (row.canRetry) {
            TextButton(onClick = { onRetry(row.fileId) }) { Text("重试") }
        }
        if (row.status == AnalysisStatus.LINKED && row.linkedEntityId != null) {
            TextButton(onClick = { onOpenSong(row.linkedEntityId) }) { Text("查看歌曲") }
        }
        TextButton(onClick = { onRemoveRecord(row.fileId) }) { Text("移除记录") }
    }
}

/** 逐行状态标签：重试中优先于底层状态（`09 §5.1` 第 4 行：重试中不计失败）。 */
private fun statusLabel(row: AnalysisFileUi): String = when {
    row.retrying -> ErrorText.resolve("analysis.row.retrying")
    row.status == AnalysisStatus.ANALYZING -> ErrorText.resolve("analysis.row.analyzing")
    row.status == AnalysisStatus.LINKED -> ErrorText.resolve("analysis.row.linked")
    row.status == AnalysisStatus.FAILED -> {
        val base = ErrorText.resolve("analysis.row.failed")
        // 只在**键确实存在**时才附原因 —— 拼出来的键取不到会静默退化成 unknown 兜底文案，
        // 界面上就会显示"出错了，请查看详情"这种没有信息量的话（真机上就是这么照出来的）。
        val reasonKey = row.errorKind?.let { "analysis.row.failed.reason.${it.lowercase()}" }
        val reason = reasonKey?.let { ErrorText.resolve(it) }?.takeIf { it != ErrorText.resolve(null) }
        if (reason == null) base else "$base（$reason）"
    }
    else -> ErrorText.resolve("analysis.row.pending")
}

private fun summaryText(state: AnalysisRunUiState): String = if (state.hasRun) {
    ErrorText.resolve("analysis.partial", mapOf("ok" to state.ok, "failed" to state.failed))
} else {
    ""
}

/**
 * 整批失败时的具体提示。
 *
 * **只映射到"已存在"的文案键**（决定 #4：文案只取自需求文档，不许在代码里发明键）：
 * 我首版按 `analysis.all_failed.<kind>` 现场拼键，AUTH 情况拼出 `analysis.all_failed.auth` ——
 * 表里只有 `analysis.all_failed.parse`，于是取到 unknown 又被兜底压掉，通用提示也不显示。
 */
private fun specificFailureText(state: AnalysisRunUiState): String? {
    val kind = state.rows.firstNotNullOfOrNull { it.errorKind } ?: return null
    val key = when (kind) {
        "PARSE" -> "analysis.all_failed.parse"   // 服务返回内容异常
        "AUTH" -> "llm.auth"                     // API Key 无效，请到设置检查（09 §6 的「去设置」）
        else -> null
    } ?: return null
    return ErrorText.resolve(key)
}
