package com.aimusic.player.llm

import com.aimusic.player.common.error.FailureKind
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * 失败类别（`05 §3.1` 的五个取值）。
 *
 * `429` **不在这里**：限流由退避层处理，耗尽后才由上层变成 `RateLimited`（spec §9.1）。
 */
enum class LlmFailureKind { NETWORK, AUTH, SERVER, INVALID_OUTPUT, TIMEOUT }

/**
 * HTTP 结果 → 失败类别。返回 `null` 表示「不是失败」（成功、或 429 交给退避层）。
 *
 * 529（anthropic overloaded）归 SERVER：它不是限流，不进退避（spec §3）。
 */
fun LlmHttpResult.toFailureKind(): LlmFailureKind? = when (this) {
    is LlmHttpResult.Ok -> null

    is LlmHttpResult.Transport -> when (cause) {
        is SocketTimeoutException -> LlmFailureKind.TIMEOUT
        is InterruptedIOException -> LlmFailureKind.TIMEOUT
        else -> LlmFailureKind.NETWORK
    }

    is LlmHttpResult.HttpError -> when (status) {
        429 -> null
        401, 403 -> LlmFailureKind.AUTH
        else -> LlmFailureKind.SERVER
    }
}

/**
 * `LlmFailureKind`（LLM 内部的失败类别）→ `FailureKind`（全项目统一的失败类别，`05 §6`）。
 *
 * 命名成 `asFailureKind` 而不是 `toFailureKind`：后者已被 `LlmHttpResult.toFailureKind()` 占用，
 * 同名重载容易读错（返回类型也不同）。
 *
 * 映射规则（`05 §6`）：`INVALID_OUTPUT → PARSE`（模型给的 JSON 不合规算解析失败）、
 * `TIMEOUT → NETWORK`（超时在用户看来就是网络问题），其余同名。
 */
fun LlmFailureKind.asFailureKind(): FailureKind = when (this) {
    LlmFailureKind.INVALID_OUTPUT -> FailureKind.PARSE
    LlmFailureKind.TIMEOUT -> FailureKind.NETWORK
    LlmFailureKind.NETWORK -> FailureKind.NETWORK
    LlmFailureKind.AUTH -> FailureKind.AUTH
    LlmFailureKind.SERVER -> FailureKind.SERVER
}
