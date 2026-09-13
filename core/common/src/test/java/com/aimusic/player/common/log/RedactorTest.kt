package com.aimusic.player.common.log

import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.common.error.UnknownError
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 脱敏是硬约束（`01 §3.1`、`03 §7`）：日志与诊断导出里**不允许**出现 API Key
 * 和用户文件的绝对路径。这里逐条钉死。
 */
class RedactorTest {

    @Test
    fun `OpenAI 风格的 key 一律被替换`() {
        assertThat(Redactor.apiKey("sk-abcdefgh12345")).isEqualTo("sk-****")
    }

    @Test
    fun `Authorization 头里的 key 不会残留`() {
        val masked = Redactor.apiKey("Authorization: Bearer sk-abcdefgh12345")

        assertThat(masked).doesNotContain("abcdefgh12345")
        assertThat(masked).contains("sk-****")
    }

    @Test
    fun `URL query 里的 key 不会残留，其余参数照旧`() {
        val masked = Redactor.apiKey("https://api.example.com/v1?api_key=SECRET123&stream=true")

        assertThat(masked).doesNotContain("SECRET123")
        assertThat(masked).contains("api_key=sk-****")
        assertThat(masked).contains("stream=true")
    }

    @Test
    fun `疑似长 hex 令牌被替换`() {
        assertThat(Redactor.apiKey("abcd1234abcd1234abcd1234abcd1234abcd1234"))
            .isEqualTo("****")
    }

    @Test
    fun `digest 只含来源与文件名，不含目录结构`() {
        val digest = Redactor.digest("LOCAL", "song.mp3", "/sdcard/secret/dir/song.mp3")

        assertThat(digest).isEqualTo("LOCAL:song.mp3#ab03f01f")
        assertThat(digest).doesNotContain("/sdcard")
        assertThat(digest).doesNotContain("secret")
        assertThat(digest).doesNotContain("dir")
    }

    @Test
    fun `digest 对同一路径稳定、对不同路径可区分`() {
        val a = Redactor.digest("LOCAL", "song.mp3", "/sdcard/secret/dir/song.mp3")
        val b = Redactor.digest("LOCAL", "song.mp3", "/other/song.mp3")

        assertThat(a).isEqualTo(Redactor.digest("LOCAL", "song.mp3", "/sdcard/secret/dir/song.mp3"))
        assertThat(b).isEqualTo("LOCAL:song.mp3#9ba1cb93")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `detail 脱敏 cause`() {
        val error = UnknownError(cause = IllegalStateException("token sk-abcdefgh12345"))

        val detail = Redactor.detail(error)

        assertThat(detail).doesNotContain("abcdefgh12345")
        assertThat(detail).contains("sk-****")
    }

    @Test
    fun `detail 按上限截断`() {
        val error = UnknownError(cause = IllegalStateException("x".repeat(600)))

        assertThat(Redactor.detail(error, limit = 512)).hasLength(512)
    }

    @Test
    fun `detail 没有 cause 时回落为 kind 名`() {
        assertThat(Redactor.detail(UnknownError())).isEqualTo(FailureKind.UNKNOWN.name)
    }

    @Test
    fun `snippet 先脱敏再截断，不会把密钥切断后漏出去`() {
        // 若先截断再脱敏，"sk-SECRETVALUE" 会被切成 "sk-SE"（不足 4 字符），
        // 脱敏规则随即失效，密钥片段就写进日志了。
        assertThat(Redactor.snippet("xxxxxxxxxxsk-SECRETVALUE", limit = 15))
            .isEqualTo("xxxxxxxxxxsk-**")
    }

    @Test
    fun `snippet 对 null 返回 null`() {
        assertThat(Redactor.snippet(null, limit = 16)).isNull()
    }
}
