# Phase 4-4 · 分析编排与界面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把扫描提交后的「交棒空实现」换成真正的分析编排器（批量 20、逐文件进度、真实计数、幂等、可取消），并把「最近分析记录」与「设置」两屏接到真机上能跑通，让 Phase 3 遗留的「提交后没有下文」空窗闭环。

**Architecture:** `AnalysisOrchestrator`（`:core:data`）是分析的**唯一入口**：照 `ScanOrchestrator` 的形态做——单例 + `Mutex` 单 worker、`analyzePending(runId)` 返回**冷 Flow** 驱动工作、逐文件状态写库并同时镜像到热 `state: StateFlow`（UI 只读热流，冷流只被 AppScope 的触发器收集）。LLM 侧只认 `:core:llm` 的 `LlmNormalizer` 接口（装饰链 `Caching(Retrying(Direct))` 由 **P3** 装配，见其 Task 7 的 `buildLlmNormalizer` / `@Provides LlmNormalizer`）；本模块只负责「取文件 → 分批 → 置 ANALYZING → 调 normalize → 逐文件落库 / 计数 / 上报」。界面按 3e 定下的「有状态外壳 + 无状态 Content」拆两半，UI 测试只测 Content。

**Tech Stack:** Kotlin 2.2.10 / Java 17 / compileSdk 36 / minSdk 26 / coroutines 1.11.0 / Room 2.8.5（真机测试）/ Hilt + KSP / Compose + Material3 / MockK + Turbine + Truth（JVM）/ createComposeRule（真机 androidTest）。

**Spec:** docs/superpowers/specs/2026-09-15-phase4-llm-protocol-design.md

## Global Constraints

- 依赖守卫：`:core:data` **已允许** `:core:storage`/`:core:common`/**`:core:llm`**（放宽已拍板，决定 #2；Task 1 落地）；feature 之间禁止互赖；`:feature:mine` 当前可依赖 `:core:ui`/`:core:playback`/`:core:data`/`:core:llm`/`:core:storage`/`:core:common`。
- Kotlin/Java 17、compileSdk 36、minSdk 26；coroutines 显式钉住 `1.11.0`（`libs.versions.toml` 的 `kotlinxCoroutines`）。
- KSP + AGP 9 需要 `android.disallowKotlinSourceSets=false`（`gradle.properties` 已开，不要动）。
- 注释与提交信息一律中文。
- 提交信息末尾带 `Co-authored-by: CommandCodeBot <noreply@commandcode.ai>`。
- 不用通用 `Result` 包装：每个操作有自己的 sealed 结果 / 或直接返回领域类型。
- 文案只在 `common/error/ErrorText.kt` 里取、不新造（本计划若要新键，集中在 `ErrorText`，**且字面只能取自需求文档 / `11 §5.1` 的原文；没有原文的不得新造** —— 决定 #4，见 Task 9 标注）。
- 屏幕拆「有状态外壳 `XxxScreen` + 无状态 `XxxContent`」，**不测 Content 以外的渲染**（`09 §8` 落地补充）。
- UI 测试跑真机 `createComposeRule`（`feature/*/src/androidTest`）。
- Room 测试跑真机（`core/data/src/androidTest`，内存库）。
- androidTest 的反引号函数名**不能含空格**（`build.gradle.kts` 有配置期检查）。
- 真机测试前先唤醒设备：`adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard`。

---

## 依赖交接：由 P1 / P2 / P3 提供的类型（期望的确切签名）

本计划**不实现**下列类型，只以它们为契约；若签名与此处不符，先对齐再动手。

```kotlin
// ——— 由 P1（协议层，:core:llm）提供 ———
enum class ProtocolKind { OPENAI, RESPONSES, ANTHROPIC }   // com.aimusic.player.llm.ProtocolKind
                                                          // （协议层内部另有 LlmCall / LlmHttpResult / ProtocolAdapter，
                                                          //   由 P1 使用；P4 不直接消费）
interface LlmNormalizer {                                   // com.aimusic.player.llm.LlmNormalizer
    /** 一次一批；返回与入参**等长、按索引对齐**。 */
    suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome>
}
fun LlmFailureKind.toFailureKind(): FailureKind             // → com.aimusic.player.common.error.FailureKind
                                                            // TIMEOUT 归入 NETWORK（05 §6）
data class LlmConfig(
    val protocol: String, val baseUrl: String, val model: String, val apiKey: String,
    val supportsJsonSchema: Boolean, val maxRetries: Int,
    val batchSize: Int = 20, val maxTokens: Int = 8_192,
    val connectTimeoutMs: Long = 15_000, val readTimeoutMs: Long = 90_000,
    val callTimeoutMs: Long = 120_000, val maxTagsPerCategory: Int = 2,
)

// ——— 由 P2（prompt / parser / `:core:llm` 契约类型）提供 ———
data class TagRef(val name: String, val category: String)   // com.aimusic.player.llm.TagRef（两字段，决定 #3）
data class NormalizeRequest(
    val fileName: String,
    val metadata: AudioMetadata?,      // com.aimusic.player.common.model.AudioMetadata（决定 #1）
    val categories: List<String>,
    val tags: List<TagRef>,
)
sealed interface NormalizeOutcome {
    data class Success(val result: NormalizeResult) : NormalizeOutcome   // NormalizeResult 已在 :core:common
    data class RateLimited(val retryAfterMs: Long?) : NormalizeOutcome
    data class Failure(val kind: LlmFailureKind, val cause: Throwable?) : NormalizeOutcome
}
enum class LlmFailureKind { NETWORK, AUTH, SERVER, INVALID_OUTPUT, TIMEOUT }

// ——— 由 P3（缓存 / 退避装饰链 / Room 实现）提供 ———
interface LlmCache {                                        // com.aimusic.player.llm.LlmCache（实现在 :core:data）
    suspend fun get(key: String): NormalizeResult?
    suspend fun put(key: String, result: NormalizeResult)
}
fun buildLlmNormalizer(                                     // 装饰链装配唯一入口（P3 Task 7）：Caching(Retrying(Direct))
    direct: LlmNormalizer, cache: LlmCache, model: String,
    promptHash: () -> String, dirFingerprint: (NormalizeRequest) -> String,
    policy: RetryPolicy, sleeper: Sleeper = RealSleeper,
    random: Random = Random.Default, onBackoff: (BackoffEvent) -> Unit = {},
): LlmNormalizer
class RoomLlmCache(dao: LlmCacheDao, now: () -> Long = System::currentTimeMillis) : LlmCache
// SongDao.attachAnalysisResult(fileId, result, now) 已存在（Phase 3 已合入），无需改动
```

**已存在、本计划直接复用**（不用新建）：`SongDao.attachAnalysisResult`、`MusicFileDao.pendingForAnalysis/setStatus/entityIdOf`、`AnalysisRunDao.createRun/linkFiles/latest/observeRunFiles/setStatus`、`MetadataReader`、`AnalysisStatus`、`RunStatus`、`FailureKind`、`ErrorText`。

**本计划负责补齐**（这些不在 P1/P2/P3 范围；签名以本文件 Task 1 为准）：`AnalysisRunDao` 的 `observeLatest/bumpAnalyzedOk/bumpFailed/finish/unlinkFiles`、`CategoryDao.currentNames`、`TagDao.currentTagRefs`、`SettingsRepository` 的 `llm_max_tokens` 等 key。

---

### Task 1: 放宽依赖守卫 + 补齐编排器所需的 data 层访问器

**Files:**
- Modify: `build.gradle.kts`（`:core:data` 白名单）
- Modify: `core/data/build.gradle.kts`（声明 `:core:llm`）
- Modify: `core/data/src/main/java/com/aimusic/player/data/dao/AnalysisRunDao.kt`
- Modify: `core/data/src/main/java/com/aimusic/player/data/dao/CategoryDao.kt`
- Modify: `core/data/src/main/java/com/aimusic/player/data/dao/TagDao.kt`
- Create: `core/data/src/androidTest/java/com/aimusic/player/data/dao/AnalysisRunDaoTest.kt`

**Interfaces:**
```kotlin
// AnalysisRunDao（新增）
fun observeLatest(): Flow<AnalysisRunEntity?>
suspend fun bumpAnalyzedOk(runId: Long)
suspend fun bumpFailed(runId: Long)
suspend fun finish(runId: Long, status: RunStatus, finishedAt: Long)
suspend fun unlinkFiles(runId: Long, fileIds: List<Long>): Int
// CategoryDao（新增）
suspend fun currentNames(): List<String>
// TagDao（新增）
suspend fun currentTagRefs(): List<TagRef>   // com.aimusic.player.llm.TagRef（两字段 name/category，由 P2 提供）
```

- [ ] **Step 1**: 放宽守卫并声明依赖。

  `build.gradle.kts`：
  ```kotlin
  // 原先：":core:data" to setOf(":core:storage", ":core:common"),
  // Phase 4-4：分析编排器（:core:data）要实现 :core:llm 的 LlmCache、并依赖 LlmNormalizer 契约 ——
  // 已拍板放开（决定 #2）；spec §2 明确「接口留在低层、实现在高层」，与 StorageSource 同一路数。
  // 先例：3e 已为 :core:ui 同理由放宽过；改代码前先改文档（02 §2 落地补充）。
  ":core:data" to setOf(":core:storage", ":core:common", ":core:llm"),
  ```
  `core/data/build.gradle.kts` 的 `dependencies` 里加：
  ```kotlin
  implementation(project(":core:llm"))
  ```

- [ ] **Step 2**: 跑守卫，确认没违反。

  ```bash
  ./gradlew :core:data:compileDebugKotlin
  ```
  预期：`BUILD SUCCESSFUL`（此时只用声明、还没 import，编译通过即说明守卫放行）。

- [ ] **Step 3**: 写**失败**测试（真机 Room）。

  `core/data/src/androidTest/java/com/aimusic/player/data/dao/AnalysisRunDaoTest.kt`：
  ```kotlin
  @RunWith(AndroidJUnit4::class)
  class AnalysisRunDaoTest {
      private lateinit var db: MusicDatabase
      private lateinit var runDao: AnalysisRunDao

      @Before
      fun setUp() {
          val context = ApplicationProvider.getApplicationContext<Context>()
          db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
              .allowMainThreadQueries().build()
          runDao = db.analysisRunDao()
      }

      @After fun tearDown() = db.close()

      @Test
      fun `计数逐次累加_结束时写状态与完成时刻`() = runBlocking {
          val runId = runDao.createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 100L))

          runDao.bumpAnalyzedOk(runId); runDao.bumpAnalyzedOk(runId); runDao.bumpFailed(runId)
          runDao.finish(runId, RunStatus.ABORTED, finishedAt = 200L)

          val row = runDao.latest()!!
          assertThat(row.analyzedOk).isEqualTo(2)
          assertThat(row.failedCount).isEqualTo(1)
          assertThat(row.status).isEqualTo(RunStatus.ABORTED)
          assertThat(row.finishedAt).isEqualTo(200L)
      }

      @Test
      fun `移除记录只删批次关联_不动文件行`() = runBlocking {
          val runId = runDao.createRun(AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = 1L))
          val fileId = db.musicFileDao().insertIgnoreAll(listOf(fileEntity(path = "/a.mp3"))) .first()
          runDao.linkFiles(listOf(AnalysisRunFileCrossRef(runId, fileId)))

          val removed = runDao.unlinkFiles(runId, listOf(fileId))

          assertThat(removed).isEqualTo(1)
          assertThat(runDao.observeRunFiles(runId).first()).isEmpty()
          assertThat(db.musicFileDao().findByPath("/a.mp3")).isNotNull()
      }
  }
  ```
  （`fileEntity(path)` 用 `MusicFileEntity(path = path, fileName = "a.mp3", size = 1L, format = "mp3", addedAt = 0L)`。）

- [ ] **Step 4**: 跑测试，确认**失败**（编译不过就是失败）。

  ```bash
  adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisRunDaoTest*'
  ```
  预期：`e: ...AnalysisRunDao.kt: Unresolved reference: bumpAnalyzedOk`（及 `bumpFailed` / `finish` / `unlinkFiles` / `observeLatest`）。

- [ ] **Step 5**: 最小实现——只加 Task 1 Interfaces 里列的方法。

  `AnalysisRunDao.kt` 追加：
  ```kotlin
  /** 「最近分析记录」页订阅：新批次建好后要自动推给 UI（`09 §3.2.10`）。 */
  @Query("SELECT * FROM analysis_run ORDER BY started_at DESC LIMIT 1")
  suspend fun observeLatest(): Flow<AnalysisRunEntity>

  /** 逐文件累加（§10：一个批 20 个里成功 18 失败 2 → 18/2）。 */
  @Query("UPDATE analysis_run SET analyzed_ok = analyzed_ok + 1 WHERE id = :runId")
  suspend fun bumpAnalyzedOk(runId: Long)

  @Query("UPDATE analysis_run SET failed_count = failed_count + 1 WHERE id = :runId")
  suspend fun bumpFailed(runId: Long)

  /** 结束：COMPLETED 或 ABORTED，两者都写 finished_at（05 §4.9）。 */
  @Query("UPDATE analysis_run SET status = :status, finished_at = :finishedAt WHERE id = :runId")
  suspend fun finish(runId: Long, status: RunStatus, finishedAt: Long)

  /** 「移除记录」：只解绑批次清单，**不删 music_file**（03 §4.4 的删除序列另有入口）。 */
  @Query("DELETE FROM analysis_run_file WHERE run_id = :runId AND file_id IN (:fileIds)")
  suspend fun unlinkFiles(runId: Long, fileIds: List<Long>): Int
  ```
  `CategoryDao.kt` 追加：
  ```kotlin
  /** 当前有效分类名（每批实时读取一次，非快照 —— `04 §3.3`、spec §10）。 */
  @Query("SELECT name FROM category ORDER BY sort_order, name")
  abstract suspend fun currentNames(): List<String>
  ```
  `TagDao.kt` 追加（import `com.aimusic.player.llm.TagRef`）：
  ```kotlin
  /** 当前有效标签全量（每批实时读取一次）。分类**名**投影为 `TagRef.category`（两字段契约，决定 #3）。 */
  @Query(
      """SELECT t.name AS name, c.name AS category
         FROM tag t JOIN category c ON c.id = t.category_id ORDER BY t.name""",
  )
  abstract suspend fun currentTagRefs(): List<TagRef>
  ```

- [ ] **Step 6**: 跑测试，确认通过。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisRunDaoTest*'
  ```
  预期：`BUILD SUCCESSFUL`，2 tests passed。

- [ ] **Step 7**: 提交。

  ```bash
  git add -A && git commit -m "build(data): 放宽 :core:data → :core:llm + 补齐分析所需 DAO 访问器

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 2: `AnalysisProgress` / `AnalysisSessionState` + 冷 Flow 骨架（批量 20、单 worker、置 ANALYZING）

**Files:**
- Create: `core/data/src/main/java/com/aimusic/player/data/analysis/AnalysisProgress.kt`
- Create: `core/data/src/main/java/com/aimusic/player/data/analysis/AnalysisOrchestrator.kt`
- Create: `core/data/src/androidTest/java/com/aimusic/player/data/analysis/AnalysisOrchestratorTest.kt`

**Interfaces:**
```kotlin
// AnalysisProgress.kt（逐字照 05 §3.6）
sealed interface AnalysisProgress {
    data class Started(val runId: Long, val total: Int) : AnalysisProgress
    data class FileUpdated(
        val fileId: Long, val fileName: String, val status: AnalysisStatus,
        val linkedEntityId: Long? = null, val error: String? = null,
    ) : AnalysisProgress
    data class Retrying(val fileId: Long, val attempt: Int, val delayMs: Long) : AnalysisProgress
    data class Running(val done: Int, val total: Int, val ok: Int, val failed: Int) : AnalysisProgress
    data class Finished(val runId: Long, val ok: Int, val failed: Int, val aborted: Boolean) : AnalysisProgress
}

// AnalysisSessionState.kt —— 热镜像：UI 只读它（冷 Flow 会被 AppScope 触发器独占）
@Immutable
data class AnalysisSessionState(
    val running: Boolean = false,
    val runId: Long? = null,
    val total: Int = 0, val done: Int = 0, val ok: Int = 0, val failed: Int = 0,
    val batchIndex: Int = 0, val batchCount: Int = 0,
    val retrying: AnalysisProgress.Retrying? = null,
    val aborted: Boolean = false,
) { val hasProgress: Boolean get() = total > 0 }

// AnalysisOrchestrator.kt
class AnalysisOrchestrator(
    private val db: MusicDatabase,
    private val metadataReader: MetadataReader,
    private val normalizer: LlmNormalizer,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,                 // 20
    private val progressThrottleMs: Long = DEFAULT_PROGRESS_THROTTLE_MS, // 250
    private val now: () -> Long = System::currentTimeMillis,
) {
    val state: StateFlow<AnalysisSessionState>
    val progress: SharedFlow<AnalysisProgress>     // 热镜像：逐文件事件 + 取消后的 Finished 都能收到
    fun analyzePending(runId: Long): Flow<AnalysisProgress>
    fun cancel()
}
```

- [ ] **Step 1**: 写**失败**测试（真机 Room + 假 `LlmNormalizer`）。

  `AnalysisOrchestratorTest.kt`（片段；假 normalizer 定义在同文件，因为 `:core:testing` 不依赖 `:core:llm`）：
  ```kotlin
  /** 记录每次收到的请求，按预设脚本逐个回答。 */
  private class FakeNormalizer(
      private val answer: (List<NormalizeRequest>) -> List<NormalizeOutcome>,
  ) : LlmNormalizer {
      val calls = mutableListOf<List<NormalizeRequest>>()
      override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
          calls += requests
          return answer(requests)
      }
  }

  @Test
  fun `一批20个只发一次请求_逐步上报FileUpdated`() = runBlocking {
      givenPendingFiles(20)
      val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }
      val orchestrator = build(fake)
      val runId = db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, 0L))

      val events = orchestrator.analyzePending(runId).toList()

      assertThat(fake.calls).hasSize(1)
      assertThat(fake.calls.single()).hasSize(20)
      assertThat(events.first()).isEqualTo(AnalysisProgress.Started(runId, 20))
      assertThat(events.filterIsInstance<AnalysisProgress.FileUpdated>()
          .count { it.status == AnalysisStatus.ANALYZING }).isEqualTo(20)
      assertThat(events.last()).isEqualTo(AnalysisProgress.Finished(runId, 20, 0, aborted = false))
  }

  @Test
  fun `21个文件分成两批_最后一批是余数`() = runBlocking {
      givenPendingFiles(21)
      val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }
      val orchestrator = build(fake)
      val runId = db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, 0L))

      orchestrator.analyzePending(runId).toList()

      assertThat(fake.calls.map { it.size }).containsExactly(20, 1).inOrder()
  }
  ```
  辅助：`private fun successFor(name: String) = NormalizeOutcome.Success(NormalizeResult(canonicalTitle = name, artists = listOf("周杰伦"), tagAssignments = emptyList()))`。

- [ ] **Step 2**: 跑测试，确认**失败**。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisOrchestratorTest*'
  ```
  预期：`Unresolved reference: AnalysisOrchestrator` / `AnalysisProgress`。

- [ ] **Step 3**: 最小实现 `AnalysisProgress.kt`（接口块逐字落），并写 `AnalysisOrchestrator.kt` 的骨架：

  ```kotlin
  class AnalysisOrchestrator(
      private val db: MusicDatabase,
      private val metadataReader: MetadataReader,
      private val normalizer: LlmNormalizer,
      private val batchSize: Int = DEFAULT_BATCH_SIZE,
      private val progressThrottleMs: Long = DEFAULT_PROGRESS_THROTTLE_MS,
      private val now: () -> Long = System::currentTimeMillis,
  ) {
      private val mutex = Mutex()
      private val stateFlow = MutableStateFlow(AnalysisSessionState())
      val state: StateFlow<AnalysisSessionState> = stateFlow.asStateFlow()

      private val progressSink = MutableSharedFlow<AnalysisProgress>(extraBufferCapacity = 64)
      val progress: SharedFlow<AnalysisProgress> = progressSink.asSharedFlow()

      private var runningJob: Job? = null

      /** 分析一批文件（runId 由建批次方给出，`05 §4.9`）。冷 Flow；非 suspend。 */
      fun analyzePending(runId: Long): Flow<AnalysisProgress> = flow {
          if (!mutex.tryLock()) return@flow      // 单 worker：已有分析在跑时不排队、直接结束
          try {
              val files = db.musicFileDao().pendingForAnalysis()   // 只取 UNANALYZED / FAILED（I4 入口过滤）
              emitAll(driveLocked(runId, files))
          } finally {
              mutex.unlock()
          }
      }.flowOn(Dispatchers.IO)

      fun cancel() {
          runningJob?.cancel()
      }

      private fun driveLocked(runId: Long, files: List<MusicFileEntity>): Flow<AnalysisProgress> = flow {
          runningJob = currentCoroutineContext()[Job]
          val total = files.size
          var done = 0; var ok = 0; var failed = 0; var lastEmit = 0L

          publish(AnalysisProgress.Started(runId, total))
          stateFlow.update { it.copy(running = true, runId = runId, total = total, done = 0, ok = 0, failed = 0) }

          files.chunked(batchSize).forEachIndexed { batchIndex, batch ->
              currentCoroutineContext().ensureActive()                 // 取消在**批次边界**生效
              stateFlow.update { it.copy(batchIndex = batchIndex + 1, batchCount = (total + batchSize - 1) / batchSize) }

              pendingOf(batch).forEach { file ->
                  db.musicFileDao().setStatus(file.id, AnalysisStatus.ANALYZING, null, null)
                  publish(AnalysisProgress.FileUpdated(file.id, file.fileName, AnalysisStatus.ANALYZING))
              }
              // 逐文件落状态 / 计数见 Task 3、4
          }

          db.analysisRunDao().finish(runId, RunStatus.COMPLETED, now())
          stateFlow.update { it.copy(running = false) }
          publish(AnalysisProgress.Finished(runId, ok, failed, aborted = false))
      }.flowOn(Dispatchers.IO)

      private suspend fun FlowCollector<AnalysisProgress>.publish(event: AnalysisProgress) {
          progressSink.tryEmit(event)
          emit(event)
      }
  ```
  同时写 `pendingOf(batch) = batch.filter { it.analysisStatus != AnalysisStatus.LINKED }`（I4 兜底）与 `DEFAULT_BATCH_SIZE = 20` / `DEFAULT_PROGRESS_THROTTLE_MS = 250L`。

- [ ] **Step 4**: 跑测试，确认通过。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisOrchestratorTest*'
  ```
  预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5**: 提交。

  ```bash
  git add -A && git commit -m "feat(data): 分析编排器骨架 —— 冷 Flow 单 worker、批量 20、逐文件 ANALYZING

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 3: 成功路径 —— 每批实时读目录、`attachAnalysisResult` 同事务、`analyzed_ok` 逐文件累加

**Files:**
- Modify: `core/data/src/main/java/com/aimusic/player/data/analysis/AnalysisOrchestrator.kt`
- Modify: `core/data/src/androidTest/java/com/aimusic/player/data/analysis/AnalysisOrchestratorTest.kt`

**Interfaces:**
```kotlin
// 本 Task 完成后 driveLocked 内部行为：
//   每批读一次 db.categoryDao().currentNames() 与 db.tagDao().currentTagRefs()（非快照）
//   每批构造 NormalizeRequest（metadata 走 metadataReader.read(FileRef(path, fileName, size, 0))）
//   Success → db.withTransaction { songDao().attachAnalysisResult(fileId, result, now()); runDao().bumpAnalyzedOk(runId) }
//           → publish FileUpdated(..., LINKED, linkedEntityId = fileDao.entityIdOf(fileId))
```

- [ ] **Step 1**: 写**失败**测试（目录每批实时读 + 成功计数 + 幂等）。

  ```kotlin
  @Test
  fun `目录每批实时读取_不是快照`() = runBlocking {
      givenPendingFiles(21)
      givenCategory("音乐类型"); givenTag("流行", "音乐类型")
      val seenCategories = mutableListOf<List<String>>()
      val fake = FakeNormalizer { reqs -> seenCategories += reqs[0].categories; reqs.map { successFor(it.fileName) } }
      // 第一批回答前插入一个新分类，第二批必须能看见它
      val orchestrator = build(fake)
      val runId = db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, 0L))

      orchestrator.analyzePending(runId).toList()

      // 20 个一批 + 1 个余数：两次请求各读一次，第一次在读后新增分类
      assertThat(seenCategories).hasSize(2)
  }

  @Test
  fun `成功逐文件累加analyzed_ok_并把文件置LINKED`() = runBlocking {
      givenPendingFiles(3)
      givenCategory("音乐类型"); givenTag("流行", "音乐类型")
      val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }
      val orchestrator = build(fake)
      val runId = db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, 0L))

      val events = orchestrator.analyzePending(runId).toList()

      assertThat(db.analysisRunDao().latest()!!.analyzedOk).isEqualTo(3)
      assertThat(db.musicFileDao().pendingForAnalysis()).isEmpty()
      assertThat(events.filterIsInstance<AnalysisProgress.FileUpdated>()
          .last { it.status == AnalysisStatus.LINKED }.linkedEntityId).isNotNull()
  }

  @Test
  fun `已经LINKED的文件再次触发_零网络请求`() = runBlocking {   // G12 / I4
      givenPendingFiles(2)
      givenCategory("音乐类型"); givenTag("流行", "音乐类型")
      val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }
      val orchestrator = build(fake)
      val run1 = db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, 0L))
      orchestrator.analyzePending(run1).toList()
      val callsAfterFirst = fake.calls.size

      val run2 = db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, 0L))
      orchestrator.analyzePending(run2).toList()

      assertThat(fake.calls.size).isEqualTo(callsAfterFirst)   // 入口 pendingForAnalysis 已过滤掉 LINKED
  }
  ```

- [ ] **Step 2**: 跑测试，确认**失败**。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisOrchestratorTest*'
  ```
  预期：`expected 3 but was 0`（`analyzedOk`）与 `expected 0 but was 2`（空请求断言）。

- [ ] **Step 3**: 实现批次内的目录读取 + 请求构造 + 成功落库。

  在 `driveLocked` 的 `forEachIndexed` 里补：
  ```kotlin
  val categories = db.categoryDao().currentNames()      // 每批实时读一次（spec §10）
  val tags = db.tagDao().currentTagRefs()
  val reqs = pending.map { file ->
      NormalizeRequest(
          fileName = file.fileName,
          metadata = metadataReader.read(FileRef(file.path, file.fileName, file.size, 0)),
          categories = categories,
          tags = tags,
      )
  }
  val outcomes = normalizer.normalize(reqs)             // 与 reqs 等长、按索引对齐
  pending.forEachIndexed { i, file ->
      when (val outcome = outcomes[i]) {
          is NormalizeOutcome.Success -> {
              val entityId = db.withTransaction {                            // 挂靠 + 计数同事务（05 §4.9）
                  db.songDao().attachAnalysisResult(file.id, outcome.result, now())
                  db.analysisRunDao().bumpAnalyzedOk(runId)
                  db.musicFileDao().entityIdOf(file.id)
              }
              ok++
              publish(AnalysisProgress.FileUpdated(
                  file.id, file.fileName, AnalysisStatus.LINKED, linkedEntityId = entityId))
          }
          else -> Unit   // Task 4 处理
      }
      done++
      stateFlow.update { it.copy(done = done, ok = ok, failed = failed) }
      val t = now()
      if (t - lastEmit >= progressThrottleMs || done == total) {   // 进度节流（05 §7.2）
          lastEmit = t
          publish(AnalysisProgress.Running(done, total, ok, failed))
      }
  }
  ```

- [ ] **Step 4**: 跑测试，确认通过。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisOrchestratorTest*'
  ```
  预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5**: 提交。

  ```bash
  git add -A && git commit -m "feat(data): 分析成功路径 —— 每批实时读目录、挂靠与计数同事务、逐文件累加

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 4: 失败路径 —— 整批调用失败同命运、条目级解析失败只连坐一个（`error_kind` / `failed_count`）

**Files:**
- Modify: `core/data/src/main/java/com/aimusic/player/data/analysis/AnalysisOrchestrator.kt`
- Modify: `core/data/src/androidTest/java/com/aimusic/player/data/analysis/AnalysisOrchestratorTest.kt`

**Interfaces:**
```kotlin
// `when (outcome)` 的剩余分支：
//   RateLimited（退避耗尽） → setStatus(FAILED, error = "RATE_LIMIT", kind = FailureKind.RATE_LIMIT.name)
//                            → runDao().bumpFailed(runId); failed++
//                            → publish FileUpdated(..., FAILED, error = FailureKind.RATE_LIMIT.name)
//   Failure(kind)           → kind.toFailureKind() → setStatus(FAILED, error = fk.name, kind = fk.name)
//                            → bumpFailed; failed++; publish FileUpdated(..., FAILED, error = fk.name)
// 整批同命运由**上游**保证：normalize 对调用失败会返回「每一项同一 Failure」（spec §9.1）；
// 编排器只负责逐文件落库，不按文件重发。
```

- [ ] **Step 1**: 写**失败**测试（整批 + 条目级隔离，G1/G5）。

  ```kotlin
  @Test
  fun `调用失败_整批20个都FAILED且error_kind为NETWORK`() = runBlocking {   // G1
      givenPendingFiles(20)
      val fake = FakeNormalizer { reqs ->
          reqs.map { NormalizeOutcome.Failure(LlmFailureKind.NETWORK, java.io.IOException("boom")) }
      }
      val orchestrator = build(fake)
      val runId = db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, 0L))

      orchestrator.analyzePending(runId).toList()

      assertThat(db.analysisRunDao().latest()!!.failedCount).isEqualTo(20)
      assertThat(db.musicFileDao().allByStatus(AnalysisStatus.FAILED)).hasSize(20)
      assertThat(db.musicFileDao().allByStatus(AnalysisStatus.FAILED).map { it.errorKind }.toSet())
          .containsExactly(FailureKind.NETWORK.name)
  }

  @Test
  fun `条目级解析失败_只连坐那一个_其余照常LINKED`() = runBlocking {   // G5
      givenPendingFiles(3)
      givenCategory("音乐类型"); givenTag("流行", "音乐类型")
      val fake = FakeNormalizer { reqs ->
          reqs.mapIndexed { i, r ->
              if (i == 1) NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, null)
              else successFor(r.fileName)
          }
      }
      val orchestrator = build(fake)
      val runId = db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, 0L))

      orchestrator.analyzePending(runId).toList()

      val run = db.analysisRunDao().latest()!!
      assertThat(run.analyzedOk).isEqualTo(2)
      assertThat(run.failedCount).isEqualTo(1)
      assertThat(db.musicFileDao().allByStatus(AnalysisStatus.FAILED).single().errorKind)
          .isEqualTo(FailureKind.PARSE.name)     // INVALID_OUTPUT → PARSE（05 §6）
  }
  ```

- [ ] **Step 2**: 跑测试，确认**失败**。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisOrchestratorTest*'
  ```
  预期：`expected 20 but was 0`（failedCount）。

- [ ] **Step 3**: 实现 `else ->` 两个分支（见 Interfaces 的落库规则）。

  ```kotlin
  is NormalizeOutcome.RateLimited -> {                       // 429 退避耗尽
      db.musicFileDao().setStatus(file.id, AnalysisStatus.FAILED,
          error = FailureKind.RATE_LIMIT.name, kind = FailureKind.RATE_LIMIT.name)
      db.analysisRunDao().bumpFailed(runId); failed++
      publish(AnalysisProgress.FileUpdated(file.id, file.fileName,
          AnalysisStatus.FAILED, error = FailureKind.RATE_LIMIT.name))
  }
  is NormalizeOutcome.Failure -> {
      val fk = outcome.kind.toFailureKind()                  // TIMEOUT 归 NETWORK（05 §6）
      db.musicFileDao().setStatus(file.id, AnalysisStatus.FAILED, error = fk.name, kind = fk.name)
      db.analysisRunDao().bumpFailed(runId); failed++
      publish(AnalysisProgress.FileUpdated(file.id, file.fileName,
          AnalysisStatus.FAILED, error = fk.name))
  }
  ```

- [ ] **Step 4**: 跑测试，确认通过。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisOrchestratorTest*'
  ```
  预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5**: 提交。

  ```bash
  git add -A && git commit -m "feat(data): 分析失败落库 —— 整批同命运、条目级只连坐一个、error_kind 映射

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 5: 中途取消 —— 第 k 个回 `UNANALYZED`、run `ABORTED`、`Finished(aborted = true)`

**Files:**
- Modify: `core/data/src/main/java/com/aimusic/player/data/analysis/AnalysisOrchestrator.kt`
- Modify: `core/data/src/androidTest/java/com/aimusic/player/data/analysis/AnalysisOrchestratorTest.kt`

**Interfaces:**
```kotlin
// driveLocked 增加取消收尾（05 §5.3）：
//   catch (CancellationException) → withContext(NonCancellable) {
//       把本批仍在 ANALYZING 的文件 setStatus(UNANALYZED, null, null)   // 取消不是错误，不置 FAILED
//       runDao().finish(runId, RunStatus.ABORTED, now())
//       stateFlow.update { it.copy(running = false, aborted = true) }
//       publish(AnalysisProgress.Finished(runId, ok, failed, aborted = true))   // 经热 progress 送达
//   } 然后 throw e
// 续跑无需额外实现：pendingForAnalysis() 天然只返回 UNANALYZED / FAILED
```

- [ ] **Step 1**: 写**失败**测试（G13；用 `cancel()` 打断一个会挂起的 normalizer）。

  ```kotlin
  @Test
  fun `中途取消_第k个回UNANALYZED_run为ABORTED_且收到Finished(aborted)`() = runBlocking {   // G13
      givenPendingFiles(3)
      givenCategory("音乐类型"); givenTag("流行", "音乐类型")
      val gate = CompletableDeferred<Unit>()
      val fake = FakeNormalizer { reqs ->
          // 第一个文件正常成功；处理第二个时挂起，等测试取消
          if (reqs.all { it.fileName != "f2.mp3" }) reqs.map { successFor(it.fileName) }
          else { gate.await() /* never */; reqs.map { successFor(it.fileName) } }
      }
      val orchestrator = build(fake, batchSize = 1)      // 每个文件一批，便于定位第 k 个
      val runId = db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, 0L))
      val finished = mutableListOf<AnalysisProgress>()
      val job = launch(Dispatchers.Default) {
          orchestrator.progress.collect { if (it is AnalysisProgress.Finished) finished += it }
      }
      val worker = launch(Dispatchers.Default) { orchestrator.analyzePending(runId).collect {} }
      awaitUntil { db.musicFileDao().allByStatus(AnalysisStatus.ANALYZING).isNotEmpty() }

      orchestrator.cancel()
      worker.join()

      val run = db.analysisRunDao().latest()!!
      assertThat(run.status).isEqualTo(RunStatus.ABORTED)
      assertThat(run.finishedAt).isNotNull()
      assertThat(db.musicFileDao().allByStatus(AnalysisStatus.UNANALYZED)).hasSize(2)  // f2 + f3
      assertThat(db.musicFileDao().allByStatus(AnalysisStatus.LINKED)).hasSize(1)      // f1 不回滚
      assertThat(finished.single().aborted).isTrue()
      job.cancel()
  }
  ```
  辅助 `awaitUntil { }` 见 Step 3 末尾。

- [ ] **Step 2**: 跑测试，确认**失败**。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisOrchestratorTest*'
  ```
  预期：`expected: ABORTED but was: RUNNING`（取消后没有收尾）。

- [ ] **Step 3**: 实现取消收尾。

  ```kotlin
  private fun driveLocked(runId: Long, files: List<MusicFileEntity>): Flow<AnalysisProgress> = flow {
      runningJob = currentCoroutineContext()[Job]
      var done = 0; var ok = 0; var failed = 0; var lastEmit = 0L
      publish(AnalysisProgress.Started(runId, files.size))
      stateFlow.update { it.copy(running = true, runId = runId, total = files.size, aborted = false) }
      try {
          /* Task 2/3/4 的批次循环 */
          db.analysisRunDao().finish(runId, RunStatus.COMPLETED, now())
          stateFlow.update { it.copy(running = false) }
          publish(AnalysisProgress.Finished(runId, ok, failed, aborted = false))
      } catch (e: CancellationException) {
          withContext(NonCancellable) {
              // 当前批仍在 ANALYZING 的文件回退为 UNANALYZED（05 §5.3：取消不是错误，不置 FAILED）
              db.musicFileDao().analyzingIdsOf(runId).forEach {
                  db.musicFileDao().setStatus(it, AnalysisStatus.UNANALYZED, null, null)
              }
              db.analysisRunDao().finish(runId, RunStatus.ABORTED, now())
              stateFlow.update { it.copy(running = false, aborted = true, retrying = null) }
              publish(AnalysisProgress.Finished(runId, ok, failed, aborted = true))
          }
          throw e
      } finally {
          runningJob = null
      }
  }.flowOn(Dispatchers.IO)

  // MusicFileDao 新增（Task 5 顺带补）
  // @Query("""SELECT f.id FROM music_file f JOIN analysis_run_file a ON a.file_id = f.id
  //            WHERE a.run_id = :runId AND f.analysis_status = 'ANALYZING'""")
  // abstract suspend fun analyzingIdsOf(runId: Long): List<Long>
  ```

  测试辅助：
  ```kotlin
  private suspend fun awaitUntil(timeoutMs: Long = 5_000, cond: suspend () -> Boolean) {
      val deadline = System.currentTimeMillis() + timeoutMs
      while (System.currentTimeMillis() < deadline) {
          if (cond()) return
          delay(20)
      }
      error("等待超时")
  }
  ```

- [ ] **Step 4**: 跑测试，确认通过；并跑一次全量编排器测试防回归。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisOrchestratorTest*' --tests '*AnalysisRunDaoTest*'
  ```
  预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5**: 提交。

  ```bash
  git add -A && git commit -m "feat(data): 分析中途取消收尾 —— 第 k 个回 UNANALYZED、run ABORTED、Finished(aborted)

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 6: `retry(fileIds)` —— 只为给定文件建批次并续跑

**Files:**
- Modify: `core/data/src/main/java/com/aimusic/player/data/analysis/AnalysisOrchestrator.kt`
- Modify: `core/data/src/androidTest/java/com/aimusic/player/data/analysis/AnalysisOrchestratorTest.kt`

**Interfaces:**
```kotlin
/**
 * 手动重试（05 §5.4 / I4）。**冷 Flow**（相对 05 §4.9 的 `suspend fun retry` 的一处偏差：
 * 返回冷 Flow 才能把取消权交给调用方，界面离开即 ABORTED）。
 * 建批次 → 关联文件 → 只对这些文件跑一遍（**不**放量成 pendingForAnalysis 全量）。
 */
fun retry(fileIds: List<Long>): Flow<AnalysisProgress>
```

- [ ] **Step 1**: 写**失败**测试（只为给定文件、顺序无关、LINKED 被跳过）。

  ```kotlin
  @Test
  fun `retry只为给定文件建批次_不扫全量pending`() = runBlocking {
      givenPendingFiles(5)
      givenCategory("音乐类型"); givenTag("流行", "音乐类型")
      val target = db.musicFileDao().pendingForAnalysis().take(2).map { it.id }
      val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }
      val orchestrator = build(fake)

      val events = orchestrator.retry(target).toList()

      assertThat(fake.calls.single()).hasSize(2)
      assertThat(db.analysisRunDao().latest()!!.analyzedOk).isEqualTo(2)
      assertThat(db.musicFileDao().pendingForAnalysis()).hasSize(3)
      assertThat((events.first() as AnalysisProgress.Started).total).isEqualTo(2)
  }

  @Test
  fun `retry传入已LINKED的id_零网络请求`() = runBlocking {      // I4 兜底
      givenPendingFiles(1)
      givenCategory("音乐类型"); givenTag("流行", "音乐类型")
      val fake = FakeNormalizer { reqs -> reqs.map { successFor(it.fileName) } }
      val orchestrator = build(fake)
      val id = db.musicFileDao().pendingForAnalysis().single().id
      orchestrator.retry(listOf(id)).toList()
      val calls = fake.calls.size

      orchestrator.retry(listOf(id)).toList()                    // 第二次：该文件已 LINKED

      assertThat(fake.calls.size).isEqualTo(calls)
  }
  ```

- [ ] **Step 2**: 跑测试，确认**失败**。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisOrchestratorTest*'
  ```
  预期：`Unresolved reference: retry`。

- [ ] **Step 3**: 实现 `retry`。

  ```kotlin
  fun retry(fileIds: List<Long>): Flow<AnalysisProgress> = flow {
      if (!mutex.tryLock()) return@flow
      try {
          val wanted = fileIds.toSet()
          val files = db.musicFileDao().pendingForAnalysis().filter { it.id in wanted }  // I4：只取未 LINKED
          val runId = db.analysisRunDao().createRun(
              AnalysisRunEntity(status = RunStatus.RUNNING, startedAt = now(), newCount = files.size),
          )
          db.analysisRunDao().linkFiles(files.map { AnalysisRunFileCrossRef(runId, it.id) })
          emitAll(driveLocked(runId, files))
      } finally {
          mutex.unlock()
      }
  }.flowOn(Dispatchers.IO)
  ```

- [ ] **Step 4**: 跑测试，确认通过。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisOrchestratorTest*'
  ```
  预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5**: 提交。

  ```bash
  git add -A && git commit -m "feat(data): 分析手动重试 retry(fileIds) —— 只为给定文件建批次、I4 兜底

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 7: 内置提供商预设常量表（**结构定死、取值留白**）

**Files:**
- Create: `core/llm/src/main/java/com/aimusic/player/llm/preset/ProviderPreset.kt`
- Create: `core/llm/src/test/java/com/aimusic/player/llm/preset/ProviderPresetsTest.kt`
- Modify: `core/llm/build.gradle.kts`（加 `testImplementation(project(":core:testing"))`）

**Interfaces:**
```kotlin
// com.aimusic.player.llm.preset
data class ProviderPreset(
    val id: String,            // 稳定标识，仅用于下拉选择；**不落库**
    val displayName: String,   // 显示名（如「DeepSeek 官方」），仅作填表助手的标签
    val protocol: ProtocolKind,
    val baseUrl: String,       // 端点前缀，以 "/" 结尾
    val defaultModel: String,
)

object ProviderPresets {
    /**
     * ⚠️ **取值待用户提供**（spec §14「后续由用户提供」）。表为空时设置页没有可点的预设，
     * 其余功能（手填四项 + 保存）完全不受影响 —— 所以先定死结构、留空。
     * 用户补齐时只加 `ProviderPreset(...)` 字面量，不改类型、不改 UI、不加测试。
     */
    val ALL: List<ProviderPreset> = emptyList()
}
```

- [ ] **Step 1**: 加测试依赖并写**失败**测试（结构与不变量的守卫，不猜取值）。

  `core/llm/build.gradle.kts`：
  ```kotlin
  testImplementation(project(":core:testing"))   // junit/truth/mockk 由它 api 暴露
  ```
  `ProviderPresetsTest.kt`：
  ```kotlin
  class ProviderPresetsTest {
      @Test
      fun `id唯一`() {
          val ids = ProviderPresets.ALL.map { it.id }
          assertThat(ids).containsNoDuplicates()
      }

      @Test
      fun `base url一律以斜杠结尾`() {          // spec §3.2：baseUrl 以 "/" 结尾
          assertThat(ProviderPresets.ALL).allMatch { it.baseUrl.endsWith("/") }
      }

      @Test
      fun `display name非空_且不携带api key`() {
          assertThat(ProviderPresets.ALL).allMatch { it.displayName.isNotBlank() }
          // 常量表里不该有 apiKey 字段：key 只走 ApiKeyStore，永不进常量
          assertThat(ProviderPresets.ALL.flatMap { listOf(it.id, it.displayName, it.baseUrl, it.defaultModel) })
              .allMatch { !it.contains("sk-") }
      }
  }
  ```

- [ ] **Step 2**: 跑测试，确认**失败**。

  ```bash
  ./gradlew :core:llm:testDebugUnitTest
  ```
  预期：`Unresolved reference: ProviderPresets`。

- [ ] **Step 3**: 落 Interfaces 里的类型与空表（**不要**编造任何 base url / 模型名）。

- [ ] **Step 4**: 跑测试，确认通过（空表下三条断言天然成立，且未来填表会被它们守住）。

  ```bash
  ./gradlew :core:llm:testDebugUnitTest
  ```
  预期：`BUILD SUCCESSFUL`，3 tests passed。

- [ ] **Step 5**: 提交。

  ```bash
  git add -A && git commit -m "feat(llm): 内置提供商预设常量表（结构定死、取值待用户填）

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 8: 交棒换成真货 —— `AnalysisTrigger` + `ApplicationScope` + `ScanOrchestrator` 接线

**Files:**
- Create: `core/data/src/main/java/com/aimusic/player/data/di/DispatcherModule.kt`
- Modify: `core/data/src/main/java/com/aimusic/player/data/di/RepositoryModule.kt`
- Create: `core/data/src/androidTest/java/com/aimusic/player/data/di/AnalysisTriggerWiringTest.kt`

**Interfaces:**
```kotlin
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

// RepositoryModule 新增/改动的 @Provides：
fun provideApplicationScope(): CoroutineScope                              // SupervisorJob() + Dispatchers.Default
fun provideAnalysisTrigger(o: AnalysisOrchestrator, @ApplicationScope scope: CoroutineScope): AnalysisTrigger
fun provideAnalysisOrchestrator(db: MusicDatabase, reader: MetadataReader, normalizer: LlmNormalizer): AnalysisOrchestrator
fun provideScanOrchestrator(..., analysisTrigger: AnalysisTrigger, lyricHandoff: LyricHandoff): ScanOrchestrator
// provideLlmNormalizer(...)：**由 P3 提供**（其 Task 7 Step 5 落在同一 RepositoryModule，
//   内部调 buildLlmNormalizer 组装 Caching(Retrying(Direct))）—— 本计划**不重复定义**，只消费该绑定。
```

- [ ] **Step 1**: 写**失败**测试（接线语义：交棒是**后台启动**、不阻塞调用方，且真的驱动了分析）。

  ```kotlin
  @Test
  fun `交棒立刻返回_分析在后台推进到完成`() = runBlocking {
      // 手工装配（照 RepositoryModule 的两行）
      val orchestrator = AnalysisOrchestrator(db, reader, fake, now = { 0L })
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val trigger = AnalysisTrigger { runId -> scope.launch { orchestrator.analyzePending(runId).collect {} } }
      givenPendingFiles(3); givenCategory("音乐类型"); givenTag("流行", "音乐类型")
      val runId = db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, 0L))

      val t0 = System.currentTimeMillis()
      trigger.analyzePending(runId)                       // 不许阻塞
      assertThat(System.currentTimeMillis() - t0).isLessThan(500L)

      awaitUntil { db.analysisRunDao().latest()!!.status == RunStatus.COMPLETED }
      assertThat(db.analysisRunDao().latest()!!.analyzedOk).isEqualTo(3)
      scope.cancel()
  }
  ```

- [ ] **Step 2**: 跑测试，确认**失败**（当前 `AnalysisTrigger {}` 是空实现）。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisTriggerWiringTest*'
  ```
  预期：`等待超时`（空实现下永远不会 COMPLETED）。

- [ ] **Step 3**: 实现。

  `DispatcherModule.kt`：
  ```kotlin
  @Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

  @Module @InstallIn(SingletonComponent::class)
  object DispatcherModule {
      /** 分析要能脱离界面独立跑完（09 §7「长任务可后台进行」），故用应用级作用域。 */
      @Provides @Singleton @ApplicationScope
      fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  }
  ```
  `RepositoryModule.kt`：
  ```kotlin
  // Phase 4-4：交棒从空实现换成真货（Phase 3 的挂点到期）。
  @Provides @Singleton
  fun provideAnalysisTrigger(
      orchestrator: AnalysisOrchestrator,
      @ApplicationScope scope: CoroutineScope,
  ): AnalysisTrigger = AnalysisTrigger { runId ->
      scope.launch { orchestrator.analyzePending(runId).collect { /* 冷 Flow 被这里独占驱动 */ } }
  }

  @Provides @Singleton
  fun provideAnalysisOrchestrator(
      db: MusicDatabase, metadataReader: MetadataReader, normalizer: LlmNormalizer,
  ): AnalysisOrchestrator = AnalysisOrchestrator(db, metadataReader, normalizer)

  // provideScanOrchestrator 补两个参数并**传进去**（原先没传，默认落到空 lambda！）
  fun provideScanOrchestrator(
      /* …, 原参数不变 */
      analysisTrigger: AnalysisTrigger,
      lyricHandoff: LyricHandoff,
  ): ScanOrchestrator = ScanOrchestrator(
      /* …, 原参数不变 */
      analysisTrigger = analysisTrigger,
      lyricHandoff = lyricHandoff,
  )
  ```

- [ ] **Step 4**: 跑测试 + 编译 `:app` 确认 Hilt 图能拼起来。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisTriggerWiringTest*'
  ./gradlew :app:assembleDebug
  ```
  预期：两次都 `BUILD SUCCESSFUL`。

- [ ] **Step 5**: 提交。

  ```bash
  git add -A && git commit -m "feat(data): 交棒换成真货 —— AnalysisTrigger + ApplicationScope，扫描编排器传入交棒

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 9: `AnalysisRunRepository` + `ErrorText` 逐行状态键（字面取自需求文档原文，决定 #4）

**Files:**
- Create: `core/data/src/main/java/com/aimusic/player/data/analysis/AnalysisRunRepository.kt`
- Modify: `core/data/src/main/java/com/aimusic/player/data/di/RepositoryModule.kt`
- Modify: `core/common/src/main/java/com/aimusic/player/common/error/ErrorText.kt`
- Create: `core/data/src/androidTest/java/com/aimusic/player/data/analysis/AnalysisRunRepositoryTest.kt`

**Interfaces:**
```kotlin
class AnalysisRunRepository(private val db: MusicDatabase) {
    fun observeLatest(): Flow<AnalysisRunEntity?>          // 无批次时 emit null
    fun observeRunFiles(runId: Long): Flow<List<RunFileRow>>
    suspend fun removeRecords(runId: Long, fileIds: List<Long>)   // 「移除记录」：只解绑批次
}

// ErrorText 新增（逐行状态短标签；字面**只能取自需求文档 / 11 §5.1 原文**，决定 #4）
"analysis.row.pending"  to "未分析"     // 原文：00 §4.3 状态「未分析」
"analysis.row.analyzing" to "分析中"    // 原文：00 §4.3 状态「分析中」
"analysis.row.linked"   to "已关联"     // 原文：00 §4.3 状态「已关联」
"analysis.row.failed"   to "分析失败"   // 原文：00 §4.3 状态「分析失败」；09 §6 文件行「分析失败〔原因〕」
"analysis.row.retrying" to "重试中"     // 原文：05 §2「重试中」；09 §5.1 / §6 文件行「重试中」
```

- [ ] **Step 1**: 写**失败**测试（仓库 + 文案表完整性）。

  ```kotlin
  @Test
  fun `没有批次时observeLatest发null_有批次时发最近一条`() = runBlocking {
      val repo = AnalysisRunRepository(db)
      assertThat(repo.observeLatest().first()).isNull()
      db.analysisRunDao().createRun(AnalysisRunEntity(RunStatus.RUNNING, startedAt = 5L))
      assertThat(repo.observeLatest().first()!!.startedAt).isEqualTo(5L)
  }

  @Test
  fun `移除记录不删文件行`() = runBlocking {
      /* 建 run + link 两个文件 → removeRecords(runId, listOf(id0)) → 清单只剩 1，music_file 仍有 2 */
  }
  ```
  `core/common/src/test/.../ErrorTextTest.kt`（若已有则追加）：
  ```kotlin
  @Test
  fun `逐行状态键都能取到非unknown文案`() {
      listOf("analysis.row.pending", "analysis.row.analyzing", "analysis.row.linked",
             "analysis.row.failed", "analysis.row.retrying").forEach { key ->
          val text = ErrorText.resolve(key)
          assertThat(text).isNotEqualTo(ErrorText.resolve(null))
      }
  }
  ```

- [ ] **Step 2**: 跑测试，确认**失败**。

  ```bash
  ./gradlew :core:common:testDebugUnitTest --tests '*ErrorTextTest*'
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisRunRepositoryTest*'
  ```
  预期：`Unresolved reference: AnalysisRunRepository`；文案测试失败（取到 `unknown`）。

- [ ] **Step 3**: 实现 `AnalysisRunRepository`（`observeLatest = db.analysisRunDao().observeLatest()`，注意 DAO 的 `suspend fun observeLatest()` 改成 `fun observeLatest(): Flow<AnalysisRunEntity?>` 之前先在 Task 1 里就按 `Flow<AnalysisRunEntity?>` 落地）、注册 `@Provides @Singleton fun provideAnalysisRunRepository(db: MusicDatabase): AnalysisRunRepository`、并在 `ErrorText.texts` 里加 5 个键。

- [ ] **Step 4**: 跑测试，确认通过。

  ```bash
  ./gradlew :core:common:testDebugUnitTest --tests '*ErrorTextTest*'
  ./gradlew :core:data:connectedDebugAndroidTest --tests '*AnalysisRunRepositoryTest*'
  ```
  预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5**: 提交。

  ```bash
  git add -A && git commit -m "feat(data): 分析记录仓库 + ErrorText 逐行状态键

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 10: 分析记录页 —— `AnalysisRunViewModel` + `AnalysisRunScreen`（外壳 + Content）

**Files:**
- Create: `feature/mine/src/main/java/com/aimusic/player/mine/analysis/AnalysisRunViewModel.kt`
- Create: `feature/mine/src/main/java/com/aimusic/player/mine/analysis/AnalysisRunScreen.kt`
- Create: `feature/mine/src/test/java/com/aimusic/player/mine/analysis/AnalysisRunViewModelTest.kt`
- Create: `feature/mine/src/androidTest/java/com/aimusic/player/mine/analysis/AnalysisRunScreenContentTest.kt`

**Interfaces:**
```kotlin
@Immutable
data class AnalysisFileUi(
    val fileId: Long, val fileName: String, val status: AnalysisStatus,
    val linkedEntityId: Long?, val errorKind: String?, val retrying: Boolean,
) { val canRetry: Boolean get() = status == AnalysisStatus.FAILED || status == AnalysisStatus.UNANALYZED }

@Immutable
data class AnalysisRunUiState(
    val runId: Long? = null, val running: Boolean = false,
    val total: Int = 0, val done: Int = 0, val ok: Int = 0, val failed: Int = 0,
    val retrying: AnalysisProgress.Retrying? = null,
    val rows: List<AnalysisFileUi> = emptyList(),
    val aborted: Boolean = false,
) {
    val hasRun: Boolean get() = runId != null
    val progressFraction: Float? get() = if (total == 0) null else done.toFloat() / total
    val allFailed: Boolean get() = failed > 0 && ok == 0
    val partiallyFailed: Boolean get() = failed > 0 && ok > 0
}

sealed interface AnalysisRunEvent {
    data class OpenSongDetail(val entityId: Long) : AnalysisRunEvent
    data class ShowMessage(val text: String) : AnalysisRunEvent
    data object OpenSettings : AnalysisRunEvent
}

@HiltViewModel
class AnalysisRunViewModel @Inject constructor(
    private val orchestrator: AnalysisOrchestrator,
    private val runs: AnalysisRunRepository,
) : ViewModel() {
    val state: StateFlow<AnalysisRunUiState>
    val events: Flow<AnalysisRunEvent>
    fun onRetry(fileId: Long)
    fun onRemoveRecord(fileId: Long)
}

// 屏幕（09 §8 的拆法）
@Composable fun AnalysisRunScreen(onOpenSettings: () -> Unit, onOpenSong: (Long) -> Unit, vm: AnalysisRunViewModel = hiltViewModel())
@Composable internal fun AnalysisRunScreenContent(
    state: AnalysisRunUiState,
    onRetry: (Long) -> Unit = {}, onRemoveRecord: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {}, onOpenSong: (Long) -> Unit = {},
)
```

- [ ] **Step 1**: 写**失败**的 VM 测试（JVM + MockK；照 `ScanViewModelTest` 的写法，`state` 必须热起来）。

  ```kotlin
  @Test
  fun `重试只看FAILED与UNANALYZED_LINKED不给重试入口`() {
      /* given rows: LINKED / FAILED / UNANALYZED
         assertThat(vm.state.value.rows.first { it.status == LINKED }.canRetry).isFalse() */
  }

  @Test
  fun `点重试_调orchestrator_retry且只带这一个fileId`() = runTest(main) {
      val vm = newViewModel()
      vm.onRetry(42L)
      coVerify(exactly = 1) { orchestrator.retry(listOf(42L)) }
  }

  @Test
  fun `429退避中_该行标记重试中_不算失败`() = runTest(main) {    // 09 §5.1 第 4 行
      /* progress 热流推 Retrying(fileId = 7, attempt = 2, delayMs = 2000)
         assertThat(vm.state.value.rows.first { it.fileId == 7L }.retrying).isTrue()
         assertThat(vm.state.value.failed).isEqualTo(0) */
  }
  ```

- [ ] **Step 2**: 跑测试，确认**失败**。

  ```bash
  ./gradlew :feature:mine:testDebugUnitTest --tests '*AnalysisRunViewModelTest*'
  ```
  预期：`Unresolved reference: AnalysisRunViewModel`。

- [ ] **Step 3**: 实现 VM：

  ```kotlin
  val state: StateFlow<AnalysisRunUiState> = combine(
      runs.observeLatest(),
      orchestrator.state,
      orchestrator.progress,
  ) { run, session, last ->
      ...
  }.stateIn(...)   // 注意：progress 是 SharedFlow，combine 会等它先发一次 —— 见下方"实现要点"
  ```
  **实现要点**（写进代码注释）：`combine` 要 4 路才能既拿 `SessionState` 又可推 `Retrying`。做法：把 `Retrying` 收进一个内部 `MutableStateFlow<AnalysisProgress.Retrying?>`（`init { viewModelScope.launch { orchestrator.progress.collect { if (it is Retrying) _retrying.value = it; if (it is FileUpdated) _lastFile.value = it } } }`），再 `combine(runs.observeLatest(), orchestrator.state, _retrying, _lastFile, _runFiles)` 组装。`runFiles` 依赖 `runId`：用 `runs.observeLatest().flatMapLatest { it?.let { r -> runs.observeRunFiles(r.id) } ?: flowOf(emptyList()) }`。`onRetry(id)` 用 `viewModelScope.launch { orchestrator.retry(listOf(id)).collect {} }`（收集即驱动，离开界面即取消 → G13 语义）。

- [ ] **Step 4**: 跑测试，确认通过。

  ```bash
  ./gradlew :feature:mine:testDebugUnitTest --tests '*AnalysisRunViewModelTest*'
  ```
  预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5**: 写**失败**的 UI 测试（真机 `createComposeRule`；只测 `AnalysisRunScreenContent`；函数名反引号**不含空格**）。

  ```kotlin
  @Test
  fun `分析进行中_渲染已分析数_与进度条`() {
      renderContent(AnalysisRunUiState(runId = 1, running = true, total = 20, done = 7, ok = 6, failed = 1))
      composeRule.onNodeWithText(ErrorText.resolve("analysis.running", mapOf("done" to 7, "total" to 20)))
          .assertIsDisplayed()
  }

  @Test
  fun `逐文件状态_每行渲染自己的标签`() {
      renderContent(AnalysisRunUiState(runId = 1, total = 3, done = 3, ok = 2, failed = 1, rows = listOf(
          AnalysisFileUi(1, "晴天.mp3", AnalysisStatus.LINKED, 11L, null, retrying = false),
          AnalysisFileUi(2, "吻别.mp3", AnalysisStatus.FAILED, null, FailureKind.PARSE.name, retrying = false),
          AnalysisFileUi(3, "菊花台.mp3", AnalysisStatus.UNANALYZED, null, null, retrying = false),
      )))
      composeRule.onNodeWithText("晴天.mp3").assertIsDisplayed()
      composeRule.onNodeWithText(ErrorText.resolve("analysis.row.failed"), substring = true).assertIsDisplayed()
      composeRule.onNodeWithText(ErrorText.resolve("analysis.row.pending")).assertIsDisplayed()
  }

  @Test
  fun `整批失败_渲染服务返回内容异常_给重试`() {   // G6 的用户可见面
      var retried: Long? = null
      renderContent(
          AnalysisRunUiState(runId = 1, total = 2, done = 2, ok = 0, failed = 2, rows = listOf(
              AnalysisFileUi(2, "吻别.mp3", AnalysisStatus.FAILED, null, FailureKind.PARSE.name, false),
          )),
          onRetry = { retried = it },
      )
      composeRule.onNodeWithText(ErrorText.resolve("analysis.all_failed.parse")).assertIsDisplayed()
      composeRule.onNodeWithText("重试").performClick()
      assertThat(retried).isEqualTo(2L)
  }

  @Test
  fun `重试中_该行显示重试中_不计失败`() {          // G2 的用户可见面
      renderContent(AnalysisRunUiState(runId = 1, running = true, total = 20, done = 3, ok = 3, failed = 0,
          retrying = AnalysisProgress.Retrying(fileId = 3L, attempt = 2, delayMs = 2000L),
          rows = listOf(AnalysisFileUi(3, "菊花台.mp3", AnalysisStatus.ANALYZING, null, null, retrying = true))))
      composeRule.onNodeWithText(ErrorText.resolve("analysis.row.retrying")).assertIsDisplayed()
  }

  @Test
  fun `认证失败_给去设置入口`() {                   // 09 §6：AUTH → 「去设置」
      var toSettings = false
      renderContent(
          AnalysisRunUiState(runId = 1, total = 1, done = 1, ok = 0, failed = 1,
              rows = listOf(AnalysisFileUi(9, "a.mp3", AnalysisStatus.FAILED, null, FailureKind.AUTH.name, false))),
          onOpenSettings = { toSettings = true },
      )
      composeRule.onNodeWithText(ErrorText.resolve("llm.auth")).assertIsDisplayed()
      composeRule.onNodeWithText("去设置").performClick()
      assertThat(toSettings).isTrue()
  }
  ```

- [ ] **Step 6**: 跑 UI 测试，确认**失败** → 实现 `AnalysisRunScreenContent`（`Scaffold` + 顶部标题「最近分析记录」+ 摘要（`analysis.partial` / `analysis.all_failed.*`）+ `LazyColumn` 逐行 `fileName` + 状态标签 + `⋯`/文本按钮「重试」「移除记录」）→ 外壳 `AnalysisRunScreen`（`collectAsState` + `events` 转 snackbar / 导航）→ 跑通。

  ```bash
  adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
  ./gradlew :feature:mine:connectedDebugAndroidTest --tests '*AnalysisRunScreenContentTest*'
  ./gradlew :feature:mine:testDebugUnitTest --tests '*AnalysisRunViewModelTest*'
  ```
  预期：先因 `Unresolved reference: AnalysisRunScreenContent` 失败；实现后 `BUILD SUCCESSFUL`。

- [ ] **Step 7**: 变异抽查（`09 §8`）：把 `analysis.row.failed` 那行的 `Text(...)` 改成 `Text("")`，确认**恰好**那 1 条变红，再改回。

  ```bash
  ./gradlew :feature:mine:connectedDebugAndroidTest --tests '*AnalysisRunScreenContentTest*'
  ```
  预期：`逐文件状态_每行渲染自己的标签` FAILED，其余通过；改回后全绿。

- [ ] **Step 8**: 提交。

  ```bash
  git add -A && git commit -m "feat(mine): 最近分析记录页 —— 摘要 + 逐文件状态 + 重试/移除记录

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 11: 设置页 —— 四项必填 + 掩码 api key + 高级项 + 预设填表助手

**Files:**
- Modify: `core/data/src/main/java/com/aimusic/player/data/settings/SettingsRepository.kt`
- Create: `feature/mine/src/main/java/com/aimusic/player/mine/settings/SettingsViewModel.kt`
- Create: `feature/mine/src/main/java/com/aimusic/player/mine/settings/SettingsScreen.kt`
- Create: `feature/mine/src/test/java/com/aimusic/player/mine/settings/SettingsViewModelTest.kt`
- Create: `feature/mine/src/androidTest/java/com/aimusic/player/mine/settings/SettingsScreenContentTest.kt`

**Interfaces:**
```kotlin
// SettingsRepository：`llm_provider` 语义 = **协议类型**（03 §6），并补 spec §12 要求的新 key
data class AppSettings(
    /* 原字段保留 */ val llmProvider: String?,          // 值域 = ProtocolKind.name
    val llmBaseUrl: String?, val llmModel: String?,
    val llmSupportsJsonSchema: Boolean, val llmMaxRetries: Int,
    val llmMaxTokens: Int,                              // 新增，默认 8_192
    val llmConnectTimeoutMs: Long,                      // 新增，默认 15_000
    val llmReadTimeoutMs: Long,                         // 新增，默认 90_000
    val llmBatchSize: Int,                              // 新增，默认 20
)
suspend fun setLlmConfig(
    protocol: String?, baseUrl: String?, model: String?,
    supportsJsonSchema: Boolean, maxRetries: Int,
    maxTokens: Int, connectTimeoutMs: Long, readTimeoutMs: Long, batchSize: Int,
)

// SettingsViewModel
enum class RequiredField { PROTOCOL, BASE_URL, API_KEY, MODEL }

@Immutable
data class SettingsUiState(
    val protocol: ProtocolKind? = null,
    val baseUrl: String = "", val model: String = "",
    val hasApiKey: Boolean = false, val apiKeyMasked: String? = null, val apiKeyInput: String = "",
    val maxRetries: Int = SettingsRepository.DEFAULT_MAX_RETRIES,
    val connectTimeoutMs: Long = 15_000, val readTimeoutMs: Long = 90_000,
    val maxTokens: Int = 8_192, val supportsJsonSchema: Boolean = false,
    val advancedExpanded: Boolean = false,
    val presets: List<ProviderPreset> = ProviderPresets.ALL,
) {
    val missing: Set<RequiredField> get() = buildSet {
        if (protocol == null) add(RequiredField.PROTOCOL)
        if (baseUrl.isBlank()) add(RequiredField.BASE_URL)
        if (model.isBlank()) add(RequiredField.MODEL)
        if (!hasApiKey && apiKeyInput.isBlank()) add(RequiredField.API_KEY)
    }
    val saveEnabled: Boolean get() = missing.isEmpty()
}

@HiltViewModel class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository, private val apiKeyStore: ApiKeyStore,
) : ViewModel() {
    val state: StateFlow<SettingsUiState>
    fun onProtocolChange(kind: ProtocolKind)
    fun onBaseUrlChange(value: String); fun onModelChange(value: String)
    fun onApiKeyChange(value: String)
    fun onSelectPreset(presetId: String)          // 只填 3 项，不落 presetId
    fun onToggleAdvanced(); fun onMaxRetriesChange(v: Int); fun onTimeoutChange(connectMs: Long, readMs: Long)
    fun onMaxTokensChange(v: Int); fun onSupportsJsonSchemaChange(v: Boolean)
    fun onSave()
}
```

- [ ] **Step 1**: 写**失败**的 VM 测试（G14 / G15 / G18 + 写入落点）。

  ```kotlin
  @Test
  fun `四项未填全_保存禁用_并逐项标出缺哪一项`() {      // G18
      val vm = newViewModel()          // 全空
      assertThat(vm.state.value.saveEnabled).isFalse()
      assertThat(vm.state.value.missing).containsExactly(
          RequiredField.PROTOCOL, RequiredField.BASE_URL, RequiredField.MODEL, RequiredField.API_KEY)
  }

  @Test
  fun `填全四项后保存可用`() {
      val vm = newViewModel()
      vm.onProtocolChange(ProtocolKind.OPENAI); vm.onBaseUrlChange("https://x/v1/")
      vm.onModelChange("m"); vm.onApiKeyChange("sk-abc")
      assertThat(vm.state.value.saveEnabled).isTrue()
  }

  @Test
  fun `选中预设_只填协议与base url与模型_不含api key`() = runTest(main) {     // G14
      /* 用固定夹具：ProviderPresets.ALL 为空时本用例 skip，改成直接构造状态注入的私有函数：
         vm.applyPreset(ProviderPreset("p1", "某服务商", ProtocolKind.ANTHROPIC, "https://api.x/", "m-1"))
         assertThat(state.protocol).isEqualTo(ANTHROPIC)
         assertThat(state.baseUrl).isEqualTo("https://api.x/"); assertThat(state.model).isEqualTo("m-1")
         assertThat(state.apiKeyInput).isEmpty() */
  }

  @Test
  fun `保存只落最终值_不落选了哪个预设`() = runTest(main) {                    // G15
      val vm = newViewModel() /* 选中预设 + 改 baseUrl */ 
      vm.onSave()
      coVerify { settings.setLlmConfig(protocol = any(), baseUrl = any(), model = any(), /* … */ any(), any(), any(), any(), any(), any()) }
      coVerify(exactly = 0) { settings.setSelectedPresetId(any()) }   // 该 API 根本不该存在；用"不落任何 presetId"的 key 断言
  }
  ```

- [ ] **Step 2**: 跑测试，确认**失败**。

  ```bash
  ./gradlew :feature:mine:testDebugUnitTest --tests '*SettingsViewModelTest*'
  ```
  预期：`Unresolved reference: SettingsViewModel` / `RequiredField`。

- [ ] **Step 3**: 实现 `SettingsRepository` 新 key + `SettingsViewModel`。

  ```kotlin
  // SettingsRepository 追加 key（`llm_provider` 保留原名不改，避免迁移；语义改为协议类型）
  private val KEY_LLM_MAX_TOKENS = intPreferencesKey("llm_max_tokens")
  private val KEY_LLM_CONNECT_TIMEOUT = longPreferencesKey("llm_connect_timeout_ms")
  private val KEY_LLM_READ_TIMEOUT = longPreferencesKey("llm_read_timeout_ms")
  private val KEY_LLM_BATCH_SIZE = intPreferencesKey("llm_batch_size")
  const val DEFAULT_MAX_TOKENS = 8_192
  const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
  const val DEFAULT_READ_TIMEOUT_MS = 90_000L
  const val DEFAULT_BATCH_SIZE = 20
  ```
  VM：`state` 由 `settings.settings` + 内部 `MutableStateFlow`（掩码 / 未保存的输入）`combine`；`onSave()` → `apiKeyStore.write(apiKeyInput)`（仅在非空时）+ `settings.setLlmConfig(...)`；`apiKeyMasked` 从 `apiKeyStore.masked()` 取（**永不回显原文**，`03 §7`）。

- [ ] **Step 4**: 跑测试，确认通过。

  ```bash
  ./gradlew :feature:mine:testDebugUnitTest --tests '*SettingsViewModelTest*'
  ```
  预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5**: 写**失败**的 UI 测试（真机；反引号名不含空格）。

  ```kotlin
  @Test
  fun `四项未填全_保存禁用_缺项逐个标出`() {          // G18
      renderContent(SettingsUiState(missing = RequiredField.entries.toSet()))
      composeRule.onNodeWithText("保存").assertIsNotEnabled()
      composeRule.onNodeWithText("协议类型").assertIsDisplayed()      // 逐项标出
      composeRule.onNodeWithText("API Key").assertIsDisplayed()
  }

  @Test
  fun `api key只显示掩码`() {
      renderContent(SettingsUiState(protocol = ProtocolKind.OPENAI, baseUrl = "https://x/v1/",
          model = "m", hasApiKey = true, apiKeyMasked = "sk-****"))
      composeRule.onNodeWithText("sk-****").assertIsDisplayed()
      composeRule.onNodeWithText("sk-abc").assertDoesNotExist()
  }

  @Test
  fun `协议类型是下拉_三项可选`() {
      var picked: ProtocolKind? = null
      renderContent(SettingsUiState(), onProtocolChange = { picked = it })
      composeRule.onNodeWithText("协议类型").performClick()
      composeRule.onNodeWithText("anthropic").performClick()
      assertThat(picked).isEqualTo(ProtocolKind.ANTHROPIC)
  }

  @Test
  fun `高级项默认折叠_展开后可见max tokens`() {
      renderContent(SettingsUiState())          // advancedExpanded = false
      composeRule.onNodeWithText("max_tokens", substring = true).assertDoesNotExist()
      composeRule.onNodeWithText("高级").performClick()
      composeRule.onNodeWithText("高级").assertIsDisplayed()
  }
  ```

- [ ] **Step 6**: 跑 UI 测试确认失败 → 实现 `SettingsScreenContent`（四项 + `missing` 逐项错误提示 + 掩码 `OutlinedTextField` + `ExposedDropdownMenu` + 「高级」折叠区 + `Button("保存", enabled = state.saveEnabled)`）与外壳 `SettingsScreen` → 跑通。

  ```bash
  ./gradlew :feature:mine:connectedDebugAndroidTest --tests '*SettingsScreenContentTest*'
  ./gradlew :feature:mine:testDebugUnitTest --tests '*SettingsViewModelTest*'
  ```
  预期：实现后两次都 `BUILD SUCCESSFUL`。

- [ ] **Step 7**: 提交。

  ```bash
  git add -A && git commit -m "feat(mine): 设置页 —— 四项必填+掩码 key+高级项+预设填表助手

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 12: 导航接线 —— 我的 → 最近分析记录 / 设置，扫描提交后跳分析页

**Files:**
- Modify: `app/src/main/java/com/aimusic/player/navigation/Routes.kt`
- Modify: `app/src/main/java/com/aimusic/player/navigation/AppNavHost.kt`
- Modify: `feature/mine/src/main/java/com/aimusic/player/mine/MineScreen.kt`
- Modify: `feature/mine/src/main/java/com/aimusic/player/mine/scan/ScanViewModel.kt`
- Modify: `feature/mine/src/main/java/com/aimusic/player/mine/scan/ScanScreen.kt`
- Create: `feature/mine/src/androidTest/java/com/aimusic/player/mine/MineScreenTest.kt`

**Interfaces:**
```kotlin
// :app/navigation/Routes.kt
@Serializable data object AnalysisRoute
@Serializable data object SettingsRoute

// MineScreen：多两个回调（屏幕只收 lambda，不 import 路由 —— 09 §4.1.2）
fun MineScreen(
    onOpenScan: () -> Unit,
    onOpenAnalysis: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
)

// ScanScreen/ViewModel：提交成功后跳分析页（Phase 3 空窗的收口）
// ScanEvent 新增 data object NavigateToAnalysis ；ScanScreen 外壳把该事件转成 onNavigateToAnalysis()
fun ScanScreen(..., onNavigateToAnalysis: () -> Unit = {}, viewModel: ScanViewModel = hiltViewModel())
```

- [ ] **Step 1**: 写**失败**测试（我的页新入口可点、扫描提交后发跳转事件）。

  `MineScreenTest.kt`：
  ```kotlin
  @Test
  fun `最近分析记录与设置入口都能点`() {
      var analysis = false; var settings = false
      composeRule.setContent { MaterialTheme {
          MineScreen(onOpenScan = {}, onOpenAnalysis = { analysis = true }, onOpenSettings = { settings = true })
      } }
      composeRule.onNodeWithText("最近分析记录").performClick()
      composeRule.onNodeWithText("设置").performClick()
      assertThat(analysis).isTrue(); assertThat(settings).isTrue()
  }
  ```
  `ScanViewModelTest.kt` 追加：
  ```kotlin
  @Test
  fun `提交成功后_发跳转分析页事件`() = runTest(main) {
      givenSource()
      coEvery { orchestrator.commit() } returns CommitResult.Committed(runId = 7L, inserted = 3, cleaned = 0)
      val vm = newViewModel()
      vm.onConfirmImport()
      // 先到的是「已提交」提示，再是跳转 —— 用 events 收两条
      assertThat(vm.events.first()).isInstanceOf(ScanEvent.ShowMessage::class.java)
      assertThat(vm.events.first()).isEqualTo(ScanEvent.NavigateToAnalysis)
  }
  ```

- [ ] **Step 2**: 跑测试，确认**失败**。

  ```bash
  ./gradlew :feature:mine:testDebugUnitTest --tests '*ScanViewModelTest*'
  ./gradlew :feature:mine:connectedDebugAndroidTest --tests '*MineScreenTest*'
  ```
  预期：`Unresolved reference: onOpenAnalysis` / `NavigateToAnalysis`。

- [ ] **Step 3**: 实现：`Routes.kt` 加两个路由；`AppNavHost` 加 `composable<AnalysisRoute> { AnalysisRunScreen(onOpenSettings = { navController.navigate(SettingsRoute) }, onOpenSong = {}) }` 与 `composable<SettingsRoute> { SettingsScreen() }`，并给 `MineScreen` 的两个新回调接上 `navController.navigate(...)`；`ScanScreen` 外壳新增 `onNavigateToAnalysis` 并在 `ScanEvent.NavigateToAnalysis` 分支调用；`ScanViewModel.onConfirmImport` 的 `Committed` 分支追加 `_events.send(ScanEvent.NavigateToAnalysis)`。

- [ ] **Step 4**: 跑测试 + 装配 `:app`。

  ```bash
  ./gradlew :feature:mine:testDebugUnitTest --tests '*ScanViewModelTest*'
  adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
  ./gradlew :feature:mine:connectedDebugAndroidTest --tests '*MineScreenTest*'
  ./gradlew :app:assembleDebug
  ```
  预期：三次都 `BUILD SUCCESSFUL`。

- [ ] **Step 5**: 提交。

  ```bash
  git add -A && git commit -m "feat(app): 导航接线 —— 我的→最近分析记录/设置，扫描提交后跳分析页

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

### Task 13: 真机端到端验证 + 文档回写

**Files:**
- Modify: `docs/技术方案/02-详细设计总纲.md`（§5.6 编排器批量语义）
- Modify: `docs/技术方案/03-数据层设计.md`（§6 settings key 语义）
- Modify: `docs/技术方案/09-界面层设计.md`（§3.2.10 进度按批、§3.2.13 字段与预设）
- Modify: `docs/技术方案/01-技术栈与架构.md`（§7 模块清单 / 依赖放宽一行）

**Interfaces:**
```kotlin
// 无新代码；只做真机验证取证与文档同步（spec §12 里属 P4 的 4 行）
```

- [ ] **Step 1**: 真机跑通「扫描 → 分析 → 逐文件状态」。

  ```bash
  adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
  ./gradlew :app:installDebug
  ```
  真机手工路径（照 spec §14：内置预设表为空 ⇒ 先在**设置页手填**协议类型 / base url / api key / 模型名四项）：
  1. 我的 → **设置**：选协议类型 `openai`、填 base url、api key、模型名 → 「保存」可用 → 保存；
  2. 我的 → **文件扫描** → 开始扫描 → 分析并添加；
  3. 自动跳到 **最近分析记录**：能看到逐文件行与状态（`ANALYZING` → `LINKED`/`FAILED`）、摘要「成功 X / 失败 Y」；
  4. 断开网络再触发一次：整批行显示 `NETWORK` 文案 + 「重试」；期间点「移除记录」不再出现该行。

  **取证**：`adb shell run-as com.aimusic.player sqlite3 databases/ai_music_player.db "SELECT id,status,analyzed_ok,failed_count FROM analysis_run ORDER BY id DESC LIMIT 1;"` 预期最近一条 `status=COMPLETED`、`analyzed_ok` 与界面一致。

- [ ] **Step 2**: 文档回写（四行，均为 spec §12 表格里属 P4 的项）。

  - `02 §5.6`：编排器「一次一个文件」→ **一次一批（默认 20）**，进度仍逐文件；
  - `03 §6`：`llm_provider` 语义 = **协议类型**；登记新增 key `llm_max_tokens` / `llm_connect_timeout_ms` / `llm_read_timeout_ms` / `llm_batch_size`；
  - `09 §3.2.10`：进度按批、逐文件展示；文件行状态文案来源；
  - `09 §3.2.13`：四项必填 + 高级项 + 预设仅作填表助手（只落最终值）；补「`defaultPlayMode` / `sortPreference` 本期未实现，随 Phase 5」；
  - `01 §7`：`:core:data → :core:llm` 的依赖放宽一行（与 3e 的 `:core:ui` 同处理）。

- [ ] **Step 3**: 全量回归（三个模块的 JVM + 真机测试）。

  ```bash
  ./gradlew :core:data:connectedDebugAndroidTest :core:common:testDebugUnitTest \
            :feature:mine:connectedDebugAndroidTest :feature:mine:testDebugUnitTest
  ```
  预期：`BUILD SUCCESSFUL`（无 FAILED）。

- [ ] **Step 4**: 提交。

  ```bash
  git add -A && git commit -m "docs: Phase 4-4 落地回写（02 §5.6 / 03 §6 / 09 §3.2.10 / §3.2.13 / 01 §7）

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
  ```

---

## 自检：spec 覆盖对照

**spec §10（编排与幂等）**

| spec 条目 | 覆盖 Task |
| --- | --- |
| 单 worker 串行、每次取 20 个待分析文件组一批（`pendingForAnalysis` 分批） | Task 2（骨架 + 批量 20） |
| `categories` / `tags` 每批**实时读取一次**（非快照） | Task 3 |
| I4：只处理 `UNANALYZED`（与 `FAILED` 的手动重试）；`LINKED` 不重分析 | Task 2（入口过滤）+ Task 3（事务兜底）+ Task 6（retry 过滤） |
| `analyzed_ok` / `failed_count` 仍**逐文件**累加 | Task 3（成功）/ Task 4（失败） |
| 进度事件逐文件（`FileUpdated`），批只是传输粒度 | Task 2（ANALYZING）/ Task 3（LINKED）/ Task 4（FAILED） |
| 取消：第 k 个回 `UNANALYZED`、run `ABORTED`、`Finished(aborted = true)` | Task 5 |

**spec §11（界面）**

| spec 条目 | 覆盖 Task |
| --- | --- |
| `AnalysisRunScreen` 进度逐文件、「正在分析」= 当前批次 | Task 10（VM + Content） |
| 429 退避期间该文件保持 `ANALYZING`、不计失败、文案「重试中」 | Task 10（`Retrying` → `retrying` 行）；`Retrying` 事件由 **P3** 的退避层（`RetryingLlmNormalizer` 的 `onBackoff`）上报，P4 只消费 |
| `SettingsScreen` 四项必填、未填全则保存禁用 + 逐项标出 | Task 11 |
| 重试 / 超时 / `max_tokens` / `supportsJsonSchema` 收在「高级」 | Task 11 |
| api key 掩码显示、写入走 `ApiKeyStore`（永不回显原文） | Task 11 |
| 预设只作填表助手：填 base url / 协议 / 模型名；**只落最终值，不落「选了哪个预设」** | Task 7（常量表）+ Task 11（VM：`onSelectPreset` 只改 UiState、`onSave` 不写 presetId） |
| 预设表放 `:core:llm` 常量表（纯 Kotlin、可单测） | Task 7 |

**spec §13.1 验收标准（G12–G18）**

| GWT | 内容 | 覆盖 Task | 说明 |
| --- | --- | --- | --- |
| **G12** | 对 `LINKED` 文件再触发 → 不重分析、零网络请求 | **Task 3**（`已经LINKED的文件再次触发_零网络请求`）+ **Task 6**（`retry传入已LINKED的id`） | 入口 `pendingForAnalysis` + 事务兜底（`attachAnalysisResult` 首行判 LINKED） |
| **G13** | 批量处理中途取消 → 第 k 个回 `UNANALYZED`、run `ABORTED`、`Finished(aborted = true)` | **Task 5** | 前 k-1 个 `LINKED` 不回滚；续跑靠 `pendingForAnalysis` 天然返回剩余 |
| **G14** | 选中内置预设 → 自动填 base url / 协议类型 / 模型名（**不含 api key**），用户可改 | **Task 7**（表）+ **Task 11**（`onSelectPreset` 的 VM 测试） | 预设表当前为空（取值待用户填）；G14 的 VM 用例用直接构造的 `ProviderPreset` 夹具，不依赖表内容 |
| **G15** | 改动任一字段后重回设置页 → **不显示预设名** | **Task 11** | 因为从不落「选了哪个预设」；`state.presets` 里也有「已选」态字段 —— 断言它不存在 / 始终为 null |
| **G16** | 批量·模型少返回一项（缺整个 item）→ 该文件 `FAILED`、其余 `LINKED` | **不属于 P4** | 属解析层（spec §9.2 / §13 表），由 **P2** 的 `NormalizeParser` 产出条目级 `Failure(INVALID_OUTPUT)`；P4 的 Task 4 负责把这种 outcome 逐文件落库（`条目级解析失败_只连坐那一个` 用例即其编排侧断言） |
| **G17** | 批量·越界 `file_index` → 忽略该条 + `logger.warn`、不算失败 | **不属于 P4** | 同 G16，越界项的「忽略」发生在 parser；P4 侧对应的不变量是「outcome 数量与输入等长」（Task 2 的 `一批20个只发一次请求` + Task 4 的整批/条目级断言） |
| **G18** | 设置页四项未填全 → 「保存」禁用 + 逐项标出缺哪一项 | **Task 11** | VM 断言 `saveEnabled == false` 且 `missing` 精确等于缺失集合；UI 断言按钮 `assertIsNotEnabled` |

**另注**：spec §9.1（调用失败整批同命运）由 **Task 4** 的 `调用失败_整批20个都FAILED且error_kind为NETWORK` 覆盖编排侧；§9.2 的判条规则本身在 **P2** 的 `NormalizeParser`。

---

## 待办与已定（spec 未写清 / 与既有约束冲突）

**已定（六条决定覆盖，无需再决策）**

1. **依赖守卫放宽** —— **已定（决定 #2）**：`:core:data → :core:llm` **允许**，与任务书「`:core:data → :core:storage`/`:core:common`」的字面冲突以本条为准（Task 1 落地，有 3e 先例）。
2. **`ErrorText` 的逐行状态短标签** —— **已定（决定 #4）**：Task 9 集中补 5 个键（仍满足「只在 `ErrorText` 里取」），但**字面只能取自需求文档 / `11 §5.1` 原文**：未分析 / 分析中 / 已关联 / 分析失败（`00 §4.3`）、重试中（`05 §2`）。**不得新造词面**。
3. **`LlmNormalizer` 的 DI 装配点** —— **已定**：装配入口由 **P3** 提供（其 Task 7 Step 5 的 `@Provides LlmNormalizer` 调 `buildLlmNormalizer` 组装 `Caching(Retrying(Direct))`）；本计划 Task 8 只消费该绑定，**不重复定义**。

**仍待办（未被六条决定覆盖）**

4. **`retry` 的形态**：spec/05 §4.9 是 `suspend fun retry(fileIds)` 内部驱动；本计划改为返回**冷 Flow**，以便调用方（VM）持有取消权。若必须与 05 逐字一致，则 `retry` 改回 `suspend`，但界面离开时的取消语义要另定。
5. **`retry(fileIds)` 的范围**：05 §4.9 的 `retry` 建完批次后调 `analyzePending`，而后者取的是**全量** `pendingForAnalysis` —— 会误伤未选中的文件。本计划 Task 6 明确「只为给定 fileIds 建批次并只跑这些文件」。
6. **`AnalysisProgress` 的取消事件送达**：冷 Flow 被 `cancel()` 打断时下游收集器同时被取消，故新增热 `progress: SharedFlow` 作为「逐文件事件 + 取消后 `Finished(aborted = true)`」的可靠通道（spec 只写了冷 `Flow<AnalysisProgress>`，未规定 UI 如何订阅）。
7. **设置页的 `defaultPlayMode` / `sortPreference`**：`09 §3.2.13` 列了它们，但本 Phase 4 范围只给 LLM 四项；Task 11 明确不做，回写到 `09` 时标注 defer 到 Phase 5。
8. **内置预设表取值**：`ProviderPreset` 的字段名/类型已定死（Task 7），**取值一律留白**（空表）——按 spec §14 待用户提供，本计划不编造任何 base url / 模型名。
