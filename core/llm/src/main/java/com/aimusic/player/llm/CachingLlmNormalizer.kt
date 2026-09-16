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