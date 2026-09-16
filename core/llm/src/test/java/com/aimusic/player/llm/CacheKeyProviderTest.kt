package com.aimusic.player.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CacheKeyProviderTest {

    // 元数据用 AudioMetadata —— 已拍板（决定 #1）：位于 com.aimusic.player.common.model。
    private fun req(
        fileName: String = "周杰伦 - 晴天.flac",
        metadata: com.aimusic.player.common.model.AudioMetadata? = null,
    ) = NormalizeRequest(
        fileName = fileName,
        metadata = metadata,
        categories = listOf("音乐类型", "情绪"),
        tags = listOf(TagRef(name = "流行", category = "音乐类型")),
    )

    private fun key(
        request: NormalizeRequest = req(),
        model: String = "gpt-4o-mini",
        promptHash: String = "p-hash",
        dirFingerprint: String = "d-hash",
    ) = CacheKeyProvider.keyFor(request, model, promptHash, dirFingerprint)

    @Test
    fun `key 是 64 位小写十六进制 sha256`() {
        val k = key()

        assertThat(k).hasLength(64)
        assertThat(k).matches("[0-9a-f]{64}")
    }

    @Test
    fun `同输入同 key`() {
        assertThat(key()).isEqualTo(key())
    }

    @Test
    fun `文件名、model、prompt 哈希、目录指纹任一变化都改变 key`() {
        val base = key()

        assertThat(key(request = req(fileName = "别的.flac"))).isNotEqualTo(base)
        assertThat(key(model = "deepseek-chat")).isNotEqualTo(base)
        assertThat(key(promptHash = "p-hash-2")).isNotEqualTo(base)   // 覆盖 G9 的核心
        assertThat(key(dirFingerprint = "d-hash-2")).isNotEqualTo(base) // 覆盖 G10 的核心
    }

    @Test
    fun `元数据指纹参与 key 且与无元数据不同`() {
        val withMeta = req(
            metadata = com.aimusic.player.common.model.AudioMetadata(
                title = "晴天", artist = "周杰伦", album = "叶惠美",
                albumArtist = "周杰伦", date = "2003-07-31",
                durationMs = 269_000, hasEmbeddedPicture = false,
            ),
        )

        assertThat(key(request = withMeta)).isNotEqualTo(key(request = req()))
    }

    @Test
    fun `五段用 U+001F 连接，避免拼接歧义`() {
        // 朴素拼接下 ("x","yz") 与 ("xy","z") 会撞成同一串；分隔符能避免。
        val a = key(request = req(fileName = "x"), promptHash = "yz")
        val b = key(request = req(fileName = "xy"), promptHash = "z")

        assertThat(a).isNotEqualTo(b)
    }
}