package com.aimusic.player.llm.parse

import com.aimusic.player.common.error.AppError
import com.aimusic.player.common.log.LogEvent
import com.aimusic.player.common.log.LogLevel
import com.aimusic.player.common.log.LogRecord
import com.aimusic.player.common.log.Logger

/** 收集 warn 文案，供断言「dropped 但不算失败」的分支确实记了日志。 */
class RecordingLogger : Logger {
    val warnings = mutableListOf<String>()

    override fun isEnabled(level: LogLevel): Boolean = true
    override fun log(level: LogLevel, event: LogEvent, message: String, fields: Map<String, Any?>, error: AppError?) {
        if (level == LogLevel.WARN) warnings += message
    }
    override fun debug(event: LogEvent, message: String, fields: Map<String, Any?>) = Unit
    override fun info(event: LogEvent, message: String, fields: Map<String, Any?>) = Unit
    override fun warn(event: LogEvent, message: String, error: AppError?, fields: Map<String, Any?>) { warnings += message }
    override fun error(event: LogEvent, message: String, error: AppError?, fields: Map<String, Any?>) = Unit
    override fun recent(limit: Int): List<LogRecord> = emptyList()
}