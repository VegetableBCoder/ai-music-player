package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "category",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "is_main", defaultValue = "0") val isMain: Boolean = false,
    @ColumnInfo(name = "is_builtin", defaultValue = "0") val isBuiltin: Boolean = false,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
)
