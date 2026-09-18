package com.aimusic.player.playback.di

import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.di.ApplicationScope
import com.aimusic.player.playback.NoopPlaybackController
import com.aimusic.player.playback.PlaybackController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/**
 * 播放器装配（`07 §7`）。
 *
 * 用 `@Provides` 而不是在 `NoopPlaybackController` 上标 `@Inject` 构造器：作用域要的是
 * `@ApplicationScope` 限定的那个（`09 §7`「长任务可后台进行」），而 `@Inject` 构造器拿不到限定符。
 *
 * **Phase 6 换真实实现时只改本文件** —— 把提供类型从 `NoopPlaybackController` 换成
 * `PlaybackControllerImpl`，调用方（各 ViewModel）无需改动。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {

    @Provides
    @Singleton
    fun providePlaybackController(
        db: MusicDatabase,
        @ApplicationScope scope: CoroutineScope,
    ): PlaybackController = NoopPlaybackController(db = db, scope = scope)
}
