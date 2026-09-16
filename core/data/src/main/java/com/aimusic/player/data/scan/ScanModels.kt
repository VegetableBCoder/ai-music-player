package com.aimusic.player.data.scan

import com.aimusic.player.common.error.AppError
import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.data.model.LyricSource
import com.aimusic.player.common.model.AudioMetadata
import com.aimusic.player.storage.FileRef
import com.aimusic.player.storage.StorageAccessLevel

/** 扫描会话的相位（`04 §4.1` 的状态机）。 */
enum class ScanPhase { IDLE, SCANNING, DIFFING, AWAITING_USER, COMMITTING, ANALYZING, DONE, DISCARDED }

/** 磁盘上的候选音乐文件 + 已提取元数据（内存态，未提交）。 */
data class ScannedFile(
    val ref: FileRef,
    /** `04 §4.6` 规范化后的绝对路径，即 `music_file.path` 的值 */
    val normalizedPath: String,
    /** 小写扩展名，如 `"flac"` */
    val format: String,
    val metadata: AudioMetadata,
)

/** `.lrc` 路径候选；匹配逻辑属 `08`，本模块只登记。 */
data class LrcCandidate(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val origin: LyricSource,
)

/** 差异比对结果；三项计数直接映射 `analysis_run` 的 `new/skipped/cleaned_count`。 */
data class ScanDiff(
    val newFiles: List<ScannedFile>,
    val skippedPaths: List<String>,
    val cleanedPaths: List<String>,
    val lrcCandidates: List<LrcCandidate>,
)

/** UI 摘要（「发现 N 个新文件」+ 新增/跳过/清理）。 */
data class ScanSummary(
    val newCount: Int,
    val skippedCount: Int,
    val cleanedCount: Int,
    val lrcCount: Int,
)

/**
 * 会话状态。
 *
 * `diff` 只在 `AWAITING_USER` / `COMMITTING` 期间非空，且**只在内存**：进程被杀就回到
 * `IDLE`，重新扫描即可复现（差异是幂等的）—— 为它落库反而会制造出「放弃后仍留记录」的违规态。
 */
data class ScanSessionState(
    val phase: ScanPhase = ScanPhase.IDLE,
    val accessLevel: StorageAccessLevel,
    val discovered: Int = 0,
    val metadataProcessed: Int = 0,
    val diff: ScanDiff? = null,
    val runId: Long? = null,
    val warnings: List<String> = emptyList(),
    val error: AppError? = null,
)

sealed interface ScanOutcome {
    /** 有新增或需清理 → 等用户决定「分析并添加」还是「放弃」 */
    data class AwaitingUser(val summary: ScanSummary) : ScanOutcome

    data class NoChanges(val summary: ScanSummary) : ScanOutcome

    data class Failed(val kind: FailureKind, val cause: Throwable?) : ScanOutcome

    data object Cancelled : ScanOutcome

    data object AlreadyRunning : ScanOutcome
}

sealed interface CommitResult {
    data class Committed(val runId: Long, val inserted: Int, val cleaned: Int) : CommitResult

    data class NothingToCommit(val reason: String) : CommitResult

    /**
     * 提交在批次边界被取消。
     *
     * 与 `NothingToCommit` **不能混用**：已提交的批次**保留**了（`04 §4.9`），说「没东西可提交」
     * 是错的。文档 §3.2 原本没有这一支，是 §4.9 的取消语义要求它可被表达。
     */
    data object Cancelled : CommitResult

    data class Failed(val kind: FailureKind, val cause: Throwable?) : CommitResult
}
