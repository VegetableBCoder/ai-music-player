package com.aimusic.player.common.log

import com.aimusic.player.common.error.AppError
import com.aimusic.player.common.error.FailureKind

/** 关键事件清单（`11 §4.6` 给出触发点） */
enum class LogEvent {
    // 扫描
    SCAN_START, SCAN_PROGRESS, SCAN_END,
    // 分析
    ANALYSIS_RUN_START, ANALYSIS_FILE_OK, ANALYSIS_FILE_FAILED, ANALYSIS_RATE_LIMITED, ANALYSIS_RUN_END,
    // 删除
    DELETE_SONG, UNLINK_FILE, DELETE_FILE_PHYSICALLY, DELETE_FAILED,
    // 播放
    PLAYBACK_START, PLAYBACK_FAILED, FILE_INVALIDATED, SESSION_SETTLE,
    // 权限 / 系统
    PERMISSION_GRANTED, PERMISSION_DENIED, DIAGNOSTICS_EXPORTED,
    // 其它
    LLM_REQUEST, CONFLICT_RESOLVED, UNEXPECTED,
}

/**
 * 一条已脱敏的日志记录（供诊断导出使用，`11 §3.6`）。
 *
 * 11 号文档只给了 `recent(limit): List<LogRecord>` 的签名，没有定义这个类型，
 * 这里按诊断导出所需的最小字段补齐。`cause` 不入记录 —— 只留 `errorKind`，
 * 避免原始异常文本带着密钥或路径进诊断文件。
 */
data class LogRecord(
    val timestampMs: Long,
    val level: LogLevel,
    val event: LogEvent,
    val message: String,
    val fields: Map<String, Any?> = emptyMap(),
    val errorKind: FailureKind? = null,
)

interface Logger {
    fun isEnabled(level: LogLevel): Boolean
    fun log(level: LogLevel, event: LogEvent, message: String, fields: Map<String, Any?> = emptyMap(), error: AppError? = null)
    fun debug(event: LogEvent, message: String, fields: Map<String, Any?> = emptyMap())
    fun info (event: LogEvent, message: String, fields: Map<String, Any?> = emptyMap())
    fun warn (event: LogEvent, message: String, error: AppError? = null, fields: Map<String, Any?> = emptyMap())
    fun error(event: LogEvent, message: String, error: AppError? = null, fields: Map<String, Any?> = emptyMap())

    /** 诊断导出取用最近 N 条（已脱敏） */
    fun recent(limit: Int): List<LogRecord>
}
