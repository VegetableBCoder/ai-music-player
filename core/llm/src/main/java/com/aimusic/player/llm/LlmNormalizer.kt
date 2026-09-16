package com.aimusic.player.llm

/**
 * 归一化器：一次一批，返回与入参**等长、按索引对齐**的结果（P3 计划里的「平行契约」原文）。
 *
 * 装饰链 `Caching(Retrying(Direct))` 的三层都实现它，于是缓存与退避对上层完全透明。
 *
 * **落点说明**：P3 计划把这个接口记在 P1 名下，但 P1 当时只做了协议编解码
 * （`DirectProvider.execute`），没建接口也没有实现类。此处补上接口本体；
 * 具体实现（把 PromptBuilder / 适配器 / NormalizeParser 串起来）落在 P3-T7 的工厂里 ——
 * 那里才拿得到 P2 的产物。
 */
interface LlmNormalizer {

    suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome>
}
