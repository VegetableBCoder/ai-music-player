package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class AnthropicMessagesProtocolTest {

    private val schema = buildJsonObject { put("type", "object") }

    private fun cfg() = LlmConfig(
        protocol = ProtocolKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-sonnet-4-5",
        apiKey = "sk-ant-test",
        supportsJsonSchema = true,
    )

    private fun call() = LlmCall("S", "U", schema, "claude-sonnet-4-5", 4096)

    @Test
    fun `encode_鉴权走 x_api_key 且带 anthropic_version`() {
        val spec = AnthropicMessagesProtocol(JsonEnforcementPolicy.Schema(schema))
            .encode(call(), cfg())

        assertThat(spec.headers["x-api-key"]).isEqualTo("sk-ant-test")
        assertThat(spec.headers["anthropic-version"]).isEqualTo("2023-06-01")
        assertThat(spec.headers.containsKey("Authorization")).isFalse()
        assertThat(spec.url).isEqualTo("https://api.anthropic.com/v1/messages")
    }

    @Test
    fun `encode_system 在顶层_且用 tools 的 input_schema 强制 JSON`() {
        val body = Json.parseToJsonElement(
            AnthropicMessagesProtocol(JsonEnforcementPolicy.Schema(schema))
                .encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body["system"]!!.jsonPrimitive.content).isEqualTo("S")
        assertThat(body["messages"]!!.jsonArray.size).isEqualTo(1)

        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertThat(tool["name"]!!.jsonPrimitive.content).isEqualTo("emit_song_normalization")
        assertThat(tool.containsKey("input_schema")).isTrue()
        assertThat(body["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            .isEqualTo("emit_song_normalization")
    }

    @Test
    fun `encode_L3 不带 tools`() {
        val body = Json.parseToJsonElement(
            AnthropicMessagesProtocol(JsonEnforcementPolicy.PromptOnly)
                .encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body.containsKey("tools")).isFalse()
        assertThat(body.containsKey("tool_choice")).isFalse()
    }

    @Test
    fun `decode_tool_use 的 input 是对象_必须还原成 JSON 字符串`() {
        val wire = """
            {"id":"msg_x","stop_reason":"tool_use","content":[
              {"type":"tool_use","name":"emit_song_normalization",
               "input":{"results":[{"file_index":1,"canonical_title":"晴天","artists":["周杰伦"],"tag_groups":[]}]}}]}
        """.trimIndent()

        val result = AnthropicMessagesProtocol(JsonEnforcementPolicy.Schema(schema))
            .decode(200, emptyMap(), wire)

        val payload = (result as LlmHttpResult.Ok).payloadJson
        // 关键：是字符串而不是对象，且内容可再解析（parser 的签名锁死为 parse(text: String)）
        assertThat(payload).startsWith("{")
        assertThat(Json.parseToJsonElement(payload).jsonObject["results"]!!.jsonArray.size)
            .isEqualTo(1)
    }

    @Test
    fun `decode_普通 text 块也能取`() {
        val wire = """{"id":"m","content":[{"type":"text","text":"{\"results\":[]}"}]}"""

        val result = AnthropicMessagesProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(200, emptyMap(), wire)

        assertThat((result as LlmHttpResult.Ok).payloadJson).isEqualTo("""{"results":[]}""")
    }

    @Test
    fun `decode_529 overloaded 当 HttpError 原样带回`() {
        val result = AnthropicMessagesProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(529, emptyMap(), """{"type":"overloaded_error"}""")

        assertThat((result as LlmHttpResult.HttpError).status).isEqualTo(529)
    }
}
