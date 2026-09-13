package com.aimusic.player.common.error

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 断言 `AppError` 覆盖 `02 §8` 的全部 `FailureKind`。
 *
 * 这些测试守的是"契约完整性"：以后有人往 `FailureKind` 里加一项却忘了加子类，
 * 或者加子类时写错了 `kind`，都会被这里拦下。
 */
class AppErrorTest {

    /** 每个 `FailureKind` 的最小可构造样本。 */
    private val samples: Map<FailureKind, AppError> = mapOf(
        FailureKind.PERMISSION to PermissionError(PermissionScope.ALL_FILES),
        FailureKind.NETWORK to NetworkError(NetworkReason.TIMEOUT),
        FailureKind.RATE_LIMIT to
            RateLimitError(RateLimitSource.HTTP_429, retryAfterMs = 1_000, attempt = 1),
        FailureKind.AUTH to AuthError(httpStatus = 401),
        FailureKind.SERVER to ServerError(httpStatus = 503),
        FailureKind.PARSE to ParseError(ParseTarget.LLM_JSON),
        FailureKind.IO to IoError(IoOperation.SCAN),
        FailureKind.NOT_FOUND to NotFoundError(MissingEntity.MUSIC_FILE),
        FailureKind.CONFLICT to ConflictError(ConflictType.DUPLICATE_TAG),
        FailureKind.UNKNOWN to UnknownError(),
    )

    @Test
    fun `FailureKind 每一项都有子类覆盖`() {
        assertThat(samples.keys).containsExactlyElementsIn(FailureKind.entries)
    }

    @Test
    fun `子类上报的 kind 与它所属的 FailureKind 一致`() {
        samples.forEach { (kind, error) ->
            assertThat(error.kind).isEqualTo(kind)
        }
    }

    @Test
    fun `只有 RATE_LIMIT 允许携带重试时长`() {
        samples
            .filterKeys { it != FailureKind.RATE_LIMIT }
            .forEach { (_, error) ->
                assertThat(error.retryAfterMs).isNull()
            }

        assertThat(samples.getValue(FailureKind.RATE_LIMIT).retryAfterMs).isEqualTo(1_000L)
    }
}
