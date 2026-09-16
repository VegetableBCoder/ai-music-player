package com.aimusic.player.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * responses 方言：`POST {base}/responses`，system 走顶层 `instructions`，
 * 长度参数叫 `max_output_tokens`，结构化输出在 `text.format`（**不套 `json_schema` 壳**）。
 * 结果要从 `output[]` 里挑 `type=message`，再取 `content[].text`。
 */
class ResponsesProtocol(private val jsonPolicy: JsonEnforcementPolicy) : ProtocolAdapter {

    override fun encode(call: LlmCall, cfg: LlmConfig): HttpRequestSpec {
        val body = buildJsonObject {
            put("model", call.model)
            put("instructions", call.system)
            put("input", call.user)
            put("temperature", call.temperature)
            put("max_output_tokens", call.maxTokens)

            if (jsonPolicy is JsonEnforcementPolicy.Schema) {
                put(
                    "text",
                    buildJsonObject {
                        put(
                            "format",
                            buildJsonObject {
                                put("type", "json_schema")
                                put("name", SCHEMA_NAME)
                                put("strict", true)
                                put("schema", jsonPolicy.schema)
                            },
                        )
                    },
                )
            }
        }

        return HttpRequestSpec(
            url = "${cfg.baseUrl.trimEnd('/')}/responses",
            headers = mapOf(
                "Authorization" to "Bearer ${cfg.apiKey}",
                "Content-Type" to "application/json",
            ),
            bodyJson = body.toString(),
        )
    }

    override fun decode(status: Int, headers: Map<String, String>, body: String): LlmHttpResult =
        if (status != 200) {
            LlmHttpResult.HttpError(status, retryAfterMs(headers), body)
        } else {
            LlmHttpResult.Ok(extractText(body))
        }

    /** 只认 `type=message` 那一块，跳过 reasoning 等非结果块。 */
    private fun extractText(body: String): String = runCatching {
        Json.parseToJsonElement(body).jsonObject["output"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
            ?.get("content")?.jsonArray
            ?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
    }.getOrNull().orEmpty()

    private fun retryAfterMs(headers: Map<String, String>): Long? =
        headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.times(1_000L)

    private companion object {
        const val SCHEMA_NAME = "song_normalization_batch"
    }
}
