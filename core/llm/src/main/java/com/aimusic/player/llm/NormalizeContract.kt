package com.aimusic.player.llm

import com.aimusic.player.common.model.AudioMetadata
import com.aimusic.player.common.model.NormalizeResult

/**
 * 标签引用 —— **`:core:llm` 契约专用的两字段形态**（`05 §3.1` 锁定）。
 *
 * 与列表 / 详情那边的 `TagProjection(name, categoryId, categoryName)` **同名不同形**：
 * 因依赖守卫禁止 `:core:llm → :core:data`，本类型拿不到 `categoryId`，故只带分类名。
 * 读到这里别顺手合并 —— 两者的类别名与用途都不同。
 *
 * （历史上 `:core:common` 里也曾有一个三字段的 `TagRef`，与本文同名同类，极易混淆；
 * 按 `02 §5.3` 决定 #3 它已改名 `TagProjection` 并移入 `:core:data`。）
 */
data class TagRef(val name: String, val category: String)

/**
 * 一次归一化请求。`metadata` 可空 —— 读不到标签的文件同样要能分析（标题就只能靠文件名）。
 */
data class NormalizeRequest(
    val fileName: String,
    val metadata: AudioMetadata?,
    val categories: List<String>,
    val tags: List<TagRef>,
)

/**
 * 归一化的结果三态。`RateLimited` 与 `Failure` 分开，是因为限流要走退避重试（`05 §4.7`），
 * 而其它失败不重试（`429` 在 `toFailureKind()` 里就返回 null 正为此）。
 */
sealed interface NormalizeOutcome {

    data class Success(val result: NormalizeResult) : NormalizeOutcome

    data class RateLimited(val retryAfterMs: Long?) : NormalizeOutcome

    data class Failure(val kind: LlmFailureKind, val cause: Throwable?) : NormalizeOutcome
}

/** 条目级失败：缺必填字段。只废掉这一条，不废整批（`05 §4.5`）。 */
class MissingField(val field: String) : IllegalStateException("缺少字段：$field")

/** 条目级失败：`file_index` 重复或越界。 */
class DuplicateIndex(val fileIndex: Int) : IllegalStateException("file_index 非法或重复：$fileIndex")
