package com.aimusic.player.common.log

import com.aimusic.player.common.error.AppError
import java.security.MessageDigest

object Redactor {
    private val API_KEY   = Regex("""sk-[A-Za-z0-9_\-]{4,}""")               // OpenAI 风格
    private val BEARER    = Regex("""(?i)bearer\s+[A-Za-z0-9._\-]+""")
    private val QUERY_KEY = Regex("""(?i)(api[_-]?key|access[_-]?token)=[^&\s]+""")
    private val HASH10    = Regex("""\b[A-Fa-f0-9]{24,}\b""")                 // 疑似长密钥 / token

    /** API Key 一律 sk-**** */
    fun apiKey(s: String): String =
        s.replace(API_KEY, "sk-****").replace(BEARER, "Bearer sk-****")
            .replace(QUERY_KEY, "${'$'}1=sk-****").replace(HASH10, "****")

    /** 文件标识：⟨sourceKind⟩:文件名#hash8，不含目录结构 */
    fun digest(sourceKind: String, name: String, path: String): String =
        "$sourceKind:$name#${sha1(path).take(8)}"

    /** 错误详情：先脱敏再截断 */
    fun detail(error: AppError, limit: Int = 512): String =
        apiKey(error.cause?.toString() ?: error.kind.name).take(limit)

    fun snippet(raw: String?, limit: Int): String? = raw?.let { apiKey(it).take(limit) }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
