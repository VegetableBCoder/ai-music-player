package com.aimusic.player.data.model

import androidx.room.ColumnInfo

/** `03 §3.4` 分类列表投影：分类字段 + 该分类下的标签数。 */
data class CategoryListItem(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "isMain") val isMain: Boolean,
    @ColumnInfo(name = "isBuiltin") val isBuiltin: Boolean,
    @ColumnInfo(name = "sortOrder") val sortOrder: Int,
    @ColumnInfo(name = "tagCount") val tagCount: Int,
)

/** `03 §3.4` 某分类下的标签 + 该标签的歌曲数（0 首也展示）。 */
data class TagListItem(
    val name: String,
    @ColumnInfo(name = "isBuiltin") val isBuiltin: Boolean,
    @ColumnInfo(name = "songCount") val songCount: Int,
)

/** `03 §3.4` 批量取若干实体的标签：一条关联对应一行，由 Repository 组合成 Map。 */
data class EntityTagRow(
    @ColumnInfo(name = "entityId") val entityId: Long,
    val name: String,
    @ColumnInfo(name = "categoryId") val categoryId: Long,
)
