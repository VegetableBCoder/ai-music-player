package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.aimusic.player.data.entity.QueueItemEntity

@Dao
abstract class QueueDao {

    @Query("SELECT * FROM queue_item ORDER BY position")
    abstract suspend fun all(): List<QueueItemEntity>

    @Query("SELECT MAX(position) FROM queue_item")
    abstract suspend fun maxPosition(): Int?

    @Query("DELETE FROM queue_item")
    abstract suspend fun clear()

    @Query("DELETE FROM queue_item WHERE id = :id")
    abstract suspend fun remove(id: Long)

    @Insert
    abstract suspend fun insert(item: QueueItemEntity): Long

    @Query("SELECT position FROM queue_item WHERE id = :id")
    abstract suspend fun positionOf(id: Long): Int?

    @Query("UPDATE queue_item SET position = position + 1 WHERE position > :position")
    abstract suspend fun shiftAfter(position: Int)

    /** 添加到队列：追加到末尾（`03 §4.6`）。 */
    @Transaction
    open suspend fun appendToQueue(entityId: Long, now: Long) {
        insert(
            QueueItemEntity(
                position = (maxPosition() ?: -1) + 1,
                entityId = entityId,
                isForced = false,
                createdAt = now,
            ),
        )
    }

    /**
     * 下一首播放：插到当前项**之后**。
     *
     * 用整数区间而不是浮点 ε：把当前位置之后的项整体 +1，腾出的槽位给新项，
     * 多次插队自然形成 LIFO（后插入者排更前，`03 §4.6`）。
     */
    @Transaction
    open suspend fun insertNext(entityId: Long, currentQueueItemId: Long?, now: Long) {
        val currentPosition = currentQueueItemId?.let { positionOf(it) } ?: -1
        shiftAfter(currentPosition)
        insert(
            QueueItemEntity(
                position = currentPosition + 1,
                entityId = entityId,
                isForced = true,
                createdAt = now,
            ),
        )
    }

    @Transaction
    open suspend fun clearQueue() = clear()
}
