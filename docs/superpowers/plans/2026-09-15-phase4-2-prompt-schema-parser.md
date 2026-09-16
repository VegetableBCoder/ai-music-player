# Phase 4-2 · Prompt 外置与解析 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 system/user prompt 与响应 JSON Schema 外置为 `:core:llm` 的资源文件（占位符朴素替换），实现"强制 JSON 三层降级"的策略判定与容错抽取，并实现 `NormalizeParser`（把 `tag_groups` 摊平成 `List<TagAssignment>`、按 `file_index` 回填、条目级失败只连坐该文件、整批不可解析整批失败），同时产出 P3 缓存 key 所需的 **prompt 文件哈希**与**目录指纹**两个接口。

**Architecture:** 全批一次性渲染（目录在前、文件清单在后，20 个文件共享一份目录）；`NormalizeParser.parse(text, req)` 是文档锁定的入口签名，只吃"适配器还原后的 JSON 字符串"，对协议方言完全无知。Parser 与 PromptBuilder 均为纯 JVM 类（无 Android import），资源走 classpath，单测不需要 Robolectric。

**Tech Stack:** Kotlin 2.2.10 / JVM 17、AGP 9.2.0（compileSdk 36、minSdk 26）、kotlinx-serialization-json 1.11.0、JUnit4 + Truth（经 `:core:testing` 暴露）、`:core:common` 的 `TextNormalizer` 与 `Logger`。

**Spec:** docs/superpowers/specs/2026-09-15-phase4-llm-protocol-design.md

## Global Constraints

- 依赖守卫：`:core:llm` 只允许依赖 `:core:common`（`build.gradle.kts` 的 `moduleDependencyRules` 配置期强制）；feature 模块之间禁止互相依赖，禁止反向依赖。
- Kotlin/Java 17、`compileSdk = 36`、`minSdk = 26`；协程显式钉住 `1.11.0`（`libs.kotlinx.coroutines.core/android`，不得依赖传递版本）。
- AGP 9 内置 Kotlin：KSP 走 `kotlin.sourceSets` 注册生成目录，构建必须保留 `android.disallowKotlinSourceSets=false`（`gradle.properties`，已在）。
- 注释与提交信息一律中文；提交信息末尾必须带 `Co-authored-by: CommandCodeBot <noreply@commandcode.ai>`。
- 不用通用 `Result` 包装：失败用既有 sealed 层次（`NormalizeOutcome` / `LlmFailureKind` / `AppError`），异常只作 `cause`。
- androidTest 的反引号函数名不得含空格（配置期检查会直接让构建失败；中文允许，空格/`+`/`.` 一律不行）。
- 真机跑 instrumented 测试前必须先唤醒设备：`adb shell input keyevent KEYCODE_WAKEUP`。
- **与 P1 的边界**：P2 **不消费** P1 的 `LlmCall` / `LlmHttpResult` / `ProtocolAdapter` / `ProtocolKind`。`parse` 只吃适配器还原后的 JSON 字符串（spec §3 硬约束 1）。
- **⚠ 执行 P1-T3 时已实地修正的归属（Task 6 必须按此改，别照本文其它处写法）**：`JsonEnforcementPolicy` 这个**类型**已归 P1 —— 见 `core/llm/src/main/java/com/aimusic/player/llm/JsonEnforcementPolicy.kt`，是 sealed interface，取值 `Schema(schema)` / `JsonObject` / `PromptOnly`，P1 的三个适配器都按它分支（已跑绿）。**P2 不得再声明同名类型**（原 Task 6 写的 `object JsonEnforcementPolicy { fun of(...): JsonEnforcement }` 会与之冲突，且多出的 `JsonEnforcement` 也是重复类型）。
  - P2 在 Task 6 只做**推导**：把能力位翻成 P1 的取值，建议落成顶层函数
    `fun jsonEnforcementFor(supportsJsonSchema: Boolean, protocolSupportsJsonMode: Boolean): JsonEnforcementPolicy`，
    L1 → `JsonEnforcementPolicy.Schema(schema)`（schema 由调用方传入或另开重载）、L2 → `JsonObject`、L3 → `PromptOnly`。
  - Task 6 里所有 `JsonEnforcementPolicy.of(...)` 的测试与实现、以及 `Create: .../parse/JsonEnforcementPolicy.kt`，
    都按上面这一条改名（文件名建议 `parse/JsonEnforcementDerivation.kt`）。
- **与 P3 的边界**：P2 产出 `PromptResources.promptHash()`（Task 3）与 `object DirectoryFingerprint { fun of(categories: List<String>, tags: List<TagRef>): String }`（Task 5），P3 的缓存 key 直接取用，不再自行计算（P3 注入的 `(NormalizeRequest) -> String` lambda 由它适配）。

---

### Task 1: 前置 —— 把 `AudioMetadata` 迁入 `:core:common`

`05 §3.1` / `02 §5.3` 锁定 `NormalizeRequest.metadata: AudioMetadata?`，而 `AudioMetadata` 现居 `:core:storage`（`core/storage/.../MetadataReader.kt`）。依赖守卫禁止 `:core:llm → :core:storage`，所以必须把**纯数据类** `AudioMetadata` 挪到 `:core:common`（与 `NormalizeResult` / `TagProjection` 同一路数，见其文件头注释）；`MetadataReader`（`fun interface`）仍留在 `:core:storage`。**此迁移已拍板（已定，见文末「已拍板事项」#1）：`AudioMetadata` 归 `:core:common` 的 `com.aimusic.player.common.model`。**

**Files:**
- Create: `core/common/src/main/java/com/aimusic/player/common/model/AudioMetadata.kt`
- Modify: `core/storage/src/main/java/com/aimusic/player/storage/MetadataReader.kt`（删数据类，加 import）
- Modify: `core/storage/src/main/java/com/aimusic/player/storage/MmrMetadataReader.kt`（加 import）
- Modify: `core/storage/src/main/java/com/aimusic/player/storage/MediaStoreAudioSource.kt`（加 import）
- Modify: `core/storage/src/main/java/com/aimusic/player/storage/AndroidMediaStoreAudioSource.kt`（加 import）
- Modify: `core/storage/src/test/java/com/aimusic/player/storage/MmrMetadataReaderTest.kt`（加 import）
- Modify: `core/data/src/main/java/com/aimusic/player/data/scan/ScanModels.kt`（改 import）
- Modify: `core/data/src/androidTest/java/com/aimusic/player/data/scan/ScanOrchestratorTest.kt`（改 import）

**Interfaces:**
- Consumes: 无（既有 `:core:storage` 的 `AudioMetadata` 消费方）
- Produces: `com.aimusic.player.common.model.AudioMetadata`（签名逐字不变：`title: String?`, `artist: String?`, `album: String?`, `albumArtist: String?`, `date: String?`, `durationMs: Long`, `hasEmbeddedPicture: Boolean`）

- [ ] **Step 1** 新建 `core/common/src/main/java/com/aimusic/player/common/model/AudioMetadata.kt`：

```kotlin
package com.aimusic.player.common.model

/**
 * 内嵌元数据快照（原居 `:core:storage`，Phase 4-2 迁入 `:core:common`）。
 *
 * 迁移动机：`05 §3.1` 锁定 `NormalizeRequest.metadata: AudioMetadata?`，而依赖守卫只允许
 * `:core:llm → :core:common`（`02 §2`、根 `build.gradle.kts` 的 `moduleDependencyRules`），
 * `:core:llm` 拿不到 `:core:storage` 的类型。它与 `NormalizeResult` / `TagProjection` 同属
 * 「无行为的纯数据类」，放 `:core:common` 不引入任何 Android 依赖。
 *
 * `MetadataReader`（`fun interface`）仍留在 `:core:storage` —— 它带读取行为，不属于本包。
 */
data class AudioMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val date: String?,
    val durationMs: Long,
    val hasEmbeddedPicture: Boolean,
)
```

- [ ] **Step 2** 删除 `core/storage/src/main/java/com/aimusic/player/storage/MetadataReader.kt` 里的 `AudioMetadata` 定义，并在文件头加 import。改完后该文件全文为：

```kotlin
package com.aimusic.player.storage

import com.aimusic.player.common.model.AudioMetadata

/** 音频元数据读取抽象（`02 §5.2`）。实现 `MmrMetadataReader` 在 Phase 3。 */
fun interface MetadataReader {
    fun read(ref: FileRef): AudioMetadata
}
```

- [ ] **Step 3** 跑一次编译，确认此刻是"红"的（其余消费方仍按旧包名解析）：

```
./gradlew :core:storage:compileDebugKotlin
```

预期输出：

```
e: file:///.../MmrMetadataReader.kt:79:22 Unresolved reference: AudioMetadata
> Task :core:storage:compileDebugKotlin FAILED
```

- [ ] **Step 4** 给下面 5 个同包文件各加一行 import `import com.aimusic.player.common.model.AudioMetadata`（它们与 `MetadataReader` 同属 `com.aimusic.player.storage`，此前无需 import）：
  - `core/storage/src/main/java/com/aimusic/player/storage/MmrMetadataReader.kt`
  - `core/storage/src/main/java/com/aimusic/player/storage/MediaStoreAudioSource.kt`
  - `core/storage/src/main/java/com/aimusic/player/storage/AndroidMediaStoreAudioSource.kt`
  - `core/storage/src/test/java/com/aimusic/player/storage/MmrMetadataReaderTest.kt`
  - `core/data/src/androidTest/java/com/aimusic/player/data/scan/ScanOrchestratorTest.kt`（把既有的 `import com.aimusic.player.storage.AudioMetadata` 整行替换）

  再把 `core/data/src/main/java/com/aimusic/player/data/scan/ScanModels.kt:6` 的 `import com.aimusic.player.storage.AudioMetadata` 替换为 `import com.aimusic.player.common.model.AudioMetadata`。

- [ ] **Step 5** 跑受影响模块的单测与依赖守卫，确认全绿（守卫在配置期执行，任一 `./gradlew` 调用都会校验）：

```
./gradlew :core:storage:testDebugUnitTest :core:data:compileDebugKotlin :core:common:testDebugUnitTest
```

预期输出：`BUILD SUCCESSFUL`（`MmrMetadataReaderTest`、`PathNormalizerTest` 等全部通过；无 `✗ :core:llm → ...` 违例）。

- [ ] **Step 6** 提交：

```bash
git add core/common/src/main/java/com/aimusic/player/common/model/AudioMetadata.kt \
        core/storage/src/main core/storage/src/test \
        core/data/src/main/java/com/aimusic/player/data/scan/ScanModels.kt \
        core/data/src/androidTest/java/com/aimusic/player/data/scan/ScanOrchestratorTest.kt
git commit -m "refactor(llm): AudioMetadata 迁入 :core:common，解开 :core:llm 的类型缺口

05 §3.1 / 02 §5.3 锁定 NormalizeRequest.metadata: AudioMetadata?，
而依赖守卫只允许 :core:llm → :core:common，:core:llm 拿不到 :core:storage 的类型。
纯数据类迁到 :core:common（与 NormalizeResult / TagProjection 同一路数），
MetadataReader 带行为、留在 :core:storage。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 2: `:core:llm` 内部契约类型 + `NoopLogger`

落地 `05 §3.1` 锁定的 `:core:llm` 内部类型（`NormalizeResult` / `TagAssignment` 已在 `:core:common`，此处只 import；`:core:llm` 契约的**两字段** `TagRef` 在此定义 —— `:core:common` 的三字段同名类型已按拍板改名为 `TagProjection`），并给出一对条目级失败的 `cause` 与一个默认 logger。同时把模块的测试基建（`:core:testing` 暴露的 JUnit4 + Truth）与序列化依赖一次性配好。

**Files:**
- ~~Modify: `core/llm/build.gradle.kts`~~（**已完成**：P1-T1 已加 kotlin-serialization 插件、kotlinx-serialization-json、okhttp、`mockwebserver` 测试依赖。本任务只需确认，不要再改，否则会与 P1 重复）
- Create: `core/llm/src/main/java/com/aimusic/player/llm/NormalizeContract.kt`
- Create: `core/llm/src/main/java/com/aimusic/player/llm/log/NoopLogger.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/NormalizeContractTest.kt`

**Interfaces:**
- Consumes: `com.aimusic.player.common.model.NormalizeResult`、`com.aimusic.player.common.model.AudioMetadata`（Task 1）、`com.aimusic.player.common.log.Logger` / `LogLevel` / `LogEvent` / `LogRecord`、`com.aimusic.player.common.error.AppError`
- Produces（逐字，来自 `05 §3.1`）：
  - `data class TagRef(val name: String, val category: String)`（**两字段**，`:core:llm` 契约类型，`com.aimusic.player.llm.TagRef`；与 `:core:common` 的三字段 `TagProjection` **不是一回事**）
  - `data class NormalizeRequest(val fileName: String, val metadata: AudioMetadata?, val categories: List<String>, val tags: List<TagRef>)`
  - `sealed interface NormalizeOutcome { data class Success(val result: NormalizeResult); data class RateLimited(val retryAfterMs: Long?); data class Failure(val kind: LlmFailureKind, val cause: Throwable?) }`
  - `enum class LlmFailureKind { NETWORK, AUTH, SERVER, INVALID_OUTPUT, TIMEOUT }` —— **不在此定义！** P1-T2 已在 `core/llm/src/main/java/com/aimusic/player/llm/LlmFailureKind.kt` 建好（连同 `toFailureKind()` 映射），本任务只 import 使用。原先重复声明会造成同名类型冲突，执行 P1-T2 时已实地发现。
  - `class MissingField(val field: String) : IllegalStateException`
  - `class DuplicateIndex(val fileIndex: Int) : IllegalStateException`
  - `object NoopLogger : com.aimusic.player.common.log.Logger`

- [ ] **Step 1** 改 `core/llm/build.gradle.kts` 的 `dependencies` 块：加序列化依赖与测试基建（`:core:testing` 用 `api` 暴露 JUnit4 + Truth + MockK）：

```kotlin
dependencies {
    // 02 §2：core:llm 只依赖 core:common。分类/标签清单由调用方以参数传入，
    // 保证 LLM 层可独立测试。
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 解析响应 JSON（NormalizeParser / PromptBuilder 的 schema 注入，spec §5/§7）。
    // 只用到 JsonElement 树与 Json.parseToJsonElement，不需要 kotlin-serialization 编译器插件。
    implementation(libs.kotlinx.serialization.json)

    // JVM 单测：core:testing 以 api 暴露 JUnit4 / Truth / MockK / coroutines-test（不引 Robolectric）
    testImplementation(project(":core:testing"))
}
```

- [ ] **Step 2** 写失败测试 `core/llm/src/test/java/com/aimusic/player/llm/NormalizeContractTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.aimusic.player.common.model.NormalizeResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `05 §3.1` 的契约为锁定值，这里把它钉住，改动即红。 */
class NormalizeContractTest {

    @Test
    fun `LlmFailureKind 恰好是契约里的五个值`() {
        assertThat(LlmFailureKind.entries.map { it.name })
            .containsExactly("NETWORK", "AUTH", "SERVER", "INVALID_OUTPUT", "TIMEOUT")
    }

    @Test
    fun `Success 包住 NormalizeResult，Failure 带 kind 与 cause`() {
        val result = NormalizeResult("晴天", listOf("周杰伦"), emptyList())
        assertThat((NormalizeOutcome.Success(result) as NormalizeOutcome.Success).result).isEqualTo(result)

        val cause = MissingField("canonical_title")
        val failure = NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, cause)
        assertThat(failure.kind).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
        assertThat(failure.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `条目级失败的 cause 带出是哪个字段 / 哪个索引`() {
        assertThat(MissingField("artists").field).isEqualTo("artists")
        assertThat(DuplicateIndex(7).fileIndex).isEqualTo(7)
    }
}
```

- [ ] **Step 3** 跑测试确认失败（类型尚不存在）：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.NormalizeContractTest"
```

预期输出：编译失败，`e: ... Unresolved reference: LlmFailureKind` / `Unresolved reference: NormalizeOutcome`，任务 `FAILED`。

- [ ] **Step 4** 最小实现。新建 `core/llm/src/main/java/com/aimusic/player/llm/NormalizeContract.kt`：

```kotlin
package com.aimusic.player.llm

import com.aimusic.player.common.model.AudioMetadata
import com.aimusic.player.common.model.NormalizeResult

/** LLM 契约的标签引用（`05 §3.1` / `02 §5.3`）：只有分类**名**，拿不到 `categoryId`（依赖守卫所限）。 */
data class TagRef(val name: String, val category: String)

/**
 * `:core:llm` 的内部契约（`05 §3.1` / `02 §5.3`，**逐字锁定**）。
 *
 * `NormalizeResult` / `TagAssignment` 已在 `:core:common`（数据层也要用），这里只 import；
 * 两字段 `TagRef` 是 `:core:llm` 契约类型，**在此定义**（`:core:common` 里那个三字段同名类型
 * 已按拍板改名 `TagProjection`，两者不是一回事）。`NormalizeOutcome` / `LlmFailureKind` /
 * `NormalizeRequest` 是 LLM 层内部类型，不外泄到上层（`05 §3.2.2`）。
 */
data class NormalizeRequest(
    val fileName: String,
    /** 来自 `:core:storage` 的 `MetadataReader`；可能缺（只按文件名分析）。 */
    val metadata: AudioMetadata?,
    /** 当前有效分类（每批实时读取一次，非快照）。 */
    val categories: List<String>,
    /** 当前有效标签。 */
    val tags: List<TagRef>,
)

sealed interface NormalizeOutcome {
    data class Success(val result: NormalizeResult) : NormalizeOutcome
    data class RateLimited(val retryAfterMs: Long?) : NormalizeOutcome
    data class Failure(val kind: LlmFailureKind, val cause: Throwable?) : NormalizeOutcome
}

enum class LlmFailureKind { NETWORK, AUTH, SERVER, INVALID_OUTPUT, TIMEOUT }

/** 条目级解析失败：标明是哪个字段缺失 / 为空（spec §9.2）。 */
class MissingField(val field: String) : IllegalStateException("缺字段: $field")

/** 条目级解析失败：两个 item 用了同一个 `file_index`（spec §9.2 / G8）。 */
class DuplicateIndex(val fileIndex: Int) : IllegalStateException("file_index 重复: $fileIndex")
```

新建 `core/llm/src/main/java/com/aimusic/player/llm/log/NoopLogger.kt`：

```kotlin
package com.aimusic.player.llm.log

import com.aimusic.player.common.error.AppError
import com.aimusic.player.common.log.LogEvent
import com.aimusic.player.common.log.LogLevel
import com.aimusic.player.common.log.LogRecord
import com.aimusic.player.common.log.Logger

/** 丢弃一切日志。作为 `NormalizeParser` 的默认 logger，让解析逻辑在无日志环境下也能构造（纯 JVM 单测）。 */
object NoopLogger : Logger {
    override fun isEnabled(level: LogLevel): Boolean = false
    override fun log(level: LogLevel, event: LogEvent, message: String, fields: Map<String, Any?>, error: AppError?) = Unit
    override fun debug(event: LogEvent, message: String, fields: Map<String, Any?>) = Unit
    override fun info(event: LogEvent, message: String, fields: Map<String, Any?>) = Unit
    override fun warn(event: LogEvent, message: String, error: AppError?, fields: Map<String, Any?>) = Unit
    override fun error(event: LogEvent, message: String, error: AppError?, fields: Map<String, Any?>) = Unit
    override fun recent(limit: Int): List<LogRecord> = emptyList()
}
```

- [ ] **Step 5** 跑测试确认通过：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.NormalizeContractTest"
```

预期输出：`BUILD SUCCESSFUL`（3 个测试通过）。

- [ ] **Step 6** 提交：

```bash
git add core/llm/build.gradle.kts core/llm/src
git commit -m "feat(llm): :core:llm 内部契约类型与默认 logger

落地 05 §3.1 的 NormalizeRequest / NormalizeOutcome / LlmFailureKind，
补条目级失败的两个 cause（MissingField / DuplicateIndex）与 NoopLogger。
模块加 kotlinx-serialization-json 与 core:testing 测试基建。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 3: 三个 prompt 资源文件 + `PromptResources`（含 prompt 文件哈希）

把 `05 §4.4.1` / `§4.4.2` 的原文与 spec §7 的 schema 骨架落成资源文件，并提供 `promptHash()`（system+user+schema 三段内容的 sha256）供 P3 缓存 key 用。

**Files:**
- Create: `core/llm/src/main/resources/prompt/system.txt`
- Create: `core/llm/src/main/resources/prompt/user.txt`
- Create: `core/llm/src/main/resources/prompt/schema.json`
- Create: `core/llm/src/main/java/com/aimusic/player/llm/crypto/Hashing.kt`
- Create: `core/llm/src/main/java/com/aimusic/player/llm/prompt/PromptResources.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/prompt/PromptResourcesTest.kt`

**Interfaces:**
- Consumes: 无（JDK `MessageDigest` + classpath 读取）
- Produces:
  - `fun sha256Hex(text: String): String`（`com.aimusic.player.llm.crypto`，十六进制小写）
  - `class PromptResources(classLoader: ClassLoader = PromptResources::class.java.classLoader)`
    - `fun system(): String` / `fun user(): String` / `fun schemaJson(): String`
    - `fun promptHash(): String` —— `sha256Hex(system ␟ user ␟ schema)`（`␟` = U+001F）

- [ ] **Step 1** 写失败测试 `core/llm/src/test/java/com/aimusic/player/llm/prompt/PromptResourcesTest.kt`：

```kotlin
package com.aimusic.player.llm.prompt

import com.aimusic.player.llm.crypto.sha256Hex
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 资源走 classpath 读，不需要 Robolectric（spec §5）。 */
class PromptResourcesTest {

    private val resources = PromptResources()

    @Test
    fun `三个资源都能从 classpath 读到且非空`() {
        assertThat(resources.system()).contains("你是本地音乐库的元数据归一化助手")
        assertThat(resources.user()).contains("{{categories}}")
        assertThat(resources.schemaJson()).contains("\"tag_groups\"")
    }

    @Test
    fun `promptHash 是 64 位小写十六进制且稳定`() {
        val hash = resources.promptHash()
        assertThat(hash).matches("[0-9a-f]{64}")
        assertThat(PromptResources().promptHash()).isEqualTo(hash)
    }

    @Test
    fun `promptHash 等于 system user schema 三段内容按 U+001F 拼接后的 sha256`() {
        val expected = sha256Hex(
            listOf(resources.system(), resources.user(), resources.schemaJson()).joinToString("\u001F")
        )
        assertThat(resources.promptHash()).isEqualTo(expected)
    }

    @Test
    fun `sha256Hex 与空串已知向量一致`() {
        assertThat(sha256Hex(""))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }
}
```

- [ ] **Step 2** 跑测试确认失败：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.prompt.PromptResourcesTest"
```

预期输出：`e: ... Unresolved reference: PromptResources` / `Unresolved reference: sha256Hex`，任务 `FAILED`。

- [ ] **Step 3** 建资源文件。`core/llm/src/main/resources/prompt/system.txt`（`05 §4.4.1` 原文，第 6/7 条即 spec §6 的批量两条）：

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

`core/llm/src/main/resources/prompt/user.txt`（`05 §4.4.2` 模板原文）：

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

`core/llm/src/main/resources/prompt/schema.json`（spec §7 骨架原文；`{{categories}}` 由运行时注入）：

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

- [ ] **Step 4** 最小实现。`core/llm/src/main/java/com/aimusic/player/llm/crypto/Hashing.kt`：

```kotlin
package com.aimusic.player.llm.crypto

import java.security.MessageDigest

/** 十六进制小写 sha256。prompt 文件哈希与目录指纹共用（spec §8）。 */
fun sha256Hex(text: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
```

`core/llm/src/main/java/com/aimusic/player/llm/prompt/PromptResources.kt`：

```kotlin
package com.aimusic.player.llm.prompt

import com.aimusic.player.llm.crypto.sha256Hex

/**
 * 三个 prompt 资源文件的唯一读取入口（spec §5）。
 *
 * 纯 JVM resource，`classLoader.getResourceAsStream` 直接读：JVM 单测不需要 Robolectric，
 * 打包进 APK 也成立（Android library 的 `src/main/resources` 会合并为 java resources）。
 */
class PromptResources(
    private val classLoader: ClassLoader = PromptResources::class.java.classLoader,
) {

    fun system(): String = read(SYSTEM_PATH)

    fun user(): String = read(USER_PATH)

    fun schemaJson(): String = read(SCHEMA_PATH)

    /**
     * system + user + schema **三段文件内容**的 sha256（十六进制小写）——参与缓存 key，
     * **取代**原 `PROMPT_VERSION` 常量（spec §8）：改文件即失效，不再靠人记得改版本号。
     */
    fun promptHash(): String = sha256Hex(
        listOf(system(), user(), schemaJson()).joinToString(SEGMENT_SEPARATOR)
    )

    private fun read(path: String): String =
        requireNotNull(classLoader.getResourceAsStream(path)) { "缺少 prompt 资源：$path" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val SYSTEM_PATH = "prompt/system.txt"
        const val USER_PATH = "prompt/user.txt"
        const val SCHEMA_PATH = "prompt/schema.json"

        /** 三段之间用 U+001F 分隔，避免拼接歧义（与 `artistsKey` 同一约定）。 */
        const val SEGMENT_SEPARATOR = "\u001F"
    }
}
```

- [ ] **Step 5** 跑测试确认通过：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.prompt.PromptResourcesTest"
```

预期输出：`BUILD SUCCESSFUL`（4 个测试通过）。

- [ ] **Step 6** 提交：

```bash
git add core/llm/src
git commit -m "feat(llm): prompt/schema 外置为资源文件 + prompt 文件哈希

system.txt / user.txt 取自 05 §4.4.1/§4.4.2 原文（第 6、7 条即 spec §6 批量规则），
schema.json 取自 spec §7 骨架。PromptResources.promptHash() = 三段内容 sha256，
供 P3 缓存 key 取代 PROMPT_VERSION（spec §8）。资源走 classpath，无需 Robolectric。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 4: `PromptBuilder`（占位符朴素替换 + schema 分类 enum 注入）

把一批请求渲染成 `PromptBundle`：目录在前、文件清单在后；schema 只在 `tag_groups[].category.enum` 处注入当前分类。

**Files:**
- Create: `core/llm/src/main/java/com/aimusic/player/llm/prompt/PromptBuilder.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/prompt/PromptBuilderTest.kt`

**Interfaces:**
- Consumes: `PromptResources`（Task 3）、`NormalizeRequest` / `TagRef`（Task 2；两字段 `name` / `category`）
- Produces:
  - `data class PromptBundle(val system: String, val user: String, val schema: JsonElement)`
  - `class PromptBuilder(resources: PromptResources = PromptResources(), maxTagsPerCategory: Int = 2)`
    - `fun promptHash(): String`
    - `fun build(reqs: List<NormalizeRequest>): PromptBundle`（签名与 `05 §4.4` 一致；上限值走构造器，不进 `build` 签名）

- [ ] **Step 1** 写失败测试 `core/llm/src/test/java/com/aimusic/player/llm/prompt/PromptBuilderTest.kt`：

```kotlin
package com.aimusic.player.llm.prompt

import com.aimusic.player.common.model.AudioMetadata
import com.aimusic.player.llm.TagRef
import com.aimusic.player.llm.NormalizeRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class PromptBuilderTest {

    private val categories = listOf("音乐类型", "情绪", "场景", "主题")
    private val tags = listOf(
        TagRef("流行", "音乐类型"),
        TagRef("摇滚", "音乐类型"),
        TagRef("怀旧", "情绪"),
    )

    private fun request(
        fileName: String,
        metadata: AudioMetadata? = null,
    ) = NormalizeRequest(fileName, metadata, categories, tags)

    private val builder = PromptBuilder(PromptResources(), maxTagsPerCategory = 2)

    @Test
    fun `目录在前、文件清单在后`() {
        val bundle = builder.build(listOf(request("a.flac"), request("b.flac")))
        assertThat(bundle.user.indexOf("【当前有效分类】"))
            .isLessThan(bundle.user.indexOf("【文件 1】"))
        assertThat(bundle.user.indexOf("【文件 1】")).isLessThan(bundle.user.indexOf("【文件 2】"))
    }

    @Test
    fun `四个占位符全部被替换，渲染后无残留`() {
        val bundle = builder.build(listOf(request("a.flac")))
        assertThat(bundle.user).doesNotContain("{{")
        assertThat(bundle.user).contains("- 音乐类型")
        assertThat(bundle.user).contains("- 音乐类型: 流行, 摇滚")
        assertThat(bundle.user).contains("- 情绪: 怀旧")
        assertThat(bundle.user).contains("音乐类型 ≤ 2, 情绪 ≤ 2, 场景 ≤ 2, 主题 ≤ 2")
    }

    @Test
    fun `二十个文件共享一份目录，文件清单有二十项`() {
        val bundle = builder.build(List(20) { request("f$it.flac") })
        val categoryHeaders = bundle.user.split("【当前有效分类】").size - 1
        assertThat(categoryHeaders).isEqualTo(1)
        assertThat(bundle.user).contains("【文件 20】")
    }

    @Test
    fun `schema 只有 category 的 enum 注入了当前分类，其余骨架不动`() {
        val schema = builder.build(listOf(request("a.flac"))).schema.jsonObject
        val groupItem = schema.at("properties", "results", "items", "properties", "tag_groups", "items")
        val enum = groupItem.at("properties", "category").getValue("enum").jsonArray
        assertThat(enum.map { it.jsonPrimitive.content }).containsExactly("音乐类型", "情绪", "场景", "主题")

        val required = schema.at("properties", "results", "items").getValue("required").jsonArray
        assertThat(required.map { it.jsonPrimitive.content })
            .containsExactly("file_index", "canonical_title", "artists", "tag_groups")
    }

    @Test
    fun `元数据为空渲染「（无）」，非空逐字段渲染`() {
        val none = builder.build(listOf(request("a.flac"))).user
        assertThat(none).contains("内嵌元数据: （无）")

        val meta = AudioMetadata("晴天", "周杰伦", "叶惠美", "周杰伦", "2003-07-31", 269_000L, true)
        val some = builder.build(listOf(request("b.flac", meta))).user
        assertThat(some).contains("  title: 晴天")
        assertThat(some).contains("  artist: 周杰伦")
        assertThat(some).contains("  durationMs: 269000")
    }

    @Test
    fun `promptHash 透传自 PromptResources`() {
        assertThat(builder.promptHash()).isEqualTo(PromptResources().promptHash())
    }
}

/** 依次取 JsonObject 的键（缺任一环即抛；测试里只用于读骨架，不吞错）。 */
private fun JsonObject.at(vararg keys: String): JsonObject {
    var current: JsonObject = this
    for (key in keys) current = current.getValue(key).jsonObject
    return current
}
```

- [ ] **Step 2** 跑测试确认失败：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.prompt.PromptBuilderTest"
```

预期输出：`e: ... Unresolved reference: PromptBuilder` / `Unresolved reference: PromptBundle`，任务 `FAILED`。

- [ ] **Step 3** 最小实现 `core/llm/src/main/java/com/aimusic/player/llm/prompt/PromptBuilder.kt`：

```kotlin
package com.aimusic.player.llm.prompt

import com.aimusic.player.llm.TagRef
import com.aimusic.player.llm.NormalizeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** 一次请求的全部文本：system / user / schema（schema 的 enum 已注入当前分类）。 */
data class PromptBundle(
    val system: String,
    val user: String,
    val schema: JsonElement,
)

/**
 * 把一批请求渲染成 `PromptBundle`（`05 §4.4`）。
 *
 * 占位符用**最朴素的字符串替换**（`String.replace`，纯字面量、无正则、无模板引擎）：
 * `{{categories}}` / `{{tags}}` / `{{maxPerCategory}}` / `{{files}}`。
 * 目录在前、文件清单在后 —— 20 个文件共享一份目录，这是批量省 token 的地方（spec §6）。
 */
class PromptBuilder(
    private val resources: PromptResources = PromptResources(),
    /** 每分类标签数量上限的 n（`LlmConfig.maxTagsPerCategory`，不落在 NormalizeRequest 上）。 */
    private val maxTagsPerCategory: Int = 2,
) {

    fun promptHash(): String = resources.promptHash()

    fun build(reqs: List<NormalizeRequest>): PromptBundle {
        require(reqs.isNotEmpty()) { "空批次不应构建 prompt" }
        val categories = reqs.first().categories       // 每批实时读取一次（非快照，spec §10）
        val tags = reqs.first().tags

        val user = resources.user()
            .substitute("{{categories}}", renderCategories(categories))
            .substitute("{{tags}}", renderTags(tags))
            .substitute("{{maxPerCategory}}", renderLimits(categories))
            .substitute("{{files}}", renderFiles(reqs))

        val schema = Json.parseToJsonElement(
            resources.schemaJson().substitute("{{categories}}", categories.joinToString("\",\""))
        )

        return PromptBundle(resources.system(), user, schema)
    }

    private fun renderCategories(categories: List<String>): String =
        categories.joinToString("\n") { "- $it" }

    private fun renderTags(tags: List<TagRef>): String =
        tags.groupBy { it.category }
            .entries
            .joinToString("\n") { (category, group) ->
                "- $category: " + group.joinToString(", ") { it.name }
            }

    private fun renderLimits(categories: List<String>): String =
        categories.joinToString(", ") { "$it ≤ $maxTagsPerCategory" }

    private fun renderFiles(reqs: List<NormalizeRequest>): String = buildString {
        reqs.forEachIndexed { i, req ->
            if (i > 0) append("\n\n")
            append("【文件 ${i + 1}】\n")
            append("文件名: ${req.fileName}\n")
            val m = req.metadata
            if (m == null) {
                append("内嵌元数据: （无）")
            } else {
                append("内嵌元数据:\n")
                if (m.title != null) append("  title: ${m.title}\n")
                if (m.artist != null) append("  artist: ${m.artist}\n")
                if (m.album != null) append("  album: ${m.album}\n")
                if (m.albumArtist != null) append("  albumArtist: ${m.albumArtist}\n")
                if (m.date != null) append("  date: ${m.date}\n")
                append("  durationMs: ${m.durationMs}")
            }
        }
    }

    /** 朴素字面量替换：不做正则、不解析 `$`。 */
    private fun String.substitute(placeholder: String, value: String): String = replace(placeholder, value)
}
```

- [ ] **Step 4** 跑确认通过：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.prompt.PromptBuilderTest"
```

预期输出：`BUILD SUCCESSFUL`（6 个测试通过）。

- [ ] **Step 5** 提交：

```bash
git add core/llm/src
git commit -m "feat(llm): PromptBuilder 渲染占位符与 schema 分类 enum

四个占位符用 String.replace 朴素替换（不引模板引擎）；目录在前、文件清单在后，
20 个文件共享一份目录；schema 只在 tag_groups[].category.enum 注入当前分类。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 5: `DirectoryFingerprint`（目录指纹，供 P3 缓存 key）

算出「分类名 + 标签名的规范化串」的 sha256。原缓存 key 漏了这一项：用户新建分类后同一文件会命中旧缓存，返回的标签里永远缺该分类（spec §8）。

**Files:**
- Create: `core/llm/src/main/java/com/aimusic/player/llm/cache/DirectoryFingerprint.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/cache/DirectoryFingerprintTest.kt`

**Interfaces:**
- Consumes: `com.aimusic.player.common.text.TextNormalizer.normalizeToken`、`com.aimusic.player.llm.TagRef`、`sha256Hex`（Task 3）
- Produces: `object DirectoryFingerprint { fun of(categories: List<String>, tags: List<TagRef>): String }`

- [ ] **Step 1** 写失败测试 `core/llm/src/test/java/com/aimusic/player/llm/cache/DirectoryFingerprintTest.kt`：

```kotlin
package com.aimusic.player.llm.cache

import com.aimusic.player.llm.TagRef
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DirectoryFingerprintTest {

    private val categories = listOf("音乐类型", "情绪")
    private val tags = listOf(
        TagRef("流行", "音乐类型"),
        TagRef("ACG", "音乐类型"),
        TagRef("怀旧", "情绪"),
    )

    @Test
    fun `相同目录得到相同指纹`() {
        assertThat(DirectoryFingerprint.of(categories, tags))
            .isEqualTo(DirectoryFingerprint.of(categories, tags))
    }

    @Test
    fun `指纹是 64 位小写十六进制`() {
        assertThat(DirectoryFingerprint.of(categories, tags)).matches("[0-9a-f]{64}")
    }

    @Test
    fun `顺序不影响指纹`() {
        assertThat(DirectoryFingerprint.of(categories.reversed(), tags.reversed()))
            .isEqualTo(DirectoryFingerprint.of(categories, tags))
    }

    @Test
    fun `新增一个分类即改变指纹`() {
        assertThat(DirectoryFingerprint.of(categories + "主题", tags))
            .isNotEqualTo(DirectoryFingerprint.of(categories, tags))
    }

    @Test
    fun `新增一个标签即改变指纹`() {
        assertThat(DirectoryFingerprint.of(categories, tags + TagRef("摇滚", "音乐类型")))
            .isNotEqualTo(DirectoryFingerprint.of(categories, tags))
    }

    @Test
    fun `大小写与全半角按 TextNormalizer 归一到同一个指纹`() {
        val halfWidth = listOf(TagRef("acg", "音乐类型"))
        val fullWidth = listOf(TagRef("ＡＣＧ", "音乐类型"))
        assertThat(DirectoryFingerprint.of(categories, halfWidth))
            .isEqualTo(DirectoryFingerprint.of(categories, fullWidth))
    }
}
```

- [ ] **Step 2** 跑测试确认失败：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.cache.DirectoryFingerprintTest"
```

预期输出：`e: ... Unresolved reference: DirectoryFingerprint`，任务 `FAILED`。

- [ ] **Step 3** 最小实现 `core/llm/src/main/java/com/aimusic/player/llm/cache/DirectoryFingerprint.kt`：

```kotlin
package com.aimusic.player.llm.cache

import com.aimusic.player.llm.TagRef
import com.aimusic.player.common.text.TextNormalizer
import com.aimusic.player.llm.crypto.sha256Hex

/**
 * 目录指纹：`sha256(规范化分类名串 ␞ 规范化标签名串)`，参与缓存 key（spec §8）。
 *
 * 原 key **漏了**这一项：用户新建分类后，同一文件会命中旧缓存、返回的标签里永远缺这个新分类，
 * 且常量版 `PROMPT_VERSION` 救不了。规范化与排序都用 `TextNormalizer` + 字典序，
 * 保证「同一份目录的任意遍历顺序」得到同一个指纹（否则顺序抖动会白扔缓存）。
 */
object DirectoryFingerprint {

    /** 分类组与标签组之间的分隔（U+001E）。 */
    private const val GROUP_SEPARATOR = "\u001E"

    /** 组内条目之间的分隔（U+001F，与 `artistsKey` 同一约定）。 */
    private const val ITEM_SEPARATOR = "\u001F"

    fun of(categories: List<String>, tags: List<TagRef>): String {
        val categoryPart = categories
            .map(TextNormalizer::normalizeToken)
            .distinct()
            .sorted()
            .joinToString(ITEM_SEPARATOR)

        val tagPart = tags
            .map { TextNormalizer.normalizeToken(it.category) + ":" + TextNormalizer.normalizeToken(it.name) }
            .distinct()
            .sorted()
            .joinToString(ITEM_SEPARATOR)

        return sha256Hex(categoryPart + GROUP_SEPARATOR + tagPart)
    }
}
```

- [ ] **Step 4** 跑测试确认通过：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.cache.DirectoryFingerprintTest"
```

预期输出：`BUILD SUCCESSFUL`（6 个测试通过）。

- [ ] **Step 5** 提交：

```bash
git add core/llm/src
git commit -m "feat(llm): DirectoryFingerprint —— 目录指纹进缓存 key

sha256(规范化分类名 + 规范化标签名串)，排序去重后拼接，顺序无关。
补上原 key 漏掉的这一项：用户新建分类后不再命中旧缓存（spec §8）。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 6: 强制 JSON 三层降级的判定 + 容错抽取

落地 spec §4 的分层降级：`JsonEnforcementPolicy` 判定 L1/L2/L3（P1 的适配器调用它决定怎么声明）；`JsonSalvage` 是 L3 的容错抽取（剥 ``` 围栏、截首个 `{` 到末个 `}`）。

> **⚠ 本任务的类型归属已改**（P1-T3 执行时实地修正）：`JsonEnforcementPolicy` **类型本体由 P1 定义**（sealed interface：`Schema(schema)` / `JsonObject` / `PromptOnly`，见 `core/llm/src/main/java/com/aimusic/player/llm/JsonEnforcementPolicy.kt`，已跑绿）。本任务**只做推导**，不要再声明该类型，也不要引入 `JsonEnforcement`。下面步骤里的 `JsonEnforcementPolicy.of(...)` 一律改成顶层函数 `jsonEnforcementFor(supportsJsonSchema, protocolSupportsJsonMode): JsonEnforcementPolicy`，文件名改为 `parse/JsonEnforcementDerivation.kt`，断言对象相应变为 `JsonEnforcementPolicy.Schema/JsonObject/PromptOnly`。

**Files:**
- Create: `core/llm/src/main/java/com/aimusic/player/llm/parse/JsonEnforcementPolicy.kt`
- Create: `core/llm/src/main/java/com/aimusic/player/llm/parse/JsonSalvage.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/parse/JsonEnforcementPolicyTest.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/parse/JsonSalvageTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum class JsonEnforcement { JSON_SCHEMA, JSON_MODE, PROMPT_ONLY }`
  - `object JsonEnforcementPolicy { fun of(supportsJsonSchema: Boolean, protocolSupportsJsonMode: Boolean): JsonEnforcement }`
  - `object JsonSalvage { fun stripFences(raw: String): String; fun toJsonText(raw: String): String }`

- [ ] **Step 1** 写失败测试 `core/llm/src/test/java/com/aimusic/player/llm/parse/JsonEnforcementPolicyTest.kt`：

```kotlin
package com.aimusic.player.llm.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** spec §4 的分层降级表。 */
class JsonEnforcementPolicyTest {

    @Test
    fun `能声明 schema 就走 L1，能力标记优先`() {
        assertThat(JsonEnforcementPolicy.of(supportsJsonSchema = true, protocolSupportsJsonMode = false))
            .isEqualTo(JsonEnforcement.JSON_SCHEMA)
        assertThat(JsonEnforcementPolicy.of(supportsJsonSchema = true, protocolSupportsJsonMode = true))
            .isEqualTo(JsonEnforcement.JSON_SCHEMA)
    }

    @Test
    fun `不支持 schema 但协议有 JSON mode 走 L2（仅 openai 有此档）`() {
        assertThat(JsonEnforcementPolicy.of(supportsJsonSchema = false, protocolSupportsJsonMode = true))
            .isEqualTo(JsonEnforcement.JSON_MODE)
    }

    @Test
    fun `都没有则纯提示词 + 容错抽取 L3`() {
        assertThat(JsonEnforcementPolicy.of(supportsJsonSchema = false, protocolSupportsJsonMode = false))
            .isEqualTo(JsonEnforcement.PROMPT_ONLY)
    }
}
```

- [ ] **Step 2** 写失败测试 `core/llm/src/test/java/com/aimusic/player/llm/parse/JsonSalvageTest.kt`：

```kotlin
package com.aimusic.player.llm.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JsonSalvageTest {

    private val payload = """{"results":[{"file_index":1}]}"""

    @Test
    fun `裸 JSON 原样通过`() {
        assertThat(JsonSalvage.toJsonText(payload)).isEqualTo(payload)
    }

    @Test
    fun `剥掉 json 围栏`() {
        assertThat(JsonSalvage.toJsonText("```json\n$payload\n```")).isEqualTo(payload)
    }

    @Test
    fun `剥掉无语言标记的围栏`() {
        assertThat(JsonSalvage.toJsonText("```\n$payload\n```")).isEqualTo(payload)
    }

    @Test
    fun `截取首个左花括号到末个右花括号，丢掉前后解释文字`() {
        assertThat(JsonSalvage.toJsonText("好的，结果如下：\n$payload\n以上。")).isEqualTo(payload)
    }

    @Test
    fun `围栏与前后文同时存在也能救回`() {
        assertThat(JsonSalvage.toJsonText("  Sure!\n```json\n$payload\n```\nDone.")).isEqualTo(payload)
    }

    @Test
    fun `完全没有花括号时原样返回，交给上层判失败`() {
        assertThat(JsonSalvage.toJsonText("not json")).isEqualTo("not json")
    }
}
```

- [ ] **Step 3** 跑两个测试确认失败：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.parse.*"
```

预期输出：`e: ... Unresolved reference: JsonEnforcementPolicy` / `Unresolved reference: JsonSalvage`，任务 `FAILED`。

- [ ] **Step 4** 最小实现。`core/llm/src/main/java/com/aimusic/player/llm/parse/JsonEnforcementPolicy.kt`：

```kotlin
package com.aimusic.player.llm.parse

/** 强制模型返回 JSON 的档位（spec §4 表）。 */
enum class JsonEnforcement {
    /** L1：声明 schema，模型被约束在 schema 内。 */
    JSON_SCHEMA,

    /** L2：JSON mode（`response_format={"type":"json_object"}`，仅 openai 有此档）。 */
    JSON_MODE,

    /** L3：纯提示词约束 + parser 容错抽取。 */
    PROMPT_ONLY,
}

/**
 * 分层降级的判定（spec §4）。入参用布尔而非 `ProtocolKind`，避开与本层无关的方言枚举；
 * 由 P1 的适配器把 `ProtocolKind.OPENAI` 翻成 `protocolSupportsJsonMode = true`。
 */
object JsonEnforcementPolicy {
    fun of(supportsJsonSchema: Boolean, protocolSupportsJsonMode: Boolean): JsonEnforcement = when {
        supportsJsonSchema -> JsonEnforcement.JSON_SCHEMA
        protocolSupportsJsonMode -> JsonEnforcement.JSON_MODE
        else -> JsonEnforcement.PROMPT_ONLY
    }
}
```

`core/llm/src/main/java/com/aimusic/player/llm/parse/JsonSalvage.kt`：

```kotlin
package com.aimusic.player.llm.parse

/**
 * L3 的容错抽取（spec §4 表末行）：先剥 ``` 围栏，再截取首个 `{` 到末个 `}`。
 *
 * 它不负责"是不是合法 JSON"——那由 `NormalizeParser` 判定失败（整批 `INVALID_OUTPUT`）。
 */
object JsonSalvage {

    /** 开头 ``` 可选语言标记，结尾 ```，两侧空白一并吃掉。 */
    private val FENCE = Regex("^```[A-Za-z0-9_-]*\\s*|\\s*```$")

    /** 剥掉 ``` / ```json 围栏。 */
    fun stripFences(raw: String): String = raw.trim().replace(FENCE, "").trim()

    fun toJsonText(raw: String): String {
        val unfenced = stripFences(raw)
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        return if (start >= 0 && end > start) unfenced.substring(start, end + 1) else unfenced
    }
}
```

- [ ] **Step 5** 跑测试确认通过：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.parse.*"
```

预期输出：`BUILD SUCCESSFUL`（9 个测试通过）。

- [ ] **Step 6** 提交：

```bash
git add core/llm/src
git commit -m "feat(llm): 强制 JSON 分层降级判定 + L3 容错抽取

JsonEnforcementPolicy 按 spec §4 表判 L1/L2/L3（入参用布尔，P1 的适配器调用）；
JsonSalvage 实现 L3 的剥围栏 + 截首个 { 到末个 }。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 7: `NormalizeParser` 骨架 —— 整批失败、`file_index` 回填、条目级标题/歌手失败

入口签名 `parse(text, req)` **文档锁定、一字不改**（spec §3 硬约束 1）。本任务先落整批判定、索引回填与条目级失败；`tag_groups` 的摊平在 Task 8。

**Files:**
- Create: `core/llm/src/main/java/com/aimusic/player/llm/parse/NormalizeParser.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/parse/NormalizeParserTest.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/parse/RecordingLogger.kt`

**Interfaces:**
- Consumes: `JsonSalvage`（Task 6）、`NormalizeRequest` / `NormalizeOutcome` / `LlmFailureKind` / `MissingField` / `DuplicateIndex`（Task 2）、`NoopLogger`（Task 2）、`Logger`（`:core:common`）、`TextNormalizer`（`:core:common`）
- Produces:
  - `class NormalizeParser(maxTagsPerCategory: Int = 2, logger: Logger = NoopLogger)`
    - `fun parse(text: String, req: List<NormalizeRequest>): List<NormalizeOutcome>`（返回与入参等长、按索引对齐）
    - `NormalizeParser.RESULT_FIELDS: Set<String>`、`NormalizeParser.GROUP_FIELDS: Set<String>`（Task 9 防漂移用）

- [ ] **Step 1** 写测试用的 `RecordingLogger`（`core/llm/src/test/java/com/aimusic/player/llm/parse/RecordingLogger.kt`）：

```kotlin
package com.aimusic.player.llm.parse

import com.aimusic.player.common.error.AppError
import com.aimusic.player.common.log.LogEvent
import com.aimusic.player.common.log.LogLevel
import com.aimusic.player.common.log.LogRecord
import com.aimusic.player.common.log.Logger

/** 收集 warn 文案，供断言「dropped 但不算失败」的分支确实记了日志。 */
class RecordingLogger : Logger {
    val warnings = mutableListOf<String>()

    override fun isEnabled(level: LogLevel): Boolean = true
    override fun log(level: LogLevel, event: LogEvent, message: String, fields: Map<String, Any?>, error: AppError?) {
        if (level == LogLevel.WARN) warnings += message
    }
    override fun debug(event: LogEvent, message: String, fields: Map<String, Any?>) = Unit
    override fun info(event: LogEvent, message: String, fields: Map<String, Any?>) = Unit
    override fun warn(event: LogEvent, message: String, error: AppError?, fields: Map<String, Any?>) { warnings += message }
    override fun error(event: LogEvent, message: String, error: AppError?, fields: Map<String, Any?>) = Unit
    override fun recent(limit: Int): List<LogRecord> = emptyList()
}
```

- [ ] **Step 2** 写失败测试 `core/llm/src/test/java/com/aimusic/player/llm/parse/NormalizeParserTest.kt`（本任务覆盖整批/回填/条目级；`tag_groups` 相关断言留到 Task 8 追加）：

```kotlin
package com.aimusic.player.llm.parse

import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.llm.LlmFailureKind
import com.aimusic.player.llm.NormalizeOutcome
import com.aimusic.player.llm.NormalizeRequest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NormalizeParserTest {

    private val categories = listOf("音乐类型", "情绪", "场景", "主题")
    private val logger = RecordingLogger()
    private val parser = NormalizeParser(maxTagsPerCategory = 2, logger = logger)

    private fun request(fileName: String) =
        NormalizeRequest(fileName, metadata = null, categories = categories, tags = emptyList())

    private fun succeed(outcome: NormalizeOutcome): NormalizeResult {
        assertThat(outcome).isInstanceOf(NormalizeOutcome.Success::class.java)
        return (outcome as NormalizeOutcome.Success).result
    }

    private fun kindOf(outcome: NormalizeOutcome): LlmFailureKind {
        assertThat(outcome).isInstanceOf(NormalizeOutcome.Failure::class.java)
        return (outcome as NormalizeOutcome.Failure).kind
    }

    private fun item(index: Int, title: String = "标题$index") =
        """{"file_index":$index,"canonical_title":"$title","artists":["歌手$index"],"tag_groups":[]}"""

    @Test
    fun `乱序返回按 file_index 对回文件（不用文件名做键）`() {
        val reqs = listOf(request("1.flac"), request("2.flac"), request("3.flac"))
        val json = """{"results":[${item(3, "三号")},${item(1, "一号")},${item(2, "二号")}]}"""

        val out = parser.parse(json, reqs)

        assertThat(out).hasSize(3)
        assertThat(succeed(out[0]).canonicalTitle).isEqualTo("一号")
        assertThat(succeed(out[1]).canonicalTitle).isEqualTo("二号")
        assertThat(succeed(out[2]).canonicalTitle).isEqualTo("三号")
    }

    @Test
    fun `整批不是 JSON → 整批 INVALID_OUTPUT`() {
        val reqs = listOf(request("a.flac"), request("b.flac"))
        val out = parser.parse("not json", reqs)
        assertThat(out).hasSize(2)
        assertThat(out.map(::kindOf)).containsExactly(LlmFailureKind.INVALID_OUTPUT, LlmFailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `缺 results → 整批 INVALID_OUTPUT`() {
        val out = parser.parse("""{"foo":1}""", listOf(request("a.flac")))
        assertThat(kindOf(out.single())).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `results 非数组 → 整批 INVALID_OUTPUT`() {
        val out = parser.parse("""{"results":{}}""", listOf(request("a.flac")))
        assertThat(kindOf(out.single())).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `模型少回一项 → 该文件失败，其余照常`() {
        val reqs = listOf(request("1.flac"), request("2.flac"), request("3.flac"))
        val json = """{"results":[${item(1)},${item(3)}]}"""

        val out = parser.parse(json, reqs)

        assertThat(succeed(out[0]).canonicalTitle).isEqualTo("标题1")
        assertThat(kindOf(out[1])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
        assertThat(succeed(out[2]).canonicalTitle).isEqualTo("标题3")
        assertThat(logger.warnings.any { it.contains("长度") }).isTrue()
    }

    @Test
    fun `重复 file_index → 该文件失败，其余照常`() {
        val reqs = listOf(request("1.flac"), request("2.flac"), request("3.flac"))
        val json = """{"results":[${item(1)},${item(2, "甲")},${item(2, "乙")},${item(3)}]}"""

        val out = parser.parse(json, reqs)

        assertThat(succeed(out[0]).canonicalTitle).isEqualTo("标题1")
        assertThat(kindOf(out[1])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
        assertThat(succeed(out[2]).canonicalTitle).isEqualTo("标题3")
    }

    @Test
    fun `越界 file_index → 忽略该条，不算失败、不影响其余`() {
        val reqs = listOf(request("1.flac"), request("2.flac"))
        val json = """{"results":[${item(1)},${item(2)},${item(99)}]}"""

        val out = parser.parse(json, reqs)

        assertThat(out).hasSize(2)
        assertThat(out[0]).isInstanceOf(NormalizeOutcome.Success::class.java)
        assertThat(out[1]).isInstanceOf(NormalizeOutcome.Success::class.java)
        assertThat(logger.warnings.any { it.contains("越界") }).isTrue()
    }

    @Test
    fun `缺 file_index 的条目被忽略，对应文件判失败`() {
        val reqs = listOf(request("1.flac"), request("2.flac"))
        val json = """{"results":[{"canonical_title":"无索引","artists":["x"],"tag_groups":[]},${item(1)}]}"""

        val out = parser.parse(json, reqs)

        assertThat(succeed(out[0]).canonicalTitle).isEqualTo("标题1")
        assertThat(kindOf(out[1])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `标题空 → 只该文件失败，其余照常`() {
        val reqs = listOf(request("1.flac"), request("2.flac"))
        val json = """{"results":[${item(1, "")},${item(2)}]}"""

        val out = parser.parse(json, reqs)

        assertThat(kindOf(out[0])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
        assertThat(succeed(out[1]).canonicalTitle).isEqualTo("标题2")
    }

    @Test
    fun `artists 缺失或空数组 → 该文件失败`() {
        val reqs = listOf(request("1.flac"), request("2.flac"))
        val json = """{"results":[
            {"file_index":1,"canonical_title":"甲","artists":[],"tag_groups":[]},
            {"file_index":2,"canonical_title":"乙","tag_groups":[]}]}"""

        val out = parser.parse(json, reqs)

        assertThat(kindOf(out[0])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
        assertThat(kindOf(out[1])).isEqualTo(LlmFailureKind.INVALID_OUTPUT)
    }

    @Test
    fun `artists 按分隔符拆分、归一、去重且不排序`() {
        val json = """{"results":[{"file_index":1,"canonical_title":"小酒窝","artists":["林俊杰 & 蔡卓妍","林俊杰"],"tag_groups":[]}]}"""
        val result = succeed(parser.parse(json, listOf(request("a.flac"))).single())
        assertThat(result.artists).containsExactly("林俊杰", "蔡卓妍").inOrder()
    }

    @Test
    fun `围栏与前后文包裹时仍能解析`() {
        val reqs = listOf(request("1.flac"))
        val fenced = "```json\n{\"results\":[${item(1, "围栏")}]}\n```"
        val prose = "好的：\n{\"results\":[${item(1, "散文")}]}\n以上。"

        assertThat(succeed(parser.parse(fenced, reqs).single()).canonicalTitle).isEqualTo("围栏")
        assertThat(succeed(parser.parse(prose, reqs).single()).canonicalTitle).isEqualTo("散文")
    }

    @Test
    fun `tag_groups 缺失视为合法，得到空标签`() {
        val json = """{"results":[{"file_index":1,"canonical_title":"甲","artists":["x"]}]}"""
        assertThat(succeed(parser.parse(json, listOf(request("a.flac"))).single()).tagAssignments).isEmpty()
    }
}
```

> 注：本任务只让整批判定、`file_index` 回填与条目级标题/歌手失败生效；`tag_groups` 在 Task 8 之前一律返回空（`tag_groups 缺失视为合法` 那条此时仍为绿）。

- [ ] **Step 3** 跑测试确认失败：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.parse.NormalizeParserTest"
```

预期输出：`e: ... Unresolved reference: NormalizeParser`，任务 `FAILED`。

- [ ] **Step 4** 最小实现 `core/llm/src/main/java/com/aimusic/player/llm/parse/NormalizeParser.kt`：

```kotlin
package com.aimusic.player.llm.parse

import com.aimusic.player.common.log.LogEvent
import com.aimusic.player.common.log.Logger
import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.model.TagAssignment
import com.aimusic.player.common.text.TextNormalizer
import com.aimusic.player.llm.DuplicateIndex
import com.aimusic.player.llm.LlmFailureKind
import com.aimusic.player.llm.MissingField
import com.aimusic.player.llm.NormalizeOutcome
import com.aimusic.player.llm.NormalizeRequest
import com.aimusic.player.llm.log.NoopLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 解析整批响应、按 `file_index` 对回入参，并把 `tag_groups` 摊平成 `TagAssignment`（`05 §4.5`）。
 *
 * 入口签名 `parse(text, req)` **文档锁定**（spec §3 硬约束 1）：`text` 是适配器还原后的
 * JSON 字符串（ANTHROPIC 的 `tool_use.input` 由适配器 `Json.encodeToString` 还原），
 * `req` 是整批请求，返回与入参等长、按索引对齐。
 *
 * 失败分两级（spec §9.2）：整批不可解析 → 整批 `INVALID_OUTPUT`；条目级失败只连坐该文件。
 */
class NormalizeParser(
    /** 每分类标签数量上限 `n`（`LlmConfig.maxTagsPerCategory`，不在 `NormalizeRequest` 上）。 */
    private val maxTagsPerCategory: Int = 2,
    private val logger: Logger = NoopLogger,
) {

    fun parse(text: String, req: List<NormalizeRequest>): List<NormalizeOutcome> {
        val root = runCatching { Json.parseToJsonElement(JsonSalvage.toJsonText(text)).jsonObject }
            .getOrNull()
            ?: return allFailure(req.size, LlmFailureKind.INVALID_OUTPUT)

        // results 必须是数组（缺 / 非数组 → 整批失败）
        val items = root["results"] as? JsonArray
            ?: return allFailure(req.size, LlmFailureKind.INVALID_OUTPUT)

        if (items.size != req.size) {
            warn("results 长度 ${items.size} != 输入 ${req.size}")
        }

        // 按 file_index 对回（1 起、与 prompt 中【文件 N】一致）；不用文件名做键
        val byIndex = HashMap<Int, JsonObject>()
        val duplicateIndices = mutableSetOf<Int>()
        for (el in items) {
            val obj = el as? JsonObject
            if (obj == null) {
                warn("drop result: 元素不是 JSON 对象")
                continue
            }
            val raw = (obj["file_index"] as? JsonPrimitive)?.content
            val index = raw?.toIntOrNull()
            when {
                index == null -> warn("drop result: 缺 file_index（raw=$raw）")
                index < 1 || index > req.size -> warn("drop result: file_index=$index 越界，忽略该条")
                byIndex.containsKey(index) -> {
                    duplicateIndices += index
                    warn("drop result: file_index=$index 重复")
                }
                else -> byIndex[index] = obj
            }
        }

        return req.mapIndexed { i, reqOne ->
            val key = i + 1
            when {
                key in duplicateIndices ->
                    NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, DuplicateIndex(key))
                else ->
                    byIndex[key]?.let { parseOne(it, reqOne) }
                        ?: NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, MissingField("results[$key]"))
            }
        }
    }

    private fun warn(message: String) = logger.warn(LogEvent.LLM_REQUEST, message)

    companion object {
        /** parser 认得的**条目**字段（Task 9 防漂移测试与 `schema.json` 的 `required` 对齐，spec §5）。 */
        val RESULT_FIELDS = setOf("file_index", "canonical_title", "artists", "tag_groups")

        /** parser 认得的**标签组**字段（同上）。 */
        val GROUP_FIELDS = setOf("category", "tags")

        /** 整批失败：列表内每一项同值。 */
        private fun allFailure(size: Int, kind: LlmFailureKind): List<NormalizeOutcome> =
            List(size) { NormalizeOutcome.Failure(kind, null) }
    }

    // parseOne 在 Task 8 补齐 tag_groups 摊平；本任务先让标题 / 歌手两条判定生效。
    private fun parseOne(obj: JsonObject, req: NormalizeRequest): NormalizeOutcome {
        val rawTitle = (obj["canonical_title"] as? JsonPrimitive)?.content
        if (rawTitle.isNullOrBlank()) {
            return NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, MissingField("canonical_title"))
        }
        val title = TextNormalizer.normalizeTitle(rawTitle)

        val rawArtists = (obj["artists"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()
        val artists = rawArtists
            .flatMap(TextNormalizer::splitArtists)   // 兜底拆分 feat. / & / / / 、
            .filter { it.isNotBlank() }
            .distinct()                              // 去重；不排序（排序在 entityKey 计算时做）
        if (artists.isEmpty()) {
            return NormalizeOutcome.Failure(LlmFailureKind.INVALID_OUTPUT, MissingField("artists"))
        }

        return NormalizeOutcome.Success(NormalizeResult(title, artists, emptyListOf<TagAssignment>()))
    }
}
```

- [ ] **Step 5** 跑测试确认通过：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.parse.NormalizeParserTest"
```

预期输出：`BUILD SUCCESSFUL`（13 个测试通过）。

- [ ] **Step 6** 提交：

```bash
git add core/llm/src
git commit -m "feat(llm): NormalizeParser 骨架 —— 整批失败、file_index 回填、条目级失败

parse(text, req) 签名照 spec §3 锁定不动；乱序按 file_index 对回；
整批不可解析/缺 results/非数组 → 整批 INVALID_OUTPUT；
缺项、重复、标题空、artists 空 → 只连坐该文件；越界/缺索引 → 忽略该条 + warn。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 8: `NormalizeParser` —— `tag_groups` 摊平、无效分类整组丢弃、组内上限

把分组结构摊平成扁平的 `TagAssignment`（`NormalizeResult.tagAssignments` 形状不动，spec §7），逐组判分类、组内逐条判名字，并按 `maxTagsPerCategory` 截断。

**Files:**
- Modify: `core/llm/src/main/java/com/aimusic/player/llm/parse/NormalizeParser.kt`（补 `parseOne` 的 tag_groups 段）
- Modify: `core/llm/src/test/java/com/aimusic/player/llm/parse/NormalizeParserTest.kt`（追加测试）

**Interfaces:**
- Consumes: 同 Task 7，另有 `com.aimusic.player.common.model.TagAssignment`
- Produces: 无新公开签名（`NormalizeParser.parse` 行为补全）

- [ ] **Step 1** 追加失败测试到 `NormalizeParserTest.kt`（加在类内）：

```kotlin
    @Test
    fun `分组结构摊平成多条 TagAssignment`() {
        val json = """{"results":[{"file_index":1,"canonical_title":"晴天","artists":["周杰伦"],
            "tag_groups":[{"category":"音乐类型","tags":["流行","摇滚"]},{"category":"情绪","tags":["怀旧"]}]}]}"""

        val result = succeed(parser.parse(json, listOf(request("a.flac"))).single())

        assertThat(result.tagAssignments).containsExactly(
            TagAssignment("音乐类型", "流行"),
            TagAssignment("音乐类型", "摇滚"),
            TagAssignment("情绪", "怀旧"),
        ).inOrder()
    }

    @Test
    fun `无效分类整组丢弃，不算失败`() {
        val json = """{"results":[{"file_index":1,"canonical_title":"甲","artists":["x"],
            "tag_groups":[{"category":"自造分类","tags":["野标签"]},{"category":"情绪","tags":["怀旧"]}]}]}"""

        val result = succeed(parser.parse(json, listOf(request("a.flac"))).single())

        assertThat(result.tagAssignments).containsExactly(TagAssignment("情绪", "怀旧"))
        assertThat(logger.warnings.any { it.contains("自造分类") }).isTrue()
    }

    @Test
    fun `标签名为空丢该条，其余照常`() {
        val json = """{"results":[{"file_index":1,"canonical_title":"甲","artists":["x"],
            "tag_groups":[{"category":"情绪","tags":["怀旧","","   "]}]}]}"""

        val result = succeed(parser.parse(json, listOf(request("a.flac"))).single())

        assertThat(result.tagAssignments).containsExactly(TagAssignment("情绪", "怀旧"))
    }

    @Test
    fun `同分类超上限按 take 截断`() {
        val json = """{"results":[{"file_index":1,"canonical_title":"甲","artists":["x"],
            "tag_groups":[{"category":"音乐类型","tags":["流行","摇滚","电子","古典"]}]}]}"""

        val result = succeed(parser.parse(json, listOf(request("a.flac"))).single())

        assertThat(result.tagAssignments).containsExactly(
            TagAssignment("音乐类型", "流行"),
            TagAssignment("音乐类型", "摇滚"),
        ).inOrder()
    }

    @Test
    fun `组内重复标签去重后再截断`() {
        val json = """{"results":[{"file_index":1,"canonical_title":"甲","artists":["x"],
            "tag_groups":[{"category":"音乐类型","tags":["流行","流行","摇滚","电子"]}]}]}"""

        val result = succeed(parser.parse(json, listOf(request("a.flac"))).single())

        assertThat(result.tagAssignments).containsExactly(
            TagAssignment("音乐类型", "流行"),
            TagAssignment("音乐类型", "摇滚"),
        ).inOrder()
    }

    @Test
    fun `tag_groups 为空数组是合法的（0 标签）`() {
        val json = """{"results":[${item(1)}]}"""
        val result = succeed(parser.parse(json, listOf(request("a.flac"))).single())
        assertThat(result.tagAssignments).isEmpty()
    }
```

并在文件头部 import 追加：`import com.aimusic.player.common.model.TagAssignment`。

- [ ] **Step 2** 跑测试确认失败：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.parse.NormalizeParserTest"
```

预期输出：断言失败 —— `分组结构摊平成多条 TagAssignment` 报 `expected ... but was []; `（`parseOne` 目前返回 `emptyList`），任务 `FAILED`。

- [ ] **Step 3** 最小实现：把 `NormalizeParser.parseOne` 的 `return NormalizeOutcome.Success(...)` 之前插入 tag_groups 段，改为：

```kotlin
        // tag_groups：逐组判分类 → 组内逐条判名字；摊平成 TagAssignment（spec §7）
        val validCategories = req.categories.toSet()
        val assignments = mutableListOf<TagAssignment>()
        for (group in (obj["tag_groups"] as? JsonArray).orEmpty()) {
            val groupObject = group as? JsonObject
            if (groupObject == null) {
                warn("drop tag group: 元素不是 JSON 对象")
                continue
            }
            val category = (groupObject["category"] as? JsonPrimitive)?.content?.trim()
            if (category == null || category !in validCategories) {
                warn("drop tag group: category '$category' 不在当前有效分类")   // 整组丢弃
                continue
            }
            for (tag in (groupObject["tags"] as? JsonArray).orEmpty()) {
                val name = (tag as? JsonPrimitive)?.content?.trim()
                if (name.isNullOrEmpty()) {
                    warn("drop tag: 名字为空")                                  // 丢该条
                    continue
                }
                assignments += TagAssignment(category, name)
            }
        }

        // 每分类数量上限兜底（prompt 已约束，此处防御）：组内去重后 take(n)
        val capped = assignments
            .groupBy { it.category }
            .flatMap { (_, list) -> list.distinctBy { it.name }.take(maxTagsPerCategory) }

        return NormalizeOutcome.Success(NormalizeResult(title, artists, capped))
```

（同时删掉原来的 `return NormalizeOutcome.Success(NormalizeResult(title, artists, emptyListOf<TagAssignment>()))` 与其上的 `TagAssignment` 未用 import 调整。）

- [ ] **Step 4** 跑全模块单测确认通过：

```
./gradlew :core:llm:testDebugUnitTest
```

预期输出：`BUILD SUCCESSFUL`（全部测试通过，`NormalizeParserTest` 19 个）。

- [ ] **Step 5** 提交：

```bash
git add core/llm/src
git commit -m "feat(llm): NormalizeParser 摊平 tag_groups 并做组内过滤与上限

逐组判分类（无效分类整组丢弃 + warn）、组内逐条判名字（空名丢该条）、
按 maxTagsPerCategory 去重后 take(n)；NormalizeResult.tagAssignments 形状不动。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

### Task 9: 防漂移测试 —— `schema.json` 的 `required` == parser 认识的字段集合

spec §5 / `05 §4.4.3`：`schema.json` 的 `required` 字段集合必须等于 `NormalizeParser` 认识的字段集合，对不上即红。这是"改 schema 却忘了改 parser（或反之）"的唯一护栏。

**Files:**
- Test: `core/llm/src/test/java/com/aimusic/player/llm/parse/SchemaParserDriftTest.kt`

**Interfaces:**
- Consumes: `PromptBuilder.build`（Task 4）产生的 schema、`NormalizeParser.RESULT_FIELDS` / `GROUP_FIELDS`（Task 7）
- Produces: 无（测试）

- [ ] **Step 1** 写测试 `core/llm/src/test/java/com/aimusic/player/llm/parse/SchemaParserDriftTest.kt`：

```kotlin
package com.aimusic.player.llm.parse

import com.aimusic.player.llm.NormalizeRequest
import com.aimusic.player.llm.prompt.PromptBuilder
import com.aimusic.player.llm.prompt.PromptResources
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * 防漂移（spec §5 / `05 §4.4.3`）：schema.json 的 `required` 字段集合 == parser 认识的字段集合。
 *
 * 这个测试**故意**读的是真实资源文件而不是内联字符串：改了 schema.json 的 required 却不改
 * parser 常量（或缺反向），它就变红 —— 阻止"发给模型的契约"和"解析器的判定"各走各的。
 */
class SchemaParserDriftTest {

    private val request = NormalizeRequest(
        fileName = "a.flac",
        metadata = null,
        categories = listOf("音乐类型", "情绪"),
        tags = emptyList(),
    )

    private val schema: JsonObject =
        PromptBuilder(PromptResources()).build(listOf(request)).schema.jsonObject

    private fun JsonObject.at(vararg keys: String): JsonObject {
        var current: JsonObject = this
        for (key in keys) current = current.getValue(key).jsonObject
        return current
    }

    private fun JsonObject.requiredSet(): Set<String> =
        getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()

    @Test
    fun `results 条目的 required 集合等于 parser 的 RESULT_FIELDS`() {
        val item = schema.at("properties", "results", "items")
        assertThat(item.requiredSet()).isEqualTo(NormalizeParser.RESULT_FIELDS)
    }

    @Test
    fun `tag_groups 条目的 required 集合等于 parser 的 GROUP_FIELDS`() {
        val groupItem = schema.at("properties", "results", "items", "properties", "tag_groups", "items")
        assertThat(groupItem.requiredSet()).isEqualTo(NormalizeParser.GROUP_FIELDS)
    }

    @Test
    fun `schema 的顶层 required 只有 results`() {
        assertThat(schema.requiredSet()).containsExactly("results")
    }

    @Test
    fun `枚举注入的是当前批次分类，且 parser 逐字比对分类名`() {
        val categoryEnum = schema
            .at("properties", "results", "items", "properties", "tag_groups", "items", "properties", "category")
            .getValue("enum").jsonArray
        assertThat(categoryEnum.map { it.jsonPrimitive.content })
            .containsExactly("音乐类型", "情绪")
    }
}
```

- [ ] **Step 2** 跑测试确认它现在是"绿"的（跨模块联动是真实成立）：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.parse.SchemaParserDriftTest"
```

预期输出：`BUILD SUCCESSFUL`（4 个测试通过）。

- [ ] **Step 3** 手动验证护栏真的会红（一次性，不要提交）：把 `core/llm/src/main/resources/prompt/schema.json` 里 `results.items.required` 的 `"artists"` 删掉，再跑：

```
./gradlew :core:llm:testDebugUnitTest --tests "com.aimusic.player.llm.parse.SchemaParserDriftTest"
```

预期输出：`results 条目的 required 集合等于 parser 的 RESULT_FIELDS` 失败（`expected [file_index, canonical_title, artists, tag_groups] but was [file_index, canonical_title, tag_groups]`）。随后 `git checkout core/llm/src/main/resources/prompt/schema.json` 还原。

- [ ] **Step 4** 跑全模块单测确认全绿：

```
./gradlew :core:llm:testDebugUnitTest
```

预期输出：`BUILD SUCCESSFUL`。

- [ ] **Step 5** 提交：

```bash
git add core/llm/src/test
git commit -m "test(llm): schema.json 与 parser 字段集合的防漂移护栏

读真实资源文件，断言 results/tag_groups 的 required == parser 认识字段（spec §5 / 05 §4.4.3）。
改了 schema 却不改 parser（或反向）即红。

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>"
```

---

## 自检：spec 覆盖对照

| spec / 05 条目 | 由哪个 Task 覆盖 | 证据 |
| --- | --- | --- |
| spec §5「prompt 与 schema 外置」+ `05 §4.4.1/§4.4.2/§4.4.3` | **Task 3** | 三个资源文件 + `PromptResources`（classpath 读，无 Robolectric） |
| spec §5「防漂移测试」+ `05 §8`「schema 防漂移」 | **Task 9** | `SchemaParserDriftTest`：`required` 集合 == `NormalizeParser.RESULT_FIELDS` / `GROUP_FIELDS` |
| `05 §8`「prompt 资源可读」（JVM 单测读三个资源并渲染占位符） | **Task 3 + Task 4** | `PromptResourcesTest` + `PromptBuilderTest`（无 `{{` 残留） |
| spec §5「占位符朴素替换，不引模板引擎」 | **Task 4** | `PromptBuilder.substitute` = `String.replace` |
| spec §7「`tag_groups` 分组结构 + 摊平成扁平 `TagAssignment`」 | **Task 3**（schema.json 骨架）+ **Task 8**（parser 摊平） | `分组结构摊平成多条 TagAssignment` |
| `05 §8`「分组结构解析」（一组多标签 → 多条 `TagAssignment`） | **Task 8** | 同上 + `无效分类整组丢弃` |
| spec §4「强制 JSON 分层强制 + 降级」（L1/L2/L3） | **Task 6**（判定）+ **Task 7**（L3 容错抽取） | `JsonEnforcementPolicyTest` + `JsonSalvageTest` + `围栏与前后文包裹时仍能解析` |
| spec §6「响应按 `file_index` 对回、不用文件名做键」+ §13「file_index 回填」 | **Task 7** | `乱序返回按 file_index 对回文件` |
| spec §6「目录在前、文件清单在后；20 个文件共享一份目录」 | **Task 4** | `目录在前、文件清单在后` + `二十个文件共享一份目录` |
| spec §9.2 整批级（不可解析 / 缺 `results` / 非数组 → 整批 `INVALID_OUTPUT`）+ `05 §8`「畸形 JSON」「整批不可解析」+ G6 | **Task 7** | `整批不是 JSON…` / `缺 results…` / `results 非数组…` |
| spec §9.2 条目级（缺 `file_index` / 重复 / 越界 / 标题空 / artists 空 → 只连坐该文件）+ G5/G8/G16/G17 + `05 §8`「条目级失败隔离」 | **Task 7** | `模型少回一项` / `重复 file_index` / `越界 file_index` / `缺 file_index` / `标题空` / `artists 缺失或空数组` |
| spec §9.2「无效分类整组丢弃」「标签名空丢该条」「超上限 `take(n)`」「`tag_groups` 缺失合法」+ `05 §8`「标签过滤」「0 标签」 | **Task 8** | 对应 6 条测试 |
| spec §8「缓存 key 含 prompt 文件哈希」（取代 `PROMPT_VERSION`） | **Task 3**（产出 `promptHash()`）→ 由 **P3** 组进 key | `promptHash 等于 …三段内容按 U+001F 拼接后的 sha256` |
| spec §8「缓存 key 含目录指纹」+ G9/G10 | **Task 5**（产出 `DirectoryFingerprint.of`）→ 由 **P3** 组进 key | `新增一个分类即改变指纹` |
| 依赖守卫不破（spec §2） | **Task 1**（把 `AudioMetadata` 迁到 `:core:common`，不为 LLM 层放宽 `:core:llm`） | Task 1 Step 5 的 `./gradlew` 调用触发配置期守卫 |

**未覆盖 / 由其他计划负责**：协议三适配器的编码解码与 `529`（P1）；缓存装饰链与部分命中（G9–G11 的实际网络断言，P3）；`RetryingLlmNormalizer`（P3/独立）；`AnalysisOrchestrator` 的批量状态机与 `entityKey`（编排计划）。

## 已拍板事项（原「需要拍板的两处」，六条决定已覆盖，无需再决策）

1. **`AudioMetadata` 的归属** —— **已定（决定 #1）**：迁入 `:core:common` 的 `com.aimusic.player.common.model`，Task 1 照此落地。`05 §3.1` / `02 §5.3` 锁定 `NormalizeRequest.metadata: AudioMetadata?`，而 spec §2 只允许 `:core:llm → :core:common`，故把纯数据类迁到 `:core:common`（与 `NormalizeResult` 同一先例）；`MetadataReader`（带行为）留在 `:core:storage`。**不再考虑**「给 `:core:llm` 放宽到 `:core:storage`」的备选。
2. **`TagRef` 的形状** —— **已定（决定 #3）**：两个类型区分开。`:core:llm` 契约的 `TagRef` = **两字段** `(name, category)`，由本计划 Task 2 在 `com.aimusic.player.llm.TagRef` 定义（`PromptBuilder.renderTags` 用 `category` 分组，`DirectoryFingerprint` 用 `category`）；`:core:common` 里原来那个三字段 `(name, categoryId, categoryName)`（`NormalizeResult.kt`）**改名 `TagProjection`**（`:core:data` 的列表投影继续用它），**不与 `TagRef` 混用**。
