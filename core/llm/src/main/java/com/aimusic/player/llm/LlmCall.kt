package com.aimusic.player.llm

import kotlinx.serialization.json.JsonElement

/** 协议无关的一次调用：who（system/user）与要什么形状（schema）。 */
data class LlmCall(
    val system: String,
    val user: String,
    val schema: JsonElement,
    val model: String,
    val maxTokens: Int,
    val temperature: Double = 0.0,
)
