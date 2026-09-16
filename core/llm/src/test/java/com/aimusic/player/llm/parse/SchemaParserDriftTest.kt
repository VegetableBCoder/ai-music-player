package com.aimusic.player.llm.parse

import com.aimusic.player.llm.NormalizeRequest
import com.aimusic.player.llm.prompt.PromptBuilder
import com.aimusic.player.llm.prompt.PromptResources
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * 防漂移（spec §5 / `05 §4.4.3`）：schema.json 的 `required` 字段集合 == parser 认识的字段集合。
 *
 * 这个测试**故意**读的是真实资源文件而不是内联字符串：改了 schema.json 的 required 却不改
 * parser 常量（或缺反向），它就变红 —— 阻止"发给模型的契约"和"解析器的判定"各走各的。
 */
class SchemaParserDriftTest {

    private val request = NormalizeRequest(
        fileName = "a.flac",
        metadata = null,
        categories = listOf("音乐类型", "情绪"),
        tags = emptyList(),
    )

    private val schema: JsonObject =
        PromptBuilder(PromptResources()).build(listOf(request)).schema.jsonObject

    private fun JsonObject.at(vararg keys: String): JsonObject {
        var current: JsonObject = this
        for (key in keys) current = current.getValue(key).jsonObject
        return current
    }

    private fun JsonObject.requiredSet(): Set<String> =
        getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()

    @Test
    fun `results 条目的 required 集合等于 parser 的 RESULT_FIELDS`() {
        val item = schema.at("properties", "results", "items")
        assertThat(item.requiredSet()).isEqualTo(NormalizeParser.RESULT_FIELDS)
    }

    @Test
    fun `tag_groups 条目的 required 集合等于 parser 的 GROUP_FIELDS`() {
        val groupItem = schema.at("properties", "results", "items", "properties", "tag_groups", "items")
        assertThat(groupItem.requiredSet()).isEqualTo(NormalizeParser.GROUP_FIELDS)
    }

    @Test
    fun `schema 的顶层 required 只有 results`() {
        assertThat(schema.requiredSet()).containsExactly("results")
    }

    @Test
    fun `枚举注入的是当前批次分类，且 parser 逐字比对分类名`() {
        val categoryEnum = schema
            .at("properties", "results", "items", "properties", "tag_groups", "items", "properties", "category")
            .getValue("enum").jsonArray
        assertThat(categoryEnum.map { it.jsonPrimitive.content })
            .containsExactly("音乐类型", "情绪")
    }
}