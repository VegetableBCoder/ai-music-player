package com.aimusic.player.llm.parse

import com.aimusic.player.common.log.LogEvent
import com.aimusic.player.common.log.Logger
import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.model.TagAssignment
import com.aimusic.player.common.text.TextNormalizer
import com.aimusic.player.llm.DuplicateIndex
import com.aimusic.player.llm.LlmFailureKind
import com.aimusic.player.llm.MissingField
import com.aimusic.player.llm.NormalizeOutcome
import com.aimusic.player.llm.NormalizeRequest
import com.aimusic.player.llm.log.NoopLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 解析整批响应、按 `file_index` 对回入参，并把 `tag_groups` 摊平成 `TagAssignment`（`05 §4.5`）。
 *
 * 入口签名 `parse(text, req)` **文档锁定**（spec §3 硬约束 1）：`text` 是适配器还原后的
 * JSON 字符串（ANTHROPIC 的 `tool_use.input` 由适配器 `Json.encodeToString` 还原），
 * `req` 是整批请求，返回与入参等长、按索引对齐。
 *
 * 失败分两级（spec §9.2）：整批不可解析 → 整批 `INVALID_OUTPUT`；条目级失败只连坐该文件。
 */
class NormalizeParser(
    /** 每分类标签数量上限 `n`（`LlmConfig.maxTagsPerCategory`，不在 `NormalizeRequest` 上）。 */
    private val maxTagsPerCategory: Int = 2,
    private val logger: Logger = NoopLogger,
) {

    fun parse(text: String, req: List<NormalizeRequest>): List<NormalizeOutcome> {
        val root = runCatching { Json.parseToJsonElement(JsonSalvage.toJsonText(text)).jsonObject }
            .getOrNull()
            ?: return allFailure(req.size, LlmFailureKind.INVALID_OUTPUT)

        // results 必须是数组（缺 / 非数组 → 整批失败）
        val items = root["results"] as? JsonArray
            ?: return allFailure(req.size, LlmFailureKind.INVALID_OUTPUT)

        if (items.size != req.size) {
            warn("results 长度 ${items.size} != 输入 ${req.size}")
        }

        // 按 file_index 对回（1 起、与 prompt 中【文件 N】一致）；不用文件名做键
        val byIndex = HashMap<Int, JsonObject>()
        val duplicateIndices = mutableSetOf<Int>()
        for (el in items) {
            val obj = el as? JsonObject
            if (obj == null) {
                warn("drop result: 元素不是 JSON 对象")
                continue
            }
            val raw = (obj["file_index"] as? JsonPrimitive)?.content
            val index = raw?.toIntOrNull()
            when {
                index == null -> warn("drop result: 缺 file_index（raw=$raw）")
                index < 1 || index > req.size -> warn("drop result: file_index=$index 越界，忽略该条")
                byIndex.containsKey(index) -> {
                    duplicateIndices += index
                    warn("drop result: file_index=$index 重复")
                }
                else -> byIndex[index] = obj
            }
        }

        return req.mapIndexed { i, reqOne ->
            val key = i + 1
            when {
                key in duplicateIndices ->
                    NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, DuplicateIndex(key))
                else ->
                    byIndex[key]?.let { parseOne(it, reqOne) }
                        ?: NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, MissingField("results[$key]"))
            }
        }
    }

    private fun warn(message: String) = logger.warn(LogEvent.LLM_REQUEST, message)

    companion object {
        /** parser 认得的**条目**字段（Task 9 防漂移测试与 `schema.json` 的 `required` 对齐，spec §5）。 */
        val RESULT_FIELDS = setOf("file_index", "canonical_title", "artists", "tag_groups")

        /** parser 认得的**标签组**字段（同上）。 */
        val GROUP_FIELDS = setOf("category", "tags")

        /** 整批失败：列表内每一项同值。 */
        private fun allFailure(size: Int, kind: LlmFailureKind): List<NormalizeOutcome> =
            List(size) { NormalizeOutcome.Failure(kind, null) }
    }

    // parseOne 在 Task 8 补齐 tag_groups 摊平；本任务先让标题 / 歌手两条判定生效。
    private fun parseOne(obj: JsonObject, req: NormalizeRequest): NormalizeOutcome {
        val rawTitle = (obj["canonical_title"] as? JsonPrimitive)?.content
        if (rawTitle.isNullOrBlank()) {
            return NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, MissingField("canonical_title"))
        }
        val title = TextNormalizer.normalizeTitle(rawTitle)

        val rawArtists = (obj["artists"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        val artists = rawArtists
            .flatMap(TextNormalizer::splitArtists)   // 兜底拆分 feat. / & / / / 、
            .filter { it.isNotBlank() }
            .distinct()                              // 去重；不排序（排序在 entityKey 计算时做）
        if (artists.isEmpty()) {
            return NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, MissingField("artists"))
        }

        return NormalizeOutcome.Success(NormalizeResult(title, artists, emptyList<TagAssignment>()))
    }
}