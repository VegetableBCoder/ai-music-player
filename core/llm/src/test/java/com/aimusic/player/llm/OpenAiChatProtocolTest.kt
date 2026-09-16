package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class OpenAiChatProtocolTest {

    private val schema = buildJsonObject { put("type", "object") }

    private fun cfg(supportsSchema: Boolean = true) = LlmConfig(
        protocol = ProtocolKind.OPENAI,
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
        apiKey = "sk-test",
        supportsJsonSchema = supportsSchema,
    )

    private fun call() = LlmCall(
        system = "S", user = "U", schema = schema, model = "deepseek-chat", maxTokens = 4096,
    )

    @Test
    fun `encode_路径与鉴权头与 system 位置`() {
        val spec = OpenAiChatProtocol(JsonEnforcementPolicy.Schema(schema)).encode(call(), cfg())

        assertThat(spec.url).isEqualTo("https://api.deepseek.com/v1/chat/completions")
        assertThat(spec.headers["Authorization"]).isEqualTo("Bearer sk-test")
        assertThat(spec.headers["Content-Type"]).isEqualTo("application/json")

        val body = Json.parseToJsonElement(spec.bodyJson)
        val messages = body.jsonObject["messages"]!!.jsonArray
        assertThat(messages[0].jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("system")
        assertThat(messages[1].jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("user")
    }

    @Test
    fun `encode_baseUrl 末尾斜杠不产生双斜杠`() {
        val spec = OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly)
            .encode(call(), cfg().copy(baseUrl = "https://api.deepseek.com/v1/"))

        assertThat(spec.url).isEqualTo("https://api.deepseek.com/v1/chat/completions")
    }

    @Test
    fun `encode_L1 声明 json_schema 且 strict 为 true`() {
        val body = Json.parseToJsonElement(
            OpenAiChatProtocol(JsonEnforcementPolicy.Schema(schema)).encode(call(), cfg()).bodyJson,
        ).jsonObject

        val rf = body["response_format"]!!.jsonObject
        assertThat(rf["type"]!!.jsonPrimitive.content).isEqualTo("json_schema")
        assertThat(rf["json_schema"]!!.jsonObject["strict"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test
    fun `encode_L2 退到 json_object`() {
        val body = Json.parseToJsonElement(
            OpenAiChatProtocol(JsonEnforcementPolicy.JsonObject).encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            .isEqualTo("json_object")
    }

    @Test
    fun `encode_L3 完全不带 response_format`() {
        val body = Json.parseToJsonElement(
            OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly).encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body.containsKey("response_format")).isFalse()
        assertThat(body["max_tokens"]!!.jsonPrimitive.content).isEqualTo("4096")
    }

    @Test
    fun `decode_从 choices 取 content 字符串`() {
        val wire = """
            {"id":"chatcmpl-x","choices":[{"finish_reason":"stop",
             "message":{"role":"assistant","content":"{\"results\":[]}"}}]}
        """.trimIndent()

        val result = OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(status = 200, headers = emptyMap(), body = wire)

        assertThat(result).isInstanceOf(LlmHttpResult.Ok::class.java)
        assertThat((result as LlmHttpResult.Ok).payloadJson).isEqualTo("""{"results":[]}""")
    }

    @Test
    fun `decode_429 带上 Retry-After 秒数`() {
        val result = OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(status = 429, headers = mapOf("Retry-After" to "5"), body = "")

        assertThat(result).isInstanceOf(LlmHttpResult.HttpError::class.java)
        val error = result as LlmHttpResult.HttpError
        assertThat(error.status).isEqualTo(429)
        assertThat(error.retryAfterMs).isEqualTo(5_000L)
    }

    @Test
    fun `decode_401 与 529 都当 HttpError 原样带回`() {
        val protocol = OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly)
        assertThat((protocol.decode(401, emptyMap(), "no") as LlmHttpResult.HttpError).status)
            .isEqualTo(401)
        assertThat((protocol.decode(529, emptyMap(), "busy") as LlmHttpResult.HttpError).status)
            .isEqualTo(529)
    }
}
