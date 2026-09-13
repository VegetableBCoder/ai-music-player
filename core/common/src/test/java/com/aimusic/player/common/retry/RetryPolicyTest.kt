package com.aimusic.player.common.retry

import com.aimusic.player.common.error.FailureKind
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

/**
 * 退避是 `RATE_LIMIT` 唯一的自动恢复手段（`11 §3.4`），算错就会把队列卡死
 * 或把限流打成更严重的限流，所以逐步钉死。
 */
class RetryPolicyTest {

    /** 注入固定抖动，才能断言精确的退避值。 */
    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }

    private val noJitter = FixedRandom(0.0)

    @Test
    fun `退避按 2 的幂增长，并在 60 秒封顶`() {
        val policy = RetryPolicy()

        val delays = (1..8).map { policy.delayFor(it, random = noJitter) }

        assertThat(delays).containsExactly(
            1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L,
        ).inOrder()
    }

    @Test
    fun `Retry-After 优先于本地退避`() {
        val policy = RetryPolicy()

        // 服务端要求等更久 → 听服务端的
        assertThat(policy.delayFor(1, retryAfterMs = 30_000, random = noJitter))
            .isEqualTo(30_000L)

        // 本地退避更大 → 用自己的
        assertThat(policy.delayFor(5, retryAfterMs = 1_000, random = noJitter))
            .isEqualTo(16_000L)
    }

    @Test
    fun `Retry-After 同样以 60 秒封顶`() {
        val policy = RetryPolicy()

        assertThat(policy.delayFor(1, retryAfterMs = 600_000, random = noJitter))
            .isEqualTo(60_000L)
    }

    @Test
    fun `抖动落在退避值的 80% 到 100% 之间`() {
        val policy = RetryPolicy()

        // attempt = 3 → 基准 4s
        assertThat(policy.delayFor(3, random = FixedRandom(0.0))).isEqualTo(4_000L)
        assertThat(policy.delayFor(3, random = FixedRandom(0.5))).isEqualTo(3_600L)
        assertThat(policy.delayFor(3, random = FixedRandom(1.0))).isEqualTo(3_200L)
    }

    @Test
    fun `只有 RATE_LIMIT 且未达上限才自动重试`() {
        val policy = RetryPolicy(maxRetries = 3)

        assertThat(policy.shouldRetry(FailureKind.RATE_LIMIT, attempt = 1)).isTrue()
        assertThat(policy.shouldRetry(FailureKind.RATE_LIMIT, attempt = 2)).isTrue()
        assertThat(policy.shouldRetry(FailureKind.RATE_LIMIT, attempt = 3)).isFalse()
    }

    @Test
    fun `其余 FailureKind 一律不自动重试`() {
        val policy = RetryPolicy()

        listOf(
            FailureKind.AUTH, FailureKind.NETWORK, FailureKind.SERVER, FailureKind.PARSE,
            FailureKind.IO, FailureKind.PERMISSION, FailureKind.NOT_FOUND,
            FailureKind.CONFLICT, FailureKind.UNKNOWN,
        ).forEach { kind ->
            assertThat(policy.shouldRetry(kind, attempt = 1)).isFalse()
        }
    }

    @Test
    fun `上限可通过配置调整`() {
        val policy = RetryPolicy(maxRetries = 5)

        assertThat(policy.shouldRetry(FailureKind.RATE_LIMIT, attempt = 4)).isTrue()
        assertThat(policy.shouldRetry(FailureKind.RATE_LIMIT, attempt = 5)).isFalse()
    }
}
