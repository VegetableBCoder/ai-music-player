package com.aimusic.player.data.analysis

import com.aimusic.player.data.model.AnalysisStatus

/**
 * 分析编排的进度事件（逐字照 `05 §3.6`）。
 *
 * 进度粒度是**文件**而不是批次：批只是传输粒度（spec §6），UI 要看到每个文件的状态。
 */
sealed interface AnalysisProgress {

    data class Started(val runId: Long, val total: Int) : AnalysisProgress

    data class FileUpdated(
        val fileId: Long,
        val fileName: String,
        val status: AnalysisStatus,
        val linkedEntityId: Long? = null,
        val error: String? = null,
    ) : AnalysisProgress

    data class Retrying(val fileId: Long, val attempt: Int, val delayMs: Long) : AnalysisProgress

    data class Running(val done: Int, val total: Int, val ok: Int, val failed: Int) : AnalysisProgress

    data class Finished(val runId: Long, val ok: Int, val failed: Int, val aborted: Boolean) :
        AnalysisProgress
}
