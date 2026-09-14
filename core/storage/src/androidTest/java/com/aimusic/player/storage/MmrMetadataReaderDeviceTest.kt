package com.aimusic.player.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `MmrMetadataReader` 的真机冒烟（`04 §4.4`）。
 *
 * JVM 上测过的是**逻辑**（回退顺序、降级、超时作废、实例独占），用假 retriever。
 * 这里只回答一件事：**真实的 `MediaMetadataRetriever` 能不能读懂我们的文件**。
 * 夹具是 `tools/gen-audio-fixtures.py` 合成的最小合法 MP3（ID3v2.3 + 38 个静音帧）。
 */
@RunWith(AndroidJUnit4::class)
class MmrMetadataReaderDeviceTest {

    private lateinit var dir: File
    private lateinit var reader: MmrMetadataReader

    @Before
    fun setUp() {
        dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "mmr-fixture",
        )
        dir.mkdirs()
        reader = MmrMetadataReader(parallelism = 2, timeoutMs = 10_000)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun asset(name: String): File {
        val target = File(dir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun ref(file: File) = FileRef(file.absolutePath, file.name, file.length(), file.lastModified())

    @Test
    fun `MMR_能读出合成夹具的标签`() {
        val meta = reader.read(ref(asset("tagged.mp3")))

        assertThat(meta.title).isEqualTo("Sunny Day")
        assertThat(meta.artist).isEqualTo("Jay Chou")
        assertThat(meta.album).isEqualTo("Ye Hui Mei")
        assertThat(meta.albumArtist).isEqualTo("Jay Chou")
        // ID3v2.3 只有 TYER（没有 TDRC），正好走 date ?: year 这条回退
        assertThat(meta.date).isEqualTo("2003")
        assertThat(meta.hasEmbeddedPicture).isFalse()
    }

    @Test
    fun `MMR_能从我们的码流推出时长`() {
        val meta = reader.read(ref(asset("tagged.mp3")))

        // 合成的是 38 个静音帧 ≈ 0.99 秒。这里只要求「> 0」：
        // 具体数值取决于 MMR 的推算方式，钉死它等于把库的实现细节写成我们的契约
        assertThat(meta.durationMs).isGreaterThan(0L)
    }

    @Test
    fun `内嵌封面被识别_且中文标签按_UTF16_读回`() {
        val meta = reader.read(ref(asset("tagged-cover.mp3")))

        assertThat(meta.hasEmbeddedPicture).isTrue()
        assertThat(meta.title).isEqualTo("晴天")
        assertThat(meta.artist).isEqualTo("周杰伦")
        assertThat(meta.album).isEqualTo("叶惠美")
    }

    @Test
    fun `坏文件降级_标题取文件名且不抛异常`() {
        val broken = File(dir, "坏文件.mp3").apply { writeBytes(ByteArray(4096) { 0x2A }) }

        val meta = reader.read(ref(broken))

        assertThat(meta.title).isEqualTo("坏文件")
        assertThat(meta.artist).isNull()
        assertThat(meta.durationMs).isEqualTo(0L)
        assertThat(meta.hasEmbeddedPicture).isFalse()
    }

    @Test
    fun `不存在的文件也走降级_不把异常抛给调用方`() {
        val missing = File(dir, "并没有这个文件.mp3")

        val meta = reader.read(ref(missing))

        assertThat(meta.title).isEqualTo("并没有这个文件")
        assertThat(meta.durationMs).isEqualTo(0L)
    }

    @Test
    fun `连续读多个文件后仍能正常工作`() {
        val a = asset("tagged.mp3")
        val b = asset("tagged-cover.mp3")

        repeat(3) {
            assertThat(reader.read(ref(a)).title).isEqualTo("Sunny Day")
            assertThat(reader.read(ref(b)).title).isEqualTo("晴天")
        }
    }
}
