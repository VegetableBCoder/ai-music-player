package com.aimusic.player.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.aimusic.player.data.model.SongSort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * `03 §6` 的 8 个 key。
 *
 * **不放**：播放模式与队列（在 Room，需与队列同事务）；API Key（见 `ApiKeyStore`）。
 */
data class AppSettings(
    val sortPreference: SongSort,
    val lastScanAt: Long?,
    val llmProvider: String?,
    val llmBaseUrl: String?,
    val llmModel: String?,
    val llmSupportsJsonSchema: Boolean,
    val llmMaxRetries: Int,
    val permissionHintShown: Boolean,
)

/**
 * 设置读写（`03 §6`）。
 *
 * 用 `PreferenceDataStoreFactory.create` 而不是 `preferencesDataStore` 委托：委托把文件名
 * 固定死在扩展属性上，测试就没法换一个干净的文件，只能污染真机的正式设置。
 *
 * `produceFile` 必须放在**最后一个参数**：Kotlin 的尾随 lambda 只会绑定到最后一个参数，
 * 否则 `SettingsRepository(context) { file }` 会被当成传 `CoroutineScope`。
 */
class SettingsRepository(
    context: Context,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    produceFile: (Context) -> File = { it.preferencesDataStoreFile(FILE_NAME) },
) {

    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { produceFile(context) }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            // 枚举里读不到的值回落到默认：宁可显示默认排序，也不能让设置页打不开
            sortPreference = prefs[KEY_SORT]
                ?.let { raw -> SongSort.entries.firstOrNull { it.name == raw } }
                ?: SongSort.NAME,
            lastScanAt = prefs[KEY_LAST_SCAN_AT],
            llmProvider = prefs[KEY_LLM_PROVIDER],
            llmBaseUrl = prefs[KEY_LLM_BASE_URL],
            llmModel = prefs[KEY_LLM_MODEL],
            llmSupportsJsonSchema = prefs[KEY_LLM_JSON_SCHEMA] ?: false,
            llmMaxRetries = prefs[KEY_LLM_MAX_RETRIES] ?: DEFAULT_MAX_RETRIES,
            permissionHintShown = prefs[KEY_PERMISSION_HINT] ?: false,
        )
    }

    /** 列表页只关心排序偏好，单独暴露省得每处都去 map 整个 `AppSettings`。 */
    val sortPreference: Flow<SongSort> = settings.map { it.sortPreference }

    /** 「我的」页只关心上次扫描时间。 */
    val lastScanAt: Flow<Long?> = settings.map { it.lastScanAt }

    suspend fun setSortPreference(value: SongSort) =
        dataStore.edit { it[KEY_SORT] = value.name }

    suspend fun setLastScanAt(epochMillis: Long) =
        dataStore.edit { it[KEY_LAST_SCAN_AT] = epochMillis }

    suspend fun setLlmConfig(
        provider: String?,
        baseUrl: String?,
        model: String?,
        supportsJsonSchema: Boolean,
        maxRetries: Int,
    ) = dataStore.edit { prefs ->
        provider?.let { prefs[KEY_LLM_PROVIDER] = it }
        baseUrl?.let { prefs[KEY_LLM_BASE_URL] = it }
        model?.let { prefs[KEY_LLM_MODEL] = it }
        prefs[KEY_LLM_JSON_SCHEMA] = supportsJsonSchema
        prefs[KEY_LLM_MAX_RETRIES] = maxRetries
    }

    suspend fun setPermissionHintShown(shown: Boolean) =
        dataStore.edit { it[KEY_PERMISSION_HINT] = shown }

    /** 仅供测试：用于验证「存储里是坏值时回落默认而不是崩」。 */
    internal suspend fun putRawSortPreference(raw: String) =
        dataStore.edit { it[KEY_SORT] = raw }

    companion object {
        const val FILE_NAME = "settings"

        /**
         * 429 重试上限的默认值。`02 §8` 只写了「上限 N 次」没给具体数，
         * 这里取 3 并在 `03 §6` 落地时备注 —— 属**实现选择**而非文档规定。
         */
        const val DEFAULT_MAX_RETRIES = 3

        private val KEY_SORT = stringPreferencesKey("sort_preference")
        private val KEY_LAST_SCAN_AT = longPreferencesKey("last_scan_at")
        private val KEY_LLM_PROVIDER = stringPreferencesKey("llm_provider")
        private val KEY_LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        private val KEY_LLM_MODEL = stringPreferencesKey("llm_model")
        private val KEY_LLM_JSON_SCHEMA = booleanPreferencesKey("llm_supports_json_schema")
        private val KEY_LLM_MAX_RETRIES = intPreferencesKey("llm_max_retries")
        private val KEY_PERMISSION_HINT = booleanPreferencesKey("permission_hint_shown")
    }
}
