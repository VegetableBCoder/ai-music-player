package com.aimusic.player.llm

import com.aimusic.player.llm.prompt.PromptResources
import com.aimusic.player.llm.prompt.PromptBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * `LlmNormalizer` 的真实现：把 PromptBuilder / 适配器 / DirectProvider / NormalizeParser 串起来。
 *
 * 重点断言的是**契约**而不是细节：
 * - 返回结果与入参**等长**（上层要按 fileId 落库，长度错了就张冠李戴）；
 * - 429 → 整批 `RateLimited` 且带 `Retry-After`（不是 Failure：限流要退避重试）。
 */
class DirectNormalizerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun sut(cfg: LlmConfig) = DirectNormalizer(
        configProvider = LlmConfigProvider { cfg },
        client = OkHttpClient(),
        promptBuilder = PromptBuilder(PromptResources(), maxTagsPerCategory = 2),
    )

    private fun cfg() = LlmConfig(
        protocol = ProtocolKind.OPENAI,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        model = "m",
        apiKey = "sk-test",
        supportsJsonSchema = false,
    )

    private fun requests(n: Int = 1) = List(n) {
        NormalizeRequest(fileName = "a$it.flac", metadata = null, categories = listOf("情绪"), tags = emptyList())
    }

    @Test
    fun `成功时按入参长度返回 Success`(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"results\":[{\"file_index\":1,\"canonical_title\":\"晴天\",\"artists\":[\"周杰伦\"],\"tag_groups\":[{\"category\":\"情绪\",\"tags\":[\"怀旧\"]}]}]}"}}]}""",
            ),
        )

        val out = sut(cfg()).normalize(requests())

        assertThat(out).hasSize(1)
        assertThat(out.single()).isInstanceOf(NormalizeOutcome.Success::class.java)
        val result = (out.single() as NormalizeOutcome.Success).result
        assertThat(result.canonicalTitle).isEqualTo("晴天")
        assertThat(result.artists).containsExactly("周杰伦")
        assertThat(result.tagAssignments).containsExactly(
            com.aimusic.player.common.model.TagAssignment("情绪", "怀旧"),
        )
    }

    @Test
    fun `429 时整批按入参长度回 RateLimited 并带上 Retry-After`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3"))

        val out = sut(cfg()).normalize(requests(2))

        assertThat(out).hasSize(2)
        assertThat(out.all { it is NormalizeOutcome.RateLimited }).isTrue()
        assertThat((out.first() as NormalizeOutcome.RateLimited).retryAfterMs).isEqualTo(3_000L)
    }

    @Test
    fun `每次调用都取当前配置（设置页改了立刻生效）`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        var captured = cfg()
        val sut = DirectNormalizer(
            configProvider = LlmConfigProvider { captured },
            client = OkHttpClient(),
            promptBuilder = PromptBuilder(PromptResources(), maxTagsPerCategory = 2),
        )

        sut.normalize(requests())
        captured = captured.copy(model = "另一个模型")
        server.enqueue(MockResponse().setResponseCode(429))
        sut.normalize(requests())

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.body.readUtf8()).contains("\"m\"")
        assertThat(second.body.readUtf8()).contains("另一个模型")
    }
}
