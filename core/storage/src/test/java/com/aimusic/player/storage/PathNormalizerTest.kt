package com.aimusic.player.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.Normalizer

/**
 * 路径规范化是差异比对的**唯一键**来源（`04 §4.6`）：同一条规则要同时喂给扫描、
 * 入库、以及后续「已删除清理」的存在性复核。规范化不唯一 → 同一文件被当两个 → 重复入库。
 *
 * `primaryRoot` 是参数而不是读 `Environment`：`Environment` 是 Android API，一读就没法在
 * JVM 上测这套规则了（别名表本身正是最容易写错的一段）。
 */
class PathNormalizerTest {

    private val root = "/storage/emulated/0"

    private fun norm(p: String) = PathNormalizer.normalize(p, root)

    @Test
    fun `分隔符统一并折叠重复斜杠`() {
        assertThat(norm("/storage/emulated/0/a\\b.mp3")).isEqualTo("$root/a/b.mp3")
        assertThat(norm("$root//a///b.mp3")).isEqualTo("$root/a/b.mp3")
    }

    @Test
    fun `去掉尾部斜杠但保留根斜杠`() {
        assertThat(norm("$root/Music/")).isEqualTo("$root/Music")
        assertThat(norm("/")).isEqualTo("/")
    }

    @Test
    fun `Unicode 归一到_NFC_让_macOS_拷入的分解式文件名命中同一条`() {
        // 注意前缀写 "Caf" 而不是 "Cafe"：NFD 的 "é" 本身就是 "e" + 组合符，
        // 多写一个 e 会得到 "Cafeé"（踩过一次 —— 夹具写错看起来却像归一化写错）
        val nfd = "$root/Music/Caf" + Normalizer.normalize("\u00e9", Normalizer.Form.NFD) + ".mp3"
        val nfc = "$root/Music/Caf\u00e9.mp3"
        assertThat(nfd).isNotEqualTo(nfc)
        assertThat(norm(nfd)).isEqualTo(norm(nfc))
    }

    @Test
    fun `别名一律归并到主存储根`() {
        val expected = norm("$root/Music/a.mp3")
        assertThat(norm("/sdcard/Music/a.mp3")).isEqualTo(expected)
        assertThat(norm("/storage/self/primary/Music/a.mp3")).isEqualTo(expected)
        assertThat(norm("/mnt/sdcard/Music/a.mp3")).isEqualTo(expected)
    }

    @Test
    fun `别名只替换前缀_不误伤名字里带_sdcard_的目录`() {
        assertThat(norm("/sdcardbackup/a.mp3")).isEqualTo("/sdcardbackup/a.mp3")
        assertThat(norm("$root/sdcard/a.mp3")).isEqualTo("$root/sdcard/a.mp3")
    }

    @Test
    fun `大小写保持原样_Android_主存储区分大小写`() {
        assertThat(norm("$root/Music/A.mp3")).isEqualTo("$root/Music/A.mp3")
        assertThat(norm("$root/Music/A.mp3")).isNotEqualTo(norm("$root/Music/a.mp3"))
    }

    @Test
    fun `非主存储路径原样保留_不强行搬进主存储`() {
        assertThat(norm("/storage/1234-5678/Music/a.mp3")).isEqualTo("/storage/1234-5678/Music/a.mp3")
    }

    @Test
    fun `不存在的路径不抛异常_降级为归一化结果`() {
        assertThat(norm("$root/没有这个目录/a.mp3")).isEqualTo("$root/没有这个目录/a.mp3")
    }

    @Test
    fun `空串与纯相对路径不炸`() {
        assertThat(norm("")).isEqualTo("")
        assertThat(norm("a/b.mp3")).isEqualTo("a/b.mp3")
    }
}
