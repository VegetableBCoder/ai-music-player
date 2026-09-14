package com.aimusic.player.storage

/** 扩展名白名单（`04 §4.3`）。音频与歌词分开，调用方按需要哪一组传参。 */
object AudioFormats {

    val AUDIO_EXTS = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "wma", "ape")

    val LRC_EXTS = setOf("lrc")

    /**
     * 取小写扩展名；无扩展名返回空串。
     *
     * `lastIndexOf` 而不是 `substringAfterLast` 的差别在 `trailing.` 这种以点结尾的名字：
     * 前者给空串（正确），后者的边界要靠调用方自己再判一次。
     */
    fun formatOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }
}
