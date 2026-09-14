package com.aimusic.player.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 扩展名白名单（`04 §4.3`）。 */
class AudioFormatsTest {

    @Test
    fun `音频白名单就是文档锁定的那八种`() {
        assertThat(AudioFormats.AUDIO_EXTS)
            .containsExactly("mp3", "flac", "m4a", "aac", "ogg", "wav", "wma", "ape")
    }

    @Test
    fun `歌词白名单只有_lrc`() {
        assertThat(AudioFormats.LRC_EXTS).containsExactly("lrc")
    }

    @Test
    fun `大小写不敏感且取出小写`() {
        assertThat(AudioFormats.formatOf("晴天.MP3")).isEqualTo("mp3")
        assertThat(AudioFormats.formatOf("Track.FlAc")).isEqualTo("flac")
    }

    @Test
    fun `取最后一段扩展名_不误取目录或名字里的点`() {
        assertThat(AudioFormats.formatOf("a.b.c.mp3")).isEqualTo("mp3")
        assertThat(AudioFormats.formatOf("feat. 袁咏琳.flac")).isEqualTo("flac")
    }

    @Test
    fun `没有扩展名时返回空串而不是崩`() {
        assertThat(AudioFormats.formatOf("README")).isEqualTo("")
        assertThat(AudioFormats.formatOf("trailing.")).isEqualTo("")
    }
}
