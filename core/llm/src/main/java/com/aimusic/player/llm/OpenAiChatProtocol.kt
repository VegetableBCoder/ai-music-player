package com.aimusic.player.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * openai 方言：`POST {base}/chat/completions`，`Authorization: Bearer`，system 是 messages[0]。
 * 结果在 `choices[0].message.content`（**字符串**）。
 */
class OpenAiChatProtocol(private val jsonPolicy: JsonEnforcementPolicy) : ProtocolAdapter {

    override fun encode(call: LlmCall, cfg: LlmConfig): HttpRequestSpec {
        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", call.system) })
            add(buildJsonObject { put("role", "user"); put("content", call.user) })
        }

        val body = buildJsonObject {
            put("model", call.model)
            put("temperature", call.temperature)
            put("max_tokens", call.maxTokens)
            put("messages", messages)

            when (jsonPolicy) {
                is JsonEnforcementPolicy.Schema -> put(
                    "response_format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put(
                            "json_schema",
                            buildJsonObject {
                                put("name", SCHEMA_NAME)
                                put("strict", true)
                                put("schema", jsonPolicy.schema)
                            },
                        )
                    },
                )

                JsonEnforcementPolicy.JsonObject -> put(
                    "response_format",
                    buildJsonObject { put("type", "json_object") },
                )

                JsonEnforcementPolicy.PromptOnly -> Unit
            }
        }

        return HttpRequestSpec(
            url = "${cfg.baseUrl.trimEnd('/')}/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer ${cfg.apiKey}",
                "Content-Type" to "application/json",
            ),
            bodyJson = body.toString(),
        )
    }

    override fun decode(status: Int, headers: Map<String, String>, body: String): LlmHttpResult {
        if (status != 200) {
            return LlmHttpResult.HttpError(status, retryAfterMs(headers), body)
        }

        val content = runCatching {
            Json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        // 取不到 content 不在这里判 INVALID_OUTPUT：交给 P2 的 parser 统一判（它才认识 schema）
        return LlmHttpResult.Ok(content.orEmpty())
    }

    private fun retryAfterMs(headers: Map<String, String>): Long? =
        headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.times(1_000L)

    private companion object {
        const val SCHEMA_NAME = "song_normalization_batch"
    }
}
