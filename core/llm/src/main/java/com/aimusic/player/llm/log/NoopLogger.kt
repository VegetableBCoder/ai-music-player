package com.aimusic.player.llm.log

import com.aimusic.player.common.error.AppError
import com.aimusic.player.common.log.LogEvent
import com.aimusic.player.common.log.LogLevel
import com.aimusic.player.common.log.LogRecord
import com.aimusic.player.common.log.Logger

/**
 * 什么都不做的 logger。
 *
 * 存在的理由：归一化链路（适配器 / parser / 装饰器）都要能记日志，但单元测试与不关心日志的
 * 调用方不该被迫拖一个真实现进来。诊断导出要到 Phase 9 才有（`11 §5.1` 的 `ErrorCounter` 等）。
 */
object NoopLogger : Logger {

    override fun isEnabled(level: LogLevel): Boolean = false

    override fun log(
        level: LogLevel,
        event: LogEvent,
        message: String,
        fields: Map<String, Any?>,
        error: AppError?,
    ) = Unit

    override fun debug(event: LogEvent, message: String, fields: Map<String, Any?>) = Unit

    override fun info(event: LogEvent, message: String, fields: Map<String, Any?>) = Unit

    override fun warn(event: LogEvent, message: String, error: AppError?, fields: Map<String, Any?>) = Unit

    override fun error(event: LogEvent, message: String, error: AppError?, fields: Map<String, Any?>) = Unit

    override fun recent(limit: Int): List<LogRecord> = emptyList()
}
