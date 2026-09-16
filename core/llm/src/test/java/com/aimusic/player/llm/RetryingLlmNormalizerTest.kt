package com.aimusic.player.llm

import com.aimusic.player.common.retry.RetryPolicy
import com.aimusic.player.common.util.Sleeper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.Test

/** 假时钟：记录请求的时长，立即返回 —— 测试绝不真 sleep。 */
private class RecordingSleeper : Sleeper {
    val delays = mutableListOf<Long>()
    override suspend fun sleep(ms: Long) {
        delays += ms
    }
}

/** 固定抖动，才能断言精确时长（与 RetryPolicyTest 同法）。 */
private class FixedRandom(private val value: Double) : Random() {
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextDouble(): Double = value
}

class RetryingLlmNormalizerTest {

    private val req = NormalizeRequest(
        fileName = "a.mp3", metadata = null, categories = emptyList(), tags = emptyList(),
    )

    private fun limited(retryAfterMs: Long? = null): List<NormalizeOutcome> =
        listOf(NormalizeOutcome.RateLimited(retryAfterMs))

    private fun ok(): List<NormalizeOutcome> =
        listOf(NormalizeOutcome.Success(com.aimusic.player.common.model.NormalizeResult("a", listOf("x"), emptyList())))

    private fun retrying(
        script: List<List<NormalizeOutcome>>,
        maxRetries: Int = 3,
        sleeper: RecordingSleeper = RecordingSleeper(),
        random: Random = FixedRandom(0.0),
        onBackoff: (BackoffEvent) -> Unit = {},
    ) = RetryingLlmNormalizer(
        delegate = ScriptedLlmNormalizer(script),
        policy = RetryPolicy(maxRetries = maxRetries),
        sleeper = sleeper,
        random = random,
        onBackoff = onBackoff,
    ) to sleeper

    @Test
    fun `429 后成功 —— 退避 1s 再重试，只等一次`(): Unit = runBlocking {
        val (sut, sleeper) = retrying(listOf(limited(), ok()))

        val out = sut.normalize(listOf(req))

        assertThat(out.single()).isInstanceOf(NormalizeOutcome.Success::class.java)
        assertThat(sleeper.delays).containsExactly(1_000L).inOrder()
    }

    @Test
    fun `连续 429 按 1s 2s 4s 退避，重试耗尽后整批上抛 RateLimited`(): Unit = runBlocking {
        val (sut, sleeper) = retrying(listOf(limited(), limited(), limited(), limited()), maxRetries = 3)

        val out = sut.normalize(listOf(req, req, req))

        // 共 3 次退避（maxRetries=3），退避后仍 429 → 原样上抛整批
        assertThat(sleeper.delays).containsExactly(1_000L, 2_000L, 4_000L).inOrder()
        assertThat(out).hasSize(3)
        assertThat(out.all { it is NormalizeOutcome.RateLimited }).isTrue()
    }

    @Test
    fun `Retry-After 优先于本地退避`(): Unit = runBlocking {
        val (sut, sleeper) = retrying(listOf(limited(retryAfterMs = 30_000), ok()))

        sut.normalize(listOf(req))

        assertThat(sleeper.delays).containsExactly(30_000L)
    }

    @Test
    fun `抖动落在退避值的 80 到 100`(): Unit = runBlocking {
        val (sut, sleeper) = retrying(listOf(limited(), ok()), random = FixedRandom(1.0))

        sut.normalize(listOf(req))

        assertThat(sleeper.delays).containsExactly(800L)   // 1000 * (1 - 0.2*1.0)
    }

    @Test
    fun `非 429 不重试 —— 只发一次、零退避`(): Unit = runBlocking {
        val (sut, sleeper) = retrying(
            listOf(listOf(NormalizeOutcome.Failure(LlmFailureKind.SERVER, null))),
        )

        val out = sut.normalize(listOf(req))

        assertThat(sleeper.delays).isEmpty()
        assertThat(out.single()).isInstanceOf(NormalizeOutcome.Failure::class.java)
    }

    @Test
    fun `每次退避都上报 BackoffEvent（第 k 次与时长）`(): Unit = runBlocking {
        val events = mutableListOf<BackoffEvent>()
        val (sut, _) = retrying(
            listOf(limited(), limited(), limited(), limited()),
            maxRetries = 3,
            onBackoff = { events += it },
        )

        sut.normalize(listOf(req))

        assertThat(events.map { it.attempt }).containsExactly(1, 2, 3).inOrder()
        assertThat(events.map { it.delayMs }).containsExactly(1_000L, 2_000L, 4_000L).inOrder()
    }

    @Test
    fun `maxRetries 为 0 时不重试`(): Unit = runBlocking {
        val (sut, sleeper) = retrying(listOf(limited()), maxRetries = 0)

        sut.normalize(listOf(req))

        assertThat(sleeper.delays).isEmpty()
    }
}
