package com.aimusic.player.llm.preset

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 预设表的**结构与不变量**守卫：不猜取值，只保证"将来填进去的值不会破坏约束"。
 * 空表时三条断言天然成立，填表后被它们守住。
 */
class ProviderPresetsTest {

    @Test
    fun `id 唯一`() {
        assertThat(ProviderPresets.ALL.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `base url 一律以斜杠结尾`() {
        // spec §3.2：baseUrl 以 "/" 结尾；适配器再拼 "chat/completions" 等子路径
        assertThat(ProviderPresets.ALL.filterNot { it.baseUrl.endsWith("/") }).isEmpty()
    }

    @Test
    fun `display name 非空_且表里绝不携带 api key`() {
        assertThat(ProviderPresets.ALL.filter { it.displayName.isBlank() }).isEmpty()
        // key 只走 ApiKeyStore，永不进常量表 —— 一旦有人往里塞 key，这条会红
        val allText = ProviderPresets.ALL.flatMap {
            listOf(it.id, it.displayName, it.baseUrl, it.defaultModel)
        }
        assertThat(allText.filter { it.contains("sk-") }).isEmpty()
    }

    @Test
    fun `每条都带取值依据_sourceNote 是 http 链接`() {
        // spec §14 把预设取值列为"由用户提供"：填进来的人必须留下**到哪里核对的**，
        // 否则后来者只能凭印象猜这些值是不是编的（本表首条的 model 名就差点被误判成编的）。
        assertThat(ProviderPresets.ALL.filterNot { it.sourceNote.startsWith("http") }.map { it.id })
            .isEmpty()
    }
}
