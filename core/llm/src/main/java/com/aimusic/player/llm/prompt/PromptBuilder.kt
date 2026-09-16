package com.aimusic.player.llm.prompt

import com.aimusic.player.llm.TagRef
import com.aimusic.player.llm.NormalizeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** 一次请求的全部文本：system / user / schema（schema 的 enum 已注入当前分类）。 */
data class PromptBundle(
    val system: String,
    val user: String,
    val schema: JsonElement,
)

/**
 * 把一批请求渲染成 `PromptBundle`（`05 §4.4`）。
 *
 * 占位符用**最朴素的字符串替换**（`String.replace`，纯字面量、无正则、无模板引擎）：
 * `{{categories}}` / `{{tags}}` / `{{maxPerCategory}}` / `{{files}}`。
 * 目录在前、文件清单在后 —— 20 个文件共享一份目录，这是批量省 token 的地方（spec §6）。
 */
class PromptBuilder(
    private val resources: PromptResources = PromptResources(),
    /** 每分类标签数量上限的 n（`LlmConfig.maxTagsPerCategory`，不落在 NormalizeRequest 上）。 */
    private val maxTagsPerCategory: Int = 2,
) {

    fun promptHash(): String = resources.promptHash()

    fun build(reqs: List<NormalizeRequest>): PromptBundle {
        require(reqs.isNotEmpty()) { "空批次不应构建 prompt" }
        val categories = reqs.first().categories       // 每批实时读取一次（非快照，spec §10）
        val tags = reqs.first().tags

        val user = resources.user()
            .substitute("{{categories}}", renderCategories(categories))
            .substitute("{{tags}}", renderTags(tags))
            .substitute("{{maxPerCategory}}", renderLimits(categories))
            .substitute("{{files}}", renderFiles(reqs))

        val schema = Json.parseToJsonElement(
            resources.schemaJson().substitute("{{categories}}", categories.joinToString("\",\""))
        )

        return PromptBundle(resources.system(), user, schema)
    }

    private fun renderCategories(categories: List<String>): String =
        categories.joinToString("\n") { "- $it" }

    private fun renderTags(tags: List<TagRef>): String =
        tags.groupBy { it.category }
            .entries
            .joinToString("\n") { (category, group) ->
                "- $category: " + group.joinToString(", ") { it.name }
            }

    private fun renderLimits(categories: List<String>): String =
        categories.joinToString(", ") { "$it ≤ $maxTagsPerCategory" }

    private fun renderFiles(reqs: List<NormalizeRequest>): String = buildString {
        reqs.forEachIndexed { i, req ->
            if (i > 0) append("\n\n")
            append("【文件 ${i + 1}】\n")
            append("文件名: ${req.fileName}\n")
            val m = req.metadata
            if (m == null) {
                append("内嵌元数据: （无）")
            } else {
                append("内嵌元数据:\n")
                if (m.title != null) append("  title: ${m.title}\n")
                if (m.artist != null) append("  artist: ${m.artist}\n")
                if (m.album != null) append("  album: ${m.album}\n")
                if (m.albumArtist != null) append("  albumArtist: ${m.albumArtist}\n")
                if (m.date != null) append("  date: ${m.date}\n")
                append("  durationMs: ${m.durationMs}")
            }
        }
    }

    /** 朴素字面量替换：不做正则、不解析 `$`。 */
    private fun String.substitute(placeholder: String, value: String): String = replace(placeholder, value)
}
