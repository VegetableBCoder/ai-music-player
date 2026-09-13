package com.aimusic.player.data.error

/**
 * 标签 / 分类写操作的业务结果（`11 §6.2`）。
 *
 * 用它们把「约束冲突」翻译成业务语义 —— `SQLiteConstraintException` 是**实现细节**，
 * 绝不允许穿透到 Repository 之上。
 */

sealed interface AddTagResult {
    data class Added(val categoryId: Long) : AddTagResult

    /** 标签名全局唯一（I5）：同名标签已存在，`existingCategoryId` 用于 UI 引导复用。 */
    data class ReuseExisting(val existingCategoryId: Long) : AddTagResult
}

sealed interface DeleteTagResult {
    data object Deleted : DeleteTagResult

    /** I12 删除保护：标签下还有歌。`songCount` 用于文案里说明原因。 */
    data class Blocked(val songCount: Int) : DeleteTagResult
}

sealed interface DeleteCategoryResult {
    data object Deleted : DeleteCategoryResult

    /** I12 删除保护：分类下还有标签。`tagCount` 用于文案里说明原因。 */
    data class Blocked(val tagCount: Int) : DeleteCategoryResult
}
