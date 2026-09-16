package com.aimusic.player.llm.parse

/**
 * L3（纯提示词档）的容错抽取（spec §4 表末行）。
 *
 * 这一档模型没被任何机器可校验的约束框住，可能加围栏、加前后解释，所以：
 * 1. 去掉首尾空白；
 * 2. 若以 ``` 围栏开头，剥掉围栏行与结尾围栏；
 * 3. 截**首个** `{` 到**末个** `}`。
 *
 * 找不到花括号时**返回第 1 步的文本而不是空串** —— 让 parser 拿着模型的原文去判 INVALID_OUTPUT，
 * 否则日志里只剩"没有 JSON"，排查时看不到模型实际吐了什么。
 */
object JsonSalvage {

    fun toJsonText(raw: String): String {
        var text = raw.trim()

        if (text.startsWith(FENCE)) {
            text = text.removePrefix(FENCE)
            text = text.substringAfter('\n', missingDelimiterValue = text)
            text = text.removeSuffix(FENCE).trim()
        }

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }

    private const val FENCE = "```"
}
