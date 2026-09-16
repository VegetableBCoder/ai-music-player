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
 * anthropic 方言：`POST {base}/messages`，`x-api-key` + `anthropic-version`，
 * system 在**顶层**（不是 message），结构化输出靠 `tools[].input_schema` + `tool_choice` 点名。
 *
 * 关键：tool_use 的 `input` 是**已解析的对象**，而 parser 的签名锁死为 `parse(text: String, …)`，
 * 所以这里负责把它**还原成 JSON 字符串**（spec §3 的两条硬约束之一）。
 */
class AnthropicMessagesProtocol(private val jsonPolicy: JsonEnforcementPolicy) : ProtocolAdapter {

    override fun encode(call: LlmCall, cfg: LlmConfig): HttpRequestSpec {
        val body = buildJsonObject {
            put("model", call.model)
            put("system", call.system)
            put("max_tokens", call.maxTokens)
            put("temperature", call.temperature)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", call.user)
                        },
                    )
                },
            )

            if (jsonPolicy is JsonEnforcementPolicy.Schema) {
                put(
                    "tools",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("name", TOOL_NAME)
                                put(
                                    "description",
                                    "输出这批歌曲的归一化结果；results 的长度必须等于输入文件数",
                                )
                                put("input_schema", jsonPolicy.schema)
                            },
                        )
                    },
                )
                put(
                    "tool_choice",
                    buildJsonObject {
                        put("type", "tool")
                        put("name", TOOL_NAME)
                    },
                )
            }
        }

        return HttpRequestSpec(
            url = "${cfg.baseUrl.trimEnd('/')}/messages",
            headers = mapOf(
                "x-api-key" to cfg.apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
                "Content-Type" to "application/json",
            ),
            bodyJson = body.toString(),
        )
    }

    override fun decode(status: Int, headers: Map<String, String>, body: String): LlmHttpResult =
        if (status != 200) {
            LlmHttpResult.HttpError(status, retryAfterMs(headers), body)
        } else {
            LlmHttpResult.Ok(extractJson(body))
        }

    /** 优先取 tool_use 的 `input`（对象 → 字符串），退到 text 块。 */
    private fun extractJson(body: String): String = runCatching {
        val blocks = Json.parseToJsonElement(body).jsonObject["content"]?.jsonArray
            ?.map { it.jsonObject }.orEmpty()

        blocks.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
            ?.get("input")
            ?.toString()
            ?: blocks.firstNotNullOfOrNull { it["text"]?.jsonPrimitive?.contentOrNull }
    }.getOrNull().orEmpty()

    private fun retryAfterMs(headers: Map<String, String>): Long? =
        headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.times(1_000L)

    private companion object {
        const val TOOL_NAME = "emit_song_normalization"

        // anthropic 要求带版本头；这是 Messages API 的稳定版本，不是模型名
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
