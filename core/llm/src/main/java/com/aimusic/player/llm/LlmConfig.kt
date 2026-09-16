package com.aimusic.player.llm

/**
 * 运行时配置。**不含预设名**：预设只是设置页的填表助手，落库的只有这些最终值。
 *
 * `supportsJsonSchema` / `supportsJsonMode` 是可手动覆盖的能力位 —— 各家兼容实现的实际支持面
 * 不一致，猜不可靠（spec §4）。
 */
data class LlmConfig(
    val protocol: ProtocolKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val supportsJsonSchema: Boolean,
    val supportsJsonMode: Boolean = true,
    val maxRetries: Int = 3,
    val batchSize: Int = 20,
    val maxTokens: Int = 8_192,
    val temperature: Double = 0.0,
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 90_000,
    val callTimeoutMs: Long = 120_000,
    val maxTagsPerCategory: Int = 2,
)
