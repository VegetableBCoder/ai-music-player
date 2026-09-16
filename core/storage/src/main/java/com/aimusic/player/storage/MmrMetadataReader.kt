package com.aimusic.player.storage

import com.aimusic.player.common.model.AudioMetadata

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** MMR 一次读取的原始结果；没读到的字段是 `null`。 */
data class RawTags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val date: String?,
    val year: String?,
    val durationMs: Long?,
    val hasEmbeddedPicture: Boolean,
)

/**
 * 元数据读取的**窄缝**：真机上是 `MediaMetadataRetriever`，JVM 测试里是假实现。
 *
 * 缝的出口选 `RawTags` 而不是「逐个 MMR 键」：键常量属平台细节，暴露出去会让假实现
 * 也得模拟一套键，测的东西就漂移了。
 */
interface MmrRetriever {
    fun read(path: String): RawTags

    fun close()
}

fun interface MmrRetrieverFactory {
    fun create(): MmrRetriever
}

/** `durationMs` 的兜底通道（`04 §4.4`）：MMR 没有 `METADATA_KEY_DURATION` 时用它再试一次。 */
fun interface MediaExtractorPool {
    fun durationMs(path: String): Long?
}

/**
 * 元数据读取（`04 §4.4`）。
 *
 * **超时由本类自己拥有**，而不是像 `04 §4.4` 正文写的那样「在调用侧用 `withTimeoutOrNull`
 * 包裹」：`MetadataReader.read` 是**非 suspend 的阻塞调用**，协程超时打断不了正在跑的原生
 * 调用，调用侧也就无从得知「这个槽脏了」，同一节的表格要求的 `discard` 会变成死条文。
 * 所以这里把读取提交给自己的一组工作线程（`future.get(timeout)`），超时后能真正把那个
 * 实例作废、补一个新实例。见文档 §4.4 的落地补充。
 *
 * 实例纪律：`MediaMetadataRetriever` 非线程安全。工作线程数 = 实例上限 = `parallelism`，
 * 借出时用信号量保证同时至多 N 个在飞，因此**同一个实例绝不会被两个线程同时使用**。
 */
class MmrMetadataReader(
    parallelism: Int = minOf(4, Runtime.getRuntime().availableProcessors()),
    private val timeoutMs: Long = 5_000,
    private val factory: MmrRetrieverFactory = MmrRetrieverFactory { AndroidMmrRetriever() },
    private val durationFallback: MediaExtractorPool = MediaExtractorPool { null },
    private val tagFallback: MetadataReader? = null,
) : MetadataReader {

    private val permits = Semaphore(parallelism)
    private val workers: ExecutorService = Executors.newFixedThreadPool(parallelism, DAEMON_THREADS)

    private val lock = Any()
    private val idle = ArrayDeque<MmrRetriever>()

    init {
        require(parallelism > 0) { "parallelism 必须为正，收到 $parallelism" }
    }

    override fun read(ref: FileRef): AudioMetadata {
        permits.acquire()
        val retriever = try {
            take()
        } catch (t: Throwable) {
            permits.release()
            throw t
        }

        var succeeded = false
        try {
            val task = CompletableFuture.supplyAsync({ extract(retriever, ref) }, workers)
            val metadata = try {
                task.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // 原生调用可能仍在跑：此刻 release 会把那个线程打死，
                // 所以推迟到任务真正结束再释放，实例本身立即作废（不再被借出）。
                task.cancel(true)
                task.whenComplete { _, _ -> closeQuietly(retriever) }
                return degrade(ref)
            } catch (e: ExecutionException) {
                closeQuietly(retriever) // 任务已结束，立即释放是安全的
                return degrade(ref)
            } catch (e: InterruptedException) {
                closeQuietly(retriever)
                Thread.currentThread().interrupt()
                return degrade(ref)
            } catch (t: Throwable) {
                closeQuietly(retriever)
                throw t
            }
            succeeded = true
            return metadata
        } finally {
            if (succeeded) giveBack(retriever)
            permits.release()
        }
    }

    private fun extract(retriever: MmrRetriever, ref: FileRef): AudioMetadata {
        val raw = retriever.read(ref.path)
        val duration = raw.durationMs ?: durationFallback.durationMs(ref.path) ?: 0L

        var metadata = AudioMetadata(
            title = raw.title,
            artist = raw.artist,
            album = raw.album,
            albumArtist = raw.albumArtist,
            // 无 DATE 时退到 YEAR（04 §4.4）
            date = raw.date ?: raw.year,
            durationMs = duration,
            hasEmbeddedPicture = raw.hasEmbeddedPicture,
        )

        // jaudiotagger 兜底成本高，只在缺日期或缺封面时按需调用（04 §4.4）
        val fallback = tagFallback
        if (fallback != null && (metadata.date == null || !metadata.hasEmbeddedPicture)) {
            val extra = runCatching { fallback.read(ref) }.getOrNull()
            if (extra != null) metadata = merge(metadata, extra)
        }
        return metadata
    }

    /** 主通道已有值不覆盖；只补空缺（封面取「任一为真」）。 */
    private fun merge(primary: AudioMetadata, extra: AudioMetadata) = AudioMetadata(
        title = primary.title ?: extra.title,
        artist = primary.artist ?: extra.artist,
        album = primary.album ?: extra.album,
        albumArtist = primary.albumArtist ?: extra.albumArtist,
        date = primary.date ?: extra.date,
        durationMs = if (primary.durationMs > 0) primary.durationMs else extra.durationMs,
        hasEmbeddedPicture = primary.hasEmbeddedPicture || extra.hasEmbeddedPicture,
    )

    /** 解析失败降级（`04 §4.4`）：标题取文件名去扩展名，其余空。文件照样入库。 */
    private fun degrade(ref: FileRef) = AudioMetadata(
        title = ref.name.substringBeforeLast('.').ifBlank { ref.name },
        artist = null,
        album = null,
        albumArtist = null,
        date = null,
        durationMs = 0L,
        hasEmbeddedPicture = false,
    )

    private fun take(): MmrRetriever = synchronized(lock) {
        idle.removeLastOrNull() ?: factory.create()
    }

    private fun giveBack(retriever: MmrRetriever) = synchronized(lock) {
        idle.addLast(retriever)
    }

    private fun closeQuietly(retriever: MmrRetriever) {
        runCatching { retriever.close() }
    }

    private companion object {
        val DAEMON_THREADS = ThreadFactory { r ->
            Thread(r, "mmr-reader").apply { isDaemon = true }
        }
    }
}

/** 真机实现：`MediaMetadataRetriever`。这一层只是把平台的键读出来，不含任何决策。 */
internal class AndroidMmrRetriever : MmrRetriever {

    private val mmr = MediaMetadataRetriever()

    override fun read(path: String): RawTags {
        mmr.setDataSource(path)
        return RawTags(
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            albumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            date = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
            year = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
            durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull(),
            hasEmbeddedPicture = mmr.embeddedPicture != null,
        )
    }

    override fun close() {
        mmr.release()
    }
}

/**
 * `MediaExtractor` 时长兜底。**每线程一个实例复用** —— `MediaExtractor` 同样不是
 * 为并发复用设计的，而实例创建 + `setDataSource` 的成本远高于一次 `getTrackFormat`。
 */
class AndroidMediaExtractorPool : MediaExtractorPool {

    private val perThread = ThreadLocal.withInitial { MediaExtractor() }

    override fun durationMs(path: String): Long? = runCatching {
        val extractor = perThread.get()
        extractor.setDataSource(path)
        (0 until extractor.trackCount)
            .map { extractor.getTrackFormat(it) }
            .firstOrNull { it.containsKey(MediaFormat.KEY_DURATION) }
            ?.let { it.getLong(MediaFormat.KEY_DURATION) / 1000 }
    }.getOrNull()
}
