package com.aimusic.player.llm.cache

import com.aimusic.player.llm.TagRef
import com.aimusic.player.common.text.TextNormalizer
import com.aimusic.player.llm.crypto.sha256Hex

/**
 * 目录指纹：`sha256(规范化分类名串 ␞ 规范化标签名串)`，参与缓存 key（spec §8）。
 *
 * 原 key **漏了**这一项：用户新建分类后，同一文件会命中旧缓存、返回的标签里永远缺这个新分类，
 * 且常量版 `PROMPT_VERSION` 救不了。规范化与排序都用 `TextNormalizer` + 字典序，
 * 保证「同一份目录的任意遍历顺序」得到同一个指纹（否则顺序抖动会白扔缓存）。
 */
object DirectoryFingerprint {

    /** 分类组与标签组之间的分隔（U+001E）。 */
    private const val GROUP_SEPARATOR = "\u001E"

    /** 组内条目之间的分隔（U+001F，与 `artistsKey` 同一约定）。 */
    private const val ITEM_SEPARATOR = "\u001F"

    fun of(categories: List<String>, tags: List<TagRef>): String {
        val categoryPart = categories
            .map(TextNormalizer::normalizeToken)
            .distinct()
            .sorted()
            .joinToString(ITEM_SEPARATOR)

        val tagPart = tags
            .map { TextNormalizer.normalizeToken(it.category) + ":" + TextNormalizer.normalizeToken(it.name) }
            .distinct()
            .sorted()
            .joinToString(ITEM_SEPARATOR)

        return sha256Hex(categoryPart + GROUP_SEPARATOR + tagPart)
    }
}
