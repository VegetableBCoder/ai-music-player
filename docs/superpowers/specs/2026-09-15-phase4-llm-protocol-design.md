# Phase 4 · LLM 协议层设计（请求内容 / 响应格式 / 多方言）

日期：2026-09-15
状态：待审（brainstorm 已定案，审完转 writing-plans 出实施计划，再动代码）
依据：`docs/技术方案/05-分析与LLM归一化模块.md`（下称 05）、`02 §5.3`、`03 §2.2/§6/§7`、`04-智能标签.md`（需求）、`11 §3.3/§5.1`

---

## 1. 范围

本设计覆盖 `:core:llm` 的全部内容与 `AnalysisOrchestrator` 的 LLM 交互面：

- **协议层**：一份内部模型 + 三套方言适配器（`responses` / `openai` / `anthropic`）
- **强制 JSON**：分层强制与降级
- **批处理**：一次请求多个文件（默认 20）
- **prompt 与 schema**：外置为资源文件，可改而不用改代码
- **缓存与重试**：装饰链、缓存 key 的组成
- **失败分类**：调用失败 vs 解析失败
- **界面**：`AnalysisRunScreen` 的进度口径、`SettingsScreen` 的字段与预设

不在本设计内：`AnalysisOrchestrator` 的状态机细节（沿用 `05 §4.9`/§5.3）、分析页的列表与多选（`09 §3.2.10`）、预设表的具体取值（**后续由用户填**：协议类型 / base url / 默认模型名）。

---

## 2. 分层与依赖

```
AnalysisOrchestrator (:core:data)
        │  只认 LlmNormalizer 与 LlmCache 两个接口
        ▼
CachingLlmNormalizer ──► RetryingLlmNormalizer ──► DirectProvider ──► 方言适配器 ──► HTTPS
   (:core:llm)               (:core:llm)              (:core:llm)        三选一
        │
        └──► LlmCache : 接口在 :core:llm，实现 RoomLlmCache 在 :core:data
```

- **依赖守卫**只允许 `:core:llm → :core:common`，所以 `llm_cache` 的**接口定义在 `:core:llm`、实现在 `:core:data`** —— 与 `StorageSource` / `AnalysisTrigger` 同一路数。
- 装饰顺序 `Caching(Retrying(Direct))` 沿用 `05 §4.2` 及其四条理由（缓存最外层＝命中即零网络；退避紧贴网络；只缓存成功；可独立单测）。

---

## 3. 协议抽象：内部模型 + 三套适配器

`protocol` 只负责**编解码**；重试、缓存、解析、JSON 强制全在协议之上，对三种方言完全无知。

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

**四种方言差异全部收敛在适配器里：**

| | `OPENAI` | `RESPONSES` | `ANTHROPIC` |
| --- | --- | --- | --- |
| 路径 | `POST {base}/chat/completions` | `POST {base}/responses` | `POST {base}/messages` |
| 鉴权 | `Authorization: Bearer <k>` | 同左 | `x-api-key: <k>` + `anthropic-version: 2023-06-01` |
| system 位置 | `messages[0].role=system` | 顶层 `instructions` | 顶层 `system`（**不是** message） |
| 结构化输出 | `response_format.json_schema` | `text.format{type:json_schema,name,strict,schema}` | `tools[].input_schema` + `tool_choice` 强制 `tool_use` |
| 取结果 | `choices[0].message.content`（字符串） | `output[]` 中 `type=message` 的 `content[].text` | `content[]` 中 `type=tool_use` 的 **`input`（对象）** |
| 长度参数 | `max_tokens` | `max_output_tokens` | `max_tokens` |
| 限流 | `429` + `Retry-After` | 同左 | 同左；另有 **`529` overloaded** |

**两条硬约束：**

1. `NormalizeParser.parse(text: String, req)` 的签名是文档锁定的，**一字不改**。`ANTHROPIC` 的 `tool_use.input` 是已解析对象，由**适配器负责 `Json.encodeToString` 还原成 JSON 字符串**后再交给 parser。
2. `529` 归 **`SERVER`**（不重试），与需求 `00 §4.3`「5xx 不自动重试」一致；只有 `429` 进退避。

---

## 4. 强制 JSON：分层强制 + 降级

| 层级 | 条件 | 手段 |
| --- | --- | --- |
| L1 | `cfg.supportsJsonSchema == true` | 声明 schema（上表第 4 行）—— 模型被约束在 schema 内 |
| L2 | 协议不支持 schema，但支持 JSON mode | `response_format={"type":"json_object"}`（仅 `OPENAI` 有此档） |
| L3 | 以上都不行 | 纯提示词约束 + parser 容错：剥 ``` 围栏、截取首个 `{` 到末个 `}` |

`supportsJsonSchema` 是**可手动覆盖**的配置项（`03 §6` 的 `llm_supports_json_schema`），因为各家兼容实现的实际支持面不一致，猜不可靠。

---

## 5. prompt 与 schema 外置

**放在资源文件里**，改文案不用碰代码：

```
core/llm/src/main/resources/prompt/
  system.txt      # 硬性规则（05 §4.4.1 + 批量两条）
  user.txt        # 目录 + 文件清单（05 §4.4.2 的批量版）
  schema.json     # 响应骨架（唯一动态处：tag_groups[].category.enum）
```

- 纯 JVM resource，`classLoader.getResourceAsStream` 直接读：**JVM 单测不需要 Robolectric**，打包进 APK 也成立。
- 占位符用最朴素的字符串替换（`{{categories}}` / `{{tags}}` / `{{maxPerCategory}}` / `{{files}}`），不引模板引擎。
- **schema 定死骨架**，只有分类 enum 运行时注入。不让它由 Kotlin 类型自动生成 —— 自动生成会在改字段时**悄悄改掉发给模型的契约**，而 parser 的判定规则还在代码里，两边必然漂移。
- **防漂移测试**：`schema.json` 的 `required` 字段集合 == `NormalizeParser` 认识的字段集合，对不上即红。

---

## 6. 批处理

- **一次请求默认 20 个文件**（`cfg.batchSize`，可配）；最后一批是余数。
- **user prompt 的顺序是「目录在前、文件在后」**：20 个文件共享一份分类/标签目录，这是批量真正省 token 的地方。
- **响应按 `file_index` 对回文件**（1 起、与 prompt 中【文件 N】一致），**不用文件名做键** —— 文件名可能重复、可能含引号。
- `max_tokens` 给一个够用的常量（默认 8192，可配）。不做 `1024×batchSize` 的推算：现代模型输出窗口充足，过度计算反而容易撞上服务商上限被拒。
- 进度事件仍**逐文件**上报（`AnalysisProgress.FileUpdated`），批只是传输粒度。

**system prompt 追加的两条（其余沿用 `05 §4.4.1` 原文）：**

```
6. 一次会给你【多个文件】。必须返回一个对象 {"results": [...]}，results 的长度必须等于输入文件数；
   每项用 file_index（与输入的【文件 N】一致）标明属于哪个文件。不得遗漏、不得新增、不得改变顺序。
7. 某个文件信息不足时仍然要输出该项：artists 至少给一个你能确定的署名（拿不准就用文件名里的歌手，
   再不行用「未知艺术家」），tag_groups 可以是 []。不要跳过任何 file_index。
```

---

## 7. 响应结构

**分组结构**（一个分类下多个标签）：

```json
{
  "results": [
    {
      "file_index": 1,
      "canonical_title": "晴天 Live",
      "artists": ["周杰伦"],
      "tag_groups": [
        { "category": "音乐类型", "tags": ["流行"] },
        { "category": "情绪", "tags": ["怀旧"] },
        { "category": "场景", "tags": ["睡前"] },
        { "category": "主题", "tags": ["青春"] }
      ]
    },
    { "file_index": 2, "canonical_title": "Track 03", "artists": ["未知艺术家"], "tag_groups": [] }
  ]
}
```

- 顶层数组叫 **`tag_groups`**（不是 `tags`）：否则 `tags[].tags` 会造成歧义。
- **内部契约不动**：`NormalizeResult.tagAssignments: List<TagAssignment>` 仍是扁平的，parser 负责把组摊平 —— `02 §5.3` 里 `NormalizeResult` 的定义一字不改（**只有 `normalize` 的入参变成列表**，见 §12）。
- 分组对模型也更自然：数量上限是按分类算的，组内计数不用跨条目统计。

**schema 骨架**（`schema.json`，`enum` 为运行时注入）：

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

---

## 8. 缓存

**key = sha256( `fileName` ␟ 元数据指纹 ␟ `model` ␟ **prompt 文件哈希** ␟ **目录指纹** )**

| 组成 | 为什么 |
| --- | --- |
| `fileName` | 文件名本身含歌名/歌手，是主要输入 |
| 元数据指纹（title/artist/album/duration） | 内嵌标签被改过应当重算 |
| `model` | 换模型后结果口径不同 |
| **prompt 文件哈希**（system+user+schema 的文件内容 sha256） | **取代**原 `PROMPT_VERSION` 常量。prompt 外置后手工版本号必被遗忘，结果是"改了 prompt 却一直吃旧缓存"；用文件哈希则改文件即失效 |
| **目录指纹**（分类名 + 标签名的规范化串 sha256） | 原 key **漏了**这一项：用户新建分类后，同一文件会命中旧缓存，**返回的标签里永远缺这个新分类**，而且 `PROMPT_VERSION` 是常量救不了 |

- 只缓存 `Success`；`RateLimited` / `Failure` 不写缓存（沿用 `05 §4.2`）。
- **批量下逐文件查缓存，只把未命中的塞进本次请求**：全命中即零网络（保住 `01 §3.3`「重扫不花 token」），部分命中时"批量大小"实际等于未命中数。

---

## 9. 失败分类：调用失败 vs 解析失败

**这是两类完全不同的东西，不混在一起处理。**

### 9.1 调用失败（整批同命运，**不按文件重发**）

| 情形 | 归类 | 处理 |
| --- | --- | --- |
| 网络异常 / 超时 | `NETWORK` / `TIMEOUT` | 整批失败，所有文件 `FAILED`，`error_kind` 为对应值 |
| `429`（含 `Retry-After`） | `RATE_LIMIT` | 退避层重试至 `maxRetries`；耗尽后整批失败 |
| `5xx` / `529` | `SERVER` | **不重试**（需求 `00 §4.3`），整批失败 |
| `401` / `403` | `AUTH` | 整批失败；提示去设置页核对 api key |

理由：调用失败是**传输层**现象，与具体文件无关；连续失败基本等于网络或服务商故障，逐个重发只是在故障上多打 20 次。

### 9.2 解析失败（条目级只连坐那一个文件）

| 情形 | 处理 |
| --- | --- |
| **整批** JSON 不可解析 / 缺 `results` / `results` 非数组 | 整批 `INVALID_OUTPUT`。**不做逐个重发兜底**（见二期） |
| 某项缺 `file_index` / index 重复 | 该文件 `FAILED(INVALID_OUTPUT)`，**同批其余照常 `LINKED`** |
| 某文件在本批响应里**没有任何对应 item**（`results` 数量少于输入文件数） | 该文件 `FAILED(INVALID_OUTPUT)`，**同批其余照常 `LINKED`** |
| 返回的 `file_index` **越界**（不对应批内任何文件，即多出的项） | **忽略该条** + `logger.warn`，**不算失败、不影响其余任何文件** |
| 某项 `canonical_title` 空 / `artists` 缺失或空数组 | 同上 |
| 某组 `category` 不在**当前有效分类** | 丢弃该组 + `logger.warn`，**不算失败**（AI 不得新增分类，需求 `04 §2.1`） |
| 某条标签 `name` 为空 | 丢弃该条 + `logger.warn`，不算失败 |
| 某标签名已存在于**另一分类** | 交给 `attachAnalysisResult`，按**库中实际分类**为准（`tag` 名全局唯一，I5） |
| 某分类标签数超上限 | 组内 `take(maxPerCategory)` |
| `tag_groups` 缺失 / 空数组 | **合法**：`tagAssignments = []`，`Success`，不算失败 |

**二期（已记，本期不做）**：整批不可解析时的逐文件兜底。当前的代价是——同一个坏文件会反复让同批其余文件一起失败。自用场景接受。

---

## 10. 编排与幂等

- `AnalysisOrchestrator` 仍是**单 worker 串行**，但每次取 **20 个**待分析文件组一批（`pendingForAnalysis` 分批）。
- `categories` / `tags` 每批**实时读取一次**（`04 §3.3`：非快照），同一批内共用一份目录。
- **I4 不变**：只处理 `UNANALYZED`（与 `FAILED` 的手动重试）；`LINKED` 文件不重分析。批处理不改变这一点。
- 计数与状态：`analyzed_ok` / `failed_count` 仍**逐文件**累加（一个批里成功 18 失败 2 → 18/2）。

---

## 11. 界面

- **`AnalysisRunScreen`**：进度仍逐文件（`FileUpdated` 的 `status`），但"正在分析"的语义是**当前批次**；429 退避期间该文件保持 `ANALYZING`、不计失败（沿用 `05 §4.7`）。文案沿用 `11 §5.1`。
- **`SettingsScreen`**：协议类型（下拉：openai / responses / anthropic）、base url、api key（掩码显示，写入走已有的 `ApiKeyStore`）、模型名 —— 四项必填，**未填全四项时「保存」按钮禁用，并逐项标出缺哪一项**；重试次数、超时、`max_tokens`、`supportsJsonSchema` 收在「高级」。**内置提供商预设**只作"填表助手"：选中后填入 base url / 协议 / 模型名，用户仍可改；**只落最终值，不落"选了哪个预设"**（否则改了 url 还显示"DeepSeek 官方"，是骗人的状态）。
- 预设表放 `:core:llm` 的**常量表**（纯 Kotlin、可单测），UI 只读它。

---

## 12. 要写回文档的清单（全局一致性）

| 文档 | 改什么 |
| --- | --- |
| `05 §3.1` | `normalize` 入参改**列表**、返回索引对齐（**唯一一处动锁定契约**，需在 `02 §5.3` 同步） |
| `05 §3.2` | `provider`（服务商）→ **`protocol`** + `batchSize` + `maxTokens`；`PROMPT_VERSION` → **prompt 文件哈希** |
| `05 §3.3` / `§3.4` | `ChatCompletionRequest` / `LlmApi` 从总纲降级为 **openai 适配器私有 DTO**，另立协议无关模型 |
| `05 §4.1` | 单 worker「一次一个文件」→ **一次一批（默认 20）** |
| `05 §4.3.2` | 请求体示例给三份（openai / responses / anthropic） |
| `05 §4.4.1` / `§4.4.2` / `§4.4.3` | prompt 与 schema **外置为资源文件 + 占位符**；system 补批量两条；响应改 `tag_groups` |
| `05 §4.5` | 判定表改为「逐组 + 组内逐条」；补缺 `file_index` / index 重复 / `results` 长度不等 |
| `05 §4.7` | 补 `529 → SERVER`（不重试） |
| `05 §4.8` | 缓存 key 补 **prompt 文件哈希**与**目录指纹** |
| `05 §8` | 测试要点补批量 / 分组结构 / 哈希失效 / schema 防漂移 / 失败分类 |
| `01-技术栈与架构.md` | 设置页四项（base url / api key / 协议类型 / 模型名）与高级项；逐文件调用 → **一次一批**（默认 20）；缓存 key 补 `model` / prompt 文件哈希 / 目录指纹；§7 模块清单 **12 个**；UI 组件归属 `:core:ui` |
| `02 §5.3`、`§5.6` | `normalize` 签名（`§5.3`）与编排器的批量语义（`§5.6` 编排器）；若写了"一次一个请求"一并改 |
| `03 §2.2` | `llm_cache` 注释里的 key 组成 |
| `03 §6` | `llm_provider` 语义 = **协议类型**；新增 `llm_batch_size` / `llm_max_tokens` |
| `09 §3.2.10` / `§3.2.13` | 分析页进度按批；设置页字段与预设 |
| `11 §3.3` / `§5.1` | 只在失败分类影响 `error_kind` 口径时改 |

---

## 13. 测试要点（增量，叠加在 `05 §8` 之上）

**唯一测试清单是 `05 §8`**；本节只列**增量**条目，与 `05 §8` 重复的不再另立。

| 测试 | 手段 | 覆盖 |
| --- | --- | --- |
| 三适配器编码 | 断言各协议的 url / 鉴权头 / system 位置 / 结构化输出字段 | §3 |
| 三适配器解码 | 三份真实响应样本 → 都还原成同一段 JSON 字符串 | §3（含 anthropic 的 `input` 对象） |
| `529 → SERVER` 不重试 | MockWebServer 返回 529，断言只发一次 | §3 / §9.1；叠加在 `05 §8`「5xx 不自动重试」之上（补 `529`） |
| 批量编码 | 20 个文件 → 一次请求、`results` 结构、目录只出现一次 | §6 |
| `file_index` 回填 | 乱序返回 → 按 index 对回正确文件 | §6 |
| 失败分类（条目级 / 整批级） | 一项标题空 → 只该文件 `FAILED`、其余 `LINKED`；返回 `not json` → 整批 `INVALID_OUTPUT` | §9.2；合并原「条目级失败隔离」「整批不可解析」两行，叠加在 `05 §8`「畸形 JSON」之上 |
| 分组结构解析 | 一组多标签 → 摊平成多条 `TagAssignment` | §7；「无效分类整组丢弃」已在 `05 §8`「标签过滤」，不重列 |
| 缓存 key 含目录指纹 | 新增一个分类 → key 变化、缓存未命中 | §8 |
| 缓存 key 含 prompt 哈希 | 改 `system.txt` → key 变化、缓存未命中 | §8；取代 `05 §8`「缓存 key 组成」中的 `PROMPT_VERSION` 一项 |
| 部分命中 | 20 个文件里 5 个命中 → 只发 15 个 | §8 |
| schema 防漂移 | `schema.json` 的 `required` == parser 认识字段 | §5 |
| prompt 资源可读 | JVM 单测能读到三个资源文件并渲染占位符 | §5 |

### 13.1 验收标准（Given-When-Then）

只覆盖**有状态迁移**与**用户可见行为**；协议差异表（§3）、`schema.json`（§7）、缓存 key 组成（§8）等**结构化**内容保持表格，不给它们写 GWT。编号 `G1…` 供后续实施计划逐条认领。

**失败分类（§9）**

### G1 · 调用失败·网络异常 / 超时 → 整批 `FAILED`

- **Given** 一批 20 个 `UNANALYZED` 文件正在分析
- **When** 调用抛网络异常（`IOException`）或超时（`SocketTimeoutException`）
- **Then** 整批 20 个文件全部 `FAILED`，`error_kind` 分别 `NETWORK` / `TIMEOUT`；用户可见「无网络，请检查网络后重试」+「重试」（`11 §5.1`、`02 §8`）
- 对应测试：`05 §8`「网络异常」

### G2 · 调用失败·`429` 重试耗尽 → 整批 `FAILED`

- **Given** 一批 20 个文件，`maxRetries = N`
- **When** 服务端始终返回 `429`（带 / 不带 `Retry-After`）
- **Then** 退避期间整批保持 `ANALYZING`、**不计失败**、文案「请求过于频繁，重试中（第 k/N 次）」；重试耗尽后整批 20 个 `FAILED`、`error_kind = RATE_LIMIT`
- 对应测试：`05 §8`「429 退避时序」/「429 重试耗尽」

### G3 · 调用失败·`5xx` / `529` → 不重试、整批 `FAILED`

- **Given** 一批 20 个文件
- **When** 服务端返回 `500` 或 `529`
- **Then** **只发一次请求**（`529` 归 `SERVER`，不进退避），整批 20 个 `FAILED`、`error_kind = SERVER`；用户可见「服务暂时不可用」+「重试」
- 对应测试：`05 §8`「5xx 不自动重试」；§13 表「`529 → SERVER` 不重试」

### G4 · 调用失败·`AUTH`（`401` / `403`）→ 整批 `FAILED` + 「去设置」

- **Given** 一批 20 个文件
- **When** 服务端返回 `401` / `403`
- **Then** **只发一次请求**，整批 20 个 `FAILED`、`error_kind = AUTH`；用户可见「API Key 无效，请到设置检查」+「去设置」（去设置页核对 api key）
- 对应测试：`05 §8`「401/403 → AUTH」

### G5 · 解析失败·条目级 → 只连坐该文件

- **Given** 一批 20 个文件，模型返回**可解析**的 `results`
- **When** 其中某一项缺 `file_index` / `index` 重复 / `canonical_title` 空 / `artists` 缺失或空数组
- **Then** 该文件 `FAILED(INVALID_OUTPUT)`，**同批其余 19 个照常 `LINKED`**
- 对应测试：§13 表「失败分类（条目级 / 整批级）」

### G6 · 解析失败·整批级 → 整批 `INVALID_OUTPUT`

- **Given** 一批 20 个文件
- **When** 响应体整体不可解析（`not json`）/ 缺 `results` / `results` 非数组
- **Then** 整批 20 个 `INVALID_OUTPUT`、**不做逐文件重发兜底**（二期）；用户可见「服务返回内容异常」+「重试」
- 对应测试：§13 表「失败分类（条目级 / 整批级）」；`05 §8`「畸形 JSON」

**批量回填（§6）**

### G7 · 批量·模型乱序返回 → 按 `file_index` 对回

- **Given** 一次请求含 20 个文件（【文件 1】…【文件 20】）
- **When** 模型把 20 个结果**乱序**返回
- **Then** 每项按 `file_index` 对回正确文件（**不用文件名做键**），20 个文件各自的标题 / 歌手 / 标签归属正确、状态 `LINKED`
- 对应测试：§13 表「`file_index` 回填」

### G8 · 批量·重复 `file_index` → 该文件 `FAILED`、其余 `LINKED`

- **Given** 一次请求含 20 个文件
- **When** 两项用了**同一个** `file_index`
- **Then** 该 index 对应的文件 `FAILED(INVALID_OUTPUT)`，其余 19 个照常 `LINKED`（§9.2）
- 对应测试：§13 表「失败分类（条目级 / 整批级）」

### G16 · 批量·模型少返回一项（缺整个 item）→ 该文件 `FAILED`、其余 `LINKED`

- **Given** 一次请求含 20 个文件，模型返回**可解析**的 `results`
- **When** 模型只回了 19 项，其中某个文件在本批响应里**没有任何对应 item**
- **Then** 该文件 `FAILED(INVALID_OUTPUT)`，**同批其余 19 个照常 `LINKED`**（§9.2）
- 对应测试：§13 表「失败分类（条目级 / 整批级）」

### G17 · 批量·模型多返回一项（越界 `file_index`）→ 忽略该条、不算失败

- **Given** 一次请求含 20 个文件
- **When** 模型返回的某一项 `file_index` **越界**（不对应批内任何文件）
- **Then** **忽略该条** + `logger.warn`，**不算失败、不影响批内任何文件**（20 个文件状态照常）（§9.2）
- 对应测试：§13 表「失败分类（条目级 / 整批级）」

**缓存（§8）**

### G9 · 改 `system.txt` 后重跑 → key 全变、全 miss

- **Given** 20 个文件首次分析成功并写入缓存（1 次请求）
- **When** 改 `core/llm/src/main/resources/prompt/system.txt` 后对同一批重跑
- **Then** prompt 文件哈希变化 → 20 个 key 全变、**全未命中** → **恰好 1 次网络请求**（20 个一批），结果重新写入缓存
- 对应测试：§13 表「缓存 key 含 prompt 哈希」

### G10 · 新增一个分类后重跑 → 目录指纹变、全 miss

- **Given** 20 个文件已有成功缓存
- **When** 用户新建一个分类后对同一批重跑
- **Then** 目录指纹变化 → 20 个 key 全变、**全未命中** → **恰好 1 次网络请求**；返回结果可含新分类的标签（不再永远缺该分类）
- 对应测试：§13 表「缓存 key 含目录指纹」

### G11 · 部分命中 → 只把未命中的塞进请求

- **Given** 20 个文件里 5 个命中缓存、15 个未命中
- **When** 对同一批重跑
- **Then** **只发 1 次请求**、请求体只含 15 个未命中文件；20 个**全命中时零网络请求**（保住 `01 §3.3`「重扫不花 token」）
- 对应测试：§13 表「部分命中」

**幂等与取消（§10）**

### G12 · 对 `LINKED` 文件再触发 → 不重分析

- **Given** 某文件 `analysis_status = LINKED`
- **When** 再次触发 `analyzePending` / `retry`
- **Then** 入口按 I4 跳过，**零网络请求**；状态仍 `LINKED`，不重建实体、不重复挂标签（事务 / 唯一索引兜底）
- 对应测试：`05 §8`「幂等 I4」

### G13 · 批量处理到中途取消 → 第 k 个回 `UNANALYZED`

- **Given** 一批 20 个文件正在串行处理，前 k-1 个已成功 `LINKED`
- **When** 处理第 k 个文件时取消（协程 cancel / 用户点取消）
- **Then** 前 k-1 个保持 `LINKED`（不回滚）、第 k 个由 `ANALYZING` **回退为 `UNANALYZED`**（不置 `FAILED`）、`analysis_run.status = ABORTED` 并写 `finished_at`、emit `Finished(aborted = true)`；再次触发从第 k 个续跑
- 对应测试：`05 §8`「中途取消」（`05 §5.3`）

**设置页（§11）**

### G14 · 选中内置预设 → 填入哪三项

- **Given** 打开 `SettingsScreen`
- **When** 选中一个内置提供商预设
- **Then** 自动填入 **base url / 协议类型 / 模型名**三项（**不含 api key**），用户仍可逐项修改；预设只作「填表助手」（常量表在 `:core:llm`）
- 对应测试：待补（设置页交互）

### G15 · 改动后不再显示预设名

- **Given** 已选中某预设并自动填表
- **When** 用户修改其中任一字段（如 base url）后离开并重回设置页
- **Then** **不显示该预设名**（状态为自定义）—— 因为只落最终值、不落「选了哪个预设」（§11）
- 对应测试：待补（设置页交互）

### G18 · 设置页·四项未填全 → 「保存」按钮禁用

- **Given** 打开 `SettingsScreen`
- **When** base url / api key / 协议类型 / 模型名四项**未填全**（缺任意一项或多项）
- **Then** **保存按钮处于禁用状态**，并**逐项标出缺哪一项**（§11）
- 对应测试：待补（设置页交互）

---

## 14. 已定/待办的边界

- **已定**：三协议 = 内部模型 + 适配器；强制 JSON 分层降级；批量默认 20；`tag_groups` 分组；prompt/schema 外置 + 哈希入缓存 key；调用失败不重发、条目级失败不兜底。
- **后续由用户提供**：内置预设表的具体值（协议类型 / base url / 默认模型名），以及真机验收用的**真实 API Key**。
- **二期**：整批不可解析时的逐文件兜底；标签库规模控制（当前判定：不需要，token 成本可接受）。