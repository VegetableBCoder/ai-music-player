package com.aimusic.player.llm.preset

import com.aimusic.player.llm.ProtocolKind

/**
 * 内置提供商的**填表助手**（`09 §3.2.13`）。
 *
 * 语义边界很重要：预设只是"帮你把表单填好"的助手 —— 选中后字段被填上，用户可以随意改，
 * 而**"选了哪个预设"不落库**（落库的只有最终的四项值）。所以 [id] 仅用于下拉选择，
 * 换了 id 方案也不会影响已存配置。
 *
 * 表里**没有 apiKey 字段**：key 只走 `ApiKeyStore`，永远不进常量表（有测试守着）。
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val protocol: ProtocolKind,
    /** 端点前缀，**以 "/" 结尾**（spec §3.2）；适配器负责再拼 `chat/completions` 等子路径。 */
    val baseUrl: String,
    val defaultModel: String,
)

object ProviderPresets {

    /**
     * 内置预设表。
     *
     * 用户补齐新提供商时**只加 `ProviderPreset(...)` 字面量** —— 不改类型、不改 UI、不加测试；
     * 上面的 `ProviderPresetsTest` 会自动替新条目守住三条不变量（id 唯一 / baseUrl 以斜杠结尾 / 不含 key）。
     */
    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek 官方",
            protocol = ProtocolKind.OPENAI,          // DeepSeek 提供 OpenAI 兼容接口
            baseUrl = "https://api.deepseek.com/",   // 拼出 …/chat/completions
            defaultModel = "deepseek-flash",
        ),
    )
}
