package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 标签名称全局唯一（I5），故直接以 `name` 为主键。 */
@Entity(
    tableName = "tag",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
        ),
    ],
    indices = [Index(value = ["category_id"], name = "idx_tag_category")],
)
data class TagEntity(
    @PrimaryKey val name: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "is_builtin", defaultValue = "0") val isBuiltin: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
