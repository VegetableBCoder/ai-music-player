package com.aimusic.player.probe

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Phase 0 技术探针：只用于验证 KSP + Room 在 AGP 9 内置 Kotlin 下能否正常生成代码。
 * 验证通过后即删除，正式表结构见 docs/技术方案/03-数据层设计.md。
 */
@Entity(tableName = "probe_item")
data class ProbeEntity(
    @PrimaryKey val id: Long,
    val path: String,
)

@Dao
interface ProbeDao {
    @Query("SELECT * FROM probe_item ORDER BY id")
    suspend fun all(): List<ProbeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ProbeEntity)
}

@Database(entities = [ProbeEntity::class], version = 1, exportSchema = true)
abstract class ProbeDatabase : RoomDatabase() {
    abstract fun probeDao(): ProbeDao
}
