package com.aimusic.player.llm

/**
 * 运行时配置的**取值入口**（P3-T8 定）。
 *
 * 为什么不让 `LlmConfig` 直接进 Hilt 图：它是**运行时可变的**（设置页随时改协议 / 模型 / key / 超时），
 * 建成单例会在改设置后继续用旧值；而它一旦可变，`OkHttpClient`（要超时）、`ProtocolAdapter`
 * （要协议与能力位）这些「建一次用很久」的依赖就没法在构造期拿到它 —— 会绕成循环依赖。
 *
 * 所以：这些组件注入 [LlmConfigProvider]，在**每次调用时**取当前配置。
 *
 * 实现由设置层提供（P4 的 DataStore + ApiKeyStore）。在那之前 Hilt 图里挂的是一段**空值**桥接，
 * 只保证图可编译可启动，**不能真发请求** —— 别拿空 key 去排查"请求为什么失败"。
 */
fun interface LlmConfigProvider {

    fun current(): LlmConfig
}
