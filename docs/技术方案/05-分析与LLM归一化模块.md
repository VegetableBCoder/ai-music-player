# 分析与 LLM 归一化模块 · V1.0

> 版本：V1.0
> 上游依据：`02-详细设计总纲.md`（共享契约：接口 / 状态机 §6.1 / 并发 §7 / 错误模型 §8 / 不变量 §9 / 章节结构 §10）、`03-数据层设计.md`（表结构、`attachAnalysisResult` 事务、DAO）、`01-技术栈与架构.md §3`（LLM 接入）
> 需求依据：`../需求文档/00-总览.md`（§1.2 实体身份键、§2 实体与文件关系、§3 展示信息、§4 扫描与分析流水线、§5 删除模型）、`../需求文档/04-智能标签.md`、`../需求文档/05-空状态与异常处理.md §2`
> 覆盖：`LlmNormalizer` 实现链、`DirectProvider`、`PromptBuilder`、输出解析与实体判定、退避与缓存、`AnalysisOrchestrator`、「最近分析记录」数据来源

---

# 1. 模块职责与边界

## 1.1 做什么

- **一次 LLM 调用完成归一化 + 标签**（需求 `00-总览.md §4.2`）：输入「文件名 + 内嵌元数据」→ 输出「归一化数据（`canonicalTitle` + 歌手集合）+ 标签集合」。
- 组装 prompt 时**实时读取当前有效分类与标签清单**（非固定快照，`04-智能标签.md §3.3`），实现标签复用。
- 把 LLM 的结构化输出解析、校验、归一化为 `NormalizeResult`，交给数据层 `attachAnalysisResult` 事务挂靠实体并写标签。
- 处理 429 自动退避（1s → 2s → 4s … 上限 60s，上限 N 次）；其他错误不自动处理 → 置 `FAILED` 等待手动重试。
- 本地结果缓存，重扫 / 重试不重复消耗 token。

## 1.2 不做什么

- **不访问文件系统 / 不读元数据**（属 `:core:storage`，`MetadataReader`）。分析输入由调用方传入。
- **不直接访问 Room 表**：`core:llm` 不依赖 `core:data`（`02 §2`）。分类 / 标签清单由 `AnalysisOrchestrator` 以参数传入，保证 LLM 层可独立单测。
- **不做实体合并**（需求 `00 §2`、`00 §8` 远期）。
- **不做 UI 文案**（属 `11-错误处理与可观测性.md`）。
- **不改写标签体系结构**：AI 只能新增标签，不能新增分类（`04 §2.1`）；分类不在当前有效集合中时**丢弃**该标签。
- **不重分析已 `LINKED` 的文件**（不变量 **I4**）。

## 1.3 依赖谁

```
:core:llm      ─→ :core:common        （TextNormalizer、Logger、BackoffPolicy、Dispatcher）
:core:data     ─→ :core:llm,:core:storage,:core:common   （AnalysisOrchestrator 编排 + Room 事务 + 缓存表）
:feature:mine  ─→ :core:data          （分析进度页 / 最近分析记录页 调用 Orchestrator）
```

- 数据流：`AnalysisOrchestrator`（:core:data）读文件与标签 → 组装 `NormalizeRequest` → 调 `LlmNormalizer`（:core:llm）→ 结果回写。
- 长任务：分析由 WorkManager 承载（`02 §7`、`10`），`analyzePending` 的 `Flow` 通过 `setForeground` / `setProgress` 桥接到通知。

---

# 2. 关键类与文件清单

```
core/llm/src/main/kotlin/com/aimusic/player/llm/
  LlmNormalizer.kt            # 接口 + 契约数据类（NormalizeRequest/Result/Outcome/LlmFailureKind/TagRef/TagAssignment）
  LlmConfig.kt                # protocol（协议方言）/ model / baseUrl / 能力标记 / batchSize / maxTokens / 超时 / maxRetries
  protocol/LlmCall.kt         # 协议无关中间表示：LlmCall / LlmHttpResult / ProtocolAdapter
  adapter/*.kt                # 三套方言适配器：OpenAiAdapter / ResponsesAdapter / AnthropicAdapter
  PromptBuilder.kt            # 读外置 prompt 资源、渲染占位符、prompt 文件哈希
  NormalizeParser.kt          # 结构化输出解析、字段校验、tag_groups 摊平、标签过滤
  BackoffPolicy.kt            # 退避参数与算法
  DirectProvider.kt           # 方言适配器 + OkHttp，协议无关（不再直连固定路径）
  RetryingLlmNormalizer.kt    # 429 退避装饰器
  CachingLlmNormalizer.kt     # 缓存装饰器
  LlmCache.kt                 # 缓存读写抽象（实现在 :core:data）
  adapter/openai/LlmApi.kt    # OPENAI 适配器私有 Retrofit 接口
  adapter/openai/dto/*.kt     # OPENAI 私有 DTO：ChatCompletionRequest/Response、JsonSchema、Message
core/llm/src/main/resources/prompt/
  system.txt                  # 硬性规则（§4.4.1 + 批量两条）
  user.txt                    # 目录 + 文件清单（§4.4.2 的批量版）
  schema.json                 # 响应骨架（唯一动态处：tag_groups[].category.enum）
core/data/src/main/kotlin/com/aimusic/player/data/
  AnalysisOrchestrator.kt     # 编排：单 worker 串行、按批取文件、进度、重试、计数
  AnalysisProgress.kt         # 进度模型（Flow 事件）
  cache/LlmCacheEntity.kt     # llm_cache 表实体
  cache/LlmCacheDao.kt        # llm_cache DAO
  cache/RoomLlmCache.kt       # LlmCache 实现
core/common/src/main/kotlin/com/aimusic/player/common/
  text/TextNormalizer.kt      # 标题/歌手规范化 + artistsKey 生成
  util/Sleeper.kt             # 可注入的 sleep（测试用假时钟）
```

| 类 | 关键签名 | 职责 |
| --- | --- | --- |
| `LlmNormalizer` | `suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome>` | 统一接口，装饰链最内层契约（一次一批、返回按索引对齐） |
| `DirectProvider` | 实现 `LlmNormalizer` | 经 `ProtocolAdapter` 编解码、错误码映射（协议无关） |
| `RetryingLlmNormalizer` | `(delegate, policy, sleeper, onBackoff)` | 429 指数退避（装饰器） |
| `CachingLlmNormalizer` | `(delegate, cache, keyProvider)` | 结果缓存（装饰器，最外层，逐文件） |
| `PromptBuilder` | `fun build(reqs: List<NormalizeRequest>): PromptBundle` | 读外置资源、渲染占位符 + schema |
| `NormalizeParser` | `fun parse(text: String, req: List<NormalizeRequest>): List<NormalizeOutcome>` | 解析校验、`file_index` 对齐、`tag_groups` 摊平 |
| `AnalysisOrchestrator` | `analyzePending(runId): Flow<AnalysisProgress>` / `retry(fileIds)` | 编排、状态机驱动、计数 |

> 命名标注：`AnalysisProgress` 与 `llm_cache` 表为本文档新引入的补充（`AnalysisProgress` 在 `02 §5.6` 只出现类型引用、未定义；`llm_cache` 在 `03` 中尚未登记），**不更名、不冲突**，仅补充定义，需同步回 `02` / `03`（详见 §3.5、§4.8 的标注）。

---

# 3. 数据结构与接口

## 3.1 `:core:llm` 公开契约（复用 `02 §5.3`；仅 `normalize` 入参改为列表、返回按索引对齐）

```kotlin
interface LlmNormalizer {
    /** 一次一批（默认 20 个文件）；返回与入参等长、按索引对齐。 */
    suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome>
}

data class NormalizeRequest(
    val fileName: String,
    val metadata: AudioMetadata?,   // 来自 :core:storage. MetadataReader
    val categories: List<String>,   // 当前有效分类（实时读取，非快照）
    val tags: List<TagRef>          // 当前有效标签
)

data class NormalizeResult(
    val canonicalTitle: String,
    val artists: List<String>,                  // 全部署名，已归一化、去重，未排序
    val tagAssignments: List<TagAssignment>     // (category, name)
)

sealed interface NormalizeOutcome {
    data class Success(val result: NormalizeResult) : NormalizeOutcome
    data class RateLimited(val retryAfterMs: Long?) : NormalizeOutcome
    data class Failure(val kind: LlmFailureKind, val cause: Throwable?) : NormalizeOutcome
}

enum class LlmFailureKind { NETWORK, AUTH, SERVER, INVALID_OUTPUT, TIMEOUT }
```

> **批量语义**：调用失败（网络 / 超时 / 429 / 5xx / AUTH）使列表内每一项取同一结果（整批同命运、不按文件重发）；解析失败只把对应索引置 `Failure(INVALID_OUTPUT)`，其余照常（见 §4.5、§6）。
> **与 `02 §5.3` 的差异**：`NormalizeRequest` / `NormalizeResult` / `NormalizeOutcome` / `LlmFailureKind` 逐字复用；**唯一改动是 `normalize` 入参由单个 `NormalizeRequest` 改为列表、返回按索引对齐**，需同步修订 `02 §5.3`。

`02 §5.3` 中 `TagRef` / `TagAssignment` 以注释形式给出，此处固化为数据类：

```kotlin
data class TagRef(val name: String, val category: String)
data class TagAssignment(val category: String, val name: String)
```

## 3.2 配置与退避参数

```kotlin
data class LlmConfig(
    val protocol: String,                 // 协议方言：openai | responses | anthropic（服务商只作 UI 预设，不落库）
    val baseUrl: String,                  // 端点前缀，以 "/" 结尾
    val model: String,                    // e.g. "gpt-4o-mini" / "deepseek-chat"
    val apiKey: String,                   // 来自 EncryptedSharedPreferences（不落盘明文、不进日志）
    val supportsJsonSchema: Boolean,      // DataStore: llm_supports_json_schema
    val maxRetries: Int,                  // DataStore: llm_max_retries，即 429 重试上限 N
    val batchSize: Int = 20,              // 一次请求的文件数（DataStore: llm_batch_size）
    val maxTokens: Int = 8_192,           // DataStore: llm_max_tokens
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 90_000,     // LLM 生成较慢，读超时放宽
    val callTimeoutMs: Long = 120_000,
    val maxTagsPerCategory: Int = 2       // 每分类标签数量上限（prompt 约束 0~n 的 n）
)

data class BackoffPolicy(
    val initialDelayMs: Long = 1_000,     // 1s
    val factor: Long = 2,                 // 2 的幂
    val maxDelayMs: Long = 60_000,        // 上限 60s
    val maxRetries: Int                  // = LlmConfig.maxRetries（N）
)
```

## 3.3 协议抽象与三套方言适配器

`:core:llm` 对上层只暴露**协议无关**的调用模型；编解码全部收敛在方言适配器里，重试 / 缓存 / 解析 / JSON 强制对三方言完全无知。

```kotlin
enum class ProtocolKind { OPENAI, RESPONSES, ANTHROPIC }

/** 协议无关的中间表示；适配器负责把它封成各方言的信封。 */
data class LlmCall(
    val system: String,
    val user: String,
    val schema: JsonElement,      // 骨架 + 运行时填入的分类 enum
    val model: String,
    val maxTokens: Int,
    val temperature: Double = 0.0
)

sealed interface LlmHttpResult {
    data class Ok(val payloadJson: String) : LlmHttpResult   // 统一还原成 JSON 字符串
    data class HttpError(val status: Int, val retryAfterMs: Long?, val body: String) : LlmHttpResult
    data class Transport(val cause: Throwable) : LlmHttpResult
}

interface ProtocolAdapter {
    fun encode(call: LlmCall, cfg: LlmConfig): HttpRequestSpec   // url / headers / body
    fun decode(status: Int, headers: Map<String, String>, body: String): LlmHttpResult
}
```

方言差异全部落在适配器里（`normalize` 之上的重试 / 缓存 / 解析层对下表一无所知）：

| | `OPENAI` | `RESPONSES` | `ANTHROPIC` |
| --- | --- | --- | --- |
| 路径 | `POST {base}/chat/completions` | `POST {base}/responses` | `POST {base}/messages` |
| 鉴权 | `Authorization: Bearer <k>` | 同左 | `x-api-key: <k>` + `anthropic-version: 2023-06-01` |
| system 位置 | `messages[0].role=system` | 顶层 `instructions` | 顶层 `system`（**不是** message） |
| 结构化输出 | `response_format.json_schema` | `text.format{type:json_schema,name,strict,schema}` | `tools[].input_schema` + `tool_choice` 强制 `tool_use` |
| 取结果 | `choices[0].message.content`（字符串） | `output[]` 中 `type=message` 的 `content[].text` | `content[]` 中 `type=tool_use` 的 `input`（已解析对象，适配器负责 `Json.encodeToString` 还原成 JSON 字符串） |
| 长度参数 | `max_tokens` | `max_output_tokens` | `max_tokens` |
| 限流 | `429` + `Retry-After` | 同左 | 同左；另有 `529` overloaded → 归 `SERVER`（不重试） |

`ChatMessage` / `ChatCompletionRequest` / `LlmApi` 等只是 **`OPENAI` 适配器的私有 DTO**（见 §3.4），不再作为总纲对外：

```kotlin
@Serializable data class ChatMessage(val role: String, val content: String)

@Serializable data class ResponseFormat(
    val type: String,                    // "json_schema" | "json_object"
    @SerialName("json_schema") val jsonSchema: JsonSchemaSpec? = null
)
@Serializable data class JsonSchemaSpec(
    val name: String, val strict: Boolean = true, val schema: JsonElement
)

@Serializable data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.0,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null   // 由 LlmCall.maxTokens 填入
)

@Serializable data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList()
) { @Serializable data class Choice(val message: ChatMessage?, val finish_reason: String? = null) }
```

## 3.4 `OPENAI` 适配器的 Retrofit 接口（私有）

```kotlin
interface LlmApi {                       // 仅 OPENAI 适配器使用
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authorization: String,   // "Bearer sk-****"
        @Header("Content-Type") contentType: String = "application/json",
        @Body body: ChatCompletionRequest
    ): retrofit2.Response<ChatCompletionResponse>   // 用 Response 读取 code 与 Retry-After
}
```

> 用 `Response<T>` 而非直接返回体，是为了**读取 HTTP 状态码与 `Retry-After` 响应头**（429 退避依赖它）。
> `RESPONSES` / `ANTHROPIC` 方言使用各自的路径、鉴权头与信封（见 §3.3 表），不共用本接口。

## 3.5 新增表 `llm_cache`

```sql
CREATE TABLE llm_cache (
  cache_key  TEXT    PRIMARY KEY,
  result_json TEXT   NOT NULL,
  created_at INTEGER NOT NULL
);
```

```kotlin
@Entity(tableName = "llm_cache")
data class LlmCacheEntity(
    @PrimaryKey @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "result_json") val resultJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Dao
interface LlmCacheDao {
    @Query("SELECT result_json FROM llm_cache WHERE cache_key = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: LlmCacheEntity)

    @Query("DELETE FROM llm_cache WHERE created_at < :before")
    suspend fun evictOlderThan(before: Long)
}
```

> **⚠ 本表为对 `03-数据层设计.md` 的补充，需同步登记到 03**：新增 `LlmCacheEntity` 到 `MusicDatabase.entities`（`03 §2.1`）、新增第 14 个 DAO（`03 §3`）、随系统 DB 版本**加表不迁移**（`03 §8`——首个发布版本起用 `Migration` 加表）。缓存表**不参与业务级联删除**：删除歌曲实体**不清空缓存**（缓存按 `cache_key` 内容寻址，与实体无外键关系，属可复用资产）。

## 3.6 `AnalysisProgress`（进度事件，Flow 上报）

```kotlin
sealed interface AnalysisProgress {
    data class Started(val runId: Long, val total: Int) : AnalysisProgress
    data class FileUpdated(
        val fileId: Long, val fileName: String,
        val status: AnalysisStatus,     // ANALYZING | LINKED | FAILED
        val linkedEntityId: Long? = null, val error: String? = null
    ) : AnalysisProgress
    data class Retrying(val fileId: Long, val attempt: Int, val delayMs: Long) : AnalysisProgress
    data class Running(val done: Int, val total: Int, val ok: Int, val failed: Int) : AnalysisProgress
    data class Finished(val runId: Long, val ok: Int, val failed: Int, val aborted: Boolean) : AnalysisProgress
}
```

`AnalysisStatus` 直接复用 `03 §2.3` 定义：`enum class AnalysisStatus { UNANALYZED, ANALYZING, LINKED, FAILED }`。

---

# 4. 核心流程

## 4.1 端到端时序

```
UI「分析并添加」
   │  ① 文件入库（analysis_status = UNANALYZED）+ 建批次
   ▼
AnalysisRunDao.createRun(status=RUNNING, new_count=…) → runId
AnalysisRunDao.linkFiles(runId, fileIds)                     // analysis_run_file
   │
   ▼
AnalysisOrchestrator.analyzePending(runId) : Flow<AnalysisProgress>   // 单 worker 串行
   │
   for batch in pendingForAnalysis().chunked(config.batchSize):       // 一次一批，默认 20
   │     categories = CategoryDao.currentNames()                       // 每批实时读取一次
   │     tags       = TagDao.currentTagRefs()                          // 每批实时读取一次
   │     reqs = batch.map { NormalizeRequest(it.fileName, it.metadata, categories, tags) }
   │     batch.forEach { MusicFileDao.setStatus(it.id, ANALYZING, null, null) }  // 保持 ANALYZING
   │     outcomes = normalizer.normalize(reqs)   // Caching( Retrying( Direct ) )，返回按索引对齐
   │     outcomes.forEachIndexed { i, outcome ->                       // 逐文件落状态 / 计数 / 上报
   │       ├─ Success  → attachAnalysisResult(...) @Transaction → LINKED；analyzed_ok++
   │       ├─ RateLimited（重试耗尽） → FAILED(error_kind=RATE_LIMIT)；failed_count++
   │       └─ Failure  → FAILED(error_kind=…, analysis_error=…)；failed_count++
   │       emit AnalysisProgress.FileUpdated / Running
   │     }
   │
   ▼
AnalysisRunDao.finish(runId, status=COMPLETED | ABORTED, finished_at)
```

> **为什么一次一批**：批内文件共享同一份「分类 + 标签目录」（user prompt 目录在前、文件清单在后，见 §4.4.2），目录只发一次 —— 这是批量真正省 token 的地方；进度仍逐文件上报（`AnalysisProgress.FileUpdated`），批只是传输粒度。

## 4.2 装饰链：顺序与理由

```
调用方 ──► CachingLlmNormalizer ──► RetryingLlmNormalizer ──► DirectProvider ──► 方言适配器 ──► HTTPS
              （最外层，逐文件）          （429 退避）            （真实网络）
```

```kotlin
val normalizer: LlmNormalizer =
    CachingLlmNormalizer(
        delegate = RetryingLlmNormalizer(
            delegate = DirectProvider(
                adapter = adapterFor(config.protocol),       // openai / responses / anthropic
                http = HttpTransport(DirectProvider.createHttpClient(config)),
                config = config, promptBuilder = promptBuilder, parser = parser
            ),
            policy = BackoffPolicy(maxRetries = config.maxRetries),
            onBackoff = { ev -> progressSink.tryEmit(ev) }
        ),
        cache = roomLlmCache,
        keyProvider = CacheKeyProvider
    )
```

**装饰顺序 = `Caching(Retrying(Direct))`，理由：**

1. **缓存最外层**：命中缓存时**完全不发起网络请求**（也不进入退避循环），是"重扫 / 重试不消耗 token"（`01 §3.3`）的关键。若缓存在内层，则每次都要先过退避层再做网络调用。（批量下逐文件查缓存：全命中即零网络，部分命中时只把未命中的塞进本次请求，见 §4.8。）
2. **退避紧贴网络**：429 是传输层现象，退避只应包裹真实 HTTP 调用；缓存层不应感知 429。
3. **只缓存成功结果**：`CachingLlmNormalizer` 仅在 `NormalizeOutcome.Success` 时写缓存；`RateLimited` / `Failure` **不写缓存**（否则会把瞬时故障固化）。而退避在缓存之内，成功结果天然是"退避之后"的最终成功。
4. **可组合、可单测**：每个装饰器只做一件事，可独立注入假实现（`Sleeper`、`LlmCache`、假的 `DirectProvider`）。

## 4.3 `DirectProvider`

### 4.3.1 适配器装配与调用

`DirectProvider` 只认 `ProtocolAdapter` 与协议无关的 `LlmCall` / `LlmHttpResult`；具体路径 / 鉴权头 / system 位置 / 结构化输出字段全在适配器里（§3.3 表）。

```kotlin
class DirectProvider(
    private val adapter: ProtocolAdapter,
    private val http: HttpTransport,                   // 发送 HttpRequestSpec，返回 LlmHttpResult
    private val config: LlmConfig,
    private val promptBuilder: PromptBuilder,
    private val parser: NormalizeParser
) : LlmNormalizer {

    companion object {
        fun createHttpClient(config: LlmConfig): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)   // 15s
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)         // 90s
            .writeTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(config.callTimeoutMs, TimeUnit.MILLISECONDS)         // 120s
            .retryOnConnectionFailure(false)   // 重试只在我们的退避层做，避免双重退避
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")   // Key 不进日志（01 §3.1）
                redactHeader("x-api-key")       // anthropic 方言
            })
            .build()
    }

    override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
        val bundle = promptBuilder.build(requests)         // system+user+schema（读外置资源）
        val call = LlmCall(
            system = bundle.system, user = bundle.user, schema = bundle.schema,
            model = config.model, maxTokens = config.maxTokens, temperature = 0.0
        )
        val spec = adapter.encode(call, config)            // 各方言：url / headers / body
        val result = try {
            http.send(spec)                                // 统一还原成 LlmHttpResult
        } catch (e: CancellationException) {
            throw e                                        // 协程取消必须透传
        } catch (e: Throwable) {
            LlmHttpResult.Transport(e)
        }
        return when (result) {
            is LlmHttpResult.Ok        -> parser.parse(result.payloadJson, requests)   // 见 §4.5
            is LlmHttpResult.HttpError -> batchFailure(mapHttpError(result.status, result.retryAfterMs), requests.size)
            is LlmHttpResult.Transport -> batchFailure(transportKind(result.cause), requests.size)
        }
    }
}
```

```kotlin
/** 整批同命运：调用失败时列表内每一项同值（不按文件重发，见 §6）。 */
private fun batchFailure(kind: LlmFailureKind, size: Int): List<NormalizeOutcome> =
    List(size) { NormalizeOutcome.Failure(kind, null) }

private fun transportKind(cause: Throwable): LlmFailureKind = when (cause) {
    is SocketTimeoutException -> LlmFailureKind.TIMEOUT      // 连接 / 读超时
    is IOException -> LlmFailureKind.NETWORK                 // 连接失败 / DNS / 断网
    else -> LlmFailureKind.SERVER
}

private fun mapHttpError(code: Int, retryAfterMs: Long?): NormalizeOutcome = when {   // 见 §4.7
    code == 429 -> NormalizeOutcome.RateLimited(retryAfterMs)
    code == 401 || code == 403 -> NormalizeOutcome.Failure(LlmFailureKind.AUTH, HttpError(code))
    code == 529 -> NormalizeOutcome.Failure(LlmFailureKind.SERVER, HttpError(code))   // overloaded，不重试
    code in 500..599 -> NormalizeOutcome.Failure(LlmFailureKind.SERVER, HttpError(code))
    else -> NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, HttpError(code)) // 400 schema 不支持等
}
```

### 4.3.2 请求体示例（三份方言）

同一份 `LlmCall`（system / user / schema 见 §4.4）由适配器封装成各方言；下列以 `response_format: json_schema` 档为例。

`OPENAI`（`POST {base}/chat/completions`）

```json
{
  "model": "gpt-4o-mini",
  "temperature": 0.0,
  "max_tokens": 8192,
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "song_normalization",
      "strict": true,
      "schema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["results"],
        "properties": {
          "results": {
            "type": "array", "minItems": 1,
            "items": {
              "type": "object", "additionalProperties": false,
              "required": ["file_index", "canonical_title", "artists", "tag_groups"],
              "properties": {
                "file_index":      { "type": "integer", "minimum": 1 },
                "canonical_title": { "type": "string" },
                "artists":         { "type": "array", "items": { "type": "string" }, "minItems": 1 },
                "tag_groups": {
                  "type": "array",
                  "items": {
                    "type": "object", "additionalProperties": false,
                    "required": ["category", "tags"],
                    "properties": {
                      "category": { "type": "string", "enum": ["音乐类型", "情绪", "场景", "主题"] },
                      "tags":     { "type": "array", "items": { "type": "string" }, "minItems": 1 }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  },
  "messages": [
    { "role": "system", "content": "……（system.txt，见 §4.4.1）……" },
    { "role": "user",   "content": "……（user.txt，见 §4.4.2）……" }
  ]
}
```

`RESPONSES`（`POST {base}/responses`）

```json
{
  "model": "gpt-4o-mini",
  "instructions": "……（system.txt）……",
  "input": "……（user.txt）……",
  "text": { "format": { "type": "json_schema", "name": "song_normalization", "strict": true,
                        "schema": { /* 同 OPENAI 骨架（§4.4.3），enum 注入当前有效分类 */ } } },
  "max_output_tokens": 8192
}
```

`ANTHROPIC`（`POST {base}/messages`，头含 `x-api-key` / `anthropic-version`）

```json
{
  "model": "claude-3-5-sonnet",
  "system": "……（system.txt）……",
  "messages": [ { "role": "user", "content": "……（user.txt）……" } ],
  "tools": [ { "name": "song_normalization",
               "input_schema": { /* 同 OPENAI 骨架（§4.4.3），enum 注入当前有效分类 */ } } ],
  "tool_choice": { "type": "tool", "name": "song_normalization" },
  "max_tokens": 8192
}
```

> `tag_groups[].category.enum` 由 `PromptBuilder` **用当前有效分类动态填充**（`04 §3.3`：实时读取、非快照）。
> **能力降级**：模型不支持 json_schema 时，`OPENAI` 用 `response_format = {"type":"json_object"}`（部分供应商支持）或省略，改为纯提示词约束 + `NormalizeParser` 容错（剥离 ```json 围栏、截取首个 `{` 到末个 `}`）。

## 4.4 `PromptBuilder`

prompt 与 schema **外置为资源文件**（`core/llm/src/main/resources/prompt/`），改文案不用碰代码；占位符 `{{categories}}` / `{{tags}}` / `{{maxPerCategory}}` / `{{files}}` 用最朴素的字符串替换（不引模板引擎），schema 定死骨架、只有分类 enum 运行时注入。

### 4.4.1 system prompt（中文，完整示例）

```
你是本地音乐库的元数据归一化助手。你的唯一任务：根据用户给出的「文件名 + 内嵌元数据」，输出一个结构化 JSON 对象，用于 (1) 确定歌曲实体身份，(2) 为歌曲打标签。

【硬性规则】
1. 只输出一个 JSON 对象；不要输出任何解释、问候、Markdown 代码块围栏或多余文本。
2. canonical_title（归一化歌名）：
   - 去除首尾空格；统一全角/半角；拉丁字母按标题惯例统一大小写。
   - 必须保留版本语义词（Live / Remix / 伴奏 / 现场 / 翻唱 / 原唱 / Instrumental 等）——它们用于区分不同录音，绝不能删除。
   - 去掉文件扩展名，以及「歌名 - 歌手」中的非歌名部分。
3. artists（全部署名演唱者数组）：
   - 将 feat. / with / & / / 、等分隔符统一拆分，每个演唱者作为一个独立元素。
   - 把歌手别名归一到规范名（例如「周董」「Jay Chou」→「周杰伦」）。
   - 去重；不要排序。纯音乐请填演奏者（如 坂本龙一），不要留空。
4. tag_groups（标签分组数组，元素为 {"category": 分类名, "tags": [标签名, …]}）：
   - 只能使用【当前有效分类】里列出的分类名；严禁创造新分类。
   - 优先复用【当前有效标签】里已存在的标签；只有当现有标签都不合适时，才可以在某分类下新增标签。
   - 严格遵守「各分类标签数量上限」；宁缺毋滥，不要堆砌近义标签。
   - 如果没有把握或缺少相关知识，可以返回空数组 []，这不算错误。
5. 标签与分类名一律使用简体中文，与输入给出一致。
6. 一次会给你【多个文件】。必须返回一个对象 {"results": [...]}，results 的长度必须等于输入文件数；
   每项用 file_index（与输入的【文件 N】一致）标明属于哪个文件。不得遗漏、不得新增、不得改变顺序。
7. 某个文件信息不足时仍然要输出该项：artists 至少给一个你能确定的署名（拿不准就用文件名里的歌手，
   再不行用「未知艺术家」），tag_groups 可以是 []。不要跳过任何 file_index。
```

### 4.4.2 user prompt（中文，完整示例 → 外置为 `user.txt` 模板）

模板 `user.txt`（**目录在前、文件清单在后**，`{{…}}` 由 `PromptBuilder` 替换）：

```
【当前有效分类】（只能使用以下分类，不得新增）
{{categories}}

【当前有效标签】（优先复用；必要时可在对应分类下新增）
{{tags}}

【各分类标签数量上限】
{{maxPerCategory}}

【文件清单】
{{files}}

请只输出 JSON。
```

渲染后（2 个文件的批次示意；20 个文件共享同一份目录，目录只发一次）：

```
【当前有效分类】（只能使用以下分类，不得新增）
- 音乐类型
- 情绪
- 场景
- 主题

【当前有效标签】（优先复用；必要时可在对应分类下新增）
- 音乐类型: 流行, 摇滚, 电子, 古典, 爵士, ACG, 纯音乐
- 情绪: 治愈, 悲伤, 热血, 欢快, 宁静, 怀旧
- 场景: 工作, 学习, 驾驶, 睡前, 运动, 旅行
- 主题: 动漫, 游戏, 电影, 青春, 爱情

【各分类标签数量上限】
音乐类型 ≤ 2, 情绪 ≤ 2, 场景 ≤ 1, 主题 ≤ 2

【文件清单】
【文件 1】
文件名: 周杰伦 - 晴天(Live).flac
内嵌元数据:
  title: 晴天
  artist: 周杰伦
  album: 叶惠美
  albumArtist: 周杰伦
  date: 2003-07-31
  durationMs: 269000

【文件 2】
文件名: 未知艺术家 - Track 03.mp3
内嵌元数据: （无）

请只输出 JSON。
```

```kotlin
class PromptBuilder(private val resources: PromptResources) {   // 读 core/llm/src/main/resources/prompt/

    /** system.txt + user.txt + schema.json 文件内容的 sha256，参与缓存 key（§4.8） */
    fun promptHash(): String = resources.promptHash()

    fun build(reqs: List<NormalizeRequest>): PromptBundle {
        val categories = reqs.first().categories                     // 每批实时读取一次（非快照）
        val tags = reqs.first().tags
        val user = resources.user()                                  // 目录在前、文件清单在后
            .substitute("{{categories}}", renderCategories(categories))
            .substitute("{{tags}}", renderTags(tags))
            .substitute("{{maxPerCategory}}", renderLimits(categories))
            .substitute("{{files}}", renderFiles(reqs))               // 逐文件【文件 N】+ 文件名 + 内嵌元数据
        val schema = Json.parseToJsonElement(
            resources.schemaJson().substitute("{{categories}}", categories.joinToString("\",\""))
        )
        return PromptBundle(system = resources.system(), user = user, schema = schema)
    }
    // renderCategories / renderTags / renderLimits / renderFiles：纯字符串拼接，见上例
}
```

### 4.4.3 输出 JSON Schema（外置为 `schema.json`）

`schema.json` 定死响应骨架（唯一动态处：`tag_groups[].category.enum`，由 `{{categories}}` 注入当前有效分类）：

```json
{
  "type": "object", "additionalProperties": false, "required": ["results"],
  "properties": {
    "results": {
      "type": "array", "minItems": 1,
      "items": {
        "type": "object", "additionalProperties": false,
        "required": ["file_index", "canonical_title", "artists", "tag_groups"],
        "properties": {
          "file_index":      { "type": "integer", "minimum": 1 },
          "canonical_title": { "type": "string" },
          "artists":         { "type": "array", "minItems": 1, "items": { "type": "string" } },
          "tag_groups": {
            "type": "array",
            "items": {
              "type": "object", "additionalProperties": false,
              "required": ["category", "tags"],
              "properties": {
                "category": { "type": "string", "enum": ["{{categories}}"] },
                "tags":     { "type": "array", "minItems": 1, "items": { "type": "string" } }
              }
            }
          }
        }
      }
    }
  }
}
```

> **防漂移测试**：`schema.json` 的 `required` 字段集合 == `NormalizeParser` 认识的字段集合，对不上即红（§8）。
> 不让它由 Kotlin 类型自动生成 —— 自动生成会在改字段时**悄悄改掉发给模型的契约**，而 parser 的判定规则还在代码里，两边必然漂移。

## 4.5 输出解析与校验（`NormalizeParser`）

解析整批响应、按 `file_index` 对回入参，并把 `tag_groups` 摊平成 `TagAssignment`。入口签名锁定为 `parse(text, req)`（`text` 为适配器还原后的 JSON 字符串；批处理下 `req` 为整批请求，返回与入参等长、按索引对齐）：

```kotlin
fun parse(text: String, req: List<NormalizeRequest>): List<NormalizeOutcome> {
    val obj = runCatching { Json.parseToJsonElement(stripFences(text)).jsonObject }.getOrNull()
        ?: return allFailure(req.size, LlmFailureKind.INVALID_OUTPUT)     // 整批不可解析（不兜底，见二期）

    // 0. results 必须是数组（缺 / 非数组 → 整批失败）
    val items = obj["results"]?.jsonArray
        ?: return allFailure(req.size, LlmFailureKind.INVALID_OUTPUT)
    if (items.size != req.size)
        logger.warn("results 长度 ${items.size} != 输入 ${req.size}")     // 长度不等不整批连坐

    // 1. 按 file_index 对回（1 起，与 prompt 中【文件 N】一致）；不用文件名做键
    val byIndex = HashMap<Int, JsonObject>()
    for (el in items) {
        val idx = el.jsonObject["file_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (idx == null || idx < 1 || idx > req.size || byIndex.put(idx, el.jsonObject) != null) {
            logger.warn("drop result: file_index=${el.jsonObject["file_index"]} 非法 / 越界 / 重复")
            continue                                        // 该文件判失败，其余照常
        }
    }
    return req.mapIndexed { i, r ->
        val o = byIndex[i + 1] ?: return@mapIndexed NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, null)
        parseOne(o, r)
    }
}

/** 整批失败：列表内每一项同值（整批不可解析 / 缺 results 等）。 */
private fun allFailure(size: Int, kind: LlmFailureKind): List<NormalizeOutcome> =
    List(size) { NormalizeOutcome.Failure(kind, null) }

/** 单个条目：标题 / 歌手 / tag_groups；条目级失败只连坐本文件。 */
private fun parseOne(obj: JsonObject, req: NormalizeRequest): NormalizeOutcome {
    // canonicalTitle：字段缺失 / 空 → 失败
    val title = obj["canonical_title"]?.jsonPrimitive?.contentOrNull?.let(TextNormalizer::title)
    if (title.isNullOrBlank())
        return NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, MissingField("canonical_title"))

    // artists：字段缺失 / 空数组 → 失败（实体身份第二维不可为空，00 §1.2）
    val rawArtists = obj["artists"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
    if (rawArtists.isNullOrEmpty())
        return NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, MissingField("artists"))
    val artists = rawArtists
        .flatMap(TextNormalizer::splitArtists)      // 兜底拆分 feat./&//、等
        .map(TextNormalizer::artist)
        .filter { it.isNotBlank() }
        .distinct()                                 // 去重；不排序（排序在 entityKey 计算时做）

    // tag_groups：逐组判分类 → 组内逐条判名字；摊平成 TagAssignment
    val validCats = req.categories.toSet()
    val assignments = mutableListOf<TagAssignment>()
    for (group in obj["tag_groups"]?.jsonArray.orEmpty()) {
        val cat = group.jsonObject["category"]?.jsonPrimitive?.contentOrNull?.trim()
        if (cat == null || cat !in validCats) {
            logger.warn("drop tag group: category '$cat' not in current categories")  // 整组丢弃
            continue
        }
        for (t in group.jsonObject["tags"]?.jsonArray.orEmpty()) {
            val name = t.jsonPrimitive.contentOrNull?.trim()
            if (name.isNullOrEmpty()) { logger.warn("drop tag: empty name"); continue }  // 丢该条
            assignments += TagAssignment(cat, name)
        }
    }
    // 每分类数量上限兜底（prompt 已约束，此处防御）
    val capped = assignments.groupBy { it.category }
        .flatMap { (_, list) -> list.distinctBy { it.name }.take(req.maxTagsPerCategory) }

    return NormalizeOutcome.Success(NormalizeResult(title, artists, capped))
}
```

**判定规则汇总**

| 情形 | 处理 |
| --- | --- |
| **整批**根不是 JSON 对象 / 解析异常 | 整批 `Failure(INVALID_OUTPUT)`（不做逐个重发兜底，见二期） |
| **整批**缺 `results` / `results` 非数组 | 整批 `Failure(INVALID_OUTPUT)` |
| `results` 长度 ≠ 输入文件数 | `logger.warn`；缺项的索引判失败，其余照常（**不**整批连坐） |
| 某条缺 `file_index` / index 越界（不在 `1..N`）/ index 重复 | 该文件 `Failure(INVALID_OUTPUT)`，**同批其余照常** |
| 某条 `canonical_title` 缺失或 trim 后为空 | 该文件 `Failure(INVALID_OUTPUT)`，其余照常 |
| 某条 `artists` 缺失 / 空数组 / 全为空串 | 该文件 `Failure(INVALID_OUTPUT)`（纯音乐也必须有演奏者，`00 §1.2`），其余照常 |
| 某**组** `category` 不在**当前有效分类** | 丢弃该**组**，`logger.warn`（AI 不得新建分类，`04 §2.1`；对齐 `03 §4.1` step 7） |
| 某**条**标签 `tags[].name` 为空 | 丢弃该条，`logger.warn` |
| 某标签名已存在于**另一分类** | 交给 `attachAnalysisResult`：`tag` 名称全局唯一（I5），以库中实际分类为准，忽略模型给的 `category` |
| 某分类标签数超上限 | 组内 `take(maxTagsPerCategory)` |
| `tag_groups` 缺失 / 空数组 | **合法**：`NormalizeResult.tagAssignments = []`，不算失败（`04 §3.3`、`05 §2`） |

**归一化规则（`TextNormalizer`）**

| 步骤 | 规则 |
| --- | --- |
| 去首尾空格 | `trim()` |
| 全/半角统一 | 全角 ASCII（U+FF01–U+FF5E）→ 半角；全角空格 U+3000 → 半角空格 |
| 大小写 | 拉丁字母按标题惯例统一（`canonicalTitle` 采用词首大写规范化，比较时大小写不敏感）；CJK 原样保留 |
| 版本语义词 | **保留** Live / Remix / 伴奏 / 现场 / 翻唱 / 原唱 / Instrumental 等（`00 §1.2`） |
| 分隔符统一 | `feat.` / `with` / `&` / `/` / `、` → 统一按分隔符拆分为多个歌手（`splitArtists`） |
| 去重 | 歌手集合去重（内容归一后 `distinct`） |
| 别名归一 | **由模型完成**（如 `Jay Chou`→`周杰伦`）；客户端只做确定性归一，不做别名映射 |

示例（对齐 `00 §1.2` 表）：

| 文件元数据 | 归一化结果 |
| --- | --- |
| `晴天 - 周杰伦` | `(晴天, [周杰伦])` |
| `晴天 - 周杰伦（原唱）` | `(晴天, [周杰伦])` |
| `小酒窝 - 林俊杰 & 蔡卓妍` | `(小酒窝, [林俊杰, 蔡卓妍])` → artistsKey 排序后 `[蔡卓妍, 林俊杰]` |
| `晴天 (Live) - 周杰伦` | `(晴天 Live, [周杰伦])` |

## 4.6 `entityKey` 计算

`entityKey` = `(canonicalTitle, artistsKey)` 二元组；`artistsKey` 与 `03 §4.1` step 2 **完全一致**：

```kotlin
object TextNormalizer {
    const val SEP = "\u001F"   // Unit Separator

    /** 03 §4.1 step 2：artistsKey = 归一化后 排序 去重 以 U+001F 连接 */
    fun artistsKey(artists: List<String>): String =
        artists.map(::artist).filter { it.isNotBlank() }
            .distinct().sorted().joinToString(SEP)

    fun title(raw: String): String   // 去首尾空格、全半角、大小写、保留版本语义词
    fun artist(raw: String): String  // 同上去空格/全半角/大小写
    fun splitArtists(raw: String): List<String>  // 分隔符拆分兜底
}
```

- **确定性**：`sorted()` 使用 Unicode 码位序（不做拼音，`03 §5`）；`distinct()` 先于排序。
- **精确比较**：`song_entity` 上有 `UNIQUE INDEX idx_song_identity(canonical_title, artists_key)`（`03 §2.2`），命中即同一实体。
- **保留版本语义词**：`title()` 归一化时**不得剥离** Live / Remix / 伴奏 / 现场 / 翻唱 / 原唱 / Instrumental（`00 §1.2`），否则「晴天」与「晴天 Live」会被错误判为同一录音。
- **已知残留**：`A` 与 `A feat. B` 会拆成两个实体，V1 不合并（`00 §2`）。

## 4.7 退避算法（`RetryingLlmNormalizer`）

```kotlin
class RetryingLlmNormalizer(
    private val delegate: LlmNormalizer,
    private val policy: BackoffPolicy,
    private val sleeper: Sleeper = RealSleeper,      // 可注入假时钟，单测用
    private val onBackoff: (BackoffEvent) -> Unit = {}
) : LlmNormalizer {

    override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
        var attempt = 0
        while (true) {
            val outs = delegate.normalize(requests)
            // 429 是批级现象：整批同命运，任一项 RateLimited 即整批退避重试
            val limited = outs.firstOrNull { it is NormalizeOutcome.RateLimited } as? NormalizeOutcome.RateLimited
                ?: return outs                                   // 无 429 → 原样返回（Success / Failure 不自动处理）
            if (attempt >= policy.maxRetries) return outs        // 重试耗尽 → 上抛，交由编排器置 FAILED
            val delayMs = backoffDelayMs(attempt, limited.retryAfterMs, policy)
            onBackoff(BackoffEvent(currentCoroutineContext(), attempt + 1, delayMs))
            sleeper.sleep(delayMs)                               // ★ 退避期间 orchestrator 仍在 await，
                                                                 //   analysis_status 保持 ANALYZING，不计失败
            attempt++
        }
    }
}

fun backoffDelayMs(attempt: Int, retryAfterMs: Long?, policy: BackoffPolicy): Long {
    // ① Retry-After 优先（服务端指示等待多久）
    val base = retryAfterMs
        ?: (policy.initialDelayMs * (1L shl attempt))   // ② 退避：1s, 2s, 4s, 8s …
    val capped = base.coerceAtMost(policy.maxDelayMs)   // ③ 上限 60s
    val half = capped / 2
    return Random.nextLong(half, capped + 1)            // ④ 抖动：区间 [cap/2, cap]，避免重试风暴
}

fun parseRetryAfterMs(header: String?): Long? = when {
    header.isNullOrBlank() -> null
    header.all { it.isDigit() } -> header.toLong() * 1000        // 秒数
    else -> runCatching {                                            // HTTP-date
        Duration.between(Instant.now(), ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()
    }.getOrNull()?.coerceAtLeast(0)
}
```

- **重试上限 N**：`policy.maxRetries = LlmConfig.maxRetries`（DataStore `llm_max_retries`，`03 §6`）。
- **`Retry-After` 优先**：非空时覆盖指数基数，但仍受 `maxDelayMs=60s` 上限约束。
- **退避期间保持 ANALYZING、不计失败**：`sleeper.sleep` 在 `normalize()` 的 `await` 内完成；`AnalysisOrchestrator` 在调用前已置 `ANALYZING`，调用返回前既不改状态、也不递增 `failed_count`。同时 `onBackoff` 让编排器 emit `AnalysisProgress.Retrying`，前端展示"重试中"（`05 §2`）。
- **其他错误不自动处理**：只有 `RateLimited` 进入重试；`Failure(NETWORK/AUTH/SERVER/INVALID_OUTPUT/TIMEOUT)` 直接返回（`01 §3.3`、`02 §6.1`）。其中 **`529` overloaded 归 `SERVER`，不重试**（与需求 `00 §4.3`「5xx 不自动重试」一致）；只有 `429` 进退避。

## 4.8 缓存（`CachingLlmNormalizer`）

```kotlin
interface LlmCache {
    suspend fun get(key: String): NormalizeResult?
    suspend fun put(key: String, result: NormalizeResult)
}

class CachingLlmNormalizer(
    private val delegate: LlmNormalizer,
    private val cache: LlmCache,
    private val keyProvider: CacheKeyProvider,
    private val model: String,
    private val promptHash: () -> String,         // prompt 文件内容 sha256（§4.4.2）
    private val dirFingerprint: () -> String      // 目录指纹：分类名 + 标签名的规范化串
) : LlmNormalizer {
    override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
        // 批量下逐文件查缓存；全命中即零网络，未命中的才进本次请求（§6）
        val keys = requests.map { keyProvider.keyFor(it, model, promptHash(), dirFingerprint()) }
        val cached = keys.map { cache.get(it) }
        val missIdx = requests.indices.filter { cached[it] == null }
        if (missIdx.isEmpty()) return cached.map { NormalizeOutcome.Success(it!!) }   // ★ 零网络

        val fresh = delegate.normalize(missIdx.map { requests[it] })                  // 只发未命中
        val out = cached.toMutableList()
        missIdx.forEachIndexed { j, i ->
            val o = fresh[j]
            if (o is NormalizeOutcome.Success) cache.put(keys[i], o.result)           // 仅缓存成功
            out[i] = o
        }
        return out
    }
}

object CacheKeyProvider {
    fun keyFor(request: NormalizeRequest, model: String, promptHash: String, dirFingerprint: String): String {
        val fingerprint = buildString {
            request.metadata?.let { m -> append(m.title).append('|').append(m.artist)
                .append('|').append(m.album).append('|').append(m.durationMs) }
        }
        val raw = listOf(request.fileName, fingerprint, model, promptHash, dirFingerprint)
            .joinToString("\u001F")
        return sha256Hex(raw)
    }
}
```

**缓存 key = sha256(`fileName` ␟ 元数据指纹 ␟ `model` ␟ prompt 文件哈希 ␟ 目录指纹)，理由：**

| 组成 | 为什么必须 |
| --- | --- |
| `fileName` | 文件名本身常含歌名/歌手信息，是分析的主要输入 |
| 元数据指纹（title/artist/album/duration） | 同文件名不同元数据（内嵌标签被改）应重新分析 |
| `model` | 换模型后结果口径不同（归一化与标签都可能变化），旧结果不可复用 |
| prompt 文件哈希（`system.txt` + `user.txt` + `schema.json` 内容的 sha256） | **取代**手工 `PROMPT_VERSION`：prompt 外置后手工版本号必被遗忘（改了 prompt 却一直吃旧缓存）；用文件哈希则改文件即失效 |
| 目录指纹（分类名 + 标签名的规范化串 sha256） | 用户**新建分类**后，同一文件若只按旧 key 命中缓存会永远缺这个新分类的标签；把它纳入 key 才能让新分类生效 |

> 说明：`02 §5.3` 给出缓存 key 的初版为 `sha1(fileName + metadata)`；本文档细化为**必须包含 `model`、prompt 文件哈希与目录指纹**（否则换模型 / 改标签体系 / 新建分类后会命中过期结果）。此细化**扩展而非冲突**，需同步登记到 `02 §5.3`。

**命中缓存的流程**

```
normalize(reqs)                                    // 一次一批
  逐文件 key = sha256(fileName ␟ 元数据指纹 ␟ model ␟ promptHash ␟ dirFingerprint)
  → 逐文件 cache.get(key)
       全命中 → 直接返回（零网络、零 token）
       部分命中 → 只把未命中的塞进本次请求；成功则逐文件 cache.put(key, result)
```

- 缓存表仅存 `result_json`（`NormalizeResult` 的序列化），与实体无外键，删除实体不清缓存（§3.5）。
- 可选维护：`LlmCacheDao.evictOlderThan(now - 90d)` 由后台任务定期清理。

## 4.9 `AnalysisOrchestrator`

```kotlin
class AnalysisOrchestrator(
    private val fileDao: MusicFileDao,
    private val runDao: AnalysisRunDao,
    private val tagDao: TagDao,
    private val categoryDao: CategoryDao,
    private val metadataReader: MetadataReader,
    private val musicDao: MusicDatabaseWriteDao,   // 承载 attachAnalysisResult
    private val normalizer: LlmNormalizer,
    private val batchSize: Int = 20,
    private val progressThrottleMs: Long = 250
) {
    /** 单 worker 串行分析一批文件（runId 由建批次方给出） */
    fun analyzePending(runId: Long): Flow<AnalysisProgress> = flow {
        val files = fileDao.pendingForAnalysis()                 // 只取 UNANALYZED / FAILED
        emit(AnalysisProgress.Started(runId, files.size))
        var done = 0; var ok = 0; var failed = 0
        var lastEmit = 0L
        var aborted = false
        for (batch in files.chunked(batchSize)) {                // 一次一批，默认 20
            if (!currentCoroutineContext().isActive) { aborted = true; break }   // 取消 → 收尾
            val pending = batch.filter { it.analysisStatus != AnalysisStatus.LINKED }   // I4 兜底
            if (pending.isEmpty()) continue

            val categories = categoryDao.currentNames()                          // 每批实时读取一次
            val tags = tagDao.currentTagRefs()                                   // 每批实时读取一次
            val reqs = pending.map { file ->
                val meta = metadataReader.read(FileRef(file.path, file.fileName, file.size, 0))
                NormalizeRequest(file.fileName, meta, categories, tags)
            }
            pending.forEach {
                fileDao.setStatus(it.id, AnalysisStatus.ANALYZING, null, null)   // 保持 ANALYZING
                emit(AnalysisProgress.FileUpdated(it.id, it.fileName, AnalysisStatus.ANALYZING))
            }

            val outcomes = normalizer.normalize(reqs)            // 返回与 reqs 等长、按索引对齐
            pending.forEachIndexed { i, file ->
                when (val outcome = outcomes[i]) {
                    is NormalizeOutcome.Success -> {
                        musicDao.commitAnalysisSuccess(file.id, outcome.result, runId, now())  // @Transaction
                        ok++
                        emit(AnalysisProgress.FileUpdated(file.id, file.fileName,
                            AnalysisStatus.LINKED, linkedEntityId = musicDao.lastLinkedEntity(file.id)))
                    }
                    is NormalizeOutcome.RateLimited -> {                          // 退避耗尽
                        fileDao.setStatus(file.id, AnalysisStatus.FAILED,
                            error = "RATE_LIMIT", kind = FailureKind.RATE_LIMIT.name)
                        runDao.bumpFailed(runId); failed++
                        emit(AnalysisProgress.FileUpdated(file.id, file.fileName,
                            AnalysisStatus.FAILED, error = FailureKind.RATE_LIMIT.name))
                    }
                    is NormalizeOutcome.Failure -> {
                        val fk = outcome.kind.toFailureKind()                     // §6 映射表
                        fileDao.setStatus(file.id, AnalysisStatus.FAILED,
                            error = fk.name, kind = fk.name)
                        runDao.bumpFailed(runId); failed++
                        emit(AnalysisProgress.FileUpdated(file.id, file.fileName,
                            AnalysisStatus.FAILED, error = fk.name))
                    }
                }
                done++
                val t = now()
                if (t - lastEmit >= progressThrottleMs || done == files.size) {   // 进度节流
                    lastEmit = t
                    emit(AnalysisProgress.Running(done, files.size, ok, failed))
                }
            }
        }
        runDao.finish(runId, if (aborted) RunStatus.ABORTED else RunStatus.COMPLETED, now())
        emit(AnalysisProgress.Finished(runId, ok, failed, aborted))
    }.flowOn(Dispatchers.IO)

    /** 手动重试（仅 UNANALYZED / FAILED 可重试；幂等，见 I4） */
    suspend fun retry(fileIds: List<Long>) {
        val runId = runDao.createRun(RunStatus.RUNNING, now())
        runDao.linkFiles(runId, fileIds)
        analyzePending(runId).collect { /* 驱动到完成；进度经 progressSink 上报 */ }
    }
}
```

**与 `attachAnalysisResult` 事务对接**

- 数据层按 `03 §4.1` 提供 `attachAnalysisResult(fileId, result, now)`：① `status==LINKED` 直接返回（I4 幂等）；② 计算 `artistsKey`；③ 按 `(canonicalTitle, artistsKey)` 查/建实体；④ `MusicFileDao.link` 置 `LINKED`；⑤ 代表文件选举；⑥ 标签写入（**分类不在当前有效集合 → 丢弃**，与 §4.5 一致）；⑦ 刷新展示字段。
- 编排器用一个 `@Transaction` 包装器把「挂靠 + 计数递增」放在同一事务：

```kotlin
@Transaction
suspend fun commitAnalysisSuccess(fileId: Long, result: NormalizeResult, runId: Long, now: Long) {
    attachAnalysisResult(fileId, result, now)   // 03 §4.1
    runDao.bumpAnalyzedOk(runId)                // analysis_run.analyzed_ok++
}
```
（该包装器为对 `03` 的补充，需同步登记到 `03 §4`。）

**`analysis_run` 计数维护**

| 事件 | 维护 |
| --- | --- |
| 建批次 | `createRun(status=RUNNING)`；`new_count` / `skipped_count` / `cleaned_count` 由扫描阶段写入（`03 §2.2`） |
| 成功 | `analyzed_ok++`（与挂靠同事务，幂等见 I4） |
| 失败 | `failed_count++`，`music_file.error_kind = FailureKind.name`、`analysis_error = 人类可读原因` |
| 结束 | `status = COMPLETED`；中途取消 `status = ABORTED`，写 `finished_at` |

**失败落库**：`music_file.analysis_status = 'FAILED'`、`error_kind = <FailureKind>`、`analysis_error = <简短原因>`（如 `HTTP 401` / `rate limit exhausted` / `invalid json`）。文案由 `11` 统一映射，模块内不硬编码用户可见文案（`02 §8`）。

## 4.10 「最近分析记录」数据来源与查询

数据来源为 `analysis_run` + `analysis_run_file`（`03 §2.2`、`03 §3.7`）：

```kotlin
// 最近一次批次摘要
@Query("SELECT * FROM analysis_run ORDER BY started_at DESC LIMIT 1") suspend fun latest(): AnalysisRunEntity?

// 该批次的文件清单 + 状态 +（若已挂靠）所属实体
@Query("""SELECT f.*, r.entity_id AS linkedEntityId FROM music_file f
          JOIN analysis_run_file arf ON arf.file_id = f.id
          LEFT JOIN song_entity r ON r.id = f.entity_id
          WHERE arf.run_id = :runId ORDER BY f.file_name""")
fun observeRunFiles(runId: Long): Flow<List<RunFileRow>>
```

- **摘要**：`new_count`（新增）/ `skipped_count`（已存在跳过）/ `cleaned_count`（已删除清理）/ `analyzed_ok`（成功）/ `failed_count`（失败）→ 对应 `00 §4.6`、`05 §2` 的"成功 X / 失败 Y"。
- **文件清单与状态**：`RunFileRow.analysisStatus ∈ {UNANALYZED, ANALYZING, LINKED, FAILED}`，`LINKED` 行显示所属歌曲（`linkedEntityId → song_entity.canonical_title`），`FAILED` 行显示原因并可「重试」。
- **入口**：`文件扫描 → 最近分析记录`（`00 §4.6` 二级常驻入口），失败 / 未分析项可继续操作（`retry` / 触发分析）。

---

# 5. 状态与边界条件

## 5.1 与状态机（`02 §6.1`）的对应

| 事件 | `music_file.analysis_status` | 是否自动恢复 | 备注 |
| --- | --- | --- | --- |
| 用户点「分析并添加」 | `UNANALYZED → ANALYZING` | — | 建 `analysis_run` |
| 429 | **保持 `ANALYZING`** | 是（退避重试） | 不计失败；emit `Retrying`，前端"重试中" |
| 成功 | `ANALYZING → LINKED` | — | **终态**，不再重分析 |
| 其他错误 / 解析失败 / 429 重试耗尽 | `ANALYZING → FAILED` | 否 | 手动重试 |
| 手动重试 | `FAILED → ANALYZING` | — | 仅 `FAILED` / `UNANALYZED` 可重试 |
| 实体被手动删除 | `LINKED → UNANALYZED` | — | 文件回到未关联，可再分析（`00 §2`） |

## 5.2 LLM 无知识：输出 0 标签

- `tags` 为空数组是**合法输出**：`NormalizeResult.tagAssignments = []`，`normalize()` 返回 `Success`。
- 走正常挂靠：歌曲**正常入库**（`analysis_status = LINKED`），`analyzed_ok++`，**不计失败**（`05 §2`、`04 §3.3`）。
- UI：歌曲列表页不显示标签区；播放页显示「暂无标签」（`05 §4`）。

## 5.3 同一批次中途取消的收尾

```
取消（协程 cancel / 用户点取消）
  → 当前 in-flight 文件：analysis_status 由 ANALYZING 回退为 UNANALYZED
     （取消不是错误，不置 FAILED；下次可从断点继续）
  → 已 LINKED 的文件保持 LINKED（不回滚，I1 已成立）
  → analysis_run.status = ABORTED，写 finished_at
  → emit AnalysisProgress.Finished(aborted = true)
```
- 断点续跑：`pendingForAnalysis()` 天然只返回 `UNANALYZED` / `FAILED`，再次触发即从剩余开始。
- 缓存使已成功文件即使被再次选中也不消耗 token（§4.8）。

## 5.4 重复重试的幂等性（I4）

- **入口过滤**：`retry` / `analyzePending` 只处理 `UNANALYZED` / `FAILED`，不选 `LINKED`。
- **事务兜底**：`attachAnalysisResult` 首行判断 `status == LINKED → 直接返回`（`03 §4.1` step 1），即使重复调用也不会二次挂靠、不重复建实体。
- **唯一索引兜底**：`idx_song_identity(canonical_title, artists_key)` 保证同身份实体唯一；`entity_tag` 主键 + `OnConflict.IGNORE` 保证标签关联不重复。
- 结论：对同一文件重复 `retry` 是**幂等**的——要么被入口跳过，要么事务内直接返回。

## 5.5 其他边界

| 边界 | 处理 |
| --- | --- |
| Key 无效（401/403） | `Failure(AUTH)` → `FAILED`；UI「去设置」 |
| json_schema 不被支持（400） | 按能力降级为提示词约束 + 容错解析（§4.3.2）；仍失败 → `INVALID_OUTPUT` |
| 标签名已存在于其它分类 | 以库中实际分类为准（I5 全局唯一），忽略模型给的分类 |
| 标签分类不在当前有效集合 | 丢弃该标签 + 记日志（§4.5） |
| 元数据全为空 | 仅凭 `fileName` 分析（`metadata` 可为 `null`） |
| `LINKED` 文件被选中分析 | 入口跳过 + 事务兜底（I4） |

---

# 6. 错误处理

`LlmFailureKind` → `02 §8` `FailureKind` 映射（写入 `music_file.error_kind`）。失败分两类，作用范围不同：**调用失败**（网络 / 超时 / 429 / 5xx / `AUTH`）是传输层现象、与具体文件无关，**整批同命运、不按文件重发**；**解析失败**（`INVALID_OUTPUT`）是**条目级**，只连坐对应的那个文件（整批不可解析除外）。

| 触发 | `LlmFailureKind` | `NormalizeOutcome` | 状态 | `error_kind`（FailureKind） | 自动恢复 | 用户可见动作 |
| --- | --- | --- | --- | --- | --- | --- |
| HTTP 429 | — | `RateLimited(retryAfterMs)` | 保持 `ANALYZING` | — | **是**（指数退避） | "重试中"，不计失败 |
| 429 重试耗尽 | — | `RateLimited` | 整批 `FAILED` | `RATE_LIMIT` | 否 | 「重试」 |
| HTTP 401/403 | `AUTH` | `Failure` | 整批 `FAILED` | `AUTH` | 否 | 「去设置」 |
| HTTP 5xx / 529 | `SERVER` | `Failure` | 整批 `FAILED` | `SERVER` | 否 | 「重试」 |
| 网络不可达 | `NETWORK` | `Failure` | 整批 `FAILED` | `NETWORK` | 否 | 「重试」 |
| 连接/读超时 | `TIMEOUT` | `Failure` | 整批 `FAILED` | `NETWORK` | 否 | 「重试」 |
| 结构化输出解析失败 | `INVALID_OUTPUT` | `Failure` | 该文件 `FAILED`（其余照常） | `PARSE` | 否 | 「重试」 |
| 400（schema 不支持） | `INVALID_OUTPUT` | `Failure` | 整批 `FAILED` | `PARSE` | 否 | 「重试」（可提示切换模型） |

```kotlin
fun LlmFailureKind.toFailureKind(): FailureKind = when (this) {
    LlmFailureKind.AUTH -> FailureKind.AUTH
    LlmFailureKind.SERVER -> FailureKind.SERVER
    LlmFailureKind.NETWORK -> FailureKind.NETWORK
    LlmFailureKind.TIMEOUT -> FailureKind.NETWORK     // FailureKind 无 TIMEOUT，归入 NETWORK
    LlmFailureKind.INVALID_OUTPUT -> FailureKind.PARSE
}
```

- 文案统一由 `11-错误处理与可观测性.md` 维护，模块内只落 `error_kind` / `analysis_error`。
- 崩溃报告 / 日志中 `Authorization` 一律脱敏（`sk-****`，`01 §3.1`、`03 §7`）。

---

# 7. 性能与并发

## 7.1 串行 vs 并发

- **单 worker 串行**（`02 §7`）：一次只发一个 LLM 请求，**避免并发触发供应商限流（429）**。限流下并发只会制造更多 429 与更长的退避，得不偿失。
- 数据量为百~千级（`03 §5`），单次请求约数秒，串行总时长可接受，且进度反馈线性可预期。
- **前/后处理可并行**：元数据读取、`entityKey` 计算、标签写入是本地 CPU/IO，快于网络调用一个数量级，无需并行；真正瓶颈是网络，故整体串行。
- 未来若确需加速：引入**有界并发**（如信号量限 2–3）并在 429 时全局降速——列为远期，V1 不做。

## 7.2 进度节流

- `AnalysisProgress.FileUpdated` 仅在文件状态变更时 emit（轻量）。
- `AnalysisProgress.Running`（聚合计数）按 **≥250ms** 或**最后一个文件**才 emit（§4.9 的 `progressThrottleMs`），配合 UI 侧 `.conflate()`，避免高频重组。
- `analyzePending` 的 `flowOn(Dispatchers.IO)`：网络与 DB 写都在 IO 线程（`02 §7`）。

## 7.3 其他

- 缓存命中为**零网络**路径（§4.8），重扫 / 重试不重复消耗 token（`01 §3.3`）。
- `analyzePending` 由 WorkManager 承载（`01 §2`、`02 §7`）：过期重试交给系统，退避策略沿用 `BackoffPolicy.EXPONENTIAL`（WorkManager 层）+ 本模块层内 429 退避（两层职责不同：前者重试整个任务，后者重试单次请求）。
- 单写者原则（`02 §7`）：`music_file.analysis_status` **只由 `AnalysisOrchestrator` 修改**。

---

# 8. 测试要点

| 测试 | 手段 | 覆盖 |
| --- | --- | --- |
| 429 退避时序 | MockWebServer 连续返回 429（带/不带 `Retry-After`）；注入假 `Sleeper` 断言 delay = 1s,2s,4s…≤60s | §4.7 |
| `Retry-After` 优先 | 返回 `Retry-After: 5` 断言首延迟≈5s（受 60s 上限约束） | §4.7 |
| 429 重试耗尽 | 始终 429，`maxRetries=N`，断言最终 `RateLimited` → `FAILED` + `error_kind=RATE_LIMIT` | §4.7、§6 |
| 5xx 不自动重试 | MockWebServer 返回 500，断言**只发一次请求**，`Failed(SERVER)` | 需求 `00 §4.3` |
| 401/403 → AUTH | 断言 `LlmFailureKind.AUTH`、`error_kind=AUTH` | §6 |
| 网络异常 | 关闭 MockWebServer / 注入 `IOException` → `NETWORK`；`SocketTimeoutException` → `TIMEOUT` | §6 |
| 畸形 JSON | 返回 `not json` → **整批** `INVALID_OUTPUT`；缺 `canonical_title` / 空 `artists` → **只该文件** `INVALID_OUTPUT` | §4.5 |
| 标签过滤 | 返回分类不在当前有效集合的**组** → **整组丢弃**且不写库；日志有 warn | §4.5、需求 `04 §2.1` |
| 0 标签 | 返回 `tag_groups: []` → `Success`、`LINKED`、`analyzed_ok++`、不计失败 | §5.2、需求 `05 §2` |
| 缓存命中不发网络 | 第一次真实返回并写缓存；第二次同 key，断言 MockWebServer `requestCount == 1` | §4.8 |
| 缓存 key 组成 | 改 `model` / prompt 文件（如 `system.txt`）/ 目录（新增一个分类）→ 断言 key 变化、缓存未命中 | §4.8 |
| `entityKey` 确定性 | `[林俊杰, 蔡卓妍]` 与 `[蔡卓妍, 林俊杰]` → 相同 `artistsKey`；`U+001F` 连接；排序 Unicode 序 | §4.6、`03 §4.1` |
| 版本语义词保留 | `晴天 (Live)` → `晴天 Live`，与 `晴天` 不同实体 | §4.6、`00 §1.2` |
| 幂等 I4 | 对 `LINKED` 文件再次 `retry` → 无网络调用、实体不重建、标签不重复 | **I4**、§5.4 |
| 计数维护 | N 成功 / M 失败 → `analysis_run.analyzed_ok=N`、`failed_count=M` | §4.9 |
| 中途取消 | 处理到第 k 个时 cancel → 前 k-1 `LINKED`、第 k 个回 `UNANALYZED`、`run.status=ABORTED` | §5.3 |
| 最近分析记录 | `latest()` + `observeRunFiles()` 返回摘要与文件状态 | §4.10 |
| 进度节流 | 大量文件时 `Running` 事件数 ≤ 时间窗上限 | §7.2 |
| 三适配器编码 | 断言各协议的 url / 鉴权头 / system 位置 / 结构化输出字段 | §3.3 |
| 三适配器解码 | 三份真实响应样本 → 都还原成同一段 JSON 字符串（含 anthropic 的 `input` 对象） | §3.3 |
| `529 → SERVER` 不重试 | MockWebServer 返回 529，断言只发一次请求 | §3.3、§4.7 |
| 批量编码 | 20 个文件 → 一次请求、`results` 结构、目录只出现一次 | §4.1、§4.4.2 |
| `file_index` 回填 | 乱序返回 → 按 index 对回正确文件 | §4.5 |
| 条目级失败隔离 | 一项标题空 → 只该文件 `FAILED`，其余 `LINKED` | §4.5、§6 |
| 整批不可解析 | 返回 `not json` → 整批 `INVALID_OUTPUT` | §4.5、§6 |
| 分组结构解析 | 一组多标签 → 摊平成多条 `TagAssignment`；无效分类整组丢弃 | §4.5 |
| 部分命中 | 20 个文件里 5 个命中 → 只发 15 个 | §4.8 |
| schema 防漂移 | `schema.json` 的 `required` == parser 认识字段 | §4.4.3 |
| prompt 资源可读 | JVM 单测能读到三个资源文件并渲染占位符 | §4.4.2 |

---

# 9. 与需求条目的对应关系

| 需求出处 | 本文章节 |
| --- | --- |
| `00-总览.md §1.2` 实体身份键（规范化歌名 + 排序去重歌手集合、保留版本语义词） | §4.6、§4.5 |
| `00-总览.md §2` 实体与文件关系（不重建实体、一致即挂靠、已知残留、不做合并） | §4.9、§5.4 |
| `00-总览.md §3` 展示信息来源（演唱者集合、专辑/封面取代表文件） | §2、§4.9、`03 §4.3` |
| `00-总览.md §4.2` 一次 LLM 调用完成归一化 + 标签 | §1.1、§4.1 |
| `00-总览.md §4.3` 分析状态机（429 退避、其他错误 → 失败可重试） | §4.7、§5.1、§6 |
| `00-总览.md §4.4` 触发流程（文件入库 → 分析进度） | §4.1、§4.9 |
| `00-总览.md §4.6` 分析进度与「最近分析记录」入口 | §4.9、§4.10 |
| `00-总览.md §5` 删除模型（删除实体 → 文件回未关联可再分析） | §5.1、§5.4 |
| `00-总览.md §6` 术语表（分析 / 归一化 / 挂靠） | §1、§4.9 |
| `04-智能标签.md §3.1` 生成依据（文件名 + 元数据 → 归一化 + 标签） | §4.4、§4.5 |
| `04-智能标签.md §3.3` 复用机制（实时读取、非快照）、0~n、可为 0 标签 | §4.4、§5.2 |
| `04-智能标签.md §2.1` AI 只能新增标签、不能新增分类 | §4.5（丢弃越界分类）、§1.2 |
| `04-智能标签.md §3.4` 已知行为（删除的标签可能被再次生成） | §4.8（prompt 不设黑名单、实时读取） |
| `04-智能标签.md §4.3` 已关联文件不支持重新分析 | §5.1、§5.4（I4） |
| `05-空状态与异常处理.md §2` 分析中/部分失败/全部失败/429/0 标签 | §4.4、§5.1、§5.2、§6 |
| `01-技术栈与架构.md §3` LLM 接入（BYOK、接口抽象、调用策略、缓存） | 全文；§3.1、§4.2、§4.4 |
| `02-详细设计总纲.md §5.3` 接口契约（复用；`normalize` 入参改列表见 §3.1 说明） | §3.1 |
| `02-详细设计总纲.md §6.1` 状态机 | §5.1 |
| `02-详细设计总纲.md §7` 单 worker 串行 / 写事务 / 单写者 | §7.1、§4.9 |
| `02-详细设计总纲.md §8` 错误模型映射 | §6 |
| `02-详细设计总纲.md §9` 不变量 I4 | §5.4、§8 |
| `03-数据层设计.md §2.2` `music_file` / `analysis_run` / `analysis_run_file` 表 | §4.9、§4.10、§3.5 |
| `03-数据层设计.md §4.1` `attachAnalysisResult` 事务 | §4.9 |
| `03-数据层设计.md §3.7` `AnalysisRunDao` 查询 | §4.10 |

---

## 附：需同步登记的补充项（对 `02` / `03`）

1. **`llm_cache` 表**（本文 §3.5）→ 登记到 `03 §2.1`（entities）、`03 §3`（第 14 个 DAO）、`03 §8`（加表迁移）。
2. **缓存 key 细化**（本文 §4.8，加入 `model` + prompt 文件哈希 + 目录指纹）→ 更新 `02 §5.3` 的 `CachingLlmNormalizer` 说明。
3. **`commitAnalysisSuccess` 事务包装器**（本文 §4.9）→ 登记到 `03 §4`。
4. **`AnalysisProgress` 定义**（本文 §3.6）→ 登记到 `02 §5.6`。
5. **`normalize` 入参改列表、返回按索引对齐**（本文 §3.1）→ 同步修订 `02 §5.3` 的接口契约。
