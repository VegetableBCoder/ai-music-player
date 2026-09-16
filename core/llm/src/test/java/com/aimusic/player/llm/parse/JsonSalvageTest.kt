package com.aimusic.player.llm.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * L3（纯提示词档）的容错抽取（spec §4 表末行）。
 *
 * 计划 Task 6 里这份测试与实现都是残片（测试断在第 17 行半个表达式上、实现只有 4 行 KDoc），
 * 故按 spec 的两条要求补齐：剥 ``` 围栏、截首个 `{` 到末个 `}`。
 */
class JsonSalvageTest {

    private val payload = """{"results":[{"file_index":1}]}"""

    @Test
    fun `裸 JSON 原样通过`() {
        assertThat(JsonSalvage.toJsonText(payload)).isEqualTo(payload)
    }

    @Test
    fun `剥掉 json 围栏`() {
        assertThat(JsonSalvage.toJsonText("```json\n" + payload + "\n```")).isEqualTo(payload)
    }

    @Test
    fun `剥掉没有语言标记的围栏`() {
        assertThat(JsonSalvage.toJsonText("```\n" + payload + "\n```")).isEqualTo(payload)
    }

    @Test
    fun `前后有解释文字也能截出来`() {
        val noisy = "好的，这是归一化结果：\n" + payload + "\n希望对你有帮助。"
        assertThat(JsonSalvage.toJsonText(noisy)).isEqualTo(payload)
    }

    @Test
    fun `首尾空白被去掉`() {
        assertThat(JsonSalvage.toJsonText("  \n " + payload + " \n ")).isEqualTo(payload)
    }

    @Test
    fun `找不到花括号时返回去空白后的原文而不是空串`() {
        assertThat(JsonSalvage.toJsonText("  这里没有 JSON  ")).isEqualTo("这里没有 JSON")
    }
}
