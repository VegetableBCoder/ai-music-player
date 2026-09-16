# Phase 4-1 · LLM 协议层 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建起 `:core:llm` 模块，把「三种 LLM 协议（openai / responses / anthropic）怎么在线上走」这件事收敛成一份内部模型 + 三套适配器。

**Architecture:** 一份协议无关的中间表示（`LlmCall` / `LlmHttpResult` / `HttpRequestSpec`），三个方言各出一个 `ProtocolAdapter` 负责编解码；重试、缓存、解析、JSON 强制都在协议之上，对协议无知。适配器只做纯函数式的「请求对象 ↔ JSON 字符串」，**不碰网络**——真正的 HTTP 由 `DirectProvider` 拿着 `HttpRequestSpec` 用 OkHttp 发出去。这样编解码可以在 JVM 上纯单测，不需要 MockWebServer。

**Tech Stack:** Kotlin 2.x / Java 17、kotlinx.serialization（JSON）、OkHttp（仅 `DirectProvider` 用）、JUnit + Truth + MockWebServer（测试）

**Spec:** docs/superpowers/specs/2026-09-15-phase4-llm-protocol-design.md

## Global Constraints

- **依赖守卫**：`:core:llm` 只能依赖 `:core:common`（禁止依赖 `:core:data`）；`:core:data` → `:core:storage` / `:core:common` / `:core:llm`；feature 模块之间禁止互相依赖。
- Kotlin / Java 17；compileSdk 36；minSdk 26；**coroutines 显式钉住 1.11.0**（Room 会传递旧版，踩过 `NoSuchMethodError: runBlockingK$default`）。
- KSP + AGP 9 需要 `android.disallowKotlinSourceSets=false`（已在 `gradle.properties`）。
- 注释与提交信息用**中文**；提交信息末尾固定带 `Co-authored-by: CommandCodeBot <noreply@commandcode.ai>`。
- **不用通用 `Result` 包装**：按操作定义 sealed 结果类型。
- androidTest 里的反引号函数名**不能含空格**（minSdk 26 → DEX 039），中文可以。本计划的测试全是 JVM，不受此限，但保持同样的命名习惯。
- 真机测试前先唤醒设备：`adb shell input keyevent KEYCODE_WAKEUP`。
- **`apiKey` 绝不进日志**：任何打印 `LlmConfig` 或请求头的地方都必须走 `Redactor.apiKey`。
- **本计划不含 `AnalysisProgress` / `LlmFailureKind` 的映射消费者**：那是 P4 的事。P1 只把类型与映射函数**定义出来**并单测。

---

### Task 1: `:core:llm` 模块骨架与依赖

**Files:**
- Modify: `settings.gradle.kts`（若 `:core:llm` 尚未 include）
- Modify: `gradle/libs.versions.toml`
- Modify: `core/llm/build.gradle.kts`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/ModuleSmokeTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: 一个能跑 JVM 单测的 `:core:llm` 模块；版本目录里 `okhttp` / `mockwebserver` / `kotlinx-serialization-json` 三个条目

- [ ] **Step 1: 确认模块已登记**

Run: `./gradlew projects --console=plain | grep core:llm`
Expected: 输出里出现 `Project ':core:llm'`。若没有，在 `settings.gradle.kts` 的 core 模块区块补一行 `include(":core:llm")`。

- [ ] **Step 2: 版本目录补三个条目**

在 `gradle/libs.versions.toml` 的 `[versions]` 与 `[libraries]` 中补（版本按仓库现有口径取，若已有同名条目就复用，不要重复定义）：

```toml
[versions]
okhttp = "4.12.0"
kotlinxSerialization = "1.7.3"

[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
```

- [ ] **Step 3: 写模块构建文件**

`core/llm/build.gradle.kts`（照 `core/common/build.gradle.kts` 的口径：Android library + 单一 `:core:testing` 测试基座）：

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aimusic.player.llm"
    compileSdk = 36

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 守卫只允许 :core:common（禁止依赖 :core:data / :core:storage）
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(project(":core:testing"))
    testImplementation(libs.okhttp.mockwebserver)
}
```

- [ ] **Step 4: 写一个冒烟测试**

`core/llm/src/test/java/com/aimusic/player/llm/ModuleSmokeTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ModuleSmokeTest {

    @Test
    fun `模块可编译且能读到 kotlinx.serialization 与 core:common`() {
        val json = Json.parseToJsonElement("""{"a":1}""")
        assertThat(json.toString()).isEqualTo("""{"a":1}""")
        // :core:common 能读到（用它的脱敏工具做记号）
        assertThat(com.aimusic.player.common.log.Redactor.apiKey("sk-1234567890abcdef")).contains("sk-")
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :core:llm:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`，1 个测试通过。若报依赖解析失败（代理不稳时见过），重跑一次即可。

- [ ] **Step 6: 提交**

```bash
git add settings.gradle.kts gradle/libs.versions.toml core/llm/build.gradle.kts core/llm/src/test
git commit -F - <<'EOF'
feat(llm): Phase 4-1 建 :core:llm 模块骨架与依赖

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>
EOF
```

---

### Task 2: 契约类型（含 `LlmFailureKind` 映射）

**Files:**
- Create: `core/llm/src/main/java/com/aimusic/player/llm/ProtocolKind.kt`
- Create: `core/llm/src/main/java/com/aimusic/player/llm/LlmConfig.kt`
- Create: `core/llm/src/main/java/com/aimusic/player/llm/LlmCall.kt`
- Create: `core/llm/src/main/java/com/aimusic/player/llm/LlmHttpResult.kt`
- Create: `core/llm/src/main/java/com/aimusic/player/llm/ProtocolAdapter.kt`
- Create: `core/llm/src/main/java/com/aimusic/player/llm/LlmFailureKind.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/LlmFailureKindTest.kt`

**Interfaces:**
- Consumes: Task 1 的模块
- Produces（**这三个类型名是 P2/P3/P4 共同依赖的契约，不得改名**）：
  - `enum class ProtocolKind { OPENAI, RESPONSES, ANTHROPIC }`
  - `data class LlmConfig(protocol, baseUrl, model, apiKey, supportsJsonSchema, supportsJsonMode, maxRetries, batchSize, maxTokens, temperature, connectTimeoutMs, readTimeoutMs, callTimeoutMs, maxTagsPerCategory)`
  - `data class LlmCall(system: String, user: String, schema: JsonElement, model: String, maxTokens: Int, temperature: Double = 0.0)`
  - `sealed interface LlmHttpResult { Ok(payloadJson) / HttpError(status, retryAfterMs, body) / Transport(cause) }`
  - `data class HttpRequestSpec(url: String, headers: Map<String, String>, bodyJson: String)`
  - `interface ProtocolAdapter { encode(call, cfg): HttpRequestSpec; decode(status, headers, body): LlmHttpResult }`
  - `enum class LlmFailureKind { NETWORK, AUTH, SERVER, INVALID_OUTPUT, TIMEOUT }` + `fun LlmHttpResult.toFailureKind(): LlmFailureKind?`

- [ ] **Step 1: 写失败测试**

`core/llm/src/test/java/com/aimusic/player/llm/LlmFailureKindTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Test

class LlmFailureKindTest {

    @Test
    fun `401 与 403 都是 AUTH`() {
        assertThat(httpError(401).toFailureKind()).isEqualTo(LlmFailureKind.AUTH)
        assertThat(httpError(403).toFailureKind()).isEqualTo(LlmFailureKind.AUTH)
    }

    @Test
    fun `5xx 与 529 都是 SERVER`() {
        assertThat(httpError(500).toFailureKind()).isEqualTo(LlmFailureKind.SERVER)
        // anthropic 的 overloaded：529 不是 429，不进退避
        assertThat(httpError(529).toFailureKind()).isEqualTo(LlmFailureKind.SERVER)
    }

    @Test
    fun `429 不映射成 FailureKind（由退避层处理）`() {
        assertThat(httpError(429).toFailureKind()).isNull()
    }

    @Test
    fun `4xx 其他按 SERVER 处理并保留原状态码给日志`() {
        assertThat(httpError(422).toFailureKind()).isEqualTo(LlmFailureKind.SERVER)
    }

    @Test
    fun `IOException 是 NETWORK，SocketTimeout 是 TIMEOUT`() {
        assertThat(LlmHttpResult.Transport(IOException("boom")).toFailureKind())
            .isEqualTo(LlmFailureKind.NETWORK)
        assertThat(LlmHttpResult.Transport(SocketTimeoutException("slow")).toFailureKind())
            .isEqualTo(LlmFailureKind.TIMEOUT)
    }

    @Test
    fun `成功结果没有失败类别`() {
        assertThat(LlmHttpResult.Ok("{}").toFailureKind()).isNull()
    }

    private fun httpError(status: Int): LlmHttpResult =
        LlmHttpResult.HttpError(status = status, retryAfterMs = null, body = "x")
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*LlmFailureKindTest*" --console=plain`
Expected: 编译失败，`Unresolved reference: LlmFailureKind`（以及 `httpError` 里用到的 `LlmHttpResult`）。

- [ ] **Step 3: 写契约类型**

`ProtocolKind.kt`：

```kotlin
package com.aimusic.player.llm

/** LLM 协议方言。决定请求怎么封、响应从哪儿取（spec §3）。 */
enum class ProtocolKind { OPENAI, RESPONSES, ANTHROPIC }
```

`LlmConfig.kt`：

```kotlin
package com.aimusic.player.llm

/**
 * 运行时配置。**不含预设名**：预设只是设置页的填表助手，落库的只有这些最终值。
 *
 * `supportsJsonSchema` / `supportsJsonMode` 是可手动覆盖的能力位 —— 各家兼容实现的实际支持面
 * 不一致，猜不可靠（spec §4）。
 */
data class LlmConfig(
    val protocol: ProtocolKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val supportsJsonSchema: Boolean,
    val supportsJsonMode: Boolean = true,
    val maxRetries: Int = 3,
    val batchSize: Int = 20,
    val maxTokens: Int = 8_192,
    val temperature: Double = 0.0,
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 90_000,
    val callTimeoutMs: Long = 120_000,
    val maxTagsPerCategory: Int = 2,
)
```

`LlmCall.kt`：

```kotlin
package com.aimusic.player.llm

import kotlinx.serialization.json.JsonElement

/** 协议无关的一次调用：who（system/user）与要什么形状（schema）。 */
data class LlmCall(
    val system: String,
    val user: String,
    val schema: JsonElement,
    val model: String,
    val maxTokens: Int,
    val temperature: Double = 0.0,
)
```

`LlmHttpResult.kt`：

```kotlin
package com.aimusic.player.llm

/** 发出去之前的一切（url / 头 / body 都已定），由 DirectProvider 执行。 */
data class HttpRequestSpec(
    val url: String,
    val headers: Map<String, String>,
    val bodyJson: String,
)

/**
 * 响应的三态。
 *
 * `Ok.payloadJson` 一定是**字符串**：anthropic 走 tool_use 时拿到的是已解析的对象，
 * 适配器负责还原成字符串，这样 `NormalizeParser.parse(text, …)` 的签名一个字都不用改（spec §3）。
 */
sealed interface LlmHttpResult {
    data class Ok(val payloadJson: String) : LlmHttpResult

    data class HttpError(
        val status: Int,
        val retryAfterMs: Long?,
        val body: String,
    ) : LlmHttpResult

    data class Transport(val cause: Throwable) : LlmHttpResult
}
```

`ProtocolAdapter.kt`：

```kotlin
package com.aimusic.player.llm

/** 方言适配器：只做编解码，不碰网络。 */
interface ProtocolAdapter {
    fun encode(call: LlmCall, cfg: LlmConfig): HttpRequestSpec

    fun decode(status: Int, headers: Map<String, String>, body: String): LlmHttpResult
}
```

`LlmFailureKind.kt`：

```kotlin
package com.aimusic.player.llm

import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * 失败类别（`05 §3.1` 的五个取值）。
 *
 * `429` **不在这里**：限流由退避层处理，耗尽后才由上层变成 `RateLimited`（spec §9.1）。
 */
enum class LlmFailureKind { NETWORK, AUTH, SERVER, INVALID_OUTPUT, TIMEOUT }

/**
 * HTTP 结果 → 失败类别。返回 `null` 表示「不是失败」（成功、或 429 交给退避层）。
 *
 * 529（anthropic overloaded）归 SERVER：它不是限流，不进退避（spec §3）。
 */
fun LlmHttpResult.toFailureKind(): LlmFailureKind? = when (this) {
    is LlmHttpResult.Ok -> null
    is LlmHttpResult.Transport -> when (cause) {
        is SocketTimeoutException -> LlmFailureKind.TIMEOUT
        is InterruptedIOException -> LlmFailureKind.TIMEOUT
        else -> LlmFailureKind.NETWORK
    }
    is LlmHttpResult.HttpError -> when (status) {
        429 -> null
        401, 403 -> LlmFailureKind.AUTH
        else -> LlmFailureKind.SERVER
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*LlmFailureKindTest*" --console=plain`
Expected: `BUILD SUCCESSFUL`，6 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add core/llm/src
git commit -F - <<'EOF'
feat(llm): Phase 4-1 协议无关契约类型与 LlmFailureKind 映射

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>
EOF
```

---

### Task 3: `OpenAiChatProtocol`（openai 方言）

**Files:**
- Create: `core/llm/src/main/java/com/aimusic/player/llm/OpenAiChatProtocol.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/OpenAiChatProtocolTest.kt`

**Interfaces:**
- Consumes: `LlmCall` / `LlmConfig` / `HttpRequestSpec` / `LlmHttpResult` / `ProtocolAdapter`（Task 2）；`JsonEnforcementPolicy`（**由 P2 提供**，签名见下）
- Produces: `class OpenAiChatProtocol(private val jsonPolicy: JsonEnforcementPolicy) : ProtocolAdapter`

**P2 提供的接口（本任务按此签名调用，不要自己定义）**：

```kotlin
// 由 P2 产出（core/llm/.../JsonEnforcementPolicy.kt）
sealed interface JsonEnforcementPolicy {
    /** L1：能在请求里声明 schema */
    data class Schema(val schema: JsonElement) : JsonEnforcementPolicy
    /** L2：只能声明 JSON mode */
    data object JsonObject : JsonEnforcementPolicy
    /** L3：只能靠提示词，客户端容错抽取 */
    data object PromptOnly : JsonEnforcementPolicy
}
```

- [ ] **Step 1: 写失败测试**

`core/llm/src/test/java/com/aimusic/player/llm/OpenAiChatProtocolTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class OpenAiChatProtocolTest {

    private val schema = buildJsonObject { put("type", "object") }

    private fun cfg(supportsSchema: Boolean = true) = LlmConfig(
        protocol = ProtocolKind.OPENAI,
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
        apiKey = "sk-test",
        supportsJsonSchema = supportsSchema,
    )

    private fun call() = LlmCall(
        system = "S", user = "U", schema = schema, model = "deepseek-chat", maxTokens = 4096,
    )

    @Test
    fun `encode_路径与鉴权头与 system 位置`() {
        val spec = OpenAiChatProtocol(JsonEnforcementPolicy.Schema(schema)).encode(call(), cfg())

        assertThat(spec.url).isEqualTo("https://api.deepseek.com/v1/chat/completions")
        assertThat(spec.headers["Authorization"]).isEqualTo("Bearer sk-test")
        assertThat(spec.headers["Content-Type"]).isEqualTo("application/json")

        val body = Json.parseToJsonElement(spec.bodyJson)
        val messages = body.jsonObject["messages"]!!.jsonArray
        assertThat(messages[0].jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("system")
        assertThat(messages[1].jsonObject["role"]!!.jsonPrimitive.content).isEqualTo("user")
    }

    @Test
    fun `encode_L1 声明 json_schema 且 strict 为 true`() {
        val body = Json.parseToJsonElement(
            OpenAiChatProtocol(JsonEnforcementPolicy.Schema(schema)).encode(call(), cfg()).bodyJson,
        ).jsonObject

        val rf = body["response_format"]!!.jsonObject
        assertThat(rf["type"]!!.jsonPrimitive.content).isEqualTo("json_schema")
        assertThat(rf["json_schema"]!!.jsonObject["strict"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test
    fun `encode_L2 退到 json_object`() {
        val body = Json.parseToJsonElement(
            OpenAiChatProtocol(JsonEnforcementPolicy.JsonObject).encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            .isEqualTo("json_object")
    }

    @Test
    fun `encode_L3 完全不带 response_format`() {
        val body = Json.parseToJsonElement(
            OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly).encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body.containsKey("response_format")).isFalse()
        assertThat(body["temperature"]!!.jsonPrimitive.content).isEqualTo("0.0")
        assertThat(body["max_tokens"]!!.jsonPrimitive.content).isEqualTo("4096")
    }

    @Test
    fun `decode_从 choices 取 content 字符串`() {
        val wire = """
            {"id":"chatcmpl-x","choices":[{"finish_reason":"stop",
             "message":{"role":"assistant","content":"{\"results\":[]}"}}]}
        """.trimIndent()

        val result = OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(status = 200, headers = emptyMap(), body = wire)

        assertThat(result).isInstanceOf(LlmHttpResult.Ok::class.java)
        assertThat((result as LlmHttpResult.Ok).payloadJson).isEqualTo("""{"results":[]}""")
    }

    @Test
    fun `decode_429 带上 Retry-After 秒数`() {
        val result = OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(status = 429, headers = mapOf("Retry-After" to "5"), body = "")

        assertThat(result).isInstanceOf(LlmHttpResult.HttpError::class.java)
        val error = result as LlmHttpResult.HttpError
        assertThat(error.status).isEqualTo(429)
        assertThat(error.retryAfterMs).isEqualTo(5_000L)
    }

    @Test
    fun `decode_401 与 529 都当 HttpError 原样带回`() {
        val protocol = OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly)
        assertThat((protocol.decode(401, emptyMap(), "no") as LlmHttpResult.HttpError).status)
            .isEqualTo(401)
        assertThat((protocol.decode(529, emptyMap(), "busy") as LlmHttpResult.HttpError).status)
            .isEqualTo(529)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*OpenAiChatProtocolTest*" --console=plain`
Expected: 编译失败，`Unresolved reference: OpenAiChatProtocol`。

- [ ] **Step 3: 写实现**

`core/llm/src/main/java/com/aimusic/player/llm/OpenAiChatProtocol.kt`：

```kotlin
package com.aimusic.player.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * openai 方言：`POST {base}/chat/completions`，`Authorization: Bearer`，system 是 messages[0]。
 * 结果在 `choices[0].message.content`（**字符串**）。
 */
class OpenAiChatProtocol(private val jsonPolicy: JsonEnforcementPolicy) : ProtocolAdapter {

    override fun encode(call: LlmCall, cfg: LlmConfig): HttpRequestSpec {
        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", call.system) })
            add(buildJsonObject { put("role", "user"); put("content", call.user) })
        }

        val body = buildJsonObject {
            put("model", call.model)
            put("temperature", call.temperature)
            put("max_tokens", call.maxTokens)
            put("messages", messages)
            when (jsonPolicy) {
                is JsonEnforcementPolicy.Schema -> put(
                    "response_format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put(
                            "json_schema",
                            buildJsonObject {
                                put("name", "song_normalization_batch")
                                put("strict", true)
                                put("schema", jsonPolicy.schema)
                            },
                        )
                    },
                )
                JsonEnforcementPolicy.JsonObject -> put(
                    "response_format",
                    buildJsonObject { put("type", "json_object") },
                )
                JsonEnforcementPolicy.PromptOnly -> Unit
            }
        }

        return HttpRequestSpec(
            url = "${cfg.baseUrl.trimEnd('/')}/chat/completions",
            headers = mapOf(
                "Authorization" to "Bearer ${cfg.apiKey}",
                "Content-Type" to "application/json",
            ),
            bodyJson = body.toString(),
        )
    }

    override fun decode(status: Int, headers: Map<String, String>, body: String): LlmHttpResult {
        if (status != 200) {
            return LlmHttpResult.HttpError(status, retryAfterMs(headers), body)
        }
        val content = runCatching {
            Json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        // 取不到 content 不在这里判 INVALID_OUTPUT：交给 P2 的 parser 统一判（它才认识 schema）
        return LlmHttpResult.Ok(content.orEmpty())
    }

    private fun retryAfterMs(headers: Map<String, String>): Long? =
        headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.times(1_000L)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*OpenAiChatProtocolTest*" --console=plain`
Expected: `BUILD SUCCESSFUL`，7 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add core/llm/src
git commit -F - <<'EOF'
feat(llm): Phase 4-1 openai 方言适配器（chat/completions）

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>
EOF
```

---

### Task 4: `ResponsesProtocol`（responses 方言）

**Files:**
- Create: `core/llm/src/main/java/com/aimusic/player/llm/ResponsesProtocol.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/ResponsesProtocolTest.kt`

**Interfaces:**
- Consumes: Task 2 的契约类型；`JsonEnforcementPolicy`（P2）
- Produces: `class ResponsesProtocol(private val jsonPolicy: JsonEnforcementPolicy) : ProtocolAdapter`

- [ ] **Step 1: 写失败测试**

`core/llm/src/test/java/com/aimusic/player/llm/ResponsesProtocolTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class ResponsesProtocolTest {

    private val schema = buildJsonObject { put("type", "object") }

    private fun cfg() = LlmConfig(
        protocol = ProtocolKind.RESPONSES,
        baseUrl = "https://api.openai.com/v1/",
        model = "gpt-4o-mini",
        apiKey = "sk-test",
        supportsJsonSchema = true,
    )

    private fun call() = LlmCall("S", "U", schema, "gpt-4o-mini", 4096)

    @Test
    fun `encode_system 走顶层 instructions 而不是 message`() {
        val body = Json.parseToJsonElement(
            ResponsesProtocol(JsonEnforcementPolicy.Schema(schema)).encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body["instructions"]!!.jsonPrimitive.content).isEqualTo("S")
        assertThat(body["input"]!!.jsonPrimitive.content).isEqualTo("U")
        assertThat(body.containsKey("messages")).isFalse()
        // 长度参数在这一方言叫 max_output_tokens
        assertThat(body["max_output_tokens"]!!.jsonPrimitive.content).isEqualTo("4096")
        assertThat(body.containsKey("max_tokens")).isFalse()
    }

    @Test
    fun `encode_schema 放在 text_format 里且不套 json_schema 壳`() {
        val body = Json.parseToJsonElement(
            ResponsesProtocol(JsonEnforcementPolicy.Schema(schema)).encode(call(), cfg()).bodyJson,
        ).jsonObject

        val format = body["text"]!!.jsonObject["format"]!!.jsonObject
        assertThat(format["type"]!!.jsonPrimitive.content).isEqualTo("json_schema")
        assertThat(format["name"]!!.jsonPrimitive.content).isEqualTo("song_normalization_batch")
        assertThat(format.containsKey("schema")).isTrue()
        // 与 openai 方言不同：这里没有 json_schema 这一层包装
        assertThat(format.containsKey("json_schema")).isFalse()
    }

    @Test
    fun `encode_L3 不带 text 字段`() {
        val body = Json.parseToJsonElement(
            ResponsesProtocol(JsonEnforcementPolicy.PromptOnly).encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body.containsKey("text")).isFalse()
    }

    @Test
    fun `encode_baseUrl 末尾斜杠不产生双斜杠`() {
        val spec = ResponsesProtocol(JsonEnforcementPolicy.PromptOnly).encode(call(), cfg())
        assertThat(spec.url).isEqualTo("https://api.openai.com/v1/responses")
    }

    @Test
    fun `decode_从 output 里的 message 取 content text`() {
        val wire = """
            {"id":"resp_x","status":"completed","output":[
              {"type":"reasoning","summary":[]},
              {"type":"message","role":"assistant","content":[
                {"type":"output_text","text":"{\"results\":[]}"}]}]}
        """.trimIndent()

        val result = ResponsesProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(200, emptyMap(), wire)

        assertThat((result as LlmHttpResult.Ok).payloadJson).isEqualTo("""{"results":[]}""")
    }

    @Test
    fun `decode_output 里没有 message 时返回空串（交给 parser 判）`() {
        val result = ResponsesProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(200, emptyMap(), """{"id":"x","output":[]}""")

        assertThat((result as LlmHttpResult.Ok).payloadJson).isEmpty()
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*ResponsesProtocolTest*" --console=plain`
Expected: 编译失败，`Unresolved reference: ResponsesProtocol`。

- [ ] **Step 3: 写实现**

`core/llm/src/main/java/com/aimusic/player/llm/ResponsesProtocol.kt`：

```kotlin
package com.aimusic.player.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * responses 方言：`POST {base}/responses`，system 走顶层 `instructions`，
 * 长度参数叫 `max_output_tokens`，结构化输出在 `text.format`（**不套 `json_schema` 壳**）。
 * 结果要从 `output[]` 里挑 `type=message`，再取 `content[].text`。
 */
class ResponsesProtocol(private val jsonPolicy: JsonEnforcementPolicy) : ProtocolAdapter {

    override fun encode(call: LlmCall, cfg: LlmConfig): HttpRequestSpec {
        val body = buildJsonObject {
            put("model", call.model)
            put("instructions", call.system)
            put("input", call.user)
            put("temperature", call.temperature)
            put("max_output_tokens", call.maxTokens)
            if (jsonPolicy is JsonEnforcementPolicy.Schema) {
                put(
                    "text",
                    buildJsonObject {
                        put(
                            "format",
                            buildJsonObject {
                                put("type", "json_schema")
                                put("name", "song_normalization_batch")
                                put("strict", true)
                                put("schema", jsonPolicy.schema)
                            },
                        )
                    },
                )
            }
        }

        return HttpRequestSpec(
            url = "${cfg.baseUrl.trimEnd('/')}/responses",
            headers = mapOf(
                "Authorization" to "Bearer ${cfg.apiKey}",
                "Content-Type" to "application/json",
            ),
            bodyJson = body.toString(),
        )
    }

    override fun decode(status: Int, headers: Map<String, String>, body: String): LlmHttpResult =
        when {
            status != 200 -> LlmHttpResult.HttpError(status, retryAfterMs(headers), body)
            else -> LlmHttpResult.Ok(extractText(body))
        }

    private fun extractText(body: String): String = runCatching {
        Json.parseToJsonElement(body).jsonObject["output"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
            ?.get("content")?.jsonArray
            ?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
    }.getOrNull().orEmpty()

    private fun retryAfterMs(headers: Map<String, String>): Long? =
        headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.times(1_000L)
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*ResponsesProtocolTest*" --console=plain`
Expected: `BUILD SUCCESSFUL`，6 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add core/llm/src
git commit -F - <<'EOF'
feat(llm): Phase 4-1 responses 方言适配器

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>
EOF
```

---

### Task 5: `AnthropicMessagesProtocol`（anthropic 方言，含对象还原成字符串）

**Files:**
- Create: `core/llm/src/main/java/com/aimusic/player/llm/AnthropicMessagesProtocol.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/AnthropicMessagesProtocolTest.kt`

**Interfaces:**
- Consumes: Task 2 的契约类型；`JsonEnforcementPolicy`（P2）
- Produces: `class AnthropicMessagesProtocol(private val jsonPolicy: JsonEnforcementPolicy) : ProtocolAdapter`

- [ ] **Step 1: 写失败测试**

`core/llm/src/test/java/com/aimusic/player/llm/AnthropicMessagesProtocolTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class AnthropicMessagesProtocolTest {

    private val schema = buildJsonObject { put("type", "object") }

    private fun cfg() = LlmConfig(
        protocol = ProtocolKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-sonnet-4-5",
        apiKey = "sk-ant-test",
        supportsJsonSchema = true,
    )

    private fun call() = LlmCall("S", "U", schema, "claude-sonnet-4-5", 4096)

    @Test
    fun `encode_鉴权走 x-api-key 且带 anthropic-version`() {
        val spec = AnthropicMessagesProtocol(JsonEnforcementPolicy.Schema(schema)).encode(call(), cfg())

        assertThat(spec.headers["x-api-key"]).isEqualTo("sk-ant-test")
        assertThat(spec.headers["anthropic-version"]).isEqualTo("2023-06-01")
        assertThat(spec.headers.containsKey("Authorization")).isFalse()
        assertThat(spec.url).isEqualTo("https://api.anthropic.com/v1/messages")
    }

    @Test
    fun `encode_用 tools 的 input_schema 强制 JSON 并且 tool_choice 点名`() {
        val body = Json.parseToJsonElement(
            AnthropicMessagesProtocol(JsonEnforcementPolicy.Schema(schema)).encode(call(), cfg()).bodyJson,
        ).jsonObject

        // system 在顶层，不是 message
        assertThat(body["system"]!!.jsonPrimitive.content).isEqualTo("S")
        assertThat(body["messages"]!!.jsonArray.size).isEqualTo(1)

        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertThat(tool["name"]!!.jsonPrimitive.content).isEqualTo("emit_song_normalization")
        assertThat(tool.containsKey("input_schema")).isTrue()
        assertThat(body["tool_choice"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            .isEqualTo("emit_song_normalization")
    }

    @Test
    fun `encode_L3 不带 tools`() {
        val body = Json.parseToJsonElement(
            AnthropicMessagesProtocol(JsonEnforcementPolicy.PromptOnly).encode(call(), cfg()).bodyJson,
        ).jsonObject

        assertThat(body.containsKey("tools")).isFalse()
        assertThat(body.containsKey("tool_choice")).isFalse()
    }

    @Test
    fun `decode_tool_use 的 input 是对象_必须还原成 JSON 字符串`() {
        val wire = """
            {"id":"msg_x","stop_reason":"tool_use","content":[
              {"type":"tool_use","name":"emit_song_normalization",
               "input":{"results":[{"file_index":1,"canonical_title":"晴天","artists":["周杰伦"],"tag_groups":[]}]}}]}
        """.trimIndent()

        val result = AnthropicMessagesProtocol(JsonEnforcementPolicy.Schema(schema))
            .decode(200, emptyMap(), wire)

        val payload = (result as LlmHttpResult.Ok).payloadJson
        // 是字符串，不是对象；且内容可再解析
        assertThat(payload).startsWith("{")
        assertThat(Json.parseToJsonElement(payload).jsonObject["results"]!!.jsonArray.size).isEqualTo(1)
    }

    @Test
    fun `decode_普通 text 块也能取`() {
        val wire = """{"id":"m","content":[{"type":"text","text":"{\"results\":[]}"}]}"""

        val result = AnthropicMessagesProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(200, emptyMap(), wire)

        assertThat((result as LlmHttpResult.Ok).payloadJson).isEqualTo("""{"results":[]}""")
    }

    @Test
    fun `decode_529 overloaded 当 HttpError 原样带回`() {
        val result = AnthropicMessagesProtocol(JsonEnforcementPolicy.PromptOnly)
            .decode(529, emptyMap(), """{"type":"overloaded_error"}""")

        assertThat((result as LlmHttpResult.HttpError).status).isEqualTo(529)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*AnthropicMessagesProtocolTest*" --console=plain`
Expected: 编译失败，`Unresolved reference: AnthropicMessagesProtocol`（`jsonArray` 等 import 也可能未解析）。

- [ ] **Step 3: 写实现**

`core/llm/src/main/java/com/aimusic/player/llm/AnthropicMessagesProtocol.kt`：

```kotlin
package com.aimusic.player.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * anthropic 方言：`POST {base}/messages`，`x-api-key` + `anthropic-version`，
 * system 在**顶层**（不是 message），结构化输出靠 `tools[].input_schema` + `tool_choice` 点名。
 *
 * 关键：tool_use 的 `input` 是**已解析的对象**，而 parser 的签名锁死为 `parse(text: String, …)`，
 * 所以这里负责把它**还原成 JSON 字符串**（spec §3 的两条硬约束之一）。
 */
class AnthropicMessagesProtocol(private val jsonPolicy: JsonEnforcementPolicy) : ProtocolAdapter {

    override fun encode(call: LlmCall, cfg: LlmConfig): HttpRequestSpec {
        val body = buildJsonObject {
            put("model", call.model)
            put("system", call.system)
            put("max_tokens", call.maxTokens)
            put("temperature", call.temperature)
            put(
                "messages",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", call.user)
                        },
                    )
                },
            )
            if (jsonPolicy is JsonEnforcementPolicy.Schema) {
                put(
                    "tools",
                    kotlinx.serialization.json.buildJsonArray {
                        add(
                            buildJsonObject {
                                put("name", TOOL_NAME)
                                put("description", "输出这批歌曲的归一化结果；results 的长度必须等于输入文件数")
                                put("input_schema", jsonPolicy.schema)
                            },
                        )
                    },
                )
                put(
                    "tool_choice",
                    buildJsonObject {
                        put("type", "tool")
                        put("name", TOOL_NAME)
                    },
                )
            }
        }

        return HttpRequestSpec(
            url = "${cfg.baseUrl.trimEnd('/')}/messages",
            headers = mapOf(
                "x-api-key" to cfg.apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
                "Content-Type" to "application/json",
            ),
            bodyJson = body.toString(),
        )
    }

    override fun decode(status: Int, headers: Map<String, String>, body: String): LlmHttpResult =
        when {
            status != 200 -> LlmHttpResult.HttpError(status, retryAfterMs(headers), body)
            else -> LlmHttpResult.Ok(extractJson(body))
        }

    /** 优先取 tool_use 的 `input`（对象 → 字符串），退到 text 块。 */
    private fun extractJson(body: String): String = runCatching {
        val blocks = Json.parseToJsonElement(body).jsonObject["content"]?.jsonArray
            ?.map { it.jsonObject }.orEmpty()

        blocks.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
            ?.get("input")
            ?.toString()
            ?: blocks.firstNotNullOfOrNull { it["text"]?.jsonPrimitive?.contentOrNull }
    }.getOrNull().orEmpty()

    private fun retryAfterMs(headers: Map<String, String>): Long? =
        headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.trim()?.toLongOrNull()?.times(1_000L)

    private companion object {
        const val TOOL_NAME = "emit_song_normalization"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*AnthropicMessagesProtocolTest*" --console=plain`
Expected: `BUILD SUCCESSFUL`，6 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add core/llm/src
git commit -F - <<'EOF'
feat(llm): Phase 4-1 anthropic 方言适配器（tool_use.input 还原成 JSON 字符串）

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>
EOF
```

---

### Task 6: 方言选择与 `DirectProvider` 的网络执行

**Files:**
- Create: `core/llm/src/main/java/com/aimusic/player/llm/ProtocolAdapters.kt`
- Create: `core/llm/src/main/java/com/aimusic/player/llm/DirectProvider.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/ProtocolAdaptersTest.kt`
- Test: `core/llm/src/test/java/com/aimusic/player/llm/DirectProviderTest.kt`

**Interfaces:**
- Consumes: Task 3/4/5 的三个适配器；`JsonEnforcementPolicy`（**由 P2 提供**）
- Produces:
  - `fun protocolAdapterFor(kind: ProtocolKind, jsonPolicy: JsonEnforcementPolicy): ProtocolAdapter`
  - `class DirectProvider(private val client: OkHttpClient, private val adapter: ProtocolAdapter)` — 只有 `execute`，**不实现 `LlmNormalizer`**（那是 P3 的装配责任）

**注**：设计文档 §3 提到过 Retrofit；这里改用 OkHttp 直发 `HttpRequestSpec`，因为「一份中间表示 + 三套适配器」的模型下，Retrofit 的「一个接口一个端点」反而要写三份接口。这是**有意的偏离**，需回写 `05 §2/§3.4`（不在本任务范围，任务完成后记一笔）。

- [ ] **Step 1: 写失败测试（方言选择）**

`core/llm/src/test/java/com/aimusic/player/llm/ProtocolAdaptersTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProtocolAdaptersTest {

    @Test
    fun `三种协议各拿到自己的适配器`() {
        val policy = JsonEnforcementPolicy.PromptOnly

        assertThat(protocolAdapterFor(ProtocolKind.OPENAI, policy))
            .isInstanceOf(OpenAiChatProtocol::class.java)
        assertThat(protocolAdapterFor(ProtocolKind.RESPONSES, policy))
            .isInstanceOf(ResponsesProtocol::class.java)
        assertThat(protocolAdapterFor(ProtocolKind.ANTHROPIC, policy))
            .isInstanceOf(AnthropicMessagesProtocol::class.java)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*ProtocolAdaptersTest*" --console=plain`
Expected: 编译失败，`Unresolved reference: protocolAdapterFor`。

- [ ] **Step 3: 写方言选择**

`core/llm/src/main/java/com/aimusic/player/llm/ProtocolAdapters.kt`：

```kotlin
package com.aimusic.player.llm

/** 协议类型 → 适配器。加第四种方言只需要在这里多一支。 */
fun protocolAdapterFor(kind: ProtocolKind, jsonPolicy: JsonEnforcementPolicy): ProtocolAdapter =
    when (kind) {
        ProtocolKind.OPENAI -> OpenAiChatProtocol(jsonPolicy)
        ProtocolKind.RESPONSES -> ResponsesProtocol(jsonPolicy)
        ProtocolKind.ANTHROPIC -> AnthropicMessagesProtocol(jsonPolicy)
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*ProtocolAdaptersTest*" --console=plain`
Expected: `BUILD SUCCESSFUL`，1 个测试通过。

- [ ] **Step 5: 写失败测试（网络执行）**

`core/llm/src/test/java/com/aimusic/player/llm/DirectProviderTest.kt`：

```kotlin
package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class DirectProviderTest {

    private lateinit var server: MockWebServer

    private val schema = buildJsonObject { put("type", "object") }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider() = DirectProvider(
        client = OkHttpClient(),
        adapter = OpenAiChatProtocol(JsonEnforcementPolicy.PromptOnly),
    )

    private fun cfg() = LlmConfig(
        protocol = ProtocolKind.OPENAI,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        model = "m",
        apiKey = "sk-test",
        supportsJsonSchema = false,
    )

    private fun call() = LlmCall("S", "U", schema, "m", 1024)

    @Test
    fun `execute_把适配器编好的请求原样发出去并解析成功`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"{\"results\":[]}"}}]}""",
            ),
        )

        val result = provider().execute(call(), cfg())

        assertThat((result as LlmHttpResult.Ok).payloadJson).isEqualTo("""{"results":[]}""")
        val recorded = server.takeRequest()
        assertThat(recorded.path).isEqualTo("/v1/chat/completions")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-test")
    }

    @Test
    fun `execute_429 带上 Retry-After`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))

        val result = provider().execute(call(), cfg())

        val error = result as LlmHttpResult.HttpError
        assertThat(error.status).isEqualTo(429)
        assertThat(error.retryAfterMs).isEqualTo(2_000L)
    }

    @Test
    fun `execute_服务器不可达时是 Transport 而不是抛异常`() {
        server.shutdown()

        val result = provider().execute(call(), cfg())

        assertThat(result).isInstanceOf(LlmHttpResult.Transport::class.java)
        assertThat(result.toFailureKind()).isEqualTo(LlmFailureKind.NETWORK)
    }
}
```

- [ ] **Step 6: 跑测试确认失败**

Run: `./gradlew :core:llm:testDebugUnitTest --tests "*DirectProviderTest*" --console=plain`
Expected: 编译失败，`Unresolved reference: DirectProvider`。

- [ ] **Step 7: 写实现**

`core/llm/src/main/java/com/aimusic/player/llm/DirectProvider.kt`：

```kotlin
package com.aimusic.player.llm

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 真实网络：拿适配器编好的 [HttpRequestSpec] 用 OkHttp 发出去，再把响应交回适配器解码。
 *
 * **不做重试、不查缓存**：那是 P3 两个装饰器的职责（spec §2 的 `Caching(Retrying(Direct))`）。
 * 这里也**不实现 [LlmNormalizer]**，装配由 P3 的 `buildLlmNormalizer` 负责。
 *
 * 网络异常一律变成 [LlmHttpResult.Transport]，不往上抛 —— 上层的失败分类基于返回值而不是异常。
 */
class DirectProvider(
    private val client: OkHttpClient,
    private val adapter: ProtocolAdapter,
) {

    fun execute(call: LlmCall, cfg: LlmConfig): LlmHttpResult {
        val spec = adapter.encode(call, cfg)
        val request = Request.Builder()
            .url(spec.url)
            .apply { spec.headers.forEach { (name, value) -> header(name, value) } }
            .post(spec.bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val headers = response.headers.names().associateWith { name ->
                    response.header(name).orEmpty()
                }
                adapter.decode(response.code, headers, response.body?.string().orEmpty())
            }
        } catch (error: IOException) {
            LlmHttpResult.Transport(error)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
```

- [ ] **Step 8: 跑测试确认全部通过**

Run: `./gradlew :core:llm:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`，本计划全部测试（约 27 个）通过。

- [ ] **Step 9: 提交**

```bash
git add core/llm/src
git commit -F - <<'EOF'
feat(llm): Phase 4-1 方言选择与 DirectProvider（OkHttp 直发）

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>
EOF
```

---

### Task 7: 回写文档里的两处偏离

**Files:**
- Modify: `docs/技术方案/05-分析与LLM归一化模块.md`

**Interfaces:**
- Consumes: 前六个任务的实际产出
- Produces: 文档与代码一致

- [ ] **Step 1: 改 `05 §2` 的文件清单**

把 `api/LlmApi.kt`（Retrofit 接口）与 `api/dto/*.kt` 两行改为实际存在的文件：`ProtocolKind.kt` / `LlmConfig.kt` / `LlmCall.kt` / `LlmHttpResult.kt` / `ProtocolAdapter.kt` / `LlmFailureKind.kt` / `OpenAiChatProtocol.kt` / `ResponsesProtocol.kt` / `AnthropicMessagesProtocol.kt` / `ProtocolAdapters.kt` / `DirectProvider.kt`。

- [ ] **Step 2: 在 `05 §3.4` 记下「不用 Retrofit」的理由**

补一句说明：一份中间表示 + 三套适配器的模型下，Retrofit 的「一个接口一个端点」要写三份；故 `DirectProvider` 用 OkHttp 直发 `HttpRequestSpec`。**保留原文，加标注，不要删掉历史。**

- [ ] **Step 3: 跑一次全量构建确认没伤到别处**

Run: `./gradlew assembleDebug --console=plain | tail -3`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bash
git add docs/技术方案/05-分析与LLM归一化模块.md
git commit -F - <<'EOF'
docs: 05 回写 Phase 4-1 的协议层实际产出（适配器取代 LlmApi/Retrofit）

Co-authored-by: CommandCodeBot <noreply@commandcode.ai>
EOF
```

---

## 自检：spec 覆盖对照

| spec 出处 | 由哪个任务覆盖 |
| --- | --- |
| §2 分层与依赖（`:core:llm` 只依赖 common） | Task 1（构建文件）+ Global Constraints |
| §3 协议抽象（内部模型 + 三适配器） | Task 2（类型）+ Task 3/4/5（三方言）+ Task 6（选择） |
| §3 表格逐格（路径 / 鉴权 / system 位置 / 结构化输出 / 取结果 / 529） | Task 3/4/5 各自的 `encode`/`decode` 与测试逐条断言 |
| §3 硬约束一：anthropic 的 `input` 对象 → JSON 字符串 | Task 5 的第三个测试 |
| §3 硬约束二：`529 → SERVER`、不重试 | Task 2 的 `toFailureKind` 测试 + Task 5 的 529 测试 |
| §3 方言差异不泄漏到上层 | Task 6 的 `protocolAdapterFor`；上层只拿到 `LlmHttpResult` |
| §13 表「三适配器编码 / 解码 / 529 不重试」 | Task 3/4/5/6 的测试 |
| `05 §3.1` 的 `LlmFailureKind` 五个取值 | Task 2 |
| `05 §3.3` 的请求体形态（temperature=0、max_tokens 等） | Task 3（`max_tokens`）/ Task 4（`max_output_tokens`）/ Task 5（`max_tokens`） |

**不在本计划内**（属 P2/P3/P4，不要在这里实现）：
- `JsonEnforcementPolicy` 的**产出**（P2）——本计划只消费它，Task 3 的 Interfaces 已给出签名
- `NormalizeParser`、prompt 资源、schema 骨架、目录指纹（P2）
- `CachingLlmNormalizer` / `RetryingLlmNormalizer` / `LlmCache` / 装配（P3）
- `AnalysisOrchestrator`、两个界面（P4）
