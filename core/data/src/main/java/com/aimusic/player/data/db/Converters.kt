package com.aimusic.player.data.db

import androidx.room.TypeConverter
import com.aimusic.player.data.model.AnalysisStatus
import com.aimusic.player.data.model.LyricSource
import com.aimusic.player.data.model.PlayMode
import com.aimusic.player.data.model.RunStatus
import com.aimusic.player.data.model.SourceKind

/**
 * 枚举统一以 `TEXT` 存 `name` 往返（`03 §2.3`）。
 * **不用整型序号**：枚举一旦重排，旧数据会被解释成另一个值。
 */
class Converters {

    @TypeConverter
    fun fromAnalysisStatus(value: AnalysisStatus): String = value.name

    @TypeConverter
    fun toAnalysisStatus(value: String): AnalysisStatus = AnalysisStatus.valueOf(value)

    @TypeConverter
    fun fromPlayMode(value: PlayMode): String = value.name

    @TypeConverter
    fun toPlayMode(value: String): PlayMode = PlayMode.valueOf(value)

    @TypeConverter
    fun fromSourceKind(value: SourceKind): String = value.name

    @TypeConverter
    fun toSourceKind(value: String): SourceKind = SourceKind.valueOf(value)

    @TypeConverter
    fun fromLyricSource(value: LyricSource): String = value.name

    @TypeConverter
    fun toLyricSource(value: String): LyricSource = LyricSource.valueOf(value)

    @TypeConverter
    fun fromRunStatus(value: RunStatus): String = value.name

    @TypeConverter
    fun toRunStatus(value: String): RunStatus = RunStatus.valueOf(value)
}
