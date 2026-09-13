package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aimusic.player.data.entity.PlaybackStateEntity

@Dao
abstract class PlaybackStateDao {

    @Query("SELECT * FROM playback_state WHERE id = 0")
    abstract suspend fun state(): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(state: PlaybackStateEntity)

    /**
     * 只改会话字段，其余列（当前项、模式、位置）保持不变。
     *
     * 会话开始与结算共用它：开始写 `sessionStartedAt = now`，结算写 `null`（`07 §4.6`）。
     * 「只写 id = 0」由本 DAO 保证 —— 数据库层没有 `CHECK (id = 0)`（见 03 §2.4）。
     */
    @Transaction
    open suspend fun putSession(sessionStartedAt: Long?, sessionMaxPositionMs: Long, updatedAt: Long) {
        val current = state()
        put(
            current?.copy(
                sessionStartedAt = sessionStartedAt,
                sessionMaxPositionMs = sessionMaxPositionMs,
                updatedAt = updatedAt,
            ) ?: PlaybackStateEntity(
                id = 0,
                sessionStartedAt = sessionStartedAt,
                sessionMaxPositionMs = sessionMaxPositionMs,
                updatedAt = updatedAt,
            ),
        )
    }
}
