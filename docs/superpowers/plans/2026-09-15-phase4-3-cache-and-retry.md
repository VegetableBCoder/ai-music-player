# Phase 4-3 · 缓存与重试 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `:core:llm` 落地缓存装饰器与 429 退避装饰器（`Caching(Retrying(Direct))`），并在 `:core:data` 落地 `LlmCache` 的 Room 实现与 Hilt 装配；缓存 key 含 prompt 文件哈希与目录指纹，批量下逐文件查缓存（全命中＝零网络），退避只处理 429、可注入假时钟。

**Architecture:** `CachingLlmNormalizer`（最外层）逐文件查 `LlmCache`，只把未命中的文件交给 `RetryingLlmNormalizer`（紧贴网络），退避成功后才写缓存；`LlmCache` 接口在 `:core:llm`、实现 `RoomLlmCache` 在 `:core:data`，用 Hilt `@Provides` 返回接口类型（同 `ScanSourceRepository`/`AnalysisTrigger` 那一路装配）。退避时长复用 `:core:common` 已实现且已测的 `RetryPolicy`，等待走可注入的 `Sleeper`。

**Tech Stack:** Kotlin 1.11 协程、Hilt、Room（KSP）、JUnit4 + Truth（JVM 单测）、androidTest（真机 SQLite）、`java.security.MessageDigest`（sha256）。

**Spec:** docs/superpowers/specs/2026-09-15-phase4-llm-protocol-design.md

## Global Constraints

- **模块依赖守卫（根 `build.gradle.kts` 的 `moduleDependencyRules`，配置期强制）**：`:core:llm` 只能依赖 `:core:common`；`:core:data` → `:core:storage` / `:core:common` / **`:core:llm`（已拍板放开，决定 #2）**；feature 之间禁止互赖，feature 只能依赖白名单 core 模块。
- Kotlin/Java 17（`jvmTarget = JVM_17`、`sourceCompatibility/targetCompatibility = 17`）、`compileSdk = 36`、`minSdk = 26`。
- coroutines **显式钉住 `1.11.0`**（`gradle/libs.versions.toml` 的 `kotlinxCoroutines`）；不得引入未锁版本的协程依赖。
- KSP + AGP 9：`gradle.properties` 保留 `android.disallowKotlinSourceSets=false`（KSP 通过 `kotlin.sourceSets` 注册生成目录，AGP 9 默认禁止）。
- **注释与提交信息一律中文**；提交信息末尾带 `Co-authored-by: CommandCodeBot <noreply@commandcode.ai>`。
- **不用通用 `Result` 包装**：成败用具名类型表达（`NormalizeOutcome`、`LlmCache.get` 返回可空 `NormalizeResult`）。
- **Room 相关测试一律跑真机 `androidTest`**（`:core:data`），不在 JVM 单测里碰 Room。
- **androidTest 反引号函数名不能含空格**（DEX 方法名限制，根 `build.gradle.kts` 配置期检查）；中文允许，分隔一律用 `_`。
- 真机测试前先唤醒设备：`adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard`。
- 退避测试**注入假 `Sleeper`（立即返回），绝不真 sleep**。

> **守卫已放开（决定 #2，原需拍板项 #1）**：`:core:data → :core:llm` **允许**。Task 6 正是需要它 —— `RoomLlmCache` 在 `:core:data` 要实现 `:core:llm` 的 `LlmCache` 接口，spec §2 也要求 `:core:data` 的 `AnalysisOrchestrator` 认得 `LlmNormalizer`。上面 Global Constraints 的白名单已含 `:core:llm`，Task 6 只是把它落到 `build.gradle.kts`。

## 依赖的平行契约（由 P1 / P2 提供，本计划不改这些文件）

> P1/P2 正在并行起草。以下签名是本计划**消费端的期望**，若与其最终落地不同，**以它们为准并回改本计划的 import**；本计划不发明新名字。

**由 P1 提供（协议层，`:core:llm`，包 `com.aimusic.player.llm`）：**

```kotlin
interface LlmNormalizer {
    /** 一次一批；返回与入参等长、按索引对齐。 */
    suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome>
}

class DirectProvider(/* … 协议层：ProtocolAdapter / LlmCall / LlmHttpResult … */) : LlmNormalizer

data class LlmConfig(/* … protocol / baseUrl / model / apiKey / maxRetries / batchSize / maxTokens … */)
```

**由 P2 提供（prompt / parser / `:core:llm` 契约类型）：**

```kotlin
/** `:core:llm` 契约的两字段标签引用（决定 #3；`:core:common` 的三字段同名类型已改名 TagProjection）。 */
data class TagRef(val name: String, val category: String)

data class NormalizeRequest(
    val fileName: String,
    val metadata: AudioMetadata?,   // com.aimusic.player.common.model.AudioMetadata（决定 #1）
    val categories: List<String>,
    val tags: List<TagRef>
)

sealed interface NormalizeOutcome {
    data class Success(val result: NormalizeResult) : NormalizeOutcome
    data class RateLimited(val retryAfterMs: Long?) : NormalizeOutcome
    data class Failure(val kind: LlmFailureKind, val cause: Throwable?) : NormalizeOutcome
}
enum class LlmFailureKind { NETWORK, AUTH, SERVER, INVALID_OUTPUT, TIMEOUT }

class PromptBuilder(/* … */) {
    /** system.txt + user.txt + schema.json 文件内容 sha256（05 §4.4）；参与缓存 key。 */
    fun promptHash(): String
}

/** 目录指纹：当前有效分类名 + 标签名的规范化串 sha256（排序去重，顺序无关）。 */
object DirectoryFingerprint { fun of(categories: List<String>, tags: List<TagRef>): String }
```

**已在 `:core:common` 落地（不在 P1/P2 范围）：** `com.aimusic.player.common.model.NormalizeResult`、`TagAssignment`、`TagProjection`（三字段标签投影，决定 #3）；`com.aimusic.player.common.error.FailureKind`；`com.aimusic.player.common.retry.RetryPolicy`（退避唯一口径，决定 #5）。

---

### Task 1: 可注入的等待时钟 `Sleeper`（`:core:common`）

**Files:**
- Create: `core/common/src/main/kotlin/com/aimusic/player/common/util/Sleeper.kt`
- Test: `core/common/src/test/kotlin/com/aimusic/player/common/util/SleeperTest.kt`

**Interfaces:**
- Consumes: `kotlinx.coroutines.delay`（协程库，`:core:common` 已依赖）
- Produces: `com.aimusic.player.common.util.Sleeper` = `fun interface Sleeper { suspend fun sleep(ms: Long) }`；`object RealSleeper : Sleeper`

- [ ] **Step 1: 写失败测试**

`core/common/src/test/kotlin/com/aimusic/player/common/util/SleeperTest.kt`：

```kotlin
package com.aimusic.player.common.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `Sleeper` 是退避层唯一「真的花时间」的地方（05 §4.7）。
 * 这里用 runBlocking（不是 runTest）—— 要的是真实时间，且要能验证协程取消。
 */
class SleeperTest {

    @Test
    fun `RealSleeper 至少等待请求的时长`() {
        val start = System.nanoTime()

        runBlocking { RealSleeper.sleep(40) }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isAtLeast(35L)
    }

    @Test
    fun `RealSleeper 可被协程取消`() = runBlocking {
        val job = launch { RealSleeper.sleep(10_000) }
        delay(20)

        job.cancel()
        job.join()

        assertThat(job.isCancelled).isTrue()
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew :core:common:testDebugUnitTest --tests "com.aimusic.player.common.util.SleeperTest"
```

预期：编译失败，`e: …SleeperTest.kt:… Unresolved reference: RealSleeper`、`Unresolved reference: Sleeper`，末行 `BUILD FAILED`。

- [ ] **Step 3: 最小实现**

`core/common/src/main/kotlin/com/aimusic/player/common/util/Sleeper.kt`：

```kotlin
package com.aimusic.player.common.util

import kotlinx.coroutines.delay

/**
 * 可注入的等待（05 §4.7）。
 *
 * 退避层用它等待；测试注入假实现，**绝不真 sleep**。`suspend` 有两个理由：
 * 等待期间让出线程，且响应协程取消（用户点「取消」要能立刻中断退避）。
 */
fun interface Sleeper {
    suspend fun sleep(ms: Long)
}

/** 生产实现：真等，但可被协程取消。 */
object RealSleeper : Sleeper {
    override suspend fun sleep(ms: Long) = delay(ms)
}
```

- [ ] **Step 4: 跑测试确认通过**

```
./gradlew :core:common:testDebugUnitTest --tests "com.aimusic.player.common.util.SleeperTest"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```
git add core/common/src/main/kotlin/com/aimusic/player/common/util/Sleeper.kt \
        core/common/src/test/kotlin/com/aimusic/player/common/util/SleeperTest.kt
git commit -m "feat(common): 增加可注入的等待时钟 Sleeper

退避层用它等待，测试注入假实现，避免真 sleep（05 §4.7）。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 2: `LlmCache` 接口（`:core:llm`）+ 测试内存假实现

**Files:**
- Create: `core/llm/src/main/kotlin/com/aimusic/player/llm/LlmCache.kt`
- Create: `core/llm/src/test/kotlin/com/aimusic/player/llm/TestSupport.kt`（测试夹具：内存缓存）
- Test: `core/llm/src/test/kotlin/com/aimusic/player/llm/LlmCacheTest.kt`
- Modify: `core/llm/build.gradle.kts`（加 `testImplementation(project(":core:testing"))`）

**Interfaces:**
- Consumes: `com.aimusic.player.common.model.NormalizeResult`（已在 `:core:common`）
- Produces: `com.aimusic.player.llm.LlmCache` = `suspend fun get(key: String): NormalizeResult?` / `suspend fun put(key: String, result: NormalizeResult)`

- [ ] **Step 1: 写失败测试 + 测试夹具**

`core/llm/build.gradle.kts` 的 `dependencies { ... }` 末尾加一行（`:core:testing` 用 `api` 暴露 junit / truth / coroutines-test）：

```kotlin
    // JVM 单测夹具（junit / truth / coroutines-test）
    testImplementation(project(":core:testing"))
```

`core/llm/src/test/kotlin/com/aimusic/player/llm/TestSupport.kt`：

```kotlin
package com.aimusic.player.llm

import com.aimusic.player.common.model.NormalizeResult

/**
 * 测试用内存缓存。`getCount` / `putCount` 用来断言「查了几次、写了几次」
 * （零网络 = delegate 零调用，见 CachingLlmNormalizerTest）。
 */
class InMemoryLlmCache : LlmCache {
    private val rows = LinkedHashMap<String, NormalizeResult>()

    var getCount = 0
        private set
    var putCount = 0
        private set

    override suspend fun get(key: String): NormalizeResult? {
        getCount++
        return rows[key]
    }

    override suspend fun put(key: String, result: NormalizeResult) {
        putCount++
        rows[key] = result
    }
}
```

`core/llm/src/test/kotlin/com/aimusic/player/llm/LlmCacheTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.model.TagAssignment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class LlmCacheTest {

    private val result = NormalizeResult(
        canonicalTitle = "晴天 Live",
        artists = listOf("周杰伦"),
        tagAssignments = listOf(TagAssignment("情绪", "怀旧")),
    )

    @Test
    fun `未写入的 key 返回 null`() = runBlocking {
        val cache = InMemoryLlmCache()

        assertThat(cache.get("missing")).isNull()
    }

    @Test
    fun `put 后 get 原样取回`() = runBlocking {
        val cache = InMemoryLlmCache()

        cache.put("k1", result)

        assertThat(cache.get("k1")).isEqualTo(result)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.LlmCacheTest"
```

预期：编译失败，`e: …TestSupport.kt:… Unresolved reference: LlmCache`，末行 `BUILD FAILED`。

- [ ] **Step 3: 最小实现**

`core/llm/src/main/kotlin/com/aimusic/player/llm/LlmCache.kt`：

```kotlin
package com.aimusic.player.llm

import com.aimusic.player.common.model.NormalizeResult

/**
 * LLM 结果缓存的读写抽象（spec §2 / 05 §4.8）。
 *
 * **接口在 `:core:llm`、实现在 `:core:data`**：`:core:llm` 不能依赖 `:core:data`（Room 在那里），
 * 于是 Room 实现 `RoomLlmCache` 落在 `:core:data`、用 Hilt 返回本接口 —— 与
 * `StorageSource` / `AnalysisTrigger` 的装配路数一致（spec §2）。
 *
 * 缓存只存**成功**的 `NormalizeResult`；`RateLimited` / `Failure` 永不入缓存
 * （写进缓存会把瞬时故障固化，05 §4.2 理由 3）。key 见 [CacheKeyProvider]。
 */
interface LlmCache {
    /** 未命中返回 null。 */
    suspend fun get(key: String): NormalizeResult?

    /** 仅 `CachingLlmNormalizer` 在 `NormalizeOutcome.Success` 时调用。 */
    suspend fun put(key: String, result: NormalizeResult)
}
```

- [ ] **Step 4: 跑测试确认通过**

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.LlmCacheTest"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```
git add core/llm/build.gradle.kts \
        core/llm/src/main/kotlin/com/aimusic/player/llm/LlmCache.kt \
        core/llm/src/test/kotlin/com/aimusic/player/llm/TestSupport.kt \
        core/llm/src/test/kotlin/com/aimusic/player/llm/LlmCacheTest.kt
git commit -m "feat(llm): 定义 LlmCache 接口与测试内存实现

接口在 :core:llm、实现在 :core:data（spec §2 / 05 §4.8）；只存成功结果。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 3: `CacheKeyProvider` —— sha256 缓存 key（`:core:llm`）

**Files:**
- Create: `core/llm/src/main/kotlin/com/aimusic/player/llm/CacheKeyProvider.kt`
- Test: `core/llm/src/test/kotlin/com/aimusic/player/llm/CacheKeyProviderTest.kt`

**Interfaces:**
- Consumes: `NormalizeRequest`（由 P2）
- Produces:
  - `com.aimusic.player.llm.CacheKeyProvider.keyFor(request: NormalizeRequest, model: String, promptHash: String, dirFingerprint: String): String`
  - `internal fun com.aimusic.player.llm.sha256Hex(raw: String): String`（小写十六进制）

- [ ] **Step 1: 写失败测试**

`core/llm/src/test/kotlin/com/aimusic/player/llm/CacheKeyProviderTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CacheKeyProviderTest {

    // 元数据用 AudioMetadata —— 已拍板（决定 #1）：位于 com.aimusic.player.common.model。
    private fun req(
        fileName: String = "周杰伦 - 晴天.flac",
        metadata: com.aimusic.player.common.model.AudioMetadata? = null,
    ) = NormalizeRequest(
        fileName = fileName,
        metadata = metadata,
        categories = listOf("音乐类型", "情绪"),
        tags = listOf(TagRef(name = "流行", category = "音乐类型")),
    )

    private fun key(
        request: NormalizeRequest = req(),
        model: String = "gpt-4o-mini",
        promptHash: String = "p-hash",
        dirFingerprint: String = "d-hash",
    ) = CacheKeyProvider.keyFor(request, model, promptHash, dirFingerprint)

    @Test
    fun `key 是 64 位小写十六进制 sha256`() {
        val k = key()

        assertThat(k).hasLength(64)
        assertThat(k).matches("[0-9a-f]{64}")
    }

    @Test
    fun `同输入同 key`() {
        assertThat(key()).isEqualTo(key())
    }

    @Test
    fun `文件名、model、prompt 哈希、目录指纹任一变化都改变 key`() {
        val base = key()

        assertThat(key(request = req(fileName = "别的.flac"))).isNotEqualTo(base)
        assertThat(key(model = "deepseek-chat")).isNotEqualTo(base)
        assertThat(key(promptHash = "p-hash-2")).isNotEqualTo(base)   // 覆盖 G9 的核心
        assertThat(key(dirFingerprint = "d-hash-2")).isNotEqualTo(base) // 覆盖 G10 的核心
    }

    @Test
    fun `元数据指纹参与 key 且与无元数据不同`() {
        val withMeta = req(
            metadata = com.aimusic.player.common.model.AudioMetadata(
                title = "晴天", artist = "周杰伦", album = "叶惠美",
                albumArtist = "周杰伦", date = "2003-07-31",
                durationMs = 269_000, hasEmbeddedPicture = false,
            ),
        )

        assertThat(key(request = withMeta)).isNotEqualTo(key(request = req()))
    }

    @Test
    fun `五段用 U+001F 连接，避免拼接歧义`() {
        // 朴素拼接下 ("x","yz") 与 ("xy","z") 会撞成同一串；分隔符能避免。
        val a = key(request = req(fileName = "x"), promptHash = "yz")
        val b = key(request = req(fileName = "xy"), promptHash = "z")

        assertThat(a).isNotEqualTo(b)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.CacheKeyProviderTest"
```

预期：编译失败，`e: …CacheKeyProviderTest.kt:… Unresolved reference: CacheKeyProvider`，末行 `BUILD FAILED`。

- [ ] **Step 3: 最小实现**

`core/llm/src/main/kotlin/com/aimusic/player/llm/CacheKeyProvider.kt`：

```kotlin
package com.aimusic.player.llm

import java.security.MessageDigest

/**
 * 缓存 key 的组成（spec §8 / 05 §4.8 / 02 §5.3）：
 *
 * ```
 * key = sha256(fileName ␟ 元数据指纹 ␟ model ␟ prompt 文件哈希 ␟ 目录指纹)
 * ```
 *
 * 五段以 U+001F（`␟`）连接：它几乎不会出现在文件名/模型名里，用来消除「拼接歧义」
 * （朴素的 `a|b` + `c` 与 `a` + `b|c` 会撞成同一串）。
 *
 * - 元数据指纹 = title ␟ artist ␟ album ␟ durationMs；元数据被改过应当重算。
 * - prompt 文件哈希：由 P2 的 `PromptBuilder.promptHash()` 提供，**取代**手工 PROMPT_VERSION。
 * - 目录指纹：由 P2 的 `DirectoryFingerprint.of(categories, tags)` 适配提供，纳入当前有效分类 + 标签 ——
 *   否则用户新建分类后同一文件会命中旧缓存、返回的标签里永远缺该分类。
 */
object CacheKeyProvider {

    private const val FIELD_SEP = "\u001F"

    fun keyFor(
        request: NormalizeRequest,
        model: String,
        promptHash: String,
        dirFingerprint: String,
    ): String {
        val metadataFingerprint = request.metadata?.let { m ->
            listOf(m.title, m.artist, m.album, m.durationMs)
                .joinToString(FIELD_SEP) { it?.toString() ?: "" }
        } ?: ""
        val raw = listOf(request.fileName, metadataFingerprint, model, promptHash, dirFingerprint)
            .joinToString(FIELD_SEP)
        return sha256Hex(raw)
    }
}

/** 小写十六进制 sha256。 */
internal fun sha256Hex(raw: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
```

- [ ] **Step 4: 跑测试确认通过**

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.CacheKeyProviderTest"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```
git add core/llm/src/main/kotlin/com/aimusic/player/llm/CacheKeyProvider.kt \
        core/llm/src/test/kotlin/com/aimusic/player/llm/CacheKeyProviderTest.kt
git commit -m "feat(llm): 缓存 key 组装（含 prompt 文件哈希与目录指纹）

key = sha256(fileName ␟ 元数据指纹 ␟ model ␟ prompt 哈希 ␟ 目录指纹)，五段以 U+001F 连接（spec §8）。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 4: `CachingLlmNormalizer` —— 逐文件查缓存、只发未命中（`:core:llm`）

**Files:**
- Create: `core/llm/src/main/kotlin/com/aimusic/player/llm/CachingLlmNormalizer.kt`
- Create: `core/llm/src/test/kotlin/com/aimusic/player/llm/ScriptedLlmNormalizer.kt`（记录调用 + 脚本化返回的假 delegate）
- Test: `core/llm/src/test/kotlin/com/aimusic/player/llm/CachingLlmNormalizerTest.kt`

**Interfaces:**
- Consumes: `LlmNormalizer`（P1）、`NormalizeRequest` / `NormalizeOutcome`（P2）、`NormalizeResult`（`:core:common`）、`LlmCache`（Task 2）、`CacheKeyProvider`（Task 3）、P2 的 `promptHash: () -> String` 与 `DirectoryFingerprint.of(categories, tags): String`（本类注入的 `(NormalizeRequest) -> String` lambda 由它适配）
- Produces: `com.aimusic.player.llm.CachingLlmNormalizer(delegate: LlmNormalizer, cache: LlmCache, model: String, promptHash: () -> String, dirFingerprint: (NormalizeRequest) -> String) : LlmNormalizer`

- [ ] **Step 1: 写失败测试 + 假 delegate**

`core/llm/src/test/kotlin/com/aimusic/player/llm/ScriptedLlmNormalizer.kt`：

```kotlin
package com.aimusic.player.llm

/**
 * 记录每次调用收到的请求，并按脚本返回（越界后固定用最后一项）。
 * 「网络请求次数」在装饰器单测里就等于这个 `calls.size`。
 */
class ScriptedLlmNormalizer(
    private val script: List<List<NormalizeOutcome>>,
) : LlmNormalizer {

    val calls = mutableListOf<List<NormalizeRequest>>()

    override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
        calls += requests
        return script[minOf(calls.size - 1, script.lastIndex)]
    }
}

/** 声明式构造：对每个请求都返回同一个成功结果。 */
fun successFor(request: NormalizeRequest): NormalizeOutcome =
    NormalizeOutcome.Success(
        NormalizeResult(
            canonicalTitle = request.fileName.substringAfterLast('/').substringBeforeLast('.'),
            artists = listOf("未知艺术家"),
            tagAssignments = emptyList(),
        ),
    )
```

`core/llm/src/test/kotlin/com/aimusic/player/llm/CachingLlmNormalizerTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.aimusic.player.common.model.NormalizeResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class CachingLlmNormalizerTest {

    private fun req(name: String) = NormalizeRequest(
        fileName = name, metadata = null, categories = listOf("音乐类型"), tags = emptyList(),
    )

    private fun caching(
        delegate: LlmNormalizer,
        cache: InMemoryLlmCache,
        promptHash: String = "p-hash",
        dirFingerprint: String = "d-hash",
    ) = CachingLlmNormalizer(
        delegate = delegate,
        cache = cache,
        model = "gpt-4o-mini",
        promptHash = { promptHash },
        dirFingerprint = { dirFingerprint },
    )

    @Test
    fun `全命中时零网络（delegate 不被调用）`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"), req("b.mp3"))
        // 先跑一次把两个文件都写进缓存
        val warming = caching(ScriptedLlmNormalizer(listOf(requests.map(::successFor))), cache)
        warming.normalize(requests)

        val delegate = ScriptedLlmNormalizer(listOf(requests.map(::successFor)))
        val sut = caching(delegate, cache)
        val out = sut.normalize(requests)

        assertThat(delegate.calls).isEmpty()          // ★ 零网络
        assertThat(out).hasSize(2)
        assertThat(out.all { it is NormalizeOutcome.Success }).isTrue()
    }

    @Test
    fun `部分命中时只把未命中的交给 delegate，返回按原顺序对齐`() = runBlocking {
        val cache = InMemoryLlmCache()
        val all = listOf(req("a.mp3"), req("b.mp3"), req("c.mp3"))
        caching(ScriptedLlmNormalizer(listOf(all.map(::successFor))), cache).normalize(all)

        // 让 a、c 的 key 失效（改名重来）——只命中 b
        val requests = listOf(req("a2.mp3"), req("b.mp3"), req("c2.mp3"))
        val delegate = ScriptedLlmNormalizer(listOf(requests.map(::successFor)))
        val out = caching(delegate, cache).normalize(requests)

        assertThat(delegate.calls).hasSize(1)                                   // 只发一次
        assertThat(delegate.calls.single().map { it.fileName })
            .containsExactly("a2.mp3", "c2.mp3").inOrder()                      // 只含未命中
        assertThat(out).hasSize(3)
        assertThat((out[1] as NormalizeOutcome.Success).result.canonicalTitle).isEqualTo("b")
    }

    @Test
    fun `只缓存成功：Failure 与 RateLimited 不写缓存`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"), req("b.mp3"))
        val delegate = ScriptedLlmNormalizer(
            listOf(
                listOf(
                    NormalizeOutcome.Failure(LlmFailureKind.SERVER, null),
                    NormalizeOutcome.RateLimited(1_000),
                ),
            ),
        )

        val out = caching(delegate, cache).normalize(requests)

        assertThat(cache.putCount).isEqualTo(0)
        assertThat(out[0]).isInstanceOf(NormalizeOutcome.Failure::class.java)
        assertThat(out[1]).isInstanceOf(NormalizeOutcome.RateLimited::class.java)
    }

    @Test
    fun `成功后写缓存：第二次同批全命中零网络`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"))
        val first = ScriptedLlmNormalizer(listOf(listOf(successFor(requests[0]))))

        caching(first, cache).normalize(requests)
        assertThat(cache.putCount).isEqualTo(1)

        val second = ScriptedLlmNormalizer(listOf(listOf(successFor(requests[0]))))
        caching(second, cache).normalize(requests)
        assertThat(second.calls).isEmpty()
    }

    @Test
    fun `prompt 哈希或目录指纹变化导致全 miss`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"), req("b.mp3"))
        caching(ScriptedLlmNormalizer(listOf(requests.map(::successFor))), cache).normalize(requests)

        // 只改 prompt 哈希（模拟改了 system.txt）→ 覆盖 G9
        val afterPrompt = ScriptedLlmNormalizer(listOf(requests.map(::successFor)))
        caching(afterPrompt, cache, promptHash = "p-hash-2").normalize(requests)
        assertThat(afterPrompt.calls.single()).hasSize(2)

        // 只改目录指纹（模拟新增分类）→ 覆盖 G10
        val afterDir = ScriptedLlmNormalizer(listOf(requests.map(::successFor)))
        caching(afterDir, cache, dirFingerprint = "d-hash-2").normalize(requests)
        assertThat(afterDir.calls.single()).hasSize(2)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.CachingLlmNormalizerTest"
```

预期：编译失败，`e: …CachingLlmNormalizerTest.kt:… Unresolved reference: CachingLlmNormalizer`，末行 `BUILD FAILED`。

- [ ] **Step 3: 最小实现**

`core/llm/src/main/kotlin/com/aimusic/player/llm/CachingLlmNormalizer.kt`：

```kotlin
package com.aimusic.player.llm

/**
 * 缓存装饰器（**最外层**，spec §2 / 05 §4.2、§4.8）。
 *
 * 批量下**逐文件查缓存**：
 * - 全命中 → 直接返回（零网络、零 token，保住 01 §3.3「重扫不花 token」）；
 * - 部分命中 → 只把**未命中**的文件交给 delegate，成功结果逐文件写回。
 *
 * **只缓存 `Success`**：`RateLimited` / `Failure` 是瞬时现象，写进缓存会把故障固化。
 *
 * @param promptHash 由 P2 提供（`PromptBuilder.promptHash()`）：每批调用一次。
 * @param dirFingerprint 由 P2 的 `DirectoryFingerprint.of(categories, tags)` 适配而来：目录指纹随请求的分类/标签变化，逐文件调用。
 */
class CachingLlmNormalizer(
    private val delegate: LlmNormalizer,
    private val cache: LlmCache,
    private val model: String,
    private val promptHash: () -> String,
    private val dirFingerprint: (NormalizeRequest) -> String,
) : LlmNormalizer {

    override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
        val promptHashValue = promptHash()
        val keys = requests.map { request ->
            CacheKeyProvider.keyFor(request, model, promptHashValue, dirFingerprint(request))
        }
        val cached = keys.map { cache.get(it) }
        val misses = requests.indices.filter { cached[it] == null }
        val out: MutableList<NormalizeOutcome?> =
            cached.map { result -> result?.let { NormalizeOutcome.Success(it) } }.toMutableList()

        if (misses.isEmpty()) return out.map { it!! }   // ★ 全命中：零网络

        val fresh = delegate.normalize(misses.map { requests[it] })
        misses.forEachIndexed { offset, index ->
            val outcome = fresh[offset]
            if (outcome is NormalizeOutcome.Success) cache.put(keys[index], outcome.result)
            out[index] = outcome
        }
        return out.map { it!! }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.CachingLlmNormalizerTest"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```
git add core/llm/src/main/kotlin/com/aimusic/player/llm/CachingLlmNormalizer.kt \
        core/llm/src/test/kotlin/com/aimusic/player/llm/ScriptedLlmNormalizer.kt \
        core/llm/src/test/kotlin/com/aimusic/player/llm/CachingLlmNormalizerTest.kt
git commit -m "feat(llm): 缓存装饰器逐文件命中，只把未命中发给下层

全命中零网络、部分命中只发未命中文件、只缓存 Success（spec §8 / 05 §4.8）。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 5: `RetryingLlmNormalizer` —— 429 退避（`:core:llm`）

**Files:**
- Create: `core/llm/src/main/kotlin/com/aimusic/player/llm/RetryingLlmNormalizer.kt`
- Test: `core/llm/src/test/kotlin/com/aimusic/player/llm/RetryingLlmNormalizerTest.kt`

**Interfaces:**
- Consumes: `com.aimusic.player.common.retry.RetryPolicy`（`delayFor(attempt: Int, retryAfterMs: Long?, random: Random): Long` / `shouldRetry(kind: FailureKind, attempt: Int): Boolean` / `maxRetries`）、`com.aimusic.player.common.error.FailureKind`、`Sleeper`（Task 1）、`LlmNormalizer`（P1）/ `NormalizeOutcome`（P2）
- Produces: `com.aimusic.player.llm.BackoffEvent(attempt: Int, delayMs: Long)`；`com.aimusic.player.llm.RetryingLlmNormalizer(delegate: LlmNormalizer, policy: RetryPolicy, sleeper: Sleeper = RealSleeper, random: Random = Random.Default, onBackoff: (BackoffEvent) -> Unit = {}) : LlmNormalizer`

> 复用决定（已拍板，决定 #5）：`:core:common` 的 `RetryPolicy` 已实现并已测「1s/2s/4s…上限 60s、Retry-After 优先、抖动、只有 RATE_LIMIT 重试」，与 `05 §4.7` 要求一致。**`BackoffPolicy` 一律删除**（`05 §3.2` 不再新造），退避口径以 `RetryPolicy` 为准（抖动 `[0.8, 1.0]×退避`、60s 封顶）。

- [ ] **Step 1: 写失败测试 + 假时钟**

`core/llm/src/test/kotlin/com/aimusic/player/llm/RetryingLlmNormalizerTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.aimusic.player.common.retry.RetryPolicy
import com.aimusic.player.common.util.Sleeper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.Test

/** 假时钟：记录请求的时长，立即返回 —— 测试绝不真 sleep。 */
private class RecordingSleeper : Sleeper {
    val delays = mutableListOf<Long>()
    override suspend fun sleep(ms: Long) {
        delays += ms
    }
}

/** 固定抖动，才能断言精确时长（与 RetryPolicyTest 同法）。 */
private class FixedRandom(private val value: Double) : Random() {
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextDouble(): Double = value
}

class RetryingLlmNormalizerTest {

    private val req = NormalizeRequest(
        fileName = "a.mp3", metadata = null, categories = emptyList(), tags = emptyList(),
    )

    private fun limited(retryAfterMs: Long? = null): List<NormalizeOutcome> =
        listOf(NormalizeOutcome.RateLimited(retryAfterMs))

    private fun ok(): List<NormalizeOutcome> =
        listOf(NormalizeOutcome.Success(com.aimusic.player.common.model.NormalizeResult("a", listOf("x"), emptyList())))

    private fun retrying(
        script: List<List<NormalizeOutcome>>,
        maxRetries: Int = 3,
        sleeper: RecordingSleeper = RecordingSleeper(),
        random: Random = FixedRandom(0.0),
        onBackoff: (BackoffEvent) -> Unit = {},
    ) = RetryingLlmNormalizer(
        delegate = ScriptedLlmNormalizer(script),
        policy = RetryPolicy(maxRetries = maxRetries),
        sleeper = sleeper,
        random = random,
        onBackoff = onBackoff,
    ) to sleeper

    @Test
    fun `429 后成功 —— 退避 1s 再重试，只等一次`() = runBlocking {
        val (sut, sleeper) = retrying(listOf(limited(), ok()))

        val out = sut.normalize(listOf(req))

        assertThat(out.single()).isInstanceOf(NormalizeOutcome.Success::class.java)
        assertThat(sleeper.delays).containsExactly(1_000L).inOrder()
    }

    @Test
    fun `连续 429 按 1s 2s 4s 退避，重试耗尽后整批上抛 RateLimited`() = runBlocking {
        val (sut, sleeper) = retrying(listOf(limited(), limited(), limited(), limited()), maxRetries = 3)

        val out = sut.normalize(listOf(req, req, req))

        // 共 3 次退避（maxRetries=3），退避后仍 429 → 原样上抛整批
        assertThat(sleeper.delays).containsExactly(1_000L, 2_000L, 4_000L).inOrder()
        assertThat(out).hasSize(3)
        assertThat(out.all { it is NormalizeOutcome.RateLimited }).isTrue()
    }

    @Test
    fun `Retry-After 优先于本地退避`() = runBlocking {
        val (sut, sleeper) = retrying(listOf(limited(retryAfterMs = 30_000), ok()))

        sut.normalize(listOf(req))

        assertThat(sleeper.delays).containsExactly(30_000L)
    }

    @Test
    fun `抖动落在退避值的 80% 到 100%`() = runBlocking {
        val (sut, sleeper) = retrying(listOf(limited(), ok()), random = FixedRandom(1.0))

        sut.normalize(listOf(req))

        assertThat(sleeper.delays).containsExactly(800L)   // 1000 * (1 - 0.2*1.0)
    }

    @Test
    fun `非 429 不重试 —— 只发一次、零退避`() = runBlocking {
        val (sut, sleeper) = retrying(
            listOf(listOf(NormalizeOutcome.Failure(LlmFailureKind.SERVER, null))),
        )

        val out = sut.normalize(listOf(req))

        assertThat(sleeper.delays).isEmpty()
        assertThat(out.single()).isInstanceOf(NormalizeOutcome.Failure::class.java)
    }

    @Test
    fun `每次退避都上报 BackoffEvent（第 k 次与时长）`() = runBlocking {
        val events = mutableListOf<BackoffEvent>()
        val (sut, _) = retrying(
            listOf(limited(), limited(), limited(), limited()),
            maxRetries = 3,
            onBackoff = { events += it },
        )

        sut.normalize(listOf(req))

        assertThat(events.map { it.attempt }).containsExactly(1, 2, 3).inOrder()
        assertThat(events.map { it.delayMs }).containsExactly(1_000L, 2_000L, 4_000L).inOrder()
    }

    @Test
    fun `maxRetries 为 0 时不重试`() = runBlocking {
        val (sut, sleeper) = retrying(listOf(limited()), maxRetries = 0)

        sut.normalize(listOf(req))

        assertThat(sleeper.delays).isEmpty()
    }
}
```

> 说明：测试里用 `LlmFailureKind.SERVER` 作为「非 429 类失败」的代表；实现内部只认 `NormalizeOutcome.RateLimited`，并通过 `RetryPolicy.shouldRetry(FailureKind.RATE_LIMIT, attempt)` 判定是否继续。

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.RetryingLlmNormalizerTest"
```

预期：编译失败，`e: …RetryingLlmNormalizerTest.kt:… Unresolved reference: RetryingLlmNormalizer` / `… BackoffEvent`，末行 `BUILD FAILED`。

- [ ] **Step 3: 最小实现**

`core/llm/src/main/kotlin/com/aimusic/player/llm/RetryingLlmNormalizer.kt`：

```kotlin
package com.aimusic.player.llm

import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.common.retry.RetryPolicy
import com.aimusic.player.common.util.RealSleeper
import com.aimusic.player.common.util.Sleeper
import kotlin.random.Random

/** 一次退避的可观测事件（05 §4.7）。编排器据此 emit「请求过于频繁，重试中（第 k/N 次）」。 */
data class BackoffEvent(val attempt: Int, val delayMs: Long)

/**
 * 429 退避装饰器（**紧贴网络层**，spec §2 / 05 §4.7）。
 *
 * - 只有整批里出现 `RateLimited` 才退避；其余 `Failure`（NETWORK / AUTH / SERVER / INVALID_OUTPUT /
 *   TIMEOUT，含 5xx 与 529）**原样返回、不自动重试**。
 * - 退避时长复用 `:core:common` 的 [RetryPolicy]（1s/2s/4s…、60s 封顶、`Retry-After` 优先、抖动）。
 * - `maxRetries` 耗尽后把最后一次结果**整批上抛**，交由 `AnalysisOrchestrator` 置 `FAILED(RATE_LIMIT)`。
 * - 等待走注入的 [Sleeper]：测试注入假实现，**绝不真 sleep**；[random] 可注入以便断言精确时长。
 */
class RetryingLlmNormalizer(
    private val delegate: LlmNormalizer,
    private val policy: RetryPolicy,
    private val sleeper: Sleeper = RealSleeper,
    private val random: Random = Random.Default,
    private val onBackoff: (BackoffEvent) -> Unit = {},
) : LlmNormalizer {

    override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
        var retriesDone = 0
        while (true) {
            val outs = delegate.normalize(requests)
            val limited = outs
                .firstOrNull { it is NormalizeOutcome.RateLimited } as? NormalizeOutcome.RateLimited
                ?: return outs                                       // 无 429：原样返回（不重试）
            if (!policy.shouldRetry(FailureKind.RATE_LIMIT, retriesDone)) return outs   // 耗尽：整批上抛

            val delayMs = policy.delayFor(retriesDone + 1, limited.retryAfterMs, random)
            retriesDone++
            onBackoff(BackoffEvent(attempt = retriesDone, delayMs = delayMs))
            sleeper.sleep(delayMs)                                   // 退避期间编排器仍在 await、保持 ANALYZING
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.RetryingLlmNormalizerTest"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```
git add core/llm/src/main/kotlin/com/aimusic/player/llm/RetryingLlmNormalizer.kt \
        core/llm/src/test/kotlin/com/aimusic/player/llm/RetryingLlmNormalizerTest.kt
git commit -m "feat(llm): 429 退避装饰器，复用 RetryPolicy 与可注入 Sleeper

只有 429 进退避；耗尽后整批上抛；非 429 只发一次（spec §9.1 / 05 §4.7）。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 6: `RoomLlmCache` 实现 + Hilt 装配（`:core:data`，真机测试）

**Files:**
- Create: `core/data/src/main/java/com/aimusic/player/data/cache/RoomLlmCache.kt`
- Test: `core/data/src/androidTest/java/com/aimusic/player/data/cache/RoomLlmCacheTest.kt`
- Modify: `core/data/build.gradle.kts`（加 `implementation(project(":core:llm"))`）
- Modify: `build.gradle.kts`（守卫：`:core:data` 允许 `:core:llm`，已拍板，决定 #2）
- Modify: `core/data/src/main/java/com/aimusic/player/data/di/RepositoryModule.kt`（`@Provides LlmCache`）

**Interfaces:**
- Consumes: `com.aimusic.player.llm.LlmCache`（Task 2）、`com.aimusic.player.data.dao.LlmCacheDao.get/put`、`com.aimusic.player.data.entity.LlmCacheEntity`
- Produces: `com.aimusic.player.data.cache.RoomLlmCache(dao: LlmCacheDao, now: () -> Long = System::currentTimeMillis) : LlmCache`；Hilt `@Provides LlmCache`

- [ ] **Step 1: 写失败测试 + 放开守卫与依赖**

`build.gradle.kts` 的 `moduleDependencyRules` 改这一行（其余不动）：

```kotlin
    // 原来：":core:data" to setOf(":core:storage", ":core:common"),
    // RoomLlmCache 在 :core:data，要实现 :core:llm 的 LlmCache 接口；spec §2 也要求
    // :core:data 的 AnalysisOrchestrator 认得 LlmNormalizer —— 故放宽（已拍板，决定 #2）。
    ":core:data" to setOf(":core:storage", ":core:common", ":core:llm"),
```

`core/data/build.gradle.kts` 的 `dependencies { ... }` 加一行：

```kotlin
    implementation(project(":core:llm"))
```

`core/data/src/androidTest/java/com/aimusic/player/data/cache/RoomLlmCacheTest.kt`：

```kotlin
package com.aimusic.player.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.model.TagAssignment
import com.aimusic.player.data.countOf
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.exec
import com.aimusic.player.data.scalar
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLlmCacheTest {

    private lateinit var db: MusicDatabase
    private lateinit var cache: RoomLlmCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = RoomLlmCache(db.llmCacheDao(), now = { 1_000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val result = NormalizeResult(
        canonicalTitle = "晴天 Live",
        artists = listOf("周杰伦"),
        tagAssignments = listOf(TagAssignment("情绪", "怀旧")),
    )

    @Test
    fun `put_后_get_原样取回`() = runDbTest {
        cache.put("k1", result)

        assertThat(cache.get("k1")).isEqualTo(result)
    }

    @Test
    fun `未写入的_key_返回_null`() = runDbTest {
        assertThat(cache.get("missing")).isNull()
    }

    @Test
    fun `同_key_覆盖写入只留一行`() = runDbTest {
        cache.put("k1", result)
        cache.put("k1", NormalizeResult("改名", listOf("A"), emptyList()))

        assertThat(cache.get("k1")!!.canonicalTitle).isEqualTo("改名")
        assertThat(db.countOf("llm_cache")).isEqualTo(1)
    }

    @Test
    fun `created_at_来自注入的时钟`() = runDbTest {
        cache.put("k1", result)

        assertThat(db.scalar("SELECT created_at FROM llm_cache WHERE cache_key = 'k1'")).isEqualTo("1000")
    }

    @Test
    fun `脏_json_视为未命中而不抛异常`() = runDbTest {
        db.exec("INSERT INTO llm_cache (cache_key, result_json, created_at) VALUES ('bad', 'not json', 0)")

        assertThat(cache.get("bad")).isNull()
    }
}
```

- [ ] **Step 2: 跑测试确认失败（真机，先唤醒设备）**

```
adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aimusic.player.data.cache.RoomLlmCacheTest
```

预期：编译失败，`e: …RoomLlmCacheTest.kt:… Unresolved reference: RoomLlmCache`，末行 `BUILD FAILED`。

- [ ] **Step 3: 最小实现**

`core/data/src/main/java/com/aimusic/player/data/cache/RoomLlmCache.kt`：

```kotlin
package com.aimusic.player.data.cache

import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.model.TagAssignment
import com.aimusic.player.data.dao.LlmCacheDao
import com.aimusic.player.data.entity.LlmCacheEntity
import com.aimusic.player.llm.LlmCache
import org.json.JSONArray
import org.json.JSONObject

/**
 * [LlmCache] 的 Room 实现（spec §2 / 05 §4.8）：把 `NormalizeResult` 序列化进 `llm_cache.result_json`。
 *
 * `llm_cache` 是内容寻址的**可复用资产**、不参与业务级联删除（03 §3.8）。
 * 解析失败（老版本/脏数据）视为**未命中**并放行，不抛异常 —— 缓存不是真相来源，坏一行不该让整次分析崩掉。
 */
class RoomLlmCache(
    private val dao: LlmCacheDao,
    private val now: () -> Long = System::currentTimeMillis,
) : LlmCache {

    override suspend fun get(key: String): NormalizeResult? {
        val json = dao.get(key) ?: return null
        return runCatching { decode(json) }.getOrNull()
    }

    override suspend fun put(key: String, result: NormalizeResult) {
        dao.put(LlmCacheEntity(cacheKey = key, resultJson = encode(result), createdAt = now()))
    }

    private fun encode(result: NormalizeResult): String = JSONObject()
        .put("canonicalTitle", result.canonicalTitle)
        .put("artists", JSONArray(result.artists))
        .put(
            "tagAssignments",
            JSONArray().apply {
                result.tagAssignments.forEach { a ->
                    put(JSONObject().put("category", a.category).put("name", a.name))
                }
            },
        )
        .toString()

    private fun decode(json: String): NormalizeResult {
        val obj = JSONObject(json)
        val artistsArr = obj.getJSONArray("artists")
        val artists = (0 until artistsArr.length()).map { artistsArr.getString(it) }
        val tagsArr = obj.getJSONArray("tagAssignments")
        val tags = (0 until tagsArr.length()).map { i ->
            val t = tagsArr.getJSONObject(i)
            TagAssignment(category = t.getString("category"), name = t.getString("name"))
        }
        return NormalizeResult(
            canonicalTitle = obj.getString("canonicalTitle"),
            artists = artists,
            tagAssignments = tags,
        )
    }
}
```

在 `core/data/src/main/java/com/aimusic/player/data/di/RepositoryModule.kt` 增加（放在 `provideDeletionService` 之后）：

```kotlin
    /**
     * `LlmCache` 接口在 `:core:llm`、实现在本模块 —— 同 `provideScanSourceRepository` 的装配路数
     * （spec §2）。单例：缓存无状态，重复构造没有意义。
     */
    @Provides
    @Singleton
    fun provideLlmCache(db: MusicDatabase): LlmCache = RoomLlmCache(db.llmCacheDao())
```

对应新增 import：

```kotlin
import com.aimusic.player.data.cache.RoomLlmCache
import com.aimusic.player.llm.LlmCache
```

- [ ] **Step 4: 跑测试确认通过（真机）**

```
adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.aimusic.player.data.cache.RoomLlmCacheTest
```

预期：`BUILD SUCCESSFUL`（`Tests on <device> passed`）。

- [ ] **Step 5: 提交**

```
git add build.gradle.kts core/data/build.gradle.kts \
        core/data/src/main/java/com/aimusic/player/data/cache/RoomLlmCache.kt \
        core/data/src/main/java/com/aimusic/player/data/di/RepositoryModule.kt \
        core/data/src/androidTest/java/com/aimusic/player/data/cache/RoomLlmCacheTest.kt
git commit -m "feat(data): RoomLlmCache 实现 LlmCache 并用 Hilt 装配

放开 :core:data → :core:llm（RoomLlmCache 要实现 :core:llm 的接口）；脏 JSON 视为未命中。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 7: 装配装饰链 `Caching(Retrying(Direct))` + 顺序测试（`:core:llm`）

**Files:**
- Create: `core/llm/src/main/kotlin/com/aimusic/player/llm/LlmNormalizerFactory.kt`
- Test: `core/llm/src/test/kotlin/com/aimusic/player/llm/LlmNormalizerFactoryTest.kt`
- Modify: `core/data/src/main/java/com/aimusic/player/data/di/RepositoryModule.kt`（`@Provides LlmNormalizer`，跨计划装配）

**Interfaces:**
- Consumes: `NormalizeRequest`（P2）、`DirectProvider`（P1）、`PromptBuilder`（P2，提供 `promptHash()`）、`DirectoryFingerprint`（P2，提供 `of(categories, tags)`）、`LlmCache`（Task 2）、`CachingLlmNormalizer`（Task 4）、`RetryingLlmNormalizer`（Task 5）、`RetryPolicy` / `Sleeper`（`:core:common`）、`LlmConfig`（P1，含 `model` / `maxRetries`）
- Produces: `com.aimusic.player.llm.buildLlmNormalizer(direct: LlmNormalizer, cache: LlmCache, model: String, promptHash: () -> String, dirFingerprint: (NormalizeRequest) -> String, policy: RetryPolicy, sleeper: Sleeper = RealSleeper, random: Random = Random.Default, onBackoff: (BackoffEvent) -> Unit = {}): LlmNormalizer`

- [ ] **Step 1: 写失败测试**

`core/llm/src/test/kotlin/com/aimusic/player/llm/LlmNormalizerFactoryTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.retry.RetryPolicy
import com.aimusic.player.common.util.Sleeper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import org.junit.Test

class LlmNormalizerFactoryTest {

    private class RecordingSleeper : Sleeper {
        val delays = mutableListOf<Long>()
        override suspend fun sleep(ms: Long) {
            delays += ms
        }
    }

    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }

    private fun req(name: String) = NormalizeRequest(
        fileName = name, metadata = null, categories = listOf("音乐类型"), tags = emptyList(),
    )

    private fun success(request: NormalizeRequest): NormalizeOutcome =
        NormalizeOutcome.Success(NormalizeResult("t", listOf("a"), emptyList()))

    private fun build(
        direct: ScriptedLlmNormalizer,
        cache: InMemoryLlmCache,
        sleeper: RecordingSleeper = RecordingSleeper(),
    ) = buildLlmNormalizer(
        direct = direct,
        cache = cache,
        model = "gpt-4o-mini",
        promptHash = { "p-hash" },
        dirFingerprint = { "d-hash" },
        policy = RetryPolicy(maxRetries = 3),
        sleeper = sleeper,
        random = FixedRandom(0.0),
    )

    @Test
    fun `顺序是 Caching 外层 —— 命中后零网络`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"), req("b.mp3"))
        build(ScriptedLlmNormalizer(listOf(requests.map(::success))), cache).normalize(requests)

        val direct = ScriptedLlmNormalizer(listOf(requests.map(::success)))
        val out = build(direct, cache).normalize(requests)

        assertThat(direct.calls).isEmpty()                    // 缓存最外层，拦截了网络
        assertThat(out.all { it is NormalizeOutcome.Success }).isTrue()
    }

    @Test
    fun `顺序是 Retrying 内层 —— 429 退避后才写缓存`() = runBlocking {
        val cache = InMemoryLlmCache()
        val requests = listOf(req("a.mp3"))
        val sleeper = RecordingSleeper()
        val direct = ScriptedLlmNormalizer(
            listOf(listOf(NormalizeOutcome.RateLimited(null)), listOf(success(requests[0]))),
        )

        val out = build(direct, cache, sleeper).normalize(requests)

        assertThat(direct.calls).hasSize(2)                   // 一次 429 + 一次成功
        assertThat(sleeper.delays).containsExactly(1_000L)    // 退避在缓存之内
        assertThat(cache.putCount).isEqualTo(1)               // 只把退避后的成功写缓存
        assertThat(out.single()).isInstanceOf(NormalizeOutcome.Success::class.java)
    }

    @Test
    fun `已命中文件不参与退避 —— 429 只对未命中批次重试`() = runBlocking {
        val cache = InMemoryLlmCache()
        val warm = listOf(req("a.mp3"))
        build(ScriptedLlmNormalizer(listOf(warm.map(::success))), cache).normalize(warm)

        // 现在 a 命中、b 未命中；下层首轮对 [b] 返回 429，次轮成功
        val requests = listOf(req("a.mp3"), req("b.mp3"))
        val direct = ScriptedLlmNormalizer(
            listOf(listOf(NormalizeOutcome.RateLimited(null)), listOf(success(requests[1]))),
        )
        val out = build(direct, cache).normalize(requests)

        assertThat(direct.calls).hasSize(2)                            // 一次 429 + 一次成功
        assertThat(direct.calls.all { it.size == 1 }).isTrue()         // 每次都只收到 1 个（b）
        assertThat(direct.calls.map { it.single().fileName }).containsExactly("b.mp3", "b.mp3")
        assertThat(out).hasSize(2)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.LlmNormalizerFactoryTest"
```

预期：编译失败，`e: …LlmNormalizerFactoryTest.kt:… Unresolved reference: buildLlmNormalizer`，末行 `BUILD FAILED`。

- [ ] **Step 3: 实现工厂**

`core/llm/src/main/kotlin/com/aimusic/player/llm/LlmNormalizerFactory.kt`：

```kotlin
package com.aimusic.player.llm

import com.aimusic.player.common.retry.RetryPolicy
import com.aimusic.player.common.util.RealSleeper
import com.aimusic.player.common.util.Sleeper
import kotlin.random.Random

/**
 * 组装装饰链：`Caching( Retrying( Direct ) )`（spec §2 / 05 §4.2）。
 *
 * 顺序**不可交换**：
 * 1. 缓存最外层 → 命中即零网络（保住 01 §3.3「重扫不花 token」）；
 * 2. 退避紧贴网络 → 只包裹真实 HTTP，缓存层不感知 429；
 * 3. 只缓存成功 → 退避在缓存之内，写进缓存的自然是「退避之后」的最终成功。
 *
 * `direct` 由 P1 提供（`DirectProvider`）；`promptHash` 由 P2 的 `PromptBuilder` 提供，`dirFingerprint` 由 P2 的 `DirectoryFingerprint.of(categories, tags)` 适配。
 */
fun buildLlmNormalizer(
    direct: LlmNormalizer,
    cache: LlmCache,
    model: String,
    promptHash: () -> String,
    dirFingerprint: (NormalizeRequest) -> String,
    policy: RetryPolicy,
    sleeper: Sleeper = RealSleeper,
    random: Random = Random.Default,
    onBackoff: (BackoffEvent) -> Unit = {},
): LlmNormalizer = CachingLlmNormalizer(
    delegate = RetryingLlmNormalizer(
        delegate = direct,
        policy = policy,
        sleeper = sleeper,
        random = random,
        onBackoff = onBackoff,
    ),
    cache = cache,
    model = model,
    promptHash = promptHash,
    dirFingerprint = dirFingerprint,
)
```

- [ ] **Step 4: 跑测试确认通过**

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.LlmNormalizerFactoryTest"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: Hilt 注册 `LlmNormalizer`（跨计划装配；参数全部来自 P1/P2 的既有绑定）**

在 `core/data/src/main/java/com/aimusic/player/data/di/RepositoryModule.kt` 增加：

```kotlin
    @Provides
    @Singleton
    fun provideSleeper(): Sleeper = RealSleeper

    /**
     * 组装 `Caching(Retrying(Direct))`。顺序固定在 [buildLlmNormalizer] 内部（Task 7 的顺序测试钉住）。
     * `direct` / `promptBuilder` / `config` 由 P1/P2 各自的 Hilt 绑定提供（本计划只消费）。
     */
    @Provides
    @Singleton
    fun provideLlmNormalizer(
        direct: DirectProvider,
        promptBuilder: PromptBuilder,
        config: LlmConfig,
        cache: LlmCache,
        sleeper: Sleeper,
    ): LlmNormalizer = buildLlmNormalizer(
        direct = direct,
        cache = cache,
        model = config.model,
        promptHash = promptBuilder::promptHash,
        dirFingerprint = { req -> DirectoryFingerprint.of(req.categories, req.tags) },
        policy = RetryPolicy(maxRetries = config.maxRetries),
        sleeper = sleeper,
    )
```

对应 import：

```kotlin
import com.aimusic.player.common.retry.RetryPolicy
import com.aimusic.player.common.util.RealSleeper
import com.aimusic.player.common.util.Sleeper
import com.aimusic.player.llm.DirectProvider
import com.aimusic.player.llm.LlmConfig
import com.aimusic.player.llm.LlmNormalizer
import com.aimusic.player.llm.PromptBuilder
import com.aimusic.player.llm.buildLlmNormalizer
import com.aimusic.player.llm.cache.DirectoryFingerprint
```

（`DirectProvider` / `PromptBuilder` / `LlmConfig` / `DirectoryFingerprint` 若 P1/P2 最终命名或所在包不同，只改这几行 import 与参数类型。）

- [ ] **Step 6: 编译校验装配可解析**

```
./gradlew :core:data:compileDebugKotlin
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 7: 提交**

```
git add core/llm/src/main/kotlin/com/aimusic/player/llm/LlmNormalizerFactory.kt \
        core/llm/src/test/kotlin/com/aimusic/player/llm/LlmNormalizerFactoryTest.kt \
        core/data/src/main/java/com/aimusic/player/data/di/RepositoryModule.kt
git commit -m "feat(llm): 装配 Caching(Retrying(Direct)) 并顺序测试

缓存最外层命中即零网络；退避紧贴网络；Hilt 注册 LlmNormalizer（spec §2 / 05 §4.2）。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

## 自检：spec 覆盖对照

| 出处 | 条目 | 由哪个 Task 覆盖 |
| --- | --- | --- |
| spec §4（强制 JSON 分层 L1/L2/L3） | 不在 P3 范围 —— 属**协议层与解析层**，由 P1（适配器的结构化输出字段）与 P2（`NormalizeParser` 容错 + `schema.json`）覆盖；P3 不触碰。 | — （显式排除） |
| spec §8 | 缓存 key = `sha256(fileName ␟ 元数据指纹 ␟ model ␟ prompt 文件哈希 ␟ 目录指纹)` | Task 3（组装）+ Task 4（prompt 哈希/目录指纹变化 → 全 miss） |
| spec §8 | 只缓存 `Success`；`RateLimited` / `Failure` 不写缓存 | Task 4（`只缓存成功：Failure 与 RateLimited 不写缓存`）；Task 7（`429 退避后才写缓存`） |
| spec §8 | 批量下逐文件查缓存，只把未命中的塞进请求；全命中＝零网络 | Task 4（全命中零网络 / 部分命中只发未命中）；Task 7（顺序测试） |
| spec §9.1 | `429`（含 `Retry-After`）进退避、耗尽后整批上抛 | Task 5（1s/2s/4s 时序、`Retry-After` 优先、耗尽上抛 `RateLimited`） |
| spec §9.1 | 非 429 不重试（5xx / 529 / AUTH 只发一次） | Task 5（`非 429 不重试 —— 只发一次、零退避`）；529→SERVER 归 P1 映射，P3 只保证"server 类不重试" |
| spec §9.1 | 网络异常 / 超时整批失败（`NETWORK`/`TIMEOUT`） | 归 P1（`DirectProvider` 的 `transportKind`）；P3 透传不重试（Task 5 的 `Failure` 分支） |
| `05 §4.7` | 退避算法：1s/2s/4s…、上限 60s、`Retry-After` 优先、抖动 | Task 5（复用已测 `RetryPolicy.delayFor`，逐项断言） |
| `05 §4.7` | `maxRetries` 耗尽 → 上抛、编排器置 `FAILED(RATE_LIMIT)` | Task 5（`重试耗尽后整批上抛 RateLimited`） |
| `05 §4.7` | 可注入 `Sleeper`（假时钟）、`onBackoff` 事件 | Task 1（`Sleeper`）+ Task 5（`RecordingSleeper` 断言、`BackoffEvent`） |
| `05 §4.8` | `LlmCache` 接口在 `:core:llm`、`RoomLlmCache` 在 `:core:data` + Hilt | Task 2（接口）+ Task 6（实现 + `@Provides LlmCache`） |
| `05 §4.8` | 缓存 key 五段组成 + "命中缓存流程"（全命中/部分命中） | Task 3 + Task 4 |
| `05 §4.2` | 装饰顺序 `Caching(Retrying(Direct))` 与四条理由 | Task 7（`buildLlmNormalizer` + 三条顺序测试） |
| spec §13 表 / G9、G10 | 改 `system.txt`（prompt 哈希）或新增分类（目录指纹）→ 全 miss、恰好 1 次网络 | Task 3（key 变化）+ Task 4（`prompt 哈希或目录指纹变化导致全 miss`） |
| spec §13 表 / G11 | 部分命中只发未命中文件；全命中零网络 | Task 4 |
| spec §13 表 / G2、G3 | 429 退避时序与耗尽；5xx/529 只发一次 | Task 5 |

## 附：待办与已定（spec 未写清 / 与既有约束冲突）

**已定（六条决定覆盖，无需再决策）**

1. **`:core:data → :core:llm` 放开** —— **已定（决定 #2）**：改 `build.gradle.kts` 的 `moduleDependencyRules`，`:core:data` 允许 `:core:llm`（Task 6 落地）。不再考虑「把 `LlmCache` 下沉到 `:core:common`」的备选。
2. **`dirFingerprint` 签名** —— **已定（决定 #3 的连带）**：P2 实际提供 `object DirectoryFingerprint { fun of(categories: List<String>, tags: List<TagRef>): String }`（P2 Task 5，排序去重、顺序无关）。本计划的 `CachingLlmNormalizer` / `buildLlmNormalizer` 仍收 `(NormalizeRequest) -> String`，由 `{ req -> DirectoryFingerprint.of(req.categories, req.tags) }` 适配；Hilt provider 见 Task 7 Step 5。
3. **退避参数类型** —— **已定（决定 #5）**：复用 `:core:common` 已实现且已测的 `RetryPolicy`，**删除 `BackoffPolicy`**；抖动口径以 `RetryPolicy` 为准（`[0.8,1.0]×退避`、60s 封顶）。
4. **`NormalizeRequest.metadata` 的类型落点** —— **已定（决定 #1）**：`AudioMetadata` 迁入 `:core:common` 的 `com.aimusic.player.common.model`（由 P2 Task 1 落地，本计划测试按此 import）。

**仍待办（未被六条决定覆盖）**

5. **`result_json` 序列化格式与位置**：`03` 未定。本计划在 `:core:data` 内用 `org.json` 手写（不引新依赖），round-trip 由 androidTest 钉住；备选是在 `:core:common` 给 `NormalizeResult` 加 `@Serializable`。
6. **`LlmConfig` 的来源**：Task 7 的 Hilt provider 需要 `LlmConfig`（含 `model` / `maxRetries`）；其归属（`SettingsRepository.llmConfig()` 还是 P1 的配置类型）仍待 P1/P2 拍板。`BackoffEvent` 字段形状已由本计划定为 `(attempt: Int, delayMs: Long)`（Task 5）。
