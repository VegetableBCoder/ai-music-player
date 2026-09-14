package com.aimusic.player.data.error

/**
 * 标签 / 分类写操作的业务结果（`06 §3.4`，覆盖 `11 §6.2` 的业务语义）。
 *
 * 用它们把「约束冲突」翻译成业务语义 —— `SQLiteConstraintException` 是**实现细节**，
 * 绝不允许穿透到 Repository 之上。
 */

sealed interface AddTagResult {
    data class Added(val name: String) : AddTagResult

    /** 标签名全局唯一（I5）：同名标签已存在，带上它原本所属的分类供 UI 引导复用。 */
    data class ReuseExisting(
        val name: String,
        val categoryId: Long,
        val categoryName: String,
    ) : AddTagResult

    /**
     * 前置不合法（空名 / 分类不存在）。
     *
     * `reason` 存的是**文案 key** 而非句子：文案归 `11 §5.1` 的 `ErrorText` 表所有，
     * 数据层只指出原因，不把中文散落到 `:core:data`。
     */
    data class Rejected(val reason: String) : AddTagResult
}

sealed interface DeleteTagResult {
    data object Deleted : DeleteTagResult

    /** I12 删除保护：标签下还有歌。`songCount` 用于文案里说明原因。 */
    data class Blocked(val songCount: Int) : DeleteTagResult

    data object NotFound : DeleteTagResult
}

sealed interface DeleteCategoryResult {
    data object Deleted : DeleteCategoryResult

    /** I12 删除保护：分类下还有标签。`tagCount` 用于文案里说明原因。 */
    data class Blocked(val tagCount: Int) : DeleteCategoryResult

    data object NotFound : DeleteCategoryResult
}

/** `AddTagResult.Rejected.reason` 的取值：文案 key，由 `11 §5.1` 的 `ErrorText` 表负责翻译。 */
object TagRejectReason {
    const val EMPTY_NAME = "tag.name.empty"
    const val MISSING_CATEGORY = "tag.category.missing"
}
