package com.aimusic.player.data.model

/**
 * 列表投影（`06 §3.1`）。字段名与 SQL 别名一致，Room 直接按名字映射。
 */

/** 分类列表：分类字段 + 该分类下的标签数（删除保护提示用）。 */
data class CategoryListItem(
    val id: Long,
    val name: String,
    /** 主要分类（多选，不互斥） */
    val isMain: Boolean,
    /** 仅用于展示标签，**不参与删除保护** */
    val isBuiltin: Boolean,
    val sortOrder: Int,
    val tagCount: Int,
)

/** 某分类下的标签 + 该标签的歌曲数（**为 0 仍展示**，`01 §8.3`）。 */
data class TagListItem(
    val name: String,
    val categoryId: Long,
    val isBuiltin: Boolean,
    val songCount: Int,
)

/** 批量取若干实体的标签：一条关联一行，由 Repository 组合成 `Map<entityId, List<TagRef>>`。 */
data class EntityTagRow(
    val entityId: Long,
    val name: String,
    val categoryId: Long,
    val categoryName: String,
)
