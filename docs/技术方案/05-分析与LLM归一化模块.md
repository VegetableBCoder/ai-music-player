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
  LlmConfig.kt                # 供应商 / model / baseUrl / 能力标记 / 超时 / maxRetries
  PromptBuilder.kt            # system + user prompt 组装、JSON Schema 生成、promptVersion
  NormalizeParser.kt          # 结构化输出解析、字段校验、标签过滤
  BackoffPolicy.kt            # 退避参数与算法
  DirectProvider.kt           # OkHttp + Retrofit + kotlinx.serialization，直连 /chat/completions
  RetryingLlmNormalizer.kt    # 429 退避装饰器
  CachingLlmNormalizer.kt     # 缓存装饰器
  LlmCache.kt                 # 缓存读写抽象（实现在 :core:data）
  api/LlmApi.kt               # Retrofit 接口
  api/dto/*.kt               # ChatCompletionRequest/Response、JsonSchema、Message
core/data/src/main/kotlin/com/aimusic/player/data/
  AnalysisOrchestrator.kt     # 编排：单 worker 串行、进度、重试、计数
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
| `LlmNormalizer` | `suspend fun normalize(request: NormalizeRequest): NormalizeOutcome` | 统一接口，装饰链最内层契约 |
| `DirectProvider` | 实现 `LlmNormalizer` | HTTP 直连、错误码映射 |
| `RetryingLlmNormalizer` | `(delegate, policy, sleeper, onBackoff)` | 429 指数退避（装饰器） |
| `CachingLlmNormalizer` | `(delegate, cache)` | 结果缓存（装饰器，最外层） |
| `PromptBuilder` | `fun build(req: NormalizeRequest): PromptBundle` | 组装 messages + json_schema |
| `NormalizeParser` | `fun parse(text: String, req: NormalizeRequest): NormalizeOutcome` | 解析校验 |
| `AnalysisOrchestrator` | `analyzePending(runId): Flow<AnalysisProgress>` / `retry(fileIds)` | 编排、状态机驱动、计数 |

> 命名标注：`AnalysisProgress` 与 `llm_cache` 表为本文档新引入的补充（`AnalysisProgress` 在 `02 §5.6` 只出现类型引用、未定义；`llm_cache` 在 `03` 中尚未登记），**不更名、不冲突**，仅补充定义，需同步回 `02` / `03`（详见 §3.5、§4.8 的标注）。

---

# 3. 数据结构与接口

## 3.1 `:core:llm` 公开契约（严格复用 `02 §5.3`，逐字一致）

```kotlin
interface LlmNormalizer {
    suspend fun normalize(request: NormalizeRequest): NormalizeOutcome
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

`02 §5.3` 中 `TagRef` / `TagAssignment` 以注释形式给出，此处固化为数据类：

```kotlin
data class TagRef(val name: String, val category: String)
data class TagAssignment(val category: String, val name: String)
```

## 3.2 配置与退避参数

```kotlin
data class LlmConfig(
    val provider: String,                 // openai | deepseek | zhipu | qwen | kimi ...
    val baseUrl: String,                  // OpenAI 兼容端点，以 "/" 结尾
    val model: String,                    // e.g. "gpt-4o-mini" / "deepseek-chat"
    val apiKey: String,                   // 来自 EncryptedSharedPreferences（不落盘明文、不进日志）
    val supportsJsonSchema: Boolean,      // DataStore: llm_supports_json_schema
    val maxRetries: Int,                  // DataStore: llm_max_retries，即 429 重试上限 N
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

## 3.3 HTTP 层 DTO（kotlinx.serialization）

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
    @SerialName("max_tokens") val maxTokens: Int? = 1_024
)

@Serializable data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList()
) { @Serializable data class Choice(val message: ChatMessage?, val finish_reason: String? = null) }
```

## 3.4 Retrofit 接口

```kotlin
interface LlmApi {
    @POST("chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authorization: String,   // "Bearer sk-****"
        @Header("Content-Type") contentType: String = "application/json",
        @Body body: ChatCompletionRequest
    ): retrofit2.Response<ChatCompletionResponse>   // 用 Response 读取 code 与 Retry-After
}
```

> 用 `Response<T>` 而非直接返回体，是为了**读取 HTTP 状态码与 `Retry-After` 响应头**（429 退避依赖它）。

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
   for each file in pendingForAnalysis():
   │     MusicFileDao.setStatus(fileId, ANALYZING, null, null)        // 保持 ANALYZING
   │     categories = CategoryDao.currentNames()                       // 实时读取
   │     tags       = TagDao.currentTagRefs()                          // 实时读取
   │     req = NormalizeRequest(fileName, metadata, categories, tags)
   │     outcome = normalizer.normalize(req)     // Caching( Retrying( Direct ) )
   │       ├─ Success  → attachAnalysisResult(...) @Transaction → LINKED；analyzed_ok++
   │       ├─ RateLimited（重试耗尽） → FAILED(error_kind=RATE_LIMIT)；failed_count++
   │       └─ Failure  → FAILED(error_kind=…, analysis_error=…)；failed_count++
   │     emit AnalysisProgress.FileUpdated / Running
   │
   ▼
AnalysisRunDao.finish(runId, status=COMPLETED | ABORTED, finished_at)
```

## 4.2 装饰链：顺序与理由

```
调用方 ──► CachingLlmNormalizer ──► RetryingLlmNormalizer ──► DirectProvider ──► HTTPS
              （最外层）                 （429 退避）            （真实网络）
```

```kotlin
val normalizer: LlmNormalizer =
    CachingLlmNormalizer(
        delegate = RetryingLlmNormalizer(
            delegate = DirectProvider(api, config, parser, promptBuilder),
            policy = BackoffPolicy(maxRetries = config.maxRetries),
            onBackoff = { ev -> progressSink.tryEmit(ev) }
        ),
        cache = roomLlmCache
    )
```

**装饰顺序 = `Caching(Retrying(Direct))`，理由：**

1. **缓存最外层**：命中缓存时**完全不发起网络请求**（也不进入退避循环），是"重扫 / 重试不消耗 token"（`01 §3.3`）的关键。若缓存在内层，则每次都要先过退避层再做网络调用。
2. **退避紧贴网络**：429 是传输层现象，退避只应包裹真实 HTTP 调用；缓存层不应感知 429。
3. **只缓存成功结果**：`CachingLlmNormalizer` 仅在 `NormalizeOutcome.Success` 时写缓存；`RateLimited` / `Failure` **不写缓存**（否则会把瞬时故障固化）。而退避在缓存之内，成功结果天然是"退避之后"的最终成功。
4. **可组合、可单测**：每个装饰器只做一件事，可独立注入假实现（`Sleeper`、`LlmCache`、假的 `DirectProvider`）。

## 4.3 `DirectProvider`

### 4.3.1 OkHttp / Retrofit 装配

```kotlin
class DirectProvider(
    private val api: LlmApi,
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
            })
            .build()
    }

    override suspend fun normalize(request: NormalizeRequest): NormalizeOutcome {
        val bundle = promptBuilder.build(request)          // system+user+json_schema
        val body = ChatCompletionRequest(
            model = config.model,
            messages = bundle.messages,
            temperature = 0.0,
            responseFormat = if (config.supportsJsonSchema)
                ResponseFormat("json_schema", JsonSchemaSpec("song_normalization", true, bundle.schema))
            else ResponseFormat("json_object", null),       // 能力降级（01 §8）
            maxTokens = 1_024
        )
        return try {
            val resp = api.chatCompletions("Bearer ${config.apiKey}", "application/json", body)
            when {
                resp.isSuccessful -> {
                    val content = resp.body()?.choices?.firstOrNull()?.message?.content
                        ?: return NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, null)
                    parser.parse(content, request)                 // 见 §4.6
                }
                else -> mapHttpError(resp.code(), resp.headers()["Retry-After"])
            }
        } catch (e: SocketTimeoutException) {
            NormalizeOutcome.Failure(LlmFailureKind.TIMEOUT, e)
        } catch (e: IOException) {                              // 连接失败 / DNS / 断网
            NormalizeOutcome.Failure(LlmFailureKind.NETWORK, e)
        } catch (e: SerializationException) {
            NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, e)
        } catch (e: CancellationException) {
            throw e                                             // 协程取消必须透传
        } catch (e: Throwable) {
            NormalizeOutcome.Failure(LlmFailureKind.SERVER, e)
        }
    }
}
```

```kotlin
private fun mapHttpError(code: Int, retryAfterHeader: String?): NormalizeOutcome {
    val retryAfterMs = parseRetryAfterMs(retryAfterHeader)      // 见 §4.7，支持秒数/HTTP-date
    return when {
        code == 429 -> NormalizeOutcome.RateLimited(retryAfterMs)
        code == 401 || code == 403 -> NormalizeOutcome.Failure(LlmFailureKind.AUTH, HttpError(code))
        code in 500..599 -> NormalizeOutcome.Failure(LlmFailureKind.SERVER, HttpError(code))
        else -> NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, HttpError(code)) // 400 schema 不支持等
    }
}
```

### 4.3.2 请求体示例（`response_format: json_schema`）

```json
{
  "model": "gpt-4o-mini",
  "temperature": 0.0,
  "max_tokens": 1024,
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "song_normalization",
      "strict": true,
      "schema": {
        "type": "object",
        "additionalProperties": false,
        "required": ["canonical_title", "artists", "tags"],
        "properties": {
          "canonical_title": { "type": "string" },
          "artists": { "type": "array", "items": { "type": "string" }, "minItems": 1 },
          "tags": {
            "type": "array",
            "items": {
              "type": "object",
              "additionalProperties": false,
              "required": ["category", "name"],
              "properties": {
                "category": { "type": "string", "enum": ["音乐类型", "情绪", "场景", "主题"] },
                "name": { "type": "string" }
              }
            }
          }
        }
      }
    }
  },
  "messages": [
    { "role": "system", "content": "……（见 §4.4）……" },
    { "role": "user",   "content": "……（见 §4.4）……" }
  ]
}
```

> `tags[].category.enum` 由 `PromptBuilder` **用当前有效分类动态填充**（`04 §3.3`：实时读取、非快照）。
> **能力降级**：模型不支持 json_schema 时，`response_format = {"type":"json_object"}`（部分供应商支持）或省略，改为纯提示词约束 + `NormalizeParser` 容错（剥离 ```json 围栏、截取首个 `{` 到末个 `}`）。

## 4.4 `PromptBuilder`

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
4. tags（标签数组，元素为 {"category": 分类名, "name": 标签名}）：
   - 只能使用【当前有效分类】里列出的分类名；严禁创造新分类。
   - 优先复用【当前有效标签】里已存在的标签；只有当现有标签都不合适时，才可以在某分类下新增标签。
   - 严格遵守「各分类标签数量上限」；宁缺毋滥，不要堆砌近义标签。
   - 如果没有把握或缺少相关知识，可以返回空数组 []，这不算错误。
5. 标签与分类名一律使用简体中文，与输入给出一致。
```

### 4.4.2 user prompt（中文，完整示例）

```
【文件名】
周杰伦 - 晴天(Live).flac

【内嵌元数据】
title: 晴天
artist: 周杰伦
album: 叶惠美
albumArtist: 周杰伦
date: 2003-07-31
durationMs: 269000

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

请只输出 JSON。
```

```kotlin
class PromptBuilder {
    companion object {
        // 修改 prompt / schema 时递增，参与缓存 key（§4.8）
        const val PROMPT_VERSION = "v1"
    }

    fun build(req: NormalizeRequest): PromptBundle {
        val sys = SYSTEM_TEMPLATE                                  // 上面的 system prompt
        val user = buildUser(req)                                  // 上面的 user prompt
        val schema = buildSchema(req.categories)                   // enum 动态注入当前有效分类
        return PromptBundle(
            messages = listOf(ChatMessage("system", sys), ChatMessage("user", user)),
            schema = schema
        )
    }

    private fun buildUser(req: NormalizeRequest): String = buildString {
        appendLine("【文件名】\n${req.fileName}")
        req.metadata?.let { m ->
            appendLine("【内嵌元数据】")
            appendLine("title: ${m.title.orEmpty()}")
            appendLine("artist: ${m.artist.orEmpty()}")
            appendLine("album: ${m.album.orEmpty()}")
            appendLine("albumArtist: ${m.albumArtist.orEmpty()}")
            appendLine("date: ${m.date.orEmpty()}")
            appendLine("durationMs: ${m.durationMs}")
        }
        appendLine("【当前有效分类】（只能使用以下分类，不得新增）")
        req.categories.forEach { appendLine("- $it") }
        appendLine("【当前有效标签】（优先复用；必要时可在对应分类下新增）")
        req.tags.groupBy { it.category }.forEach { (cat, list) ->
            appendLine("- $cat: ${list.joinToString(", ") { it.name }}")
        }
        appendLine("【各分类标签数量上限】" + req.categories.joinToString(", ") { "$it ≤ $maxPerCategory" })
        appendLine("请只输出 JSON。")
    }
}
```

### 4.4.3 输出 JSON Schema（客户端 `schema` 字段，此处以可读形式给出）

```kotlin
fun buildSchema(categories: List<String>): JsonElement = buildJsonObject {
    put("type", "object"); put("additionalProperties", false)
    putJsonArray("required") { add("canonical_title"); add("artists"); add("tags") }
    putJsonObject("properties") {
        putJsonObject("canonical_title") { put("type", "string") }
        putJsonObject("artists") {
            put("type", "array"); put("minItems", 1)
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("tags") {
            put("type", "array")
            putJsonObject("items") {
                put("type", "object"); put("additionalProperties", false)
                putJsonArray("required") { add("category"); add("name") }
                putJsonObject("properties") {
                    putJsonObject("category") {
                        put("type", "string")
                        putJsonArray("enum") { categories.forEach { add(it) } }   // ← 当前有效分类
                    }
                    putJsonObject("name") { put("type", "string") }
                }
            }
        }
    }
}
```

## 4.5 输出解析与校验（`NormalizeParser`）

```kotlin
fun parse(text: String, req: NormalizeRequest): NormalizeOutcome {
    val obj = runCatching { Json.parseToJsonElement(stripFences(text)).jsonObject }.getOrNull()
        ?: return NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, null)

    // 1. canonicalTitle：字段缺失 / 空 → 失败
    val rawTitle = obj["canonical_title"]?.jsonPrimitive?.contentOrNull
    val title = rawTitle?.let(TextNormalizer::title)
    if (title.isNullOrBlank())
        return NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, MissingField("canonical_title"))

    // 2. artists：字段缺失 / 空数组 → 失败（实体身份第二维不可为空，00 §1.2）
    val rawArtists = obj["artists"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
    if (rawArtists.isNullOrEmpty())
        return NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, MissingField("artists"))

    val artists = rawArtists
        .flatMap(TextNormalizer::splitArtists)      // 兜底拆分 feat./&//、等
        .map(TextNormalizer::artist)
        .filter { it.isNotBlank() }
        .distinct()                                 // 去重；不排序（排序在 entityKey 计算时做）

    // 3. tags：逐条校验
    val validCats = req.categories.toSet()
    val assignments = mutableListOf<TagAssignment>()
    for (el in obj["tags"]?.jsonArray.orEmpty()) {
        val name = el.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim()
        val cat  = el.jsonObject["category"]?.jsonPrimitive?.contentOrNull?.trim()
        if (name.isNullOrEmpty()) continue
        if (cat == null || cat !in validCats) {
            logger.warn("drop tag '$name': category '$cat' not in current categories")  // 丢弃 + 记日志
            continue
        }
        assignments += TagAssignment(cat, name)
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
| 根不是 JSON 对象 / 解析异常 | `Failure(INVALID_OUTPUT)` |
| `canonical_title` 缺失或 trim 后为空 | `Failure(INVALID_OUTPUT)` |
| `artists` 缺失 / 空数组 / 全为空串 | `Failure(INVALID_OUTPUT)`（纯音乐也必须有演奏者，`00 §1.2`） |
| 某标签 `name` 为空 | 丢弃该条，`logger.warn` |
| 某标签 `category` 不在**当前有效分类** | 丢弃该条，`logger.warn`（AI 不得新建分类，`04 §2.1`；对齐 `03 §4.1` step 7） |
| 标签 `name` 已存在于**另一分类** | 交给 `attachAnalysisResult`：`tag` 名称全局唯一（I5），以库中实际分类为准，忽略模型给的 `category` |
| 标签数量超上限 | 每分类 `take(maxTagsPerCategory)` |
| `tags` 缺失 / 空数组 | **合法**：`NormalizeResult.tagAssignments = []`，不算失败（`04 §3.3`、`05 §2`） |

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

    override suspend fun normalize(request: NormalizeRequest): NormalizeOutcome {
        var attempt = 0
        while (true) {
            when (val out = delegate.normalize(request)) {
                is NormalizeOutcome.RateLimited -> {
                    if (attempt >= policy.maxRetries) return out      // 重试耗尽 → 上抛，交由编排器置 FAILED
                    val delayMs = backoffDelayMs(attempt, out.retryAfterMs, policy)
                    onBackoff(BackoffEvent(currentCoroutineContext(), attempt + 1, delayMs))
                    sleeper.sleep(delayMs)                            // ★ 退避期间 orchestrator 仍在 await，
                                                                      //   analysis_status 保持 ANALYZING，不计失败
                    attempt++
                }
                else -> return out      // Success / Failure 原样返回（非 429 不自动处理）
            }
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
- **其他错误不自动处理**：只有 `RateLimited` 进入重试；`Failure(NETWORK/AUTH/SERVER/INVALID_OUTPUT/TIMEOUT)` 直接返回（`01 §3.3`、`02 §6.1`）。

## 4.8 缓存（`CachingLlmNormalizer`）

```kotlin
interface LlmCache {
    suspend fun get(key: String): NormalizeResult?
    suspend fun put(key: String, result: NormalizeResult)
}

class CachingLlmNormalizer(
    private val delegate: LlmNormalizer,
    private val cache: LlmCache,
    private val keyProvider: CacheKeyProvider
) : LlmNormalizer {
    override suspend fun normalize(request: NormalizeRequest): NormalizeOutcome {
        val key = keyProvider.keyFor(request)                    // 见下方
        cache.get(key)?.let { return NormalizeOutcome.Success(it) }   // ★ 命中：不发网络请求
        val outcome = delegate.normalize(request)
        if (outcome is NormalizeOutcome.Success) cache.put(key, outcome.result)  // 仅缓存成功
        return outcome
    }
}

object CacheKeyProvider {
    fun keyFor(request: NormalizeRequest, model: String, promptVersion: String): String {
        val fingerprint = buildString {
            request.metadata?.let { m -> append(m.title).append('|').append(m.artist)
                .append('|').append(m.album).append('|').append(m.durationMs) }
        }
        val raw = listOf(request.fileName, fingerprint, model, promptVersion).joinToString("\u001F")
        return sha256Hex(raw)
    }
}
```

**缓存 key 必须包含 `fileName + 元数据指纹 + model + prompt 版本`，理由：**

| 组成 | 为什么必须 |
| --- | --- |
| `fileName` | 文件名本身常含歌名/歌手信息，是分析的主要输入 |
| 元数据指纹（title/artist/album/duration） | 同文件名不同元数据（内嵌标签被改）应重新分析 |
| `model` | 换模型后结果口径不同（归一化与标签都可能变化），旧结果不可复用 |
| `prompt 版本`（`PromptBuilder.PROMPT_VERSION`） | **标签体系/prompt 变更后旧结果不可用**：分类增删、标签复用清单、输出 schema 调整都会改变语义 |

> 说明：`02 §5.3` 给出缓存 key 的初版为 `sha1(fileName + metadata)`；本文档细化为**必须包含 `model` 与 `prompt 版本`**（否则换模型 / 改标签体系后会命中过期结果）。此细化**扩展而非冲突**，需同步登记到 `02 §5.3`。

**命中缓存的流程**

```
normalize(req)
  → key = sha256(fileName ␟ metadataFingerprint ␟ model ␟ promptVersion)
  → cache.get(key)
       命中 → 直接返回 Success(result)   （零网络、零 token）
       未命中 → 进入 Retrying(Direct) → 成功则 cache.put(key, result)
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
    private val progressThrottleMs: Long = 250
) {
    /** 单 worker 串行分析一批文件（runId 由建批次方给出） */
    fun analyzePending(runId: Long): Flow<AnalysisProgress> = flow {
        val files = fileDao.pendingForAnalysis()                 // 只取 UNANALYZED / FAILED
        emit(AnalysisProgress.Started(runId, files.size))
        var done = 0; var ok = 0; var failed = 0
        var lastEmit = 0L
        var aborted = false
        for (file in files) {
            if (!currentCoroutineContext().isActive) { aborted = true; break }   // 取消 → 收尾
            if (file.analysisStatus == AnalysisStatus.LINKED) continue           // I4 兜底
            fileDao.setStatus(file.id, AnalysisStatus.ANALYZING, null, null)     // 保持 ANALYZING
            emit(AnalysisProgress.FileUpdated(file.id, file.fileName, AnalysisStatus.ANALYZING))

            val categories = categoryDao.currentNames()                          // 实时读取
            val tags = tagDao.currentTagRefs()                                   // 实时读取
            val meta = metadataReader.read(FileRef(file.path, file.fileName, file.size, 0))
            val request = NormalizeRequest(file.fileName, meta, categories, tags)

            when (val outcome = normalizer.normalize(request)) {
                is NormalizeOutcome.Success -> {
                    musicDao.commitAnalysisSuccess(file.id, outcome.result, runId, now())  // @Transaction
                    ok++
                    emit(AnalysisProgress.FileUpdated(file.id, file.fileName,
                        AnalysisStatus.LINKED, linkedEntityId = musicDao.lastLinkedEntity(file.id)))
                }
                is NormalizeOutcome.RateLimited -> {                                 // 退避耗尽
                    fileDao.setStatus(file.id, AnalysisStatus.FAILED,
                        error = "RATE_LIMIT", kind = FailureKind.RATE_LIMIT.name)
                    runDao.bumpFailed(runId); failed++
                    emit(AnalysisProgress.FileUpdated(file.id, file.fileName,
                        AnalysisStatus.FAILED, error = FailureKind.RATE_LIMIT.name))
                }
                is NormalizeOutcome.Failure -> {
                    val fk = outcome.kind.toFailureKind()                            // §6 映射表
                    fileDao.setStatus(file.id, AnalysisStatus.FAILED,
                        error = fk.name, kind = fk.name)
                    runDao.bumpFailed(runId); failed++
                    emit(AnalysisProgress.FileUpdated(file.id, file.fileName,
                        AnalysisStatus.FAILED, error = fk.name))
                }
            }
            done++
            val t = now()
            if (t - lastEmit >= progressThrottleMs || done == files.size) {          // 进度节流
                lastEmit = t
                emit(AnalysisProgress.Running(done, files.size, ok, failed))
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

`LlmFailureKind` → `02 §8` `FailureKind` 映射（写入 `music_file.error_kind`）：

| 触发 | `LlmFailureKind` | `NormalizeOutcome` | 状态 | `error_kind`（FailureKind） | 自动恢复 | 用户可见动作 |
| --- | --- | --- | --- | --- | --- | --- |
| HTTP 429 | — | `RateLimited(retryAfterMs)` | 保持 `ANALYZING` | — | **是**（指数退避） | "重试中"，不计失败 |
| 429 重试耗尽 | — | `RateLimited` | `FAILED` | `RATE_LIMIT` | 否 | 「重试」 |
| HTTP 401/403 | `AUTH` | `Failure` | `FAILED` | `AUTH` | 否 | 「去设置」 |
| HTTP 5xx | `SERVER` | `Failure` | `FAILED` | `SERVER` | 否 | 「重试」 |
| 网络不可达 | `NETWORK` | `Failure` | `FAILED` | `NETWORK` | 否 | 「重试」 |
| 连接/读超时 | `TIMEOUT` | `Failure` | `FAILED` | `NETWORK` | 否 | 「重试」 |
| 结构化输出解析失败 | `INVALID_OUTPUT` | `Failure` | `FAILED` | `PARSE` | 否 | 「重试」 |
| 400（schema 不支持） | `INVALID_OUTPUT` | `Failure` | `FAILED` | `PARSE` | 否 | 「重试」（可提示切换模型） |

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
| 畸形 JSON | 返回 `not json` / 缺 `canonical_title` / 空 `artists` → `INVALID_OUTPUT` | §4.5 |
| 标签过滤 | 返回分类不在当前有效集合的标签 → **丢弃**且不写库；日志有 warn | §4.5、需求 `04 §2.1` |
| 0 标签 | 返回 `tags: []` → `Success`、`LINKED`、`analyzed_ok++`、不计失败 | §5.2、需求 `05 §2` |
| 缓存命中不发网络 | 第一次真实返回并写缓存；第二次同 key，断言 MockWebServer `requestCount == 1` | §4.8 |
| 缓存 key 组成 | 改 `model` 或 `PROMPT_VERSION` → 断言 key 变化、缓存未命中 | §4.8 |
| `entityKey` 确定性 | `[林俊杰, 蔡卓妍]` 与 `[蔡卓妍, 林俊杰]` → 相同 `artistsKey`；`U+001F` 连接；排序 Unicode 序 | §4.6、`03 §4.1` |
| 版本语义词保留 | `晴天 (Live)` → `晴天 Live`，与 `晴天` 不同实体 | §4.6、`00 §1.2` |
| 幂等 I4 | 对 `LINKED` 文件再次 `retry` → 无网络调用、实体不重建、标签不重复 | **I4**、§5.4 |
| 计数维护 | N 成功 / M 失败 → `analysis_run.analyzed_ok=N`、`failed_count=M` | §4.9 |
| 中途取消 | 处理到第 k 个时 cancel → 前 k-1 `LINKED`、第 k 个回 `UNANALYZED`、`run.status=ABORTED` | §5.3 |
| 最近分析记录 | `latest()` + `observeRunFiles()` 返回摘要与文件状态 | §4.10 |
| 进度节流 | 大量文件时 `Running` 事件数 ≤ 时间窗上限 | §7.2 |

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
| `02-详细设计总纲.md §5.3` 接口契约（逐字复用） | §3.1 |
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
2. **缓存 key 细化**（本文 §4.8，加入 `model` + `prompt 版本`）→ 更新 `02 §5.3` 的 `CachingLlmNormalizer` 说明。
3. **`commitAnalysisSuccess` 事务包装器**（本文 §4.9）→ 登记到 `03 §4`。
4. **`AnalysisProgress` 定义**（本文 §3.6）→ 登记到 `02 §5.6`。
