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

/**
 * 标签引用 —— **全项目唯一的标签展示类型**。
 *
 * `06 §3.1` 明确要求它与 `02 §5.3` 同构、「避免第二套命名」，因此 LLM 侧的
 * `NormalizeRequest.tags` 与列表投影共用这一个类型。
 *
 * 比 `02 §5.3` 原稿多出 `categoryId`：列表与筛选需要按分类定位，只靠分类名不便关联。
 */
data class TagRef(
    val name: String,
    val categoryId: Long,
    val categoryName: String,
)
