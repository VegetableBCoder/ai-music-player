package com.aimusic.player.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * LLM 归一化结果缓存：内容寻址，**不参与业务级联删除**
 * （删除歌曲不清缓存 —— 它是可复用资产，见 03 §3.8）。
 */
@Entity(tableName = "llm_cache")
data class LlmCacheEntity(
    @PrimaryKey @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "result_json") val resultJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
