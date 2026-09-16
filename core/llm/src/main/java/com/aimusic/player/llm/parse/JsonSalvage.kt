package com.aimusic.player.llm.parse

/**
 * L3 的容错抽取（spec §4 表末行）：先剥 ``` 围栏，再截取首个 `{` 到末个 `}`。
 *
 * 它不负责"是不是合法 JSON"——那由 `NormalizeParser` 判定失败（整批 `INVALID_OUTPUT`）。
 */
object JsonSalvage {

    /** 开头 ``` 可选语言标记，结尾 ```，两侧空白一并吃掉。 */
    private val FENCE = Regex("^```[A-Za-z0-9_-]*\\s*|\\s*```$")

    /** 剥掉 ``` / ```json 围栏。 */
    fun stripFences(raw: String): String = raw.trim().replace(FENCE, "").trim()

    fun toJsonText(raw: String): String {
        val unfenced = stripFences(raw)
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        return if (start >= 0 && end > start) unfenced.substring(start, end + 1) else unfenced
    }
}