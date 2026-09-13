package com.aimusic.player.common.text

import java.text.Normalizer

/**
 * 实体身份键的**唯一实现**（`00 §1.2`，落地见 `08 §4.2`）；`03 §4.1` 的 `::normalize`
 * 也指向它。纯 Kotlin、无 Android 依赖，可纯 JVM 单测。
 *
 * 版本语义词（Live / Remix / 伴奏 / 现场 / 翻唱 / 原唱 / Instrumental）**不剥离**：
 * 它们用于区分不同录音，`晴天 (Live)` 与 `晴天` 必须归为不同实体（`00 §1.2`）。
 */
object TextNormalizer {

    private val WHITESPACE = Regex("\\s+")

    // 全角/半角统一用 NFKC；大小写折叠用 lowercase；基础拉丁 + CJK 通用
    private val PUNCT_SPACES = Regex("\\s*([()\\[\\]（）【】\\-–—:：,，、/&])\\s*")

    // 轻量演绎实体身份键的「归一化演唱者」：分隔符与角色词（00 §1.2）
    //
    // 与 08 §4.2 的代码有一处修正：原文用尾部 `\b` 收口，但 `feat\.?` 一旦吃掉句点，
    // 句点与后面的空格之间不存在词边界，`\b` 必然失败；引擎会退化成只匹配 `feat`，
    // 句点就漏进下一个演唱者名（"Jay Chou feat. 袁咏琳" → [". 袁咏琳", "jay chou"]）。
    // 改用负向前瞻 `(?!\w)`：既能让 `feat` / `feat.` 都正确收口，又仍然不会误伤
    // `featuring` 这类以角色词开头的完整单词。
    private val ARTIST_SPLIT = Regex(
        """\s*(?:[&/;、,，]|\b(?:feat|ft|with|vs)\.?(?!\w))\s*""",
        RegexOption.IGNORE_CASE
    )

    fun normalizeToken(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC) // 全角→半角
        val lowered = nfkc.lowercase()                             // 统一大小写
        val collapsed = WHITESPACE.replace(lowered, " ").trim()     // 去首尾空格 + 折叠
        return PUNCT_SPACES.replace(collapsed) { it.groupValues[1] } // 去掉标点两侧空格
    }

    /** 版本语义词「不剥离」，原样保留。 */
    fun normalizeTitle(raw: String): String = normalizeToken(raw)

    fun splitArtists(raw: String): List<String> =
        ARTIST_SPLIT.split(raw).map(::normalizeToken).filter { it.isNotEmpty() }.distinct().sorted()

    /** 归一化演唱者集合：逐个归一、去重、按 Unicode 码位排序。 */
    fun normalizeArtists(artists: List<String>): List<String> =
        artists.map(::normalizeToken).distinct().sorted()

    /** 与 `song_entity.artists_key` 同构：排序去重后以 U+001F 连接。 */
    fun artistsKey(artists: List<String>): String =
        normalizeArtists(artists).joinToString("\u001F")
}
