package com.aimusic.player.llm

import com.aimusic.player.llm.prompt.PromptBuilder
import com.aimusic.player.llm.parse.JsonSalvage
import com.aimusic.player.llm.parse.NormalizeParser
import com.aimusic.player.llm.parse.jsonEnforcementFor
import okhttp3.OkHttpClient

/**
 * `LlmNormalizer` 的**真实现**：把 P2 的 prompt/解析、P1 的协议适配与网络串起来。
 *
 * 为什么单独一个类（而不是让 `DirectProvider` 直接实现接口）：
 * `DirectProvider` 只认「请求对象 → HTTP 结果」，它不该知道 prompt 怎么拼、响应怎么解析、
 * 以及**当前配置从哪来**。这三件事都需要 per-call 取值 —— 配置是运行时可变的（设置页随时改），
 * 每次调用都向 [LlmConfigProvider] 取当前值，改了立刻生效。
 *
 * 强制 JSON 的档位也在这里 per-call 推导：它同时取决于配置的能力位与 prompt 里注入的分类枚举，
 * 两者都是每次调用才算出来的。
 */
class DirectNormalizer(
    private val configProvider: LlmConfigProvider,
    private val client: OkHttpClient,
    private val promptBuilder: PromptBuilder,
) : LlmNormalizer {

    override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
        if (requests.isEmpty()) return emptyList()

        val cfg = configProvider.current()

        return try {
            val bundle = promptBuilder.build(requests)
            val policy = jsonEnforcementFor(
                supportsJsonSchema = cfg.supportsJsonSchema,
                protocolSupportsJsonMode = protocolSupportsJsonMode(cfg.protocol),
                schema = bundle.schema,
            )
            val call = LlmCall(
                system = bundle.system,
                user = bundle.user,
                schema = bundle.schema,
                model = cfg.model,
                maxTokens = cfg.maxTokens,
                temperature = cfg.temperature,
            )
            val adapter = protocolAdapterFor(cfg.protocol, policy)

            when (val result = DirectProvider(client, adapter).execute(call, cfg)) {
                is LlmHttpResult.Ok ->
                    NormalizeParser(maxTagsPerCategory = cfg.maxTagsPerCategory)
                        .parse(JsonSalvage.toJsonText(result.payloadJson), requests)

                is LlmHttpResult.HttpError -> failureFor(requests, result)

                is LlmHttpResult.Transport -> List(requests.size) {
                    NormalizeOutcome.Failure(LlmFailureKind.NETWORK, result.cause)
                }
            }
        } catch (error: Throwable) {
            // 拼 prompt / 推导档位 / 解析里的意外都不该炸掉编排器：按整批 INVALID_OUTPUT 回。
            List(requests.size) { NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, error) }
        }
    }

    private fun failureFor(
        requests: List<NormalizeRequest>,
        error: LlmHttpResult.HttpError,
    ): List<NormalizeOutcome> = if (error.status == 429) {
        // 限流不是失败：整批按入参长度回 RateLimited，由退避装饰器决定是否重试。
        List(requests.size) { NormalizeOutcome.RateLimited(error.retryAfterMs) }
    } else {
        val kind = error.toFailureKind() ?: LlmFailureKind.INVALID_OUTPUT
        List(requests.size) { NormalizeOutcome.Failure(kind, null) }
    }
}

/** 只有 openai 方言有 JSON mode 这一档（spec §4 表；anthropic 走 tools、responses 走 text.format）。 */
internal fun protocolSupportsJsonMode(kind: ProtocolKind): Boolean = kind == ProtocolKind.OPENAI
