package com.aimusic.player.common.error

/** 02 号文档 §8 定义，10 类，不得新增 / 改名。 */
enum class FailureKind {
    PERMISSION, NETWORK, RATE_LIMIT, AUTH, SERVER, PARSE, IO, NOT_FOUND, CONFLICT, UNKNOWN
}

/**
 * 全应用统一错误模型。
 * - cause          : 底层异常，仅用于日志与诊断，**绝不直接展示给用户**。
 * - retryAfterMs   : 仅 RATE_LIMIT 允许非空；其余恒为 null。
 * - userMessageKey : 指向 ErrorText 的文案键；null 表示由调用方按上下文补默认文案。
 */
sealed interface AppError {
    val kind: FailureKind
    val cause: Throwable?
    val retryAfterMs: Long?
    val userMessageKey: String?
}

enum class PermissionScope { ALL_FILES, READ_MEDIA_AUDIO, POST_NOTIFICATIONS }
data class PermissionError(
    val scope: PermissionScope,
    override val cause: Throwable? = null,
    override val retryAfterMs: Long? = null,
    override val userMessageKey: String? = "perm.denied",
) : AppError { override val kind get() = FailureKind.PERMISSION }

enum class NetworkReason { UNREACHABLE, TIMEOUT, DNS, TLS }
data class NetworkError(
    val reason: NetworkReason,
    override val cause: Throwable? = null,
    override val retryAfterMs: Long? = null,
    override val userMessageKey: String? = "net.error",
) : AppError { override val kind get() = FailureKind.NETWORK }

enum class RateLimitSource { HTTP_429, LOCAL_BACKOFF }
data class RateLimitError(
    val source: RateLimitSource,
    override val retryAfterMs: Long?,           // 唯一允许非空的重试时长
    val attempt: Int,                            // 第几次尝试，1 起
    override val cause: Throwable? = null,
    override val userMessageKey: String? = "llm.retrying",
) : AppError { override val kind get() = FailureKind.RATE_LIMIT }

data class AuthError(
    val httpStatus: Int?,                        // 401 / 403
    override val cause: Throwable? = null,
    override val retryAfterMs: Long? = null,
    override val userMessageKey: String? = "llm.auth",
) : AppError { override val kind get() = FailureKind.AUTH }

data class ServerError(
    val httpStatus: Int,                         // 5xx
    val requestId: String? = null,               // 服务端回传的 x-request-id（可选）
    override val cause: Throwable? = null,
    override val retryAfterMs: Long? = null,
    override val userMessageKey: String? = "llm.server",
) : AppError { override val kind get() = FailureKind.SERVER }

enum class ParseTarget { LLM_JSON, LRC, METADATA }
data class ParseError(
    val target: ParseTarget,
    val snippet: String? = null,                 // 已脱敏 + 截断（≤256 字符）
    override val cause: Throwable? = null,
    override val retryAfterMs: Long? = null,
    override val userMessageKey: String? = "llm.parse",
) : AppError { override val kind get() = FailureKind.PARSE }

enum class IoOperation { SCAN, READ_METADATA, READ_COVER, DELETE_FILE, WRITE_CACHE, DB }
data class IoError(
    val op: IoOperation,
    val pathDigest: String? = null,              // 脱敏文件标识 name#hash8（§3.5）
    override val cause: Throwable? = null,
    override val retryAfterMs: Long? = null,
    override val userMessageKey: String? = "io.error",
) : AppError { override val kind get() = FailureKind.IO }

enum class MissingEntity { MUSIC_FILE, LYRIC_FILE, QUEUE_ITEM, SONG }
data class NotFoundError(
    val entity: MissingEntity,
    val pathDigest: String? = null,
    override val cause: Throwable? = null,
    override val retryAfterMs: Long? = null,
    override val userMessageKey: String? = "file.missing",
) : AppError { override val kind get() = FailureKind.NOT_FOUND }

enum class ConflictType {
    DUPLICATE_FILE, DUPLICATE_LYRIC, DUPLICATE_TAG,
    DUPLICATE_ENTITY, TAG_HAS_SONGS, CATEGORY_HAS_TAGS, DUPLICATE_SOURCE
}
data class ConflictError(
    val conflict: ConflictType,
    val detail: String? = null,
    override val cause: Throwable? = null,
    override val retryAfterMs: Long? = null,
    override val userMessageKey: String? = null, // 由 ConflictType 决定，见 §5
) : AppError { override val kind get() = FailureKind.CONFLICT }

data class UnknownError(
    val message: String? = null,
    override val cause: Throwable? = null,
    override val retryAfterMs: Long? = null,
    override val userMessageKey: String? = "unknown",
) : AppError { override val kind get() = FailureKind.UNKNOWN }
