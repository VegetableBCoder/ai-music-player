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