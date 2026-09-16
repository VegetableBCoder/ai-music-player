package com.aimusic.player.llm

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 真实网络：拿适配器编好的 [HttpRequestSpec] 用 OkHttp 发出去，再把响应交回适配器解码。
 *
 * **不做重试、不查缓存**：那是 P3 两个装饰器的职责（spec §2 的 `Caching(Retrying(Direct))`）。
 * 这里也**不实现 `LlmNormalizer`**，装配由 P3 的 `buildLlmNormalizer` 负责。
 *
 * 网络异常一律变成 [LlmHttpResult.Transport]，不往上抛 —— 上层的失败分类基于返回值而不是异常，
 * 所以 IO 问题不会被漏成崩溃。
 */
class DirectProvider(
    private val client: OkHttpClient,
    private val adapter: ProtocolAdapter,
) {

    fun execute(call: LlmCall, cfg: LlmConfig): LlmHttpResult {
        val spec = adapter.encode(call, cfg)
        val request = Request.Builder()
            .url(spec.url)
            .apply { spec.headers.forEach { (name, value) -> header(name, value) } }
            .post(spec.bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val headers = response.headers.names()
                    .associateWith { name -> response.header(name).orEmpty() }
                adapter.decode(response.code, headers, response.body?.string().orEmpty())
            }
        } catch (error: IOException) {
            LlmHttpResult.Transport(error)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
