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