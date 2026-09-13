package com.aimusic.player.common.error

import com.aimusic.player.common.log.Redactor
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.NoSuchFileException
import javax.net.ssl.SSLException

object ErrorMapper {
    const val MAX_SNIPPET = 256

    /** HTTP 响应 → AppError（BYOK 直连与未来 Gateway 共用） */
    fun fromHttp(
        status: Int,
        retryAfterMs: Long? = null,
        requestId: String? = null,
    ): AppError = when {
        status == 401 || status == 403 -> AuthError(status)
        status == 408 || status == 504 -> NetworkError(NetworkReason.TIMEOUT)
        status == 429 -> RateLimitError(RateLimitSource.HTTP_429, retryAfterMs ?: 1_000, attempt = 1)
        status in 500..599 -> ServerError(status, requestId)
        else -> UnknownError("HTTP $status")        // 其余 4xx 归 UNKNOWN，需人工排查
    }

    /** IOException 家族 → AppError */
    fun fromIo(t: Throwable, op: IoOperation = IoOperation.SCAN): AppError = when (t) {
        is FileNotFoundException, is NoSuchFileException ->
            NotFoundError(MissingEntity.MUSIC_FILE, cause = t)
        is SocketTimeoutException -> NetworkError(NetworkReason.TIMEOUT, t)
        is UnknownHostException -> NetworkError(NetworkReason.DNS, t)
        is ConnectException -> NetworkError(NetworkReason.UNREACHABLE, t)
        is SSLException -> NetworkError(NetworkReason.TLS, t)
        is IOException -> IoError(op, cause = t)
        else -> UnknownError(cause = t)
    }

    /** 解析异常（LLM JSON / LRC / 元数据） → PARSE */
    fun fromParse(t: Throwable, target: ParseTarget, raw: String?): AppError =
        ParseError(target, Redactor.snippet(raw, MAX_SNIPPET), t)

    /**
     * SQLite 约束报文 → 默认 `CONFLICT`，交由数据层再翻译（`11 §6`）。
     *
     * 这里收的是报文而非 `SQLiteConstraintException`：后者是 Android 类型，
     * 会让这个类无法纯 JVM 单测。`SQLiteConstraintException` → 业务语义的适配
     * 由 `:core:data` 的 `DbErrorTranslator` 负责（`11 §2`）。
     */
    fun fromSqliteMessage(
        message: String?,
        op: IoOperation = IoOperation.DB,
        cause: Throwable? = null,
    ): AppError =
        SqliteConstraint.parse(message)?.let { ConflictError(it.type, it.detail, cause) }
            ?: IoError(op, cause = cause)
}
