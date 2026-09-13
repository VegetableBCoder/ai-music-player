package com.aimusic.player.common.error

import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.NoSuchFileException
import javax.net.ssl.SSLException
import org.junit.Test

/**
 * 来源 → `AppError` 的映射表（`11 §3.2.1`）。
 * 这里每条断言都对应映射总表里的一行，避免"某个状态码悄悄归到 UNKNOWN"。
 */
class ErrorMapperTest {

    @Test
    fun `HTTP 401 与 403 映射为 AUTH`() {
        val unauthorized = ErrorMapper.fromHttp(401)
        val forbidden = ErrorMapper.fromHttp(403)

        assertThat(unauthorized.kind).isEqualTo(FailureKind.AUTH)
        assertThat((unauthorized as AuthError).httpStatus).isEqualTo(401)
        assertThat((forbidden as AuthError).httpStatus).isEqualTo(403)
    }

    @Test
    fun `HTTP 408 与 504 映射为 NETWORK 超时`() {
        listOf(408, 504).forEach { status ->
            val error = ErrorMapper.fromHttp(status)

            assertThat(error.kind).isEqualTo(FailureKind.NETWORK)
            assertThat((error as NetworkError).reason).isEqualTo(NetworkReason.TIMEOUT)
        }
    }

    @Test
    fun `HTTP 429 映射为 RATE_LIMIT 并采用 Retry-After`() {
        val withoutHeader = ErrorMapper.fromHttp(429) as RateLimitError

        assertThat(withoutHeader.source).isEqualTo(RateLimitSource.HTTP_429)
        assertThat(withoutHeader.retryAfterMs).isEqualTo(1_000L)
        assertThat(withoutHeader.attempt).isEqualTo(1)

        val withHeader = ErrorMapper.fromHttp(429, retryAfterMs = 5_000) as RateLimitError

        assertThat(withHeader.retryAfterMs).isEqualTo(5_000L)
    }

    @Test
    fun `HTTP 5xx 映射为 SERVER 并保留 requestId`() {
        val error = ErrorMapper.fromHttp(503, requestId = "req-42")

        assertThat(error.kind).isEqualTo(FailureKind.SERVER)
        assertThat((error as ServerError).httpStatus).isEqualTo(503)
        assertThat(error.requestId).isEqualTo("req-42")
    }

    @Test
    fun `其它 4xx 归 UNKNOWN，需人工排查`() {
        listOf(400, 404, 422).forEach { status ->
            val error = ErrorMapper.fromHttp(status)

            assertThat(error.kind).isEqualTo(FailureKind.UNKNOWN)
            assertThat((error as UnknownError).message).isEqualTo("HTTP $status")
        }
    }

    @Test
    fun `IOException 家族逐一映射`() {
        assertThat(ErrorMapper.fromIo(FileNotFoundException()).kind)
            .isEqualTo(FailureKind.NOT_FOUND)
        assertThat(ErrorMapper.fromIo(NoSuchFileException("/a")).kind)
            .isEqualTo(FailureKind.NOT_FOUND)

        assertThat((ErrorMapper.fromIo(SocketTimeoutException()) as NetworkError).reason)
            .isEqualTo(NetworkReason.TIMEOUT)
        assertThat((ErrorMapper.fromIo(UnknownHostException()) as NetworkError).reason)
            .isEqualTo(NetworkReason.DNS)
        assertThat((ErrorMapper.fromIo(ConnectException()) as NetworkError).reason)
            .isEqualTo(NetworkReason.UNREACHABLE)
        assertThat((ErrorMapper.fromIo(SSLException("tls")) as NetworkError).reason)
            .isEqualTo(NetworkReason.TLS)

        val io = ErrorMapper.fromIo(IOException(), IoOperation.READ_METADATA)
        assertThat(io.kind).isEqualTo(FailureKind.IO)
        assertThat((io as IoError).op).isEqualTo(IoOperation.READ_METADATA)

        assertThat(ErrorMapper.fromIo(IllegalArgumentException()).kind)
            .isEqualTo(FailureKind.UNKNOWN)
    }

    @Test
    fun `fromParse 用目标类型标记来源，并脱敏截断到 256`() {
        // 尾部必须用空格与密钥隔开：API_KEY 的字符类含大小写字母，
        // 直接跟字母会让贪婪匹配把整段尾部一起吞掉，测不出截断行为。
        val raw = "sk-SECRETVALUE " + "y".repeat(400)

        val error = ErrorMapper.fromParse(IllegalStateException("boom"), ParseTarget.LLM_JSON, raw)

        assertThat(error.kind).isEqualTo(FailureKind.PARSE)
        val parseError = error as ParseError
        assertThat(parseError.target).isEqualTo(ParseTarget.LLM_JSON)
        assertThat(parseError.snippet).hasLength(256)
        assertThat(parseError.snippet).doesNotContain("SECRETVALUE")
    }

    @Test
    fun `fromParse 对 null 原文给出空 snippet`() {
        val error = ErrorMapper.fromParse(IllegalStateException("boom"), ParseTarget.LRC, null)

        assertThat((error as ParseError).snippet).isNull()
    }

    @Test
    fun `fromSqliteMessage 命中约束转 CONFLICT`() {
        val error = ErrorMapper.fromSqliteMessage("UNIQUE constraint failed: tag.name")

        assertThat(error.kind).isEqualTo(FailureKind.CONFLICT)
        val conflict = error as ConflictError
        assertThat(conflict.conflict).isEqualTo(ConflictType.DUPLICATE_TAG)
        assertThat(conflict.detail).isEqualTo("tag.name")
    }

    @Test
    fun `fromSqliteMessage 未命中约束转 IO，不猜语义`() {
        val error = ErrorMapper.fromSqliteMessage("disk I/O error", op = IoOperation.DB)

        assertThat(error.kind).isEqualTo(FailureKind.IO)
        assertThat((error as IoError).op).isEqualTo(IoOperation.DB)
    }
}
