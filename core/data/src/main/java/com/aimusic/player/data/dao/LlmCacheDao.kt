package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aimusic.player.data.entity.LlmCacheEntity

/**
 * `llm_cache` 不参与业务级联删除：删除歌曲实体不清空缓存（内容寻址，属可复用资产，`03 §3.8`）。
 */
@Dao
interface LlmCacheDao {

    @Query("SELECT result_json FROM llm_cache WHERE cache_key = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: LlmCacheEntity)

    @Query("DELETE FROM llm_cache WHERE created_at < :before")
    suspend fun evictOlderThan(before: Long)
}
