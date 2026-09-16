package com.aimusic.player.storage

import com.aimusic.player.common.model.AudioMetadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 元数据读取（`04 §4.4`）。
 *
 * 真机上的 `MediaMetadataRetriever` 换成了可注入的 `MmrRetriever` 缝，于是**逻辑**
 * （字段回退顺序、解析失败降级、超时作废、实例独占）全部能在 JVM 上测；真机上只留
 * 「MMR 本身读得对不对」的冒烟。
 *
 * 缝的边界选在 `RawTags` 而不是「逐个 MMR 键」：键常量属于平台细节，把它暴露出来会让
 * 假实现也得跟着模拟一套键，测的东西就漂移了。
 */
class MmrMetadataReaderTest {

    private val ref = FileRef("/music/a.mp3", "晴天.mp3", 1024, 0)

    private fun tags(
        title: String? = "晴天",
        artist: String? = "周杰伦",
        album: String? = "叶惠美",
        albumArtist: String? = null,
        date: String? = "2003",
        year: String? = null,
        durationMs: Long? = 269_000,
        hasPicture: Boolean = false,
    ) = RawTags(title, artist, album, albumArtist, date, year, durationMs, hasPicture)

    private class FakeRetriever(
        private val tags: RawTags? = null,
        private val failWith: Throwable? = null,
        private val sleepMs: Long = 0,
        private val started: CountDownLatch? = null,
    ) : MmrRetriever {

        /** 同一实例被两个线程同时使用会立刻抛错 —— 这就是「独占复用」的断言手段。 */
        private val inUse = AtomicBoolean(false)
        var closed = false
            private set

        override fun read(path: String): RawTags {
            check(inUse.compareAndSet(false, true)) { "同一个 MmrRetriever 实例被并发使用" }
            try {
                started?.countDown()
                if (sleepMs > 0) Thread.sleep(sleepMs)
                failWith?.let { throw it }
                return tags!!
            } finally {
                inUse.set(false)
            }
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeFactory(private val newRetriever: () -> MmrRetriever) : MmrRetrieverFactory {
        val created = AtomicInteger()
        val instances: MutableList<MmrRetriever> = Collections.synchronizedList(mutableListOf())

        override fun create(): MmrRetriever = newRetriever().also {
            created.incrementAndGet()
            instances += it
        }
    }

    private fun reader(
        factory: MmrRetrieverFactory,
        parallelism: Int = 4,
        timeoutMs: Long = 5_000,
        durationFallback: MediaExtractorPool = MediaExtractorPool { null },
        tagFallback: MetadataReader? = null,
    ) = MmrMetadataReader(
        parallelism = parallelism,
        timeoutMs = timeoutMs,
        factory = factory,
        durationFallback = durationFallback,
        tagFallback = tagFallback,
    )

    @Test
    fun `正常路径读出全部字段`() {
        val factory = FakeFactory { FakeRetriever(tags(title = "晴天", artist = "周杰伦", hasPicture = true)) }

        val meta = reader(factory).read(ref)

        assertThat(meta.title).isEqualTo("晴天")
        assertThat(meta.artist).isEqualTo("周杰伦")
        assertThat(meta.album).isEqualTo("叶惠美")
        assertThat(meta.durationMs).isEqualTo(269_000L)
        assertThat(meta.hasEmbeddedPicture).isTrue()
        assertThat(factory.created.get()).isEqualTo(1)
    }

    @Test
    fun `date_缺失时回退到_year`() {
        val factory = FakeFactory { FakeRetriever(tags(date = null, year = "2003")) }

        assertThat(reader(factory).read(ref).date).isEqualTo("2003")
    }

    @Test
    fun `date_优先于_year`() {
        val factory = FakeFactory { FakeRetriever(tags(date = "2003-07-31", year = "2003")) }

        assertThat(reader(factory).read(ref).date).isEqualTo("2003-07-31")
    }

    @Test
    fun `时长缺失时先用_MediaExtractor_兜底`() {
        val factory = FakeFactory { FakeRetriever(tags(durationMs = null)) }
        val pool = MediaExtractorPool { path -> if (path == ref.path) 123_000L else null }

        assertThat(reader(factory, durationFallback = pool).read(ref).durationMs).isEqualTo(123_000L)
    }

    @Test
    fun `两处都拿不到时长时为_0_而不是抛错`() {
        val factory = FakeFactory { FakeRetriever(tags(durationMs = null)) }

        assertThat(reader(factory).read(ref).durationMs).isEqualTo(0L)
    }

    @Test
    fun `解析抛异常时降级_标题取文件名去扩展名`() {
        val factory = FakeFactory { FakeRetriever(failWith = IllegalStateException("坏文件")) }

        val meta = reader(factory).read(ref)

        assertThat(meta.title).isEqualTo("晴天")
        assertThat(meta.artist).isNull()
        assertThat(meta.album).isNull()
        assertThat(meta.albumArtist).isNull()
        assertThat(meta.date).isNull()
        assertThat(meta.durationMs).isEqualTo(0L)
        assertThat(meta.hasEmbeddedPicture).isFalse()
    }

    @Test
    fun `降级时标题只去掉最后一段扩展名`() {
        val factory = FakeFactory { FakeRetriever(failWith = RuntimeException("x")) }

        val meta = reader(factory).read(FileRef("/m/a.b.mp3", "a.b.mp3", 1, 0))

        assertThat(meta.title).isEqualTo("a.b")
    }

    @Test
    fun `解析失败的实例被作废_下一次读拿到新实例`() {
        val factory = FakeFactory { FakeRetriever(tags = null, failWith = RuntimeException("坏")) }

        val reader = reader(factory)
        reader.read(ref)
        reader.read(ref)

        assertThat(factory.created.get()).isEqualTo(2)
        assertThat((factory.instances[0] as FakeRetriever).closed).isTrue()
    }

    @Test
    fun `正常读完后实例归还复用_两次顺序读只建一个实例`() {
        val factory = FakeFactory { FakeRetriever(tags()) }

        val reader = reader(factory)
        reader.read(ref)
        reader.read(ref)

        assertThat(factory.created.get()).isEqualTo(1)
        assertThat((factory.instances[0] as FakeRetriever).closed).isFalse()
    }

    @Test
    fun `超时降级并作废该实例_下一次读拿到新实例`() {
        val factory = FakeFactory { FakeRetriever(tags(), sleepMs = 400) }
        val reader = reader(factory, parallelism = 1, timeoutMs = 50)

        val first = reader.read(ref)

        assertThat(first.title).isEqualTo("晴天") // 降级：标题取文件名（去扩展名）
        assertThat(first.durationMs).isEqualTo(0L)
        assertThat(factory.created.get()).isEqualTo(1)

        // 被作废的实例不会再被借出 —— 这才是超时那条规则的意义
        reader.read(ref)
        assertThat(factory.created.get()).isEqualTo(2)

        // 释放是**推迟**到原生调用真正结束之后（此刻 release 会打死那个线程）
        val discarded = factory.instances[0] as FakeRetriever
        val deadline = System.currentTimeMillis() + 3_000
        while (!discarded.closed && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(discarded.closed).isTrue()
    }

    @Test
    fun `并发读不会让同一实例被两个线程同时使用`() {
        val factory = FakeFactory { FakeRetriever(tags(), sleepMs = 20) }
        val reader = reader(factory, parallelism = 4)

        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { pool.submit<AudioMetadata> { reader.read(ref) } }
                .map { it.get(5, TimeUnit.SECONDS) }

            // 一旦发生共享，假实例会抛错 → 那条结果会降级成文件名，断言就会红
            assertThat(results.map { it.title }).containsExactly("晴天", "晴天", "晴天", "晴天", "晴天", "晴天", "晴天", "晴天")
            assertThat(factory.created.get()).isAtMost(4)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `jaudiotagger_兜底只在_date_缺失或没有内嵌封面时才被调用`() {
        val factory = FakeFactory { FakeRetriever(tags(date = "2003", hasPicture = true)) }
        val fallbackCalls = AtomicInteger()
        val fallback = MetadataReader { r ->
            fallbackCalls.incrementAndGet()
            AudioMetadata("封面标题", null, null, null, "1999", 1L, true)
        }

        reader(factory, tagFallback = fallback).read(ref)

        assertThat(fallbackCalls.get()).isEqualTo(0)
    }

    @Test
    fun `需要兜底时把结果并进来`() {
        val factory = FakeFactory { FakeRetriever(tags(date = null, hasPicture = false)) }
        val fallback = MetadataReader { _ ->
            AudioMetadata("兜底标题", "兜底歌手", null, null, "1999", 1L, true)
        }

        val meta = reader(factory, tagFallback = fallback).read(ref)

        assertThat(meta.title).isEqualTo("晴天")        // 主通道已有值不覆盖
        assertThat(meta.date).isEqualTo("1999")        // 缺的补上
        assertThat(meta.hasEmbeddedPicture).isTrue()   // 有封面就认
    }

    @Test
    fun `并发度配置必须为正`() {
        val factory = FakeFactory { FakeRetriever(tags()) }

        val thrown = runCatching { reader(factory, parallelism = 0) }

        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
