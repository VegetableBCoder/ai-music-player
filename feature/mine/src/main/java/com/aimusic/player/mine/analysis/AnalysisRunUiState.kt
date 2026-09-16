package com.aimusic.player.mine.analysis

import com.aimusic.player.data.analysis.AnalysisProgress
import com.aimusic.player.data.model.AnalysisStatus

/** 逐文件行（`09 §3.2.10`）。 */
data class AnalysisFileUi(
    val fileId: Long,
    val fileName: String,
    val status: AnalysisStatus,
    val linkedEntityId: Long?,
    val errorKind: String?,
    val retrying: Boolean,
) {
    /** 只有"没成功"的行才给重试入口；`LINKED` 的不给（重试它毫无意义）。 */
    val canRetry: Boolean get() = status == AnalysisStatus.FAILED || status == AnalysisStatus.UNANALYZED
}

/** 分析记录页的整体状态。 */
data class AnalysisRunUiState(
    val runId: Long? = null,
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val ok: Int = 0,
    val failed: Int = 0,
    val retrying: AnalysisProgress.Retrying? = null,
    val rows: List<AnalysisFileUi> = emptyList(),
    val aborted: Boolean = false,
) {
    val hasRun: Boolean get() = runId != null

    /** 没有总数时不给进度（避免 0/0 让进度条闪成全满）。 */
    val progressFraction: Float? get() = if (total == 0) null else done.toFloat() / total

    val allFailed: Boolean get() = failed > 0 && ok == 0
    val partiallyFailed: Boolean get() = failed > 0 && ok > 0
}
