package com.aimusic.player.llm

/** 方言适配器：只做编解码，不碰网络。 */
interface ProtocolAdapter {

    fun encode(call: LlmCall, cfg: LlmConfig): HttpRequestSpec

    fun decode(status: Int, headers: Map<String, String>, body: String): LlmHttpResult
}
