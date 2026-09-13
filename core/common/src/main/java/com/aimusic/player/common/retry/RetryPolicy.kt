package com.aimusic.player.common.retry

import com.aimusic.player.common.error.FailureKind
import kotlin.math.pow
import kotlin.random.Random

data class RetryPolicy(
    /** 429 重试上限 N，来源 DataStore.llm_max_retries，默认 3 */
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 1_000,
    val factor: Int = 2,
    val maxDelayMs: Long = 60_000,
    /** 抖动比例：实际等待 = 退避 * [1-jitter, 1] 内随机 */
    val jitterRatio: Double = 0.2,
) {
    /** 第 attempt 次重试（attempt 从 1 起）的等待时长。Retry-After 优先。 */
    fun delayFor(attempt: Int, retryAfterMs: Long? = null, random: Random = Random.Default): Long {
        val backoff = (baseDelayMs.toDouble() * factor.toDouble().pow(attempt - 1))
            .toLong().coerceAtMost(maxDelayMs)
        val jittered = (backoff * (1 - jitterRatio * random.nextDouble())).toLong()
        // Retry-After 优先：服务端给的值 > 本地退避时取服务端值，仍以 maxDelayMs 封顶
        return maxOf(jittered, retryAfterMs ?: 0L).coerceAtMost(maxDelayMs)
    }

    /** 仅 RATE_LIMIT 且未超上限时自动重试；其余一律不自动重试。 */
    fun shouldRetry(kind: FailureKind, attempt: Int): Boolean =
        kind == FailureKind.RATE_LIMIT && attempt < maxRetries
}
