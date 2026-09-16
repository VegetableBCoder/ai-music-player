package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class ResponsesProtocolTest {

    private val schema = buildJsonObject { put("type", "object") }

    private fun cfg() = LlmConfig(
        protocol = ProtocolKind.RESPONSES,
        baseUrl = "https://api.openai.com/v1/",
        model = "gpt-4o-mini",
        apiKey = "sk-test",
        supportsJsonSchema = true,
    )

    private fun call() = LlmCall("S", "U", schema, "gpt-4o-mini", 4096)

    @Test
    fun `encode_system 走顶层 instructions 而不是 messages 且长度参数改名`() {
        val body = Json.parseToJsonElement(
            ResponsesProtocol(JsonEnforcementPolicy.Schema(schema)).encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body["instructions"]!!.jsonPrimitive.content).isEqualTo("S")
        assertThat(body["input"]!!.jsonPrimitive.content).isEqualTo("U")
        assertThat(body.containsKey("messages")).isFalse()
        assertThat(body["max_output_tokens"]!!.jsonPrimitive.content).isEqualTo("4096")
        assertThat(body.containsKey("max_tokens")).isFalse()
    }

    @Test
    fun `encode_schema 放在 text_format 里且不套 json_schema 壳`() {
        val body = Json.parseToJsonElement(
            ResponsesProtocol(JsonEnforcementPolicy.Schema(schema)).encode(call(), cfg()).bodyJson,
        ).jsonObject

        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        assertThat(format["type"]!!.jsonPrimitive.content).isEqualTo("json_schema")
        assertThat(format["name"]!!.jsonPrimitive.content).isEqualTo("song_normalization_batch")
        assertThat(format.containsKey("schema")).isTrue()
        // 与 openai 方言不同：这里没有 json_schema 这一层包装
        assertThat(format.containsKey("json_schema")).isFalse()
    }

    @Test
    fun `encode_L3 不带 text 字段`() {
        val body = Json.parseToJsonElement(
            ResponsesProtocol(JsonEnforcementPolicy.PromptOnly).encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body.containsKey("text")).isFalse()
    }

    @Test
    fun `encode_baseUrl 末尾斜杠不产生双斜杠`() {
        val spec = ResponsesProtocol(JsonEnforcementPolicy.PromptOnly).encode(call(), cfg())
        assertThat(spec.url).isEqualTo("https://api.openai.com/v1/responses")
    }

    @Test
    fun `decode_从 output 里的 message 取 content text`() {
        val wire = """
            {"id":"resp_x","status":"completed","output":[
              {"type":"reasoning","summary":[]},
              {"type":"message","role":"assistant","content":[
                {"type":"output_text","text":"{\"results\":[]}"}]}]}
        """.trimIndent()

        val result = ResponsesProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(200, emptyMap(), wire)

        assertThat((result as LlmHttpResult.Ok).payloadJson).isEqualTo("""{"results":[]}""")
    }

    @Test
    fun `decode_output 里没有 message 时返回空串（交给 parser 判）`() {
        val result = ResponsesProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(200, emptyMap(), """{"id":"x","output":[]}""")

        assertThat((result as LlmHttpResult.Ok).payloadJson).isEmpty()
    }
}
