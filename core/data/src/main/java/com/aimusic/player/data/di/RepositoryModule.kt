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
import com.aimusic.player.common.retry.RetryPolicy
import com.aimusic.player.common.util.RealSleeper
import com.aimusic.player.common.util.Sleeper
import com.aimusic.player.data.cache.RoomLlmCache
import com.aimusic.player.llm.LlmCache
import com.aimusic.player.llm.LlmConfig
import com.aimusic.player.llm.LlmConfigProvider
import com.aimusic.player.llm.LlmNormalizer
import com.aimusic.player.llm.ProtocolKind
import com.aimusic.player.llm.buildLlmNormalizer
import com.aimusic.player.llm.cache.DirectoryFingerprint
import com.aimusic.player.llm.directNormalizer
import com.aimusic.player.llm.prompt.PromptBuilder
import com.aimusic.player.llm.prompt.PromptResources
import com.aimusic.player.data.analysis.AnalysisOrchestrator
import com.aimusic.player.data.analysis.AnalysisRunRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    fun provideAnalysisTrigger(
        orchestrator: AnalysisOrchestrator,
        @ApplicationScope scope: CoroutineScope,
    ): AnalysisTrigger = AnalysisTrigger { runId ->
        // 冷 Flow 由应用级作用域独占驱动：界面离开也不影响它跑完；界面看 state / progress 两个热镜像。
        scope.launch { orchestrator.analyzePending(runId).collect {} }
    }

    @Provides
    @Singleton
    fun provideAnalysisRunRepository(db: MusicDatabase): AnalysisRunRepository = AnalysisRunRepository(db)

    @Provides
    @Singleton
    fun provideAnalysisOrchestrator(
        db: MusicDatabase,
        metadataReader: MetadataReader,
        normalizer: LlmNormalizer,
    ): AnalysisOrchestrator = AnalysisOrchestrator(db, metadataReader, normalizer)

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
        analysisTrigger: AnalysisTrigger,
        lyricHandoff: LyricHandoff,
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
        // 原先这两处**没传**，于是默认值落到了空 SAM —— 扫描完永不触发分析（潜伏 bug，Task 8 修）
        analysisTrigger = analysisTrigger,
        lyricHandoff = lyricHandoff,
    )

    // ---------- Phase 4：LLM 归一化链（P3-T8/T9 定） ----------

    @Provides
    @Singleton
    fun provideSleeper(): Sleeper = RealSleeper

    @Provides
    @Singleton
    fun providePromptResources(): PromptResources = PromptResources()

    @Provides
    @Singleton
    fun providePromptBuilder(resources: PromptResources): PromptBuilder =
        PromptBuilder(resources, maxTagsPerCategory = 2)

    /** ⚠ 临时桥接：P4 设置页落地前配置取不到真值（baseUrl/key 全空）——只保证图可启动，不能真发请求。P4 落地时**替换**本绑定。 */
    @Provides
    @Singleton
    fun provideLlmConfigProvider(
        settings: SettingsRepository,
        apiKeyStore: ApiKeyStore,
        @ApplicationScope scope: CoroutineScope,
    ): LlmConfigProvider = SettingsLlmConfigProvider(settings, apiKeyStore, scope)

    @Provides
    @Singleton
    fun provideLlmCache(db: MusicDatabase): LlmCache = RoomLlmCache(db.llmCacheDao())

    @Provides
    @Singleton
    fun provideLlmNormalizer(
        configProvider: LlmConfigProvider,
        promptBuilder: PromptBuilder,
        cache: LlmCache,
        sleeper: Sleeper,
    ): LlmNormalizer = buildLlmNormalizer(
        direct = directNormalizer(configProvider, promptBuilder),
        cache = cache,
        model = configProvider.current().model,
        promptHash = promptBuilder::promptHash,
        dirFingerprint = { req -> DirectoryFingerprint.of(req.categories, req.tags) },
        policy = RetryPolicy(maxRetries = configProvider.current().maxRetries),
        sleeper = sleeper,
    )
}