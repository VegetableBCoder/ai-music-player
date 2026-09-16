package com.aimusic.player.llm.prompt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class PromptResourcesTest {

    private val resources = PromptResources()

    @Test
    fun `三份资源都能从 classpath 读到且是文档原文`() {
        assertThat(resources.system()).contains("本地音乐库的元数据归一化助手")
        assertThat(resources.system()).contains("未知艺术家")
        assertThat(resources.user()).contains("请只输出 JSON。")
        assertThat(resources.schemaJson()).contains("\"additionalProperties\": false")
    }

    @Test
    fun `user 模板四个占位符一个不少`() {
        val user = resources.user()
        listOf("{{categories}}", "{{tags}}", "{{maxPerCategory}}", "{{files}}")
            .forEach { assertThat(user).contains(it) }
    }

    @Test
    fun `user 是目录在前文件清单在后（批量省 token 的关键）`() {
        val user = resources.user()
        assertThat(user.indexOf("【当前有效分类】")).isLessThan(user.indexOf("【文件清单】"))
        assertThat(user.indexOf("【当前有效标签】")).isLessThan(user.indexOf("【文件清单】"))
    }

    @Test
    fun `schema 是合法 JSON 且分类 enum 留了注入点`() {
        val schema = Json.parseToJsonElement(resources.schemaJson()).jsonObject
        val results = schema["properties"]!!.jsonObject["results"]!!.jsonObject
        assertThat(results["type"]!!.jsonPrimitive.content).isEqualTo("array")

        val item = results["items"]!!.jsonObject
        assertThat(item["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("file_index", "canonical_title", "artists", "tag_groups")

        val category = item["properties"]!!.jsonObject["tag_groups"]!!.jsonObject["items"]!!
            .jsonObject["properties"]!!.jsonObject["category"]!!.jsonObject
        assertThat(category["enum"]!!.jsonArray[0].jsonPrimitive.content).isEqualTo("{{categories}}")
    }

    @Test
    fun `promptHash 等于外部工具算出的那个值`() {
        // 期望值由 python hashlib 独立算出（system + U+0000 + user + U+0000 + schema 的 sha256），
        // 不是用被测代码自己算 —— 否则等式两边同源，测了等于没测
        assertThat(resources.promptHash())
            .isEqualTo("acc0c9210a366998617878f17f82d02ca19a09fe81c63dd729d4c99ba1227e71")
    }

    @Test
    fun `promptHash 稳定且形如 sha256 十六进制`() {
        val hash = resources.promptHash()
        assertThat(hash).isEqualTo(resources.promptHash())
        assertThat(hash).matches("[0-9a-f]{64}")
    }
}
