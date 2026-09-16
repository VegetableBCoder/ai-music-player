package com.aimusic.player.llm.parse

import com.aimusic.player.llm.JsonEnforcementPolicy
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * spec §4 的分层降级表。
 *
 * ⚠ 与计划原稿的差别（P1-T3 执行时定的归属）：档位**类型本体归 P1**
 * （`com.aimusic.player.llm.JsonEnforcementPolicy`，sealed：`Schema(schema)` / `JsonObject` / `PromptOnly`），
 * 这里只做**推导**。schema 由调用方传入 —— 调用方（PromptBuilder）本来就持有它，
 * 不必让推导层再去读资源。
 */
class JsonEnforcementPolicyTest {

    private val schema = buildJsonObject { put("type", "object") }

    @Test
    fun `能声明 schema 就走 L1，能力标记优先`() {
        assertThat(
            jsonEnforcementFor(supportsJsonSchema = true, protocolSupportsJsonMode = false, schema = schema),
        ).isEqualTo(JsonEnforcementPolicy.Schema(schema))
        assertThat(
            jsonEnforcementFor(supportsJsonSchema = true, protocolSupportsJsonMode = true, schema = schema),
        ).isEqualTo(JsonEnforcementPolicy.Schema(schema))
    }

    @Test
    fun `不支持 schema 但协议有 JSON mode 走 L2（仅 openai 有此档）`() {
        assertThat(
            jsonEnforcementFor(supportsJsonSchema = false, protocolSupportsJsonMode = true, schema = schema),
        ).isEqualTo(JsonEnforcementPolicy.JsonObject)
    }

    @Test
    fun `都没有则纯提示词加容错抽取走 L3`() {
        assertThat(
            jsonEnforcementFor(supportsJsonSchema = false, protocolSupportsJsonMode = false, schema = schema),
        ).isEqualTo(JsonEnforcementPolicy.PromptOnly)
    }
}
