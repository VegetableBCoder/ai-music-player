package com.aimusic.player.llm

import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.common.retry.RetryPolicy
import com.aimusic.player.common.util.RealSleeper
import com.aimusic.player.common.util.Sleeper
import kotlin.random.Random

/** 一次退避的可观测事件（05 §4.7）。编排器据此 emit「请求过于频繁，重试中（第 k/N 次）」。 */
data class BackoffEvent(val attempt: Int, val delayMs: Long)

/**
 * 429 退避装饰器（**紧贴网络层**，spec §2 / 05 §4.7）。
 *
 * - 只有整批里出现 `RateLimited` 才退避；其余 `Failure`（NETWORK / AUTH / SERVER / INVALID_OUTPUT /
 *   TIMEOUT，含 5xx 与 529）**原样返回、不自动重试**。
 * - 退避时长复用 `:core:common` 的 [RetryPolicy]（1s/2s/4s…、60s 封顶、`Retry-After` 优先、抖动）。
 * - `maxRetries` 耗尽后把最后一次结果**整批上抛**，交由 `AnalysisOrchestrator` 置 `FAILED(RATE_LIMIT)`。
 * - 等待走注入的 [Sleeper]：测试注入假实现，**绝不真 sleep**；[random] 可注入以便断言精确时长。
 */
class RetryingLlmNormalizer(
    private val delegate: LlmNormalizer,
    private val policy: RetryPolicy,
    private val sleeper: Sleeper = RealSleeper,
    private val random: Random = Random.Default,
    private val onBackoff: (BackoffEvent) -> Unit = {},
) : LlmNormalizer {

    override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
        var retriesDone = 0
        while (true) {
            val outs = delegate.normalize(requests)
            val limited = outs
                .firstOrNull { it is NormalizeOutcome.RateLimited } as? NormalizeOutcome.RateLimited
                ?: return outs                                       // 无 429：原样返回（不重试）
            if (!policy.shouldRetry(FailureKind.RATE_LIMIT, retriesDone)) {
            // 接口契约要求「与入参等长、按索引对齐」。委托方在限流时常常只回整批一个结果
            // （"这一批被限流了"），这里补齐到入参长度，别把契约漏给上层 —— 上层要按 fileId 落库。
            return requests.indices.map { outs.getOrElse(it) { outs.last() } }
        }   // 耗尽：整批上抛

            val delayMs = policy.delayFor(retriesDone + 1, limited.retryAfterMs, random)
            retriesDone++
            onBackoff(BackoffEvent(attempt = retriesDone, delayMs = delayMs))
            sleeper.sleep(delayMs)                                   // 退避期间编排器仍在 await、保持 ANALYZING
        }
    }
}