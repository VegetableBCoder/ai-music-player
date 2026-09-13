package com.aimusic.player.probe

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 0 技术探针：验证 Hilt Gradle 插件 + KSP 注解处理在 AGP 9 下能否正常生成 DI 代码。
 */
@Module
@InstallIn(SingletonComponent::class)
object ProbeModule {

    @Provides
    @Singleton
    fun provideProbeDatabase(@ApplicationContext context: Context): ProbeDatabase =
        Room.databaseBuilder(context, ProbeDatabase::class.java, "probe.db").build()

    @Provides
    fun provideProbeDao(database: ProbeDatabase): ProbeDao = database.probeDao()
}
