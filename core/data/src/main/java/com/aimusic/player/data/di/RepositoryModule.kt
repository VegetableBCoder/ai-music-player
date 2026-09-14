package com.aimusic.player.data.di

import android.content.Context
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.deletion.DeletionService
import com.aimusic.player.data.scan.AnalysisTrigger
import com.aimusic.player.data.scan.LyricHandoff
import com.aimusic.player.data.scan.ScanOrchestrator
import com.aimusic.player.data.scan.ScanSourceRepository
import com.aimusic.player.data.scan.ScanSourceRepositoryImpl
import com.aimusic.player.data.settings.ApiKeyStore
import com.aimusic.player.data.settings.SettingsRepository
import com.aimusic.player.storage.MediaStoreAudioSource
import com.aimusic.player.storage.MetadataReader
import com.aimusic.player.storage.StorageAccessChecker
import com.aimusic.player.storage.StorageSource
import com.aimusic.player.storage.di.PrimaryStorageRoot
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据层的装配（`10 §5.4`）。
 *
 * 与存储层同理用 `@Provides`：这些类都是先写好、再装配的（`ScanOrchestrator` 有 14 个
 * 构造参数且带可测性用的默认值），给它们加 `@Inject` 会污染已经被独立测试过的构造契约。
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    /**
     * 设置仓储必须是**单例**。
     *
     * `EncryptedSharedPreferences`（`ApiKeyStore`）同一个文件在一个进程里只能有一个实例，
     * 多建一个会读不回数据（真机验证过，见 `01 §2.2` 第 20 条）；DataStore 同理，两个实例
     * 会互相抢文件。
     */
    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideApiKeyStore(@ApplicationContext context: Context): ApiKeyStore = ApiKeyStore(context)

    @Provides
    @Singleton
    fun provideScanSourceRepository(
        db: MusicDatabase,
        storage: StorageSource,
    ): ScanSourceRepository = ScanSourceRepositoryImpl(db, storage)

    @Provides
    @Singleton
    fun provideDeletionService(db: MusicDatabase, storage: StorageSource): DeletionService =
        DeletionService(db, storage)

    /**
     * 交棒接口在 Phase 3 是**空实现**：真实的分析器（`05`）与歌词匹配（`08`）还不存在。
     *
     * 用窄接口而不是让 `ScanOrchestrator` 直接依赖 `AnalysisOrchestrator`，就是为了这一刻 —— 
     * 本期状态机止于 `ANALYZING`，Phase 4/7 只换这两处绑定，编排器不用动。
     */
    @Provides
    @Singleton
    fun provideAnalysisTrigger(): AnalysisTrigger = AnalysisTrigger {}

    @Provides
    @Singleton
    fun provideLyricHandoff(): LyricHandoff = LyricHandoff {}

    @Provides
    @Singleton
    fun provideScanOrchestrator(
        @ApplicationContext context: Context,
        storage: StorageSource,
        metadataReader: MetadataReader,
        accessChecker: StorageAccessChecker,
        mediaStoreAudio: MediaStoreAudioSource,
        db: MusicDatabase,
        deletionService: DeletionService,
        settings: SettingsRepository,
        scanSources: ScanSourceRepository,
        @PrimaryStorageRoot primaryRoot: String,
    ): ScanOrchestrator = ScanOrchestrator(
        context = context,
        storage = storage,
        metadataReader = metadataReader,
        accessChecker = accessChecker,
        mediaStoreAudio = mediaStoreAudio,
        db = db,
        deletionService = deletionService,
        settings = settings,
        scanSources = scanSources,
        primaryRoot = primaryRoot,
    )
}
