package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aimusic.player.data.model.RunStatus

@Entity(tableName = "analysis_run")
data class AnalysisRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val status: RunStatus,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    @ColumnInfo(name = "new_count", defaultValue = "0") val newCount: Int = 0,
    @ColumnInfo(name = "skipped_count", defaultValue = "0") val skippedCount: Int = 0,
    @ColumnInfo(name = "cleaned_count", defaultValue = "0") val cleanedCount: Int = 0,
    @ColumnInfo(name = "analyzed_ok", defaultValue = "0") val analyzedOk: Int = 0,
    @ColumnInfo(name = "failed_count", defaultValue = "0") val failedCount: Int = 0,
)
