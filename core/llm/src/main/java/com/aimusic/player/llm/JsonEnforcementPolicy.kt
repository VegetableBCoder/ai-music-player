package com.aimusic.player.llm

import kotlinx.serialization.json.JsonElement

/**
 * 强制 JSON 的层级（spec §4）。三层降级，由 P2 从 `LlmConfig` 的能力位推导出来，
 * 交给适配器决定「怎么在请求里声明」——适配器不理解能力位，只理解这一层。
 *
 * **归属说明**：本类型由 P1 定义（协议层的输入契约），P2 负责产出它的实例。
 */
sealed interface JsonEnforcementPolicy {

    /** L1：能在请求里声明 schema（openai 的 `response_format.json_schema` 等）。 */
    data class Schema(val schema: JsonElement) : JsonEnforcementPolicy

    /** L2：只能声明 JSON mode（`response_format.type = json_object`），schema 靠提示词。 */
    data object JsonObject : JsonEnforcementPolicy

    /** L3：连 JSON mode 都没有，纯靠提示词 + 客户端容错抽取。 */
    data object PromptOnly : JsonEnforcementPolicy
}
