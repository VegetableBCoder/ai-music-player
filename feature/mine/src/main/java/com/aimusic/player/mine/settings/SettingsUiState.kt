package com.aimusic.player.mine.settings

import com.aimusic.player.llm.ProtocolKind
import com.aimusic.player.llm.preset.ProviderPreset
import com.aimusic.player.llm.preset.ProviderPresets

/** 四项必填（`09 §3.2.13`）。缺哪一项就在界面上标出哪一项，而不是只说"必填"。 */
enum class RequiredField { PROTOCOL, BASE_URL, API_KEY, MODEL }

/** 设置页状态。**不含 presetId** —— 预设只是填表助手，选了哪个不落库（T7 的语义边界）。 */
data class SettingsUiState(
    val protocol: ProtocolKind? = null,
    val baseUrl: String = "",
    val model: String = "",
    val hasApiKey: Boolean = false,
    val apiKeyMasked: String? = null,
    val apiKeyInput: String = "",
    val maxRetries: Int = 3,
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 90_000L,
    val maxTokens: Int = 8_192,
    val batchSize: Int = 20,
    val supportsJsonSchema: Boolean = false,
    val advancedExpanded: Boolean = false,
    val presets: List<ProviderPreset> = ProviderPresets.ALL,
) {
    /** 已存在 key（掩码的那个）或本次输入了 key，都算"有 key"。 */
    val effectiveHasApiKey: Boolean get() = hasApiKey || apiKeyInput.isNotBlank()

    val missing: Set<RequiredField> get() = buildSet {
        if (protocol == null) add(RequiredField.PROTOCOL)
        if (baseUrl.isBlank()) add(RequiredField.BASE_URL)
        if (model.isBlank()) add(RequiredField.MODEL)
        if (!effectiveHasApiKey) add(RequiredField.API_KEY)
    }

    /** 未填全则禁用保存 —— 而不是允许保存再弹错。 */
    val saveEnabled: Boolean get() = missing.isEmpty()
}
