package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.aimusic.player.data.entity.LyricEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LyricDao {

    @Query("SELECT * FROM lyric WHERE entity_id = :entityId LIMIT 1")
    fun observeLyric(entityId: Long): Flow<LyricEntity?>

    /**
     * 匹配前载入**已占用**的歌词路径集合，让扫描器在候选阶段就跳过它们。
     *
     * 这是 I7 的前置防线：`lyric.path` 的唯一索引只能「撞了才知道」，
     * 而有了占用集合，扫描器不会为了一个必然失败的候选去走插入。
     */
    @Query("SELECT path FROM lyric")
    suspend fun allMatchedPaths(): List<String>
}
