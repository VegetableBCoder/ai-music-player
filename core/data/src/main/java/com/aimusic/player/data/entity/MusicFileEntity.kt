package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aimusic.player.data.model.AnalysisStatus

@Entity(
    tableName = "music_file",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["entity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["entity_id"], name = "idx_file_entity"),
        Index(value = ["analysis_status"], name = "idx_file_status"),
        Index(value = ["path"], unique = true),
        // I2 兜底：每个实体至多一个代表文件。
        //
        // 文档 03 §2.2 原本用「部分唯一索引 + WHERE is_representative = 1 AND entity_id IS NOT NULL」，
        // 但 Room 的 @Index 只有 value/orders/name/unique，表达不了部分索引。
        // 改成 (entity_id, is_representative) 复合唯一索引 + is_representative 可空：
        // SQLite 唯一索引中 NULL 互不冲突，所以非代表文件（NULL）可以有任意多条，
        // 而每个实体的 1 至多一条 —— 保证强度与部分索引等价。
        Index(
            value = ["entity_id", "is_representative"],
            unique = true,
            name = "idx_file_representative",
        ),
    ],
)
data class MusicFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** NULL = 尚未挂靠到实体 */
    @ColumnInfo(name = "entity_id") val entityId: Long? = null,
    val path: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    val size: Long,
    val format: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,

    /**
     * `1` = 代表文件；`NULL` = 非代表。
     * **不可写 0** —— 否则复合唯一索引会把同一实体的多个非代表文件判为冲突。
     */
    @ColumnInfo(name = "is_representative") val isRepresentative: Int? = null,
    @ColumnInfo(name = "analysis_status", defaultValue = "UNANALYZED")
    val analysisStatus: AnalysisStatus = AnalysisStatus.UNANALYZED,
    @ColumnInfo(name = "analysis_error") val analysisError: String? = null,
    @ColumnInfo(name = "error_kind") val errorKind: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "analyzed_at") val analyzedAt: Long? = null,
)
