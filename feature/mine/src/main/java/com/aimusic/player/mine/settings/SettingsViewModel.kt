package com.aimusic.player.mine.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aimusic.player.data.settings.ApiKeyStore
import com.aimusic.player.data.settings.SettingsRepository
import com.aimusic.player.llm.ProtocolKind
import com.aimusic.player.llm.preset.ProviderPresets
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 VM（`09 §3.2.13`）。
 *
 * 三条边界：
 * 1. **四项必填**的判定在 state 里（`missing` / `saveEnabled`），界面只负责标出缺哪项；
 * 2. **预设是填表助手**：`onSelectPreset` 只填协议/baseUrl/模型三项，**不记 presetId**
 *    （落库的只有最终值 —— 否则用户改了字段却还留着"选了某预设"的错觉）；
 * 3. **api key 只写不读回**：界面显示的是掩码（`sk-****`），输入框是本次新输入的值。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val apiKeyStore: ApiKeyStore,
) : ViewModel() {

    private val draft = MutableStateFlow<SettingsUiState?>(null)

    val state: StateFlow<SettingsUiState> = draft
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            // 落库值只用来**播种一次**草稿；之后草稿是唯一真相（否则用户在输入时会被仓库回流覆盖）
            val stored = settings.settings.first()
            val masked = withContext(Dispatchers.IO) { apiKeyStore.masked() }
            draft.value = SettingsUiState(
                protocol = stored.llmProvider?.let { runCatching { ProtocolKind.valueOf(it) }.getOrNull() },
                baseUrl = stored.llmBaseUrl.orEmpty(),
                model = stored.llmModel.orEmpty(),
                hasApiKey = masked != null,
                apiKeyMasked = masked,
                maxRetries = stored.llmMaxRetries,
                connectTimeoutMs = stored.llmConnectTimeoutMs,
                readTimeoutMs = stored.llmReadTimeoutMs,
                maxTokens = stored.llmMaxTokens,
                batchSize = stored.llmBatchSize,
                supportsJsonSchema = stored.llmSupportsJsonSchema,
                presets = ProviderPresets.ALL,
            )
        }
    }

    private fun edit(block: (SettingsUiState) -> SettingsUiState) {
        draft.update { current -> block(current ?: SettingsUiState(presets = ProviderPresets.ALL)) }
    }

    fun onProtocolChange(kind: ProtocolKind) = edit { it.copy(protocol = kind) }

    fun onBaseUrlChange(value: String) = edit { it.copy(baseUrl = value) }

    fun onModelChange(value: String) = edit { it.copy(model = value) }

    fun onApiKeyChange(value: String) = edit { it.copy(apiKeyInput = value) }

    /** 填表助手：选中预设只填三项，**不落 presetId**。 */
    fun onSelectPreset(presetId: String) {
        val preset = ProviderPresets.ALL.firstOrNull { it.id == presetId } ?: return
        edit { it.copy(protocol = preset.protocol, baseUrl = preset.baseUrl, model = preset.defaultModel) }
    }

    fun onToggleAdvanced() = edit { it.copy(advancedExpanded = !it.advancedExpanded) }

    fun onMaxRetriesChange(value: Int) = edit { it.copy(maxRetries = value) }

    fun onTimeoutChange(connectMs: Long, readMs: Long) =
        edit { it.copy(connectTimeoutMs = connectMs, readTimeoutMs = readMs) }

    fun onMaxTokensChange(value: Int) = edit { it.copy(maxTokens = value) }

    fun onSupportsJsonSchemaChange(value: Boolean) = edit { it.copy(supportsJsonSchema = value) }

    fun onSave() {
        val current = state.value
        if (!current.saveEnabled) return          // 界面已禁用，这里再挡一次（防止绕过）
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                current.apiKeyInput.takeIf { it.isNotBlank() }?.let { apiKeyStore.write(it) }
            }
            settings.setLlmConfig(
                provider = current.protocol?.name,
                baseUrl = current.baseUrl.trim(),
                model = current.model.trim(),
                supportsJsonSchema = current.supportsJsonSchema,
                maxRetries = current.maxRetries,
                maxTokens = current.maxTokens,
                connectTimeoutMs = current.connectTimeoutMs,
                readTimeoutMs = current.readTimeoutMs,
                batchSize = current.batchSize,
            )
            // 保存后清空输入框并刷新掩码：key 已进加密存储，界面回到"只显示掩码"的态
            val masked = withContext(Dispatchers.IO) { apiKeyStore.masked() }
            edit { it.copy(apiKeyInput = "", hasApiKey = masked != null, apiKeyMasked = masked) }
        }
    }
}
