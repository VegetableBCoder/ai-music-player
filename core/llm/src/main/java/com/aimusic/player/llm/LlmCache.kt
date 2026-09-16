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