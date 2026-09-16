package com.aimusic.player.llm

import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.retry.RetryPolicy
import com.aimusic.player.common.util.Sleeper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.Test

class LlmNormalizerFactoryTest {

    private class RecordingSleeper : Sleeper {
        val delays = mutableListOf<Long>()
        override suspend fun sleep(ms: Long) {
            delays += ms
        }
    }

    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }

    private fun req(name: String) = NormalizeRequest(
        fileName = name, metadata = null, categories = listOf("音乐类型"), tags = emptyList(),
    )

    private fun success(request: NormalizeRequest): NormalizeOutcome =
        NormalizeOutcome.Success(NormalizeResult("t", listOf("a"), emptyList()))

    private fun build(
        direct: ScriptedLlmNormalizer,
        cache: InMemoryLlmCache,
        sleeper: RecordingSleeper = RecordingSleeper(),
    ) = buildLlmNormalizer(
        direct = direct,
        cache = cache,
        model = "gpt-4o-mini",
        promptHash = { "p-hash" },
        dirFingerprint = { "d-hash" },
        policy = RetryPolicy(maxRetries = 3),
        sleeper = sleeper,
        random = FixedRandom(0.0),
    )

    @Test
    fun `顺序是 Caching 外层 —— 命中后零网络`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"), req("b.mp3"))
        build(ScriptedLlmNormalizer(listOf(requests.map(::success))), cache).normalize(requests)

        val direct = ScriptedLlmNormalizer(listOf(requests.map(::success)))
        val out = build(direct, cache).normalize(requests)

        assertThat(direct.calls).isEmpty()                    // 缓存最外层，拦截了网络
        assertThat(out.all { it is NormalizeOutcome.Success }).isTrue()
    }

    @Test
    fun `顺序是 Retrying 内层 —— 429 退避后才写缓存`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"))
        val sleeper = RecordingSleeper()
        val direct = ScriptedLlmNormalizer(
            listOf(listOf(NormalizeOutcome.RateLimited(null)), listOf(success(requests[0]))),
        )

        val out = build(direct, cache, sleeper).normalize(requests)

        assertThat(direct.calls).hasSize(2)                   // 一次 429 + 一次成功
        assertThat(sleeper.delays).containsExactly(1_000L)    // 退避在缓存之内
        assertThat(cache.putCount).isEqualTo(1)               // 只把退避后的成功写缓存
        assertThat(out.single()).isInstanceOf(NormalizeOutcome.Success::class.java)
    }

    @Test
    fun `已命中文件不参与退避 —— 429 只对未命中批次重试`() = runBlocking {
        val cache = InMemoryLlmCache()
        val warm = listOf(req("a.mp3"))
        build(ScriptedLlmNormalizer(listOf(warm.map(::success))), cache).normalize(warm)

        // 现在 a 命中、b 未命中；下层首轮对 [b] 返回 429，次轮成功
        val requests = listOf(req("a.mp3"), req("b.mp3"))
        val direct = ScriptedLlmNormalizer(
            listOf(listOf(NormalizeOutcome.RateLimited(null)), listOf(success(requests[1]))),
        )
        val out = build(direct, cache).normalize(requests)

        assertThat(direct.calls).hasSize(2)                            // 一次 429 + 一次成功
        assertThat(direct.calls.all { it.size == 1 }).isTrue()         // 每次都只收到 1 个（b）
        assertThat(direct.calls.map { it.single().fileName }).containsExactly("b.mp3", "b.mp3")
        assertThat(out).hasSize(2)
    }
}