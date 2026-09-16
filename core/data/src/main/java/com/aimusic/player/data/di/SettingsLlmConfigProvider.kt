package com.aimusic.player.data.di

import com.aimusic.player.data.settings.ApiKeyStore
import com.aimusic.player.data.settings.AppSettings
import com.aimusic.player.data.settings.SettingsRepository
import com.aimusic.player.llm.LlmConfig
import com.aimusic.player.llm.LlmConfigProvider
import com.aimusic.player.llm.ProtocolKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * `LlmConfigProvider` 的真实现（T8 里挂的是空值桥接，这里替换它）。
 *
 * 为什么用**后台快照**而不是每次去读 DataStore：`LlmConfigProvider.current()` 被
 * `DirectProvider` 在**每次调用**时同步使用，而 DataStore 的读取是挂起的。
 * 所以这里让应用级作用域把 `settings.settings` 收成一份 `@Volatile` 快照，
 * `current()` 只读快照 —— 设置页一保存，下一次调用就拿到新值（有 P3-T9 的测试守着这条语义）。
 *
 * api key 例外：`ApiKeyStore` 的读取是**同步**的（加密 SharedPreferences），所以每次都现读，
 * 不留快照 —— 免得"刚改完 key 但快照还是旧的"。
 */
class SettingsLlmConfigProvider(
    private val settings: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
    scope: CoroutineScope,
) : LlmConfigProvider {

    @Volatile
    private var snapshot: AppSettings? = null

    init {
        scope.launch { settings.settings.collect { snapshot = it } }
    }

    override fun current(): LlmConfig {
        val stored = snapshot
        return LlmConfig(
            protocol = stored?.llmProvider
                ?.let { runCatching { ProtocolKind.valueOf(it) }.getOrNull() }
                ?: ProtocolKind.OPENAI,
            baseUrl = stored?.llmBaseUrl.orEmpty(),
            model = stored?.llmModel.orEmpty(),
            apiKey = apiKeyStore.read().orEmpty(),
            supportsJsonSchema = stored?.llmSupportsJsonSchema ?: false,
            maxRetries = stored?.llmMaxRetries ?: SettingsRepository.DEFAULT_MAX_RETRIES,
            batchSize = stored?.llmBatchSize ?: SettingsRepository.DEFAULT_BATCH_SIZE,
            maxTokens = stored?.llmMaxTokens ?: SettingsRepository.DEFAULT_MAX_TOKENS,
            connectTimeoutMs = stored?.llmConnectTimeoutMs ?: SettingsRepository.DEFAULT_CONNECT_TIMEOUT_MS,
            readTimeoutMs = stored?.llmReadTimeoutMs ?: SettingsRepository.DEFAULT_READ_TIMEOUT_MS,
        )
    }
}
