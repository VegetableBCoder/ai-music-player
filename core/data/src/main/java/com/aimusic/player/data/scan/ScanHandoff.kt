package com.aimusic.player.data.scan

/**
 * 「分析并提交」之后的交棒窄接口（`04 §3.5`）。
 *
 * 真实实现属 `05`（`AnalysisOrchestrator`），本期只留挂点：本模块**只触发**分析、
 * 不修改 `analysis_status`（`04 §1.2` 第 3 条，`02 §7` 单写者原则）。
 */
fun interface AnalysisTrigger {
    suspend fun analyzePending(runId: Long)
}

/**
 * `.lrc` 候选的交棒窄接口（`04 §4.7`）。匹配与 `lyric` 表写入属 `08`，
 * 本模块只负责「发现并登记路径」。
 */
fun interface LyricHandoff {
    suspend fun ingest(candidates: List<LrcCandidate>)
}
