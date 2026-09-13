package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 实体 ↔ 标签（多对多）。 */
@Entity(
    tableName = "entity_tag",
    primaryKeys = ["entity_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["name"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag_id"], name = "idx_entity_tag_tag")],
)
data class EntityTagCrossRef(
    @ColumnInfo(name = "entity_id") val entityId: Long,
    @ColumnInfo(name = "tag_id") val tagId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
