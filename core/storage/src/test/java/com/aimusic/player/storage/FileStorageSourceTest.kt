package com.aimusic.player.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 目录遍历（`04 §4.3`）。
 *
 * 全部用 `TemporaryFolder` 造真实目录树跑 —— 遍历的正确性（软链成环、深度、隐藏项、
 * 错误目录不中断）只有在**真实文件系统**上才有意义，用 mock 掉 `File` 等于把要测的东西
 * 测没了。
 */
class FileStorageSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val warnings = mutableListOf<String>()

    private fun source(maxDepth: Int = 12) = FileStorageSource(
        primaryRoot = temp.root.absolutePath,
        maxDepth = maxDepth,
        onWarning = { warnings += it },
    )

    private fun touch(relative: String, content: String = "x"): File {
        val f = File(temp.root, relative)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    private fun collect(
        roots: List<String> = listOf(temp.root.absolutePath),
        exts: Set<String> = AudioFormats.AUDIO_EXTS,
    ): Pair<List<FileRef>, List<Int>> {
        val progress = mutableListOf<Int>()
        val refs = source().listFiles(roots, exts) { progress += it }.toList()
        return refs to progress
    }

    @Test
    fun `只命中白名单扩展名_且大小写不敏感`() {
        touch("Music/a.mp3")
        touch("Music/b.FLAC")
        touch("Music/cover.jpg")
        touch("Music/notes.txt")

        val (refs, _) = collect()

        assertThat(refs.map { it.name }).containsExactly("a.mp3", "b.FLAC")
    }

    @Test
    fun `歌词用另一组白名单命中`() {
        touch("Music/a.mp3")
        touch("Music/a.lrc")

        val (refs, _) = collect(exts = AudioFormats.LRC_EXTS)

        assertThat(refs.map { it.name }).containsExactly("a.lrc")
    }

    @Test
    fun `隐藏文件与隐藏目录整个跳过`() {
        touch("Music/a.mp3")
        touch("Music/.hidden.mp3")
        touch(".hiddenDir/b.mp3")

        val (refs, _) = collect()

        assertThat(refs.map { it.name }).containsExactly("a.mp3")
    }

    @Test
    fun `系统目录跳过_含两层相对路径与_thumbnails`() {
        touch("Music/a.mp3")
        touch("Android/data/com.x/b.mp3")
        touch("Android/obb/com.x/c.mp3")
        touch(".thumbnails/d.mp3")
        touch("LOST.DIR/e.mp3")

        val (refs, _) = collect()

        assertThat(refs.map { it.name }).containsExactly("a.mp3")
    }

    @Test
    fun `深度超过上限的子目录不再进入`() {
        touch("d1/d2/deep.mp3")
        touch("d1/d2/d3/d4/too_deep.mp3")

        val refs = source(maxDepth = 2).listFiles(listOf(temp.root.absolutePath), AudioFormats.AUDIO_EXTS) {}
            .toList()

        assertThat(refs.map { it.name }).containsExactly("deep.mp3")
    }

    @Test
    fun `软链指回祖先不会死循环`() {
        File(temp.root, "Music").mkdirs()
        touch("Music/a.mp3")
        val loop = File(temp.root, "Music/loop")
        val created = loop.toPath().let { p ->
            runCatching { java.nio.file.Files.createSymbolicLink(p, File(temp.root, "Music").toPath()) }
                .isSuccess
        }
        org.junit.Assume.assumeTrue("本环境不支持创建符号链接", created)

        val (refs, _) = collect()

        assertThat(refs.map { it.name }).containsExactly("a.mp3")
    }

    @Test
    fun `同一目录被两个根覆盖时只扫一次`() {
        touch("Music/a.mp3")

        val (refs, _) = collect(
            roots = listOf(temp.root.absolutePath, File(temp.root, "Music").absolutePath),
        )

        assertThat(refs.map { it.name }).containsExactly("a.mp3")
    }

    @Test
    fun `不存在的根与文件当根都被忽略_不抛异常`() {
        touch("Music/a.mp3")

        val (refs, _) = collect(
            roots = listOf(
                File(temp.root, "不存在").absolutePath,
                File(temp.root, "Music/a.mp3").absolutePath,
                temp.root.absolutePath,
            ),
        )

        assertThat(refs.map { it.name }).containsExactly("a.mp3")
    }

    @Test
    fun `不可读的子目录记警告但扫描继续`() {
        touch("Music/a.mp3")
        val locked = File(temp.root, "locked")
        locked.mkdirs()
        touch("locked/b.mp3")
        org.junit.Assume.assumeTrue("以 root 运行，权限位不生效", locked.setReadable(false, false))
        org.junit.Assume.assumeTrue("setReadable 未生效", !locked.canRead())

        val (refs, _) = collect()

        assertThat(refs.map { it.name }).containsExactly("a.mp3")
        assertThat(warnings).isNotEmpty()
    }

    @Test
    fun `进度回调按发现顺序递增`() {
        touch("Music/a.mp3")
        touch("Music/b.mp3")
        touch("Music/c.mp3")

        val (_, progress) = collect()

        assertThat(progress).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `FileRef_带出路径_名字_体积_修改时间`() {
        val file = touch("Music/a.mp3", "0123456789")

        val (refs, _) = collect()
        val ref = refs.single()

        // 遍历阶段给的是磁盘上的绝对路径（规范化在 §4.5 流水线里做），
        // 所以这里只要求「规范化之后同键」，不要求字面相等（临时目录本身可能是软链）
        assertThat(ref.path).endsWith("Music/a.mp3")
        assertThat(PathNormalizer.normalize(ref.path, temp.root.absolutePath))
            .isEqualTo(PathNormalizer.normalize(file.absolutePath, temp.root.absolutePath))
        assertThat(ref.name).isEqualTo("a.mp3")
        assertThat(ref.size).isEqualTo(10L)
        assertThat(ref.lastModified).isEqualTo(file.lastModified())
    }

    @Test
    fun `exists_与_size_与_delete_直接作用于磁盘`() {
        val file = touch("Music/a.mp3", "0123456789")
        val src = source()

        assertThat(src.exists(file.absolutePath)).isTrue()
        assertThat(src.size(file.absolutePath)).isEqualTo(10L)
        assertThat(src.delete(file.absolutePath)).isTrue()
        assertThat(src.exists(file.absolutePath)).isFalse()
        assertThat(src.delete(file.absolutePath)).isFalse()
    }

    @Test
    fun `isDirectory_区分「不存在」与「存在但不是目录」`() {
        val file = touch("Music/a.mp3")
        val src = source()

        assertThat(src.isDirectory(File(temp.root, "Music").absolutePath)).isTrue()
        assertThat(src.isDirectory(file.absolutePath)).isFalse()
        // 关键区别：来源管理要靠它给出两种不同的提示（04 §3.4）
        assertThat(src.exists(File(temp.root, "并没有这个").absolutePath)).isFalse()
        assertThat(src.isDirectory(File(temp.root, "并没有这个").absolutePath)).isFalse()
    }

    @Test
    fun `readBytes_按上限截断_不整个读进内存`() {
        val file = touch("Music/big.mp3", "0123456789")
        val src = source()

        assertThat(src.readBytes(file.absolutePath, maxBytes = 4).size).isEqualTo(4)
        assertThat(String(src.readBytes(file.absolutePath, maxBytes = 4))).isEqualTo("0123")
        assertThat(src.readBytes(file.absolutePath, maxBytes = 100).size).isEqualTo(10)
    }

    @Test
    fun `readBytes_对不存在的文件抛_IOException_由调用方决定降级`() {
        val src = source()

        val thrown = runCatching { src.readBytes(File(temp.root, "无.mp3").absolutePath) }

        assertThat(thrown.exceptionOrNull()).isInstanceOf(java.io.IOException::class.java)
    }

    @Test
    fun `canWrite_反映主存储根的可写性`() {
        assertThat(source().canWrite()).isTrue()
    }
}
