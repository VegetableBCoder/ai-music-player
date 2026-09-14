package com.aimusic.player.storage.di

import android.content.Context
import android.os.Environment
import com.aimusic.player.storage.AndroidMediaStoreAudioSource
import com.aimusic.player.storage.AndroidMediaExtractorPool
import com.aimusic.player.storage.AndroidStorageAccessChecker
import com.aimusic.player.storage.FileStorageSource
import com.aimusic.player.storage.MediaStoreAudioSource
import com.aimusic.player.storage.MetadataReader
import com.aimusic.player.storage.MmrMetadataReader
import com.aimusic.player.storage.StorageAccessChecker
import com.aimusic.player.storage.StorageSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 主存储根的限定符。
 *
 * `FileStorageSource` 与 `ScanOrchestrator` 都要它（`PathNormalizer` 的基准根，`04 §4.6`），
 * 但裸 `String` 会被其他 String 依赖误注，故加限定符。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PrimaryStorageRoot

/**
 * 存储层的装配（`10 §5.4`）。
 *
 * 用 `@Provides` 而不是 `@Binds`：这几个实现是 3a/3b 就写好的**无注解构造**类
 * （`FileStorageSource(primaryRoot)` 等），为它们加 `@Inject` 会把 DI 反向钉进被独立测试
 * 的纯逻辑里。装配的责任留在装配层。
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    /**
     * 生产装配**启用** MediaExtractor 时长兜底（`04 §4.4` 的回退链：MMR → MediaExtractor）。
     *
     * 3b 的默认值是 `MediaExtractorPool { null }`（不兜底）—— 那是给 JVM 测试的默认，
     * 而 `AndroidMediaExtractorPool` 存在的意义就是在真机上补 MMR 拿不到时长的那些文件。
     * 不接上它，这些文件的时长会恒为「未知」。
     */
    @Provides
    @Singleton
    fun provideMetadataReader(): MetadataReader = MmrMetadataReader(
        durationFallback = AndroidMediaExtractorPool(),
    )

    @Provides
    @Singleton
    fun provideStorageSource(@PrimaryStorageRoot primaryRoot: String): StorageSource =
        FileStorageSource(primaryRoot = primaryRoot)

    @Provides
    @Singleton
    fun provideMediaStoreAudioSource(@ApplicationContext context: Context): MediaStoreAudioSource =
        AndroidMediaStoreAudioSource(context)

    @Provides
    @Singleton
    fun provideStorageAccessChecker(): StorageAccessChecker = AndroidStorageAccessChecker()

    /** 主存储根：物理上就是 `/storage/emulated/0`，但取值必须走 API，不硬编码。 */
    @Provides
    @Singleton
    @PrimaryStorageRoot
    fun providePrimaryStorageRoot(): String = Environment.getExternalStorageDirectory().absolutePath
}
