package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/** 批次 ↔ 文件：「最近分析记录」的文件清单。 */
@Entity(
    tableName = "analysis_run_file",
    primaryKeys = ["run_id", "file_id"],
    foreignKeys = [
        ForeignKey(
            entity = AnalysisRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MusicFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AnalysisRunFileCrossRef(
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "file_id") val fileId: Long,
)
