package com.aimusic.player.data.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API Key 的加密存储（`03 §7`）：`EncryptedSharedPreferences` + Android Keystore。
 *
 * 只存取 `api_key` 一项；不写 `DataStore`、不写 Room、不进崩溃报告（`03 §7`）。
 * 对外**只提供掩码**给已配置状态下的回显，原文仅在真正调用 LLM 时读取。
 *
 * **必须由 DI 以单例提供**：`EncryptedSharedPreferences` 不保证同一文件名存在多个实例时的
 * 行为（真机验证时，同进程内为同一文件再建一个实例读不回数据）。所以这里不做内部缓存 ——
 * 一个进程里只应构造一次，这也是 `10 §5.4` 把它放在 `@Singleton` 的原因。
 */
class ApiKeyStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        fileName,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun read(): String? = prefs.getString(KEY_API_KEY, null)

    fun write(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    /** 供 UI 回显：**永不返回原文**。 */
    fun masked(): String? = read()?.let(::mask)

    companion object {
        const val DEFAULT_FILE_NAME = "llm_secure"

        private const val KEY_API_KEY = "api_key"

        /**
         * `03 §7` 规定的脱敏形态。
         *
         * 前缀不是 `sk-` 的（自建网关的 token）也一律掩掉全部字符 —— 掩码的目的是
         * **不泄露**，不是展示前几位；对未知格式的串保留前几位反而是泄露。
         */
        fun mask(key: String): String = if (key.startsWith("sk-")) "sk-****" else "****"
    }
}
