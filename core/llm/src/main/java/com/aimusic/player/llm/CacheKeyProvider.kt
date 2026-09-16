package com.aimusic.player.llm

import java.security.MessageDigest

/**
 * 缓存 key 的组成（spec §8 / 05 §4.8 / 02 §5.3）：
 *
 * ```
 * key = sha256(fileName ␟ 元数据指纹 ␟ model ␟ prompt 文件哈希 ␟ 目录指纹)
 * ```
 *
 * 五段以 U+001F（`␟`）连接：它几乎不会出现在文件名/模型名里，用来消除「拼接歧义」
 * （朴素的 `a|b` + `c` 与 `a` + `b|c` 会撞成同一串）。
 *
 * - 元数据指纹 = title ␟ artist ␟ album ␟ durationMs；元数据被改过应当重算。
 * - prompt 文件哈希：由 P2 的 `PromptBuilder.promptHash()` 提供，**取代**手工 PROMPT_VERSION。
 * - 目录指纹：由 P2 的 `DirectoryFingerprint.of(categories, tags)` 适配提供，纳入当前有效分类 + 标签 ——
 *   否则用户新建分类后同一文件会命中旧缓存、返回的标签里永远缺该分类。
 */
object CacheKeyProvider {

    private const val FIELD_SEP = "\u001F"

    fun keyFor(
        request: NormalizeRequest,
        model: String,
        promptHash: String,
        dirFingerprint: String,
    ): String {
        val metadataFingerprint = request.metadata?.let { m ->
            listOf(m.title, m.artist, m.album, m.durationMs)
                .joinToString(FIELD_SEP) { it?.toString() ?: "" }
        } ?: ""
        val raw = listOf(request.fileName, metadataFingerprint, model, promptHash, dirFingerprint)
            .joinToString(FIELD_SEP)
        return sha256Hex(raw)
    }
}

/** 小写十六进制 sha256。 */
internal fun sha256Hex(raw: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }