package com.aimusic.player.llm

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
