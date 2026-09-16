package com.aimusic.player.llm.prompt

import com.aimusic.player.common.model.AudioMetadata
import com.aimusic.player.llm.TagRef
import com.aimusic.player.llm.NormalizeRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class PromptBuilderTest {

    private val categories = listOf("音乐类型", "情绪", "场景", "主题")
    private val tags = listOf(
        TagRef("流行", "音乐类型"),
        TagRef("摇滚", "音乐类型"),
        TagRef("怀旧", "情绪"),
    )

    private fun request(
        fileName: String,
        metadata: AudioMetadata? = null,
    ) = NormalizeRequest(fileName, metadata, categories, tags)

    private val builder = PromptBuilder(PromptResources(), maxTagsPerCategory = 2)

    @Test
    fun `目录在前、文件清单在后`() {
        val bundle = builder.build(listOf(request("a.flac"), request("b.flac")))
        assertThat(bundle.user.indexOf("【当前有效分类】"))
            .isLessThan(bundle.user.indexOf("【文件 1】"))
        assertThat(bundle.user.indexOf("【文件 1】")).isLessThan(bundle.user.indexOf("【文件 2】"))
    }

    @Test
    fun `四个占位符全部被替换，渲染后无残留`() {
        val bundle = builder.build(listOf(request("a.flac")))
        assertThat(bundle.user).doesNotContain("{{")
        assertThat(bundle.user).contains("- 音乐类型")
        assertThat(bundle.user).contains("- 音乐类型: 流行, 摇滚")
        assertThat(bundle.user).contains("- 情绪: 怀旧")
        assertThat(bundle.user).contains("音乐类型 ≤ 2, 情绪 ≤ 2, 场景 ≤ 2, 主题 ≤ 2")
    }

    @Test
    fun `二十个文件共享一份目录，文件清单有二十项`() {
        val bundle = builder.build(List(20) { request("f$it.flac") })
        val categoryHeaders = bundle.user.split("【当前有效分类】").size - 1
        assertThat(categoryHeaders).isEqualTo(1)
        assertThat(bundle.user).contains("【文件 20】")
    }

    @Test
    fun `schema 只有 category 的 enum 注入了当前分类，其余骨架不动`() {
        val schema = builder.build(listOf(request("a.flac"))).schema.jsonObject
        val groupItem = schema.at("properties", "results", "items", "properties", "tag_groups", "items")
        val enum = groupItem.at("properties", "category").getValue("enum").jsonArray
        assertThat(enum.map { it.jsonPrimitive.content }).containsExactly("音乐类型", "情绪", "场景", "主题")

        val required = schema.at("properties", "results", "items").getValue("required").jsonArray
        assertThat(required.map { it.jsonPrimitive.content })
            .containsExactly("file_index", "canonical_title", "artists", "tag_groups")
    }

    @Test
    fun `元数据为空渲染「（无）」，非空逐字段渲染`() {
        val none = builder.build(listOf(request("a.flac"))).user
        assertThat(none).contains("内嵌元数据: （无）")

        val meta = AudioMetadata("晴天", "周杰伦", "叶惠美", "周杰伦", "2003-07-31", 269_000L, true)
        val some = builder.build(listOf(request("b.flac", meta))).user
        assertThat(some).contains("  title: 晴天")
        assertThat(some).contains("  artist: 周杰伦")
        assertThat(some).contains("  durationMs: 269000")
    }

    @Test
    fun `promptHash 透传自 PromptResources`() {
        assertThat(builder.promptHash()).isEqualTo(PromptResources().promptHash())
    }
}

/** 依次取 JsonObject 的键（缺任一环即抛；测试里只用于读骨架，不吞错）。 */
private fun JsonObject.at(vararg keys: String): JsonObject {
    var current: JsonObject = this
    for (key in keys) current = current.getValue(key).jsonObject
    return current
}
