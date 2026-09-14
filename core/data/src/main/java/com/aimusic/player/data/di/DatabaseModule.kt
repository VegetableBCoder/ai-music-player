package com.aimusic.player.data.di

import android.content.Context
import androidx.room.Room
import com.aimusic.player.data.db.MusicDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Room 库的内存实现与生产实现只在文件名上不同（测试用 `inMemoryDatabaseBuilder`）。 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * 文件名**一旦发布就不能改**：改名等于丢用户已有数据。表结构变化走版本迁移
     * （`03 §2` 的 schema 导出即为此服务）。
     */
    private const val DB_NAME = "ai_music_player.db"

    @Provides
    @Singleton
    fun provideMusicDatabase(@ApplicationContext context: Context): MusicDatabase =
        Room.databaseBuilder(context, MusicDatabase::class.java, DB_NAME).build()

    // DAO 由 MusicDatabase 派生；本期消费者（编排器 / 仓储）都直接拿 MusicDatabase，
    // 故不逐个 @Provides —— 出现第一个注入 DAO 的消费者时再加（10 §5.4 的「13 个 DAO」）。
}
