package com.aimusic.player.llm.parse

import com.aimusic.player.llm.JsonEnforcementPolicy
import kotlinx.serialization.json.JsonElement

/**
 * 能力位 → 强制 JSON 的档位（spec §4 表）。
 *
 * **归属**：档位类型本体归 P1（`com.aimusic.player.llm.JsonEnforcementPolicy`，sealed：
 * `Schema(schema)` / `JsonObject` / `PromptOnly`），本文件只做**推导**。
 * 计划的 Task 6 原稿自己声明了一个同名 `object JsonEnforcementPolicy` 与一个 `JsonEnforcement` 枚举，
 * 那会与 P1 撞名并造出重复类型，故不采用。
 *
 * 入参用布尔而非 `ProtocolKind`：避开与本层无关的方言枚举；本层只关心「协议有没有 JSON mode 这一档」。
 * `schema` 由调用方传入 —— 调用方（`PromptBuilder`）本来就持有它，不必让推导层再去读资源。
 */
fun jsonEnforcementFor(
    supportsJsonSchema: Boolean,
    protocolSupportsJsonMode: Boolean,
    schema: JsonElement,
): JsonEnforcementPolicy = when {
    supportsJsonSchema -> JsonEnforcementPolicy.Schema(schema)
    protocolSupportsJsonMode -> JsonEnforcementPolicy.JsonObject
    else -> JsonEnforcementPolicy.PromptOnly
}
