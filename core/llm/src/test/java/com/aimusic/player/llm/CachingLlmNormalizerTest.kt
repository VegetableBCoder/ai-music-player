package com.aimusic.player.llm

import com.aimusic.player.common.model.NormalizeResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CachingLlmNormalizerTest {

    private fun req(name: String) = NormalizeRequest(
        fileName = name, metadata = null, categories = listOf("音乐类型"), tags = emptyList(),
    )

    private fun caching(
        delegate: LlmNormalizer,
        cache: InMemoryLlmCache,
        promptHash: String = "p-hash",
        dirFingerprint: String = "d-hash",
    ) = CachingLlmNormalizer(
        delegate = delegate,
        cache = cache,
        model = "gpt-4o-mini",
        promptHash = { promptHash },
        dirFingerprint = { dirFingerprint },
    )

    @Test
    fun `全命中时零网络（delegate 不被调用）`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"), req("b.mp3"))
        // 先跑一次把两个文件都写进缓存
        val warming = caching(ScriptedLlmNormalizer(listOf(requests.map(::successFor))), cache)
        warming.normalize(requests)

        val delegate = ScriptedLlmNormalizer(listOf(requests.map(::successFor)))
        val sut = caching(delegate, cache)
        val out = sut.normalize(requests)

        assertThat(delegate.calls).isEmpty()          // ★ 零网络
        assertThat(out).hasSize(2)
        assertThat(out.all { it is NormalizeOutcome.Success }).isTrue()
    }

    @Test
    fun `部分命中时只把未命中的交给 delegate，返回按原顺序对齐`() = runBlocking {
        val cache = InMemoryLlmCache()
        val all = listOf(req("a.mp3"), req("b.mp3"), req("c.mp3"))
        caching(ScriptedLlmNormalizer(listOf(all.map(::successFor))), cache).normalize(all)

        // 让 a、c 的 key 失效（改名重来）——只命中 b
        val requests = listOf(req("a2.mp3"), req("b.mp3"), req("c2.mp3"))
        val delegate = ScriptedLlmNormalizer(listOf(requests.map(::successFor)))
        val out = caching(delegate, cache).normalize(requests)

        assertThat(delegate.calls).hasSize(1)                                   // 只发一次
        assertThat(delegate.calls.single().map { it.fileName })
            .containsExactly("a2.mp3", "c2.mp3").inOrder()                      // 只含未命中
        assertThat(out).hasSize(3)
        assertThat((out[1] as NormalizeOutcome.Success).result.canonicalTitle).isEqualTo("b")
    }

    @Test
    fun `只缓存成功：Failure 与 RateLimited 不写缓存`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"), req("b.mp3"))
        val delegate = ScriptedLlmNormalizer(
            listOf(
                listOf(
                    NormalizeOutcome.Failure(LlmFailureKind.SERVER, null),
                    NormalizeOutcome.RateLimited(1_000),
                ),
            ),
        )

        val out = caching(delegate, cache).normalize(requests)

        assertThat(cache.putCount).isEqualTo(0)
        assertThat(out[0]).isInstanceOf(NormalizeOutcome.Failure::class.java)
        assertThat(out[1]).isInstanceOf(NormalizeOutcome.RateLimited::class.java)
    }

    @Test
    fun `成功后写缓存：第二次同批全命中零网络`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"))
        val first = ScriptedLlmNormalizer(listOf(listOf(successFor(requests[0]))))

        caching(first, cache).normalize(requests)
        assertThat(cache.putCount).isEqualTo(1)

        val second = ScriptedLlmNormalizer(listOf(listOf(successFor(requests[0]))))
        caching(second, cache).normalize(requests)
        assertThat(second.calls).isEmpty()
    }

    @Test
    fun `prompt 哈希或目录指纹变化导致全 miss`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"), req("b.mp3"))
        caching(ScriptedLlmNormalizer(listOf(requests.map(::successFor))), cache).normalize(requests)

        // 只改 prompt 哈希（模拟改了 system.txt）→ 覆盖 G9
        val afterPrompt = ScriptedLlmNormalizer(listOf(requests.map(::successFor)))
        caching(afterPrompt, cache, promptHash = "p-hash-2").normalize(requests)
        assertThat(afterPrompt.calls.single()).hasSize(2)

        // 只改目录指纹（模拟新增分类）→ 覆盖 G10
        val afterDir = ScriptedLlmNormalizer(listOf(requests.map(::successFor)))
        caching(afterDir, cache, dirFingerprint = "d-hash-2").normalize(requests)
        assertThat(afterDir.calls.single()).hasSize(2)
    }
}