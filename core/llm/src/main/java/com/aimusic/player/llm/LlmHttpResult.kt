package com.aimusic.player.llm

/** 发出去之前的一切（url / 头 / body 都已定），由 DirectProvider 执行。 */
data class HttpRequestSpec(
    val url: String,
    val headers: Map<String, String>,
    val bodyJson: String,
)

/**
 * 响应的三态。
 *
 * `Ok.payloadJson` 一定是**字符串**：anthropic 走 tool_use 时拿到的是已解析的对象，
 * 适配器负责还原成字符串，这样 `NormalizeParser.parse(text, …)` 的签名一个字都不用改（spec §3）。
 */
sealed interface LlmHttpResult {

    data class Ok(val payloadJson: String) : LlmHttpResult

    data class HttpError(
        val status: Int,
        val retryAfterMs: Long?,
        val body: String,
    ) : LlmHttpResult

    data class Transport(val cause: Throwable) : LlmHttpResult
}
