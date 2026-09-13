package com.aimusic.player.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aimusic.player.data.dao.AnalysisRunDao
import com.aimusic.player.data.dao.CategoryDao
import com.aimusic.player.data.dao.LlmCacheDao
import com.aimusic.player.data.dao.LyricDao
import com.aimusic.player.data.dao.MusicFileDao
import com.aimusic.player.data.dao.PlayHistoryDao
import com.aimusic.player.data.dao.PlaybackStateDao
import com.aimusic.player.data.dao.QueueDao
import com.aimusic.player.data.dao.ScanSourceDao
import com.aimusic.player.data.dao.SongArtistDao
import com.aimusic.player.data.dao.SongDao
import com.aimusic.player.data.dao.TagDao
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.entity.AnalysisRunFileCrossRef
import com.aimusic.player.data.entity.CategoryEntity
import com.aimusic.player.data.entity.EntityTagCrossRef
import com.aimusic.player.data.entity.LlmCacheEntity
import com.aimusic.player.data.entity.LyricEntity
import com.aimusic.player.data.entity.MusicFileEntity
import com.aimusic.player.data.entity.PlayHistoryEntity
import com.aimusic.player.data.entity.PlaybackStateEntity
import com.aimusic.player.data.entity.QueueItemEntity
import com.aimusic.player.data.entity.ScanSourceEntity
import com.aimusic.player.data.entity.SongArtistEntity
import com.aimusic.player.data.entity.SongEntity
import com.aimusic.player.data.entity.TagEntity

/**
 * 14 张业务表（`03 §2.2`）。DAO 访问器在 2b 按行为逐个加入 —— 先写 14 个没人测的
 * 接口会违反「生产代码必须先有失败测试」。
 */
@Database(
    entities = [
        SongEntity::class,
        SongArtistEntity::class,
        MusicFileEntity::class,
        CategoryEntity::class,
        TagEntity::class,
        EntityTagCrossRef::class,
        LyricEntity::class,
        QueueItemEntity::class,
        PlaybackStateEntity::class,
        PlayHistoryEntity::class,
        AnalysisRunEntity::class,
        AnalysisRunFileCrossRef::class,
        ScanSourceEntity::class,
        LlmCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MusicDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao

    abstract fun tagDao(): TagDao

    abstract fun categoryDao(): CategoryDao

    abstract fun musicFileDao(): MusicFileDao

    abstract fun lyricDao(): LyricDao

    abstract fun songArtistDao(): SongArtistDao

    abstract fun queueDao(): QueueDao

    abstract fun playbackStateDao(): PlaybackStateDao

    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun analysisRunDao(): AnalysisRunDao

    abstract fun scanSourceDao(): ScanSourceDao

    abstract fun llmCacheDao(): LlmCacheDao
}
