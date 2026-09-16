package com.aimusic.player.llm.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JsonSalvageTest {

    private val payload = """{"results":[{"file_index":1}]}"""

    @Test
    fun `裸 JSON 原样通过`() {
        assertThat(JsonSalvage.toJsonText(payload)).isEqualTo(payload)
    }

    @Test
    fun `剥掉 json 围栏`() {
        assertThat(JsonSalvage.toJsonText("```json\n$payload\n```")).isEqualTo(payload)
    }

    @Test
    fun `剥掉无语言标记的围栏`() {
        assertThat(JsonSalvage.toJsonText("```\n$payload\n```")).isEqualTo(payload)
    }

    @Test
    fun `截取首个左花括号到末个右花括号，丢掉前后解释文字`() {
        assertThat(JsonSalvage.toJsonText("好的，结果如下：\n$payload\n以上。")).isEqualTo(payload)
    }

    @Test
    fun `围栏与前后文同时存在也能救回`() {
        assertThat(JsonSalvage.toJsonText("  Sure!\n```json\n$payload\n```\nDone.")).isEqualTo(payload)
    }

    @Test
    fun `完全没有花括号时原样返回，交给上层判失败`() {
        assertThat(JsonSalvage.toJsonText("not json")).isEqualTo("not json")
    }
}