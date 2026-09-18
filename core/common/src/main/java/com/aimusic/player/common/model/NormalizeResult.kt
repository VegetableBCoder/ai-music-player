package com.aimusic.player.common.model

/**
 * LLM 归一化的**产出**，也是数据层的**输入** —— 因此放在 `:core:common` 而不是 `:core:llm`。
 *
 * 依据：`02 §2` 的依赖图规定 `core:data ─→ core:storage, core:common`（不含 `core:llm`），
 * 而 `03 §4.1` 的 `attachAnalysisResult(fileId, result, now)` 要收这个结果。
 * `:core:llm` 的内部类型（`NormalizeOutcome`、`LlmFailureKind`、`NormalizeRequest`）
 * 不外泄，仍留在 `:core:llm` —— 这也符合 `05 §3.2.2`「`LlmFailureKind` 不越界到上层」的既有约定。
 */
data class NormalizeResult(
    val canonicalTitle: String,
    /** 全部署名，已归一化、去重，**未排序**（排序由 TextNormalizer.artistsKey 负责） */
    val artists: List<String>,
    val tagAssignments: List<TagAssignment>,
)

/** (分类, 标签名)。分类不在当前有效集合中时由数据层丢弃（AI 不得新建分类）。 */
data class TagAssignment(val category: String, val name: String)

// 注：三字段的「标签引用投影」原本也叫 `TagRef` 放在这里，但它其实只被 `:core:data` 与上层 UI 消费，
// 与归一化无关，且与 `:core:llm` 契约里的两字段 `TagRef` 同名不同形（本文件此前还自称
// 「全项目唯一的标签展示类型」，与该同名者直接矛盾）。按 `02 §5.3` 决定 #3，它已改名为
// `TagProjection` 并移入 `:core:data`（`com.aimusic.player.data.model.TagProjection`）。
