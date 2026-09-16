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
