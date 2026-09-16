package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ModuleSmokeTest {

    @Test
    fun `模块可编译且能读到 kotlinx_serialization 与 core_common`() {
        val json = Json.parseToJsonElement("""{"a":1}""")
        assertThat(json.toString()).isEqualTo("""{"a":1}""")

        // :core:common 能读到（用它的脱敏工具做记号，顺带确认 key 不会被原样带出）
        val masked = com.aimusic.player.common.log.Redactor.apiKey("sk-1234567890abcdef")
        assertThat(masked).contains("sk-")
        assertThat(masked).doesNotContain("1234567890abcdef")
    }
}
