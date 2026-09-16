package com.aimusic.player.llm.prompt

import com.aimusic.player.llm.crypto.sha256Hex

/**
 * 读 `core/llm/src/main/resources/prompt/` 下的三份资源。
 *
 * 纯 JVM resource，classLoader 直读 —— JVM 单测不需要 Robolectric，打包进 APK 也成立（spec §5）。
 * 内容一字不改地取自 `05 §4.4.1/§4.4.2/§4.4.3`：prompt 是发给模型的契约，不允许在代码里二次加工。
 */
class PromptResources(private val loader: ClassLoader = PromptResources::class.java.classLoader) {

    private val systemText: String by lazy { load(SYSTEM_FILE) }
    private val userTemplate: String by lazy { load(USER_FILE) }
    private val schemaText: String by lazy { load(SCHEMA_FILE) }

    fun system(): String = systemText

    /** 模板本身（含四个占位符）；渲染是 `PromptBuilder` 的事。 */
    fun user(): String = userTemplate

    fun schemaJson(): String = schemaText

    /**
     * 三份资源内容的 sha256，参与缓存 key（`05 §4.8`）。
     *
     * 用 U+0000 作分隔符：否则「把边界挪一位」也能得到另一个合法拼接串，不同的 (system,user,schema)
     * 三元组可能算出同一个值 —— 缓存就会串味。
     */
    fun promptHash(): String = sha256Hex(systemText + SEP + userTemplate + SEP + schemaText)

    private fun load(name: String): String =
        loader.getResourceAsStream("prompt/$name")?.use { it.readBytes().decodeToString() }
            ?: error("缺少 prompt 资源：prompt/$name")

    private companion object {
        const val SYSTEM_FILE = "system.txt"
        const val USER_FILE = "user.txt"
        const val SCHEMA_FILE = "schema.json"
        const val SEP = "\u0000"
    }
}
