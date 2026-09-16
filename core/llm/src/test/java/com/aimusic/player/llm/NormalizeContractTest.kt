package com.aimusic.player.llm

import com.aimusic.player.common.log.LogEvent
import com.aimusic.player.common.log.LogLevel
import com.aimusic.player.common.model.AudioMetadata
import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.llm.log.NoopLogger
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NormalizeContractTest {

    @Test
    fun `TagRef 是两字段且按值相等`() {
        assertThat(TagRef("摇滚", "风格")).isEqualTo(TagRef("摇滚", "风格"))
        assertThat(TagRef("摇滚", "风格")).isNotEqualTo(TagRef("摇滚", "情绪"))
    }

    @Test
    fun `NormalizeRequest 允许 metadata 为 null`() {
        val request = NormalizeRequest(
            fileName = "a.flac",
            metadata = null,
            categories = listOf("风格"),
            tags = emptyList(),
        )
        assertThat(request.metadata).isNull()
    }

    @Test
    fun `NormalizeRequest 能带上已迁入 common 的 AudioMetadata`() {
        val metadata = AudioMetadata("标题", "歌手", null, null, null, 1_000L, false)
        val request = NormalizeRequest("a.flac", metadata, emptyList(), emptyList())

        assertThat(request.metadata?.title).isEqualTo("标题")
        assertThat(request.metadata?.durationMs).isEqualTo(1_000L)
    }

    @Test
    fun `NormalizeOutcome 三态互不混淆`() {
        val success: NormalizeOutcome =
            NormalizeOutcome.Success(NormalizeResult("标题", listOf("歌手"), emptyList()))
        val limited: NormalizeOutcome = NormalizeOutcome.RateLimited(retryAfterMs = 2_000L)
        val failure: NormalizeOutcome = NormalizeOutcome.Failure(LlmFailureKind.NETWORK, null)

        assertThat(success).isInstanceOf(NormalizeOutcome.Success::class.java)
        assertThat((limited as NormalizeOutcome.RateLimited).retryAfterMs).isEqualTo(2_000L)
        assertThat((failure as NormalizeOutcome.Failure).kind).isEqualTo(LlmFailureKind.NETWORK)
    }

    @Test
    fun `RateLimited 允许没有 Retry_After`() {
        assertThat(NormalizeOutcome.RateLimited(null).retryAfterMs).isNull()
    }

    @Test
    fun `条目级失败的异常带得出是哪一条`() {
        assertThat(MissingField("canonical_title").field).isEqualTo("canonical_title")
        assertThat(DuplicateIndex(3).fileIndex).isEqualTo(3)
        assertThat(MissingField("x")).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `NoopLogger 什么都不做也不抛`() {
        assertThat(NoopLogger.isEnabled(LogLevel.DEBUG)).isFalse()

        NoopLogger.debug(LogEvent.LLM_REQUEST, "x")
        NoopLogger.info(LogEvent.ANALYSIS_FILE_OK, "x")
        NoopLogger.warn(LogEvent.UNEXPECTED, "x")
        NoopLogger.error(LogEvent.UNEXPECTED, "x")

        assertThat(NoopLogger.recent(10)).isEmpty()
    }
}
