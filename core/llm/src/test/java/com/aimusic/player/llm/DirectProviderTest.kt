package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class DirectProviderTest {

    private lateinit var server: MockWebServer

    private val schema = buildJsonObject { put("type", "object") }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun provider() = DirectProvider(
        client = OkHttpClient(),
        adapter = OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly),
    )

    private fun cfg() = LlmConfig(
        protocol = ProtocolKind.OPENAI,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        model = "m",
        apiKey = "sk-test",
        supportsJsonSchema = false,
    )

    private fun call() = LlmCall("S", "U", schema, "m", 1024)

    @Test
    fun `execute_把适配器编好的请求原样发出去并解析成功`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"{\"results\":[]}"}}]}""",
            ),
        )

        val result = provider().execute(call(), cfg())

        assertThat((result as LlmHttpResult.Ok).payloadJson).isEqualTo("""{"results":[]}""")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/chat/completions")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-test")
    }

    @Test
    fun `execute_429 带上 Retry-After`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))

        val result = provider().execute(call(), cfg())

        val error = result as LlmHttpResult.HttpError
        assertThat(error.status).isEqualTo(429)
        assertThat(error.retryAfterMs).isEqualTo(2_000L)
    }

    @Test
    fun `execute_服务器不可达时是 Transport 而不是抛异常`() {
        server.shutdown()

        val result = provider().execute(call(), cfg())

        assertThat(result).isInstanceOf(LlmHttpResult.Transport::class.java)
        assertThat(result.toFailureKind()).isEqualTo(LlmFailureKind.NETWORK)
    }
}
