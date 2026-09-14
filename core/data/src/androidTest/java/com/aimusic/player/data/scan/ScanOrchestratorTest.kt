package com.aimusic.player.data.scan

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.data.countOf
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.deletion.DeletionService
import com.aimusic.player.data.exec
import com.aimusic.player.data.insertFile
import com.aimusic.player.data.insertSong
import com.aimusic.player.data.linkFile
import com.aimusic.player.data.model.LyricSource
import com.aimusic.player.data.representativeIdOf
import com.aimusic.player.data.scalar
import com.aimusic.player.data.settings.SettingsRepository
import com.aimusic.player.storage.AudioFormats
import com.aimusic.player.storage.AudioMetadata
import com.aimusic.player.storage.FileRef
import com.aimusic.player.storage.MediaStoreAudioEntry
import com.aimusic.player.storage.MediaStoreAudioSource
import com.aimusic.player.storage.MetadataReader
import com.aimusic.player.storage.StorageAccessChecker
import com.aimusic.player.storage.StorageAccessLevel
import com.aimusic.player.storage.StorageSource
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 扫描编排器（`04 §4.1`–`§4.9`）。
 *
 * 用真实 Room（内存库）+ 假文件系统/元数据读取器：状态机、提交事务、清理序列与
 * 不变量的联动才是被测对象，真机文件系统与 MMR 已经在 3a/3b 单独验过。
 */
@RunWith(AndroidJUnit4::class)
class ScanOrchestratorTest {

    private lateinit var db: MusicDatabase
    private lateinit var storage: FakeStorage
    private lateinit var access: FakeAccess
    private lateinit var mediaStore: FakeMediaStore
    private lateinit var trigger: RecordingTrigger
    private lateinit var handoff: RecordingHandoff
    private lateinit var settings: SettingsRepository
    private lateinit var orchestrator: ScanOrchestrator

    private val root = "/storage/emulated/0"
    private val musicDir = "$root/Music"
    private val albumOfReader = "叶惠美"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storage = FakeStorage()
        access = FakeAccess(StorageAccessLevel.FULL)
        mediaStore = FakeMediaStore()
        trigger = RecordingTrigger()
        handoff = RecordingHandoff()
        settings = SettingsRepository(context) {
            File(it.filesDir, "scan-orchestrator-${UUID.randomUUID()}.preferences_pb")
        }
        orchestrator = build()
    }

    private fun build(reader: MetadataReader = defaultReader()) = ScanOrchestrator(
        context = ApplicationProvider.getApplicationContext(),
        storage = storage,
        metadataReader = reader,
        accessChecker = access,
        mediaStoreAudio = mediaStore,
        db = db,
        deletionService = DeletionService(db, storage),
        settings = settings,
        scanSources = ScanSourceRepositoryImpl(db, storage),
        primaryRoot = root,
        analysisTrigger = trigger,
        lyricHandoff = handoff,
        parallelism = 2,
        now = { NOW },
    )

    private fun defaultMetadata(ref: FileRef) = AudioMetadata(
        title = ref.name,
        artist = "周杰伦",
        album = albumOfReader,
        albumArtist = "周杰伦",
        date = "2003",
        durationMs = SONG_DURATION,
        hasEmbeddedPicture = false,
    )

    private fun defaultReader() = MetadataReader { ref -> defaultMetadata(ref) }

    @After
    fun tearDown() {
        db.close()
    }

    private fun addMusicSource(path: String = musicDir) {
        storage.dirs += path
        storage.existing += path
        db.exec(
            "INSERT INTO scan_source (kind, path, enabled, created_at) VALUES ('MUSIC', '$path', 1, 0)",
        )
    }

    private fun addLyricSource(path: String) {
        storage.dirs += path
        storage.existing += path
        db.exec(
            "INSERT INTO scan_source (kind, path, enabled, created_at) VALUES ('LYRICS', '$path', 1, 0)",
        )
    }

    /**
     * 夹具用**真实量级**的歌曲（4 MB / 3 分钟），不是 10 字节 / 1 秒。
     * 因为扫描过滤默认开启（需求 `01 §2.5`），不合理的小夹具会被过滤器挡掉，
     * 到期用例会因为「夹具不真实」而红，误导成产品有问题。
     */
    private fun onDisk(path: String, size: Long = SONG_SIZE) {
        storage.refs += FileRef(path, path.substringAfterLast('/'), size, 0)
        storage.existing += path
    }

    /** 降级通道的条目：路径与元数据一起来自媒体库。 */
    private fun onDiskViaStore(path: String, size: Long = SONG_SIZE, durationMs: Long = SONG_DURATION) {
        mediaStore.entries += MediaStoreAudioEntry(
            ref = FileRef(path, path.substringAfterLast('/'), size, 0),
            metadata = AudioMetadata(
                title = path.substringAfterLast('/'),
                artist = "周杰伦",
                album = albumOfReader,
                albumArtist = "周杰伦",
                date = "2003",
                durationMs = durationMs,
                hasEmbeddedPicture = false,
            ),
        )
    }

    // —— 门禁与守卫 ——

    @Test
    fun `无权限时不进入扫描_返回_PERMISSION`() = runDbTest {
        access.level = StorageAccessLevel.NONE

        val outcome = orchestrator.scan()

        assertThat(outcome).isEqualTo(ScanOutcome.Failed(FailureKind.PERMISSION, null))
        assertThat(orchestrator.state.value.phase).isEqualTo(ScanPhase.IDLE)
        assertThat(storage.listFilesCalls).isEqualTo(0)
    }

    @Test
    fun `没有音乐来源时不启动扫描_也不算权限问题`() = runDbTest {
        val outcome = orchestrator.scan()

        assertThat(outcome).isEqualTo(ScanOutcome.NoChanges(ScanSummary(0, 0, 0, 0)))
        assertThat(orchestrator.state.value.warnings).isNotEmpty()
        assertThat(storage.listFilesCalls).isEqualTo(0)
    }

    @Test
    fun `全部来源都不可读时升级为致命失败`() = runDbTest {
        db.exec(
            "INSERT INTO scan_source (kind, path, enabled, created_at) " +
                "VALUES ('MUSIC', '/storage/emulated/0/不存在', 1, 0)",
        )

        assertThat(orchestrator.scan()).isEqualTo(ScanOutcome.Failed(FailureKind.PERMISSION, null))
    }

    // —— 正常全链路 ——

    @Test
    fun `新文件经提交写入_为_UNANALYZED_且未挂靠`() = runDbTest {
        addMusicSource()
        onDisk("$musicDir/a.mp3")
        onDisk("$musicDir/b.mp3")

        val scanned = orchestrator.scan() as ScanOutcome.AwaitingUser
        assertThat(scanned.summary.newCount).isEqualTo(2)
        assertThat(orchestrator.state.value.phase).isEqualTo(ScanPhase.AWAITING_USER)
        assertThat(db.countOf("music_file")).isEqualTo(0) // 扫描阶段不写库

        val result = orchestrator.commit() as CommitResult.Committed

        assertThat(result.inserted).isEqualTo(2)
        assertThat(result.cleaned).isEqualTo(0)
        assertThat(db.countOf("music_file")).isEqualTo(2)
        assertThat(db.scalar("SELECT DISTINCT analysis_status FROM music_file")).isEqualTo("UNANALYZED")
        assertThat(db.scalar("SELECT COUNT(*) FROM music_file WHERE entity_id IS NULL")).isEqualTo("2")
        assertThat(db.scalar("SELECT COUNT(*) FROM music_file WHERE is_representative IS NULL"))
            .isEqualTo("2")
        assertThat(db.countOf("analysis_run_file")).isEqualTo(2)
        assertThat(orchestrator.state.value.phase).isEqualTo(ScanPhase.ANALYZING)
        assertThat(settings.lastScanAt.first()).isEqualTo(NOW)
    }

    @Test
    fun `批次计数如实映射新增_跳过_清理`() = runDbTest {
        addMusicSource()
        db.insertFile("$musicDir/existing.mp3", 10)
        db.insertFile("$musicDir/gone.mp3", 10) // 磁盘上没有 → 清理
        onDisk("$musicDir/existing.mp3")
        onDisk("$musicDir/new.mp3")

        val scanned = orchestrator.scan() as ScanOutcome.AwaitingUser

        assertThat(scanned.summary.newCount).isEqualTo(1)
        assertThat(scanned.summary.skippedCount).isEqualTo(1)
        assertThat(scanned.summary.cleanedCount).isEqualTo(1)

        orchestrator.commit()

        assertThat(db.scalar("SELECT new_count FROM analysis_run")).isEqualTo("1")
        assertThat(db.scalar("SELECT skipped_count FROM analysis_run")).isEqualTo("1")
        assertThat(db.scalar("SELECT cleaned_count FROM analysis_run")).isEqualTo("1")
        assertThat(db.scalar("SELECT COUNT(*) FROM music_file WHERE path LIKE '%gone.mp3'")).isEqualTo("0")
    }

    @Test
    fun `时长缺失写_NULL_而不是_0`() = runDbTest {
        addMusicSource()
        onDisk("$musicDir/short.mp3")
        orchestrator = build(MetadataReader { ref ->
            AudioMetadata(ref.name, null, null, null, null, 0L, false)
        })

        orchestrator.scan()
        orchestrator.commit()

        assertThat(db.scalar("SELECT COUNT(*) FROM music_file WHERE duration_ms IS NULL")).isEqualTo("1")
    }

    // —— 不变量 ——

    @Test
    fun `I1_同一路径只入库一次_重扫归入已存在跳过`() = runDbTest {
        addMusicSource()
        onDisk("$musicDir/a.mp3")
        orchestrator.scan()
        orchestrator.commit()

        val again = orchestrator.scan()

        assertThat(again).isInstanceOf(ScanOutcome.NoChanges::class.java)
        assertThat(db.countOf("music_file")).isEqualTo(1)
    }

    @Test
    fun `I4_已_LINKED_的文件重扫不重置状态`() = runDbTest {
        addMusicSource()
        val entityId = db.insertSong("晴天", "周杰伦")
        val fileId = db.insertFile("$musicDir/a.mp3", 10, status = "LINKED")
        db.linkFile(fileId, entityId)
        onDisk("$musicDir/a.mp3")

        orchestrator.scan()

        assertThat(db.scalar("SELECT analysis_status FROM music_file WHERE id = $fileId"))
            .isEqualTo("LINKED")
        assertThat(db.countOf("music_file")).isEqualTo(1)
    }

    @Test
    fun `I8_清理已删记录不触发任何磁盘删除`() = runDbTest {
        addMusicSource()
        db.insertFile("$musicDir/gone.mp3", 10)

        orchestrator.scan()
        orchestrator.commit()

        assertThat(db.countOf("music_file")).isEqualTo(0)
        assertThat(storage.deleted).isEmpty()
    }

    @Test
    fun `I3_清理掉实体最后一个文件时实体一并删除`() = runDbTest {
        addMusicSource()
        val entityId = db.insertSong("晴天", "周杰伦")
        val fileId = db.insertFile("$musicDir/gone.mp3", 10, status = "LINKED")
        db.linkFile(fileId, entityId, isRepresentative = true)

        orchestrator.scan()
        orchestrator.commit()

        assertThat(db.countOf("music_file")).isEqualTo(0)
        assertThat(db.countOf("song_entity")).isEqualTo(0)
        assertThat(db.countOf("song_artist")).isEqualTo(0)
    }

    @Test
    fun `I2_I11_清理后重选代表文件并刷新展示字段`() = runDbTest {
        addMusicSource()
        val entityId = db.insertSong("晴天", "周杰伦")
        val lost = db.insertFile("$musicDir/lost.mp3", 20, status = "LINKED")
        val kept = db.insertFile("$musicDir/kept.mp3", 10, status = "LINKED")
        db.linkFile(lost, entityId, isRepresentative = true)
        db.linkFile(kept, entityId)
        onDisk("$musicDir/kept.mp3") // 只有 kept 还在磁盘上

        orchestrator.scan()
        orchestrator.commit()

        // 先分开确认「清理跑了」与「重选发生了」，失败时能直接指向哪一步
        assertThat(db.countOf("music_file")).isEqualTo(1)
        assertThat(db.representativeIdOf(entityId)).isEqualTo(kept)
        // 展示字段由**新代表文件**的元数据回填（I11），不是留在旧值上
        assertThat(db.scalar("SELECT album_name FROM song_entity WHERE id = $entityId"))
            .isEqualTo(albumOfReader)
        // 封面缓存路径作废（刷新时置 NULL），下次访问时由封面仓库重建
        assertThat(
            db.scalar(
                "SELECT COUNT(*) FROM song_entity WHERE id = $entityId AND cover_cache_path IS NULL",
            ),
        ).isEqualTo("1")
    }

    // —— 放弃、取消、交棒 ——

    @Test
    fun `放弃不写库_下次扫描仍视为新增`() = runDbTest {
        addMusicSource()
        onDisk("$musicDir/a.mp3")

        orchestrator.scan()
        orchestrator.discard()

        assertThat(orchestrator.state.value.phase).isEqualTo(ScanPhase.DISCARDED)
        assertThat(db.countOf("analysis_run")).isEqualTo(0)
        assertThat(db.countOf("music_file")).isEqualTo(0)

        // 差异是幂等的：重新扫描会再提示一次
        assertThat(orchestrator.scan()).isInstanceOf(ScanOutcome.AwaitingUser::class.java)
    }

    @Test
    fun `扫描中取消_不写库且回到_IDLE`() = runDbTest {
        addMusicSource()
        onDisk("$musicDir/a.mp3")
        val started = CountDownLatch(1)
        orchestrator = build(
            MetadataReader { ref ->
                started.countDown()
                Thread.sleep(300)
                AudioMetadata(ref.name, null, null, null, null, 0, false)
            },
        )

        val job = launch(Dispatchers.IO) { orchestrator.scan() }
        started.await(5, TimeUnit.SECONDS)
        orchestrator.cancel()
        job.join()

        assertThat(orchestrator.state.value.phase).isEqualTo(ScanPhase.IDLE)
        assertThat(db.countOf("music_file")).isEqualTo(0)
        assertThat(db.countOf("analysis_run")).isEqualTo(0)
    }

    @Test
    fun `提交后把批次与歌词候选交棒出去`() = runDbTest {
        addMusicSource()
        addLyricSource("$root/Lyrics")
        onDisk("$musicDir/a.mp3")
        onDisk("$musicDir/a.lrc")
        onDisk("$root/Lyrics/b.lrc")

        orchestrator.scan()
        val result = orchestrator.commit() as CommitResult.Committed

        assertThat(trigger.runIds).containsExactly(result.runId)
        assertThat(handoff.ingested.map { it.origin })
            .containsExactly(LyricSource.SAME_DIR, LyricSource.EXTERNAL_DIR)
    }

    @Test
    fun `没有待提交差异时提交返回_NothingToCommit`() = runDbTest {
        assertThat(orchestrator.commit())
            .isInstanceOf(CommitResult.NothingToCommit::class.java)
    }

    // —— 降级通道 ——

    @Test
    fun `降级通道走媒体库_不调用_listFiles_也不读元数据`() = runDbTest {
        access.level = StorageAccessLevel.MEDIA_LIBRARY_ONLY
        addMusicSource()
        addLyricSource("$root/Lyrics")
        onDiskViaStore("$musicDir/fromStore.mp3")

        val scanned = orchestrator.scan() as ScanOutcome.AwaitingUser

        assertThat(scanned.summary.newCount).isEqualTo(1)
        assertThat(storage.listFilesCalls).isEqualTo(0) // 一次都没走文件系统遍历
        assertThat(scanned.summary.lrcCount).isEqualTo(0) // 降级下不产出歌词候选

        orchestrator.commit()

        assertThat(db.scalar("SELECT file_name FROM music_file")).isEqualTo("fromStore.mp3")
    }

    @Test
    fun `降级通道不清理库中文件_媒体库查不到不等于磁盘没有`() = runDbTest {
        access.level = StorageAccessLevel.MEDIA_LIBRARY_ONLY
        addMusicSource()
        // 自定义目录来源下已入库、但不在媒体库里 —— 以前会被当成「已删除」清掉
        db.insertFile("$musicDir/custom.mp3", 10)

        val outcome = orchestrator.scan()

        assertThat(outcome).isInstanceOf(ScanOutcome.NoChanges::class.java)
        assertThat(db.countOf("music_file")).isEqualTo(1)
    }

    // —— 扫描过滤（需求 01 §2.5） ——

    @Test
    fun `过滤_体积不足的不读元数据也不进比对`() = runDbTest {
        addMusicSource()
        onDisk("$musicDir/tiny.mp3", size = 1_000L)
        onDisk("$musicDir/song.mp3")
        val read = mutableListOf<String>()
        orchestrator = build(MetadataReader { ref ->
            read += ref.name
            defaultMetadata(ref)
        })

        val scanned = orchestrator.scan() as ScanOutcome.AwaitingUser

        // 体积在遍历阶段就挡：连元数据都不该去解（小文件往往是碎片，解码纯浪费）
        assertThat(read.toSet()).containsExactly("song.mp3")
        assertThat(scanned.summary.newCount).isEqualTo(1)
        // 遍历计数仍是「扫到几个」（进度语义），被挡下来的也扫到过
        assertThat(orchestrator.state.value.discovered).isEqualTo(2)
    }

    @Test
    fun `过滤_时长不足的读了元数据但入库_且不算已存在跳过`() = runDbTest {
        addMusicSource()
        onDisk("$musicDir/ringtone.mp3")
        onDisk("$musicDir/song.mp3")
        val read = mutableListOf<String>()
        orchestrator = build(MetadataReader { ref ->
            read += ref.name
            defaultMetadata(ref).copy(
                durationMs = if (ref.name == "ringtone.mp3") 5_000L else SONG_DURATION,
            )
        })

        val scanned = orchestrator.scan() as ScanOutcome.AwaitingUser

        // 时长只有解出元数据才知道，所以这个文件**必须**被读过
        assertThat(read.toSet()).containsExactly("ringtone.mp3", "song.mp3")
        assertThat(scanned.summary.newCount).isEqualTo(1)
        // 被过滤 ≠ 已在库里：skippedCount 的语义是「库中已有」，混进来会让「跳过 N 首」没法解释
        assertThat(scanned.summary.skippedCount).isEqualTo(0)
        // 也不该出现在差异里
        assertThat(orchestrator.state.value.diff?.newFiles?.map { it.ref.name })
            .containsExactly("song.mp3")
    }

    @Test
    fun `过滤_时长未知的不挡_不能把读不出时长当成太短`() = runDbTest {
        addMusicSource()
        onDisk("$musicDir/broken.mp3")
        orchestrator = build(MetadataReader { ref ->
            AudioMetadata(ref.name, null, null, null, null, 0L, false)
        })

        val scanned = orchestrator.scan() as ScanOutcome.AwaitingUser

        // 0 是「时长未知」的哨兵，不是「0 秒」。验证不了就不判、放行
        // ——与 04 §4.6「验证不了存在性就不清理」同一原则
        assertThat(scanned.summary.newCount).isEqualTo(1)
    }

    @Test
    fun `过滤_降级通道走媒体库时同样生效`() = runDbTest {
        access.level = StorageAccessLevel.MEDIA_LIBRARY_ONLY
        addMusicSource()
        onDiskViaStore("$musicDir/tiny.mp3", size = 1_000L)
        onDiskViaStore("$musicDir/ringtone.mp3", durationMs = 5_000L)
        onDiskViaStore("$musicDir/song.mp3")

        val scanned = orchestrator.scan() as ScanOutcome.AwaitingUser

        assertThat(scanned.summary.newCount).isEqualTo(1)
        assertThat(storage.listFilesCalls).isEqualTo(0)
    }

    @Test
    fun `过滤_关掉规则后不再设限`() = runDbTest {
        addMusicSource()
        onDisk("$musicDir/tiny.mp3", size = 1_000L)
        settings.setScanFilter(minDurationMs = 0L, minSizeBytes = 0L)
        orchestrator = build(MetadataReader { ref ->
            defaultMetadata(ref).copy(durationMs = 5_000L)
        })

        val scanned = orchestrator.scan() as ScanOutcome.AwaitingUser

        assertThat(scanned.summary.newCount).isEqualTo(1)
    }

    @Test
    fun `过滤_两条规则相互独立_关一条不影响另一条`() = runDbTest {
        addMusicSource()
        onDisk("$musicDir/tiny.mp3", size = 1_000L) // 体积不合格
        onDisk("$musicDir/short.mp3") // 体积合格，但时长只有 5 秒
        settings.setScanFilter(minDurationMs = 0L, minSizeBytes = 102_400L)
        orchestrator = build(MetadataReader { ref ->
            defaultMetadata(ref).copy(durationMs = 5_000L)
        })

        val scanned = orchestrator.scan() as ScanOutcome.AwaitingUser

        // 时长规则关了 → 5 秒的文件放行；体积规则还在 → 1 KB 的照样挡。
        // 正是「关一条不动另一条」的证据。
        assertThat(scanned.summary.newCount).isEqualTo(1)
        assertThat(orchestrator.state.value.diff?.newFiles?.map { it.ref.name })
            .containsExactly("short.mp3")
    }

    private companion object {
        const val NOW = 1_700_000_000_000L

        /** 真实量级的夹具：4 MB / 3 分钟，两条默认过滤规则都过得去。 */
        const val SONG_SIZE = 4_000_000L
        const val SONG_DURATION = 180_000L
    }
}

// —— 假协作者 ——

private class FakeStorage : StorageSource {
    val refs = mutableListOf<FileRef>()
    val existing = mutableSetOf<String>()
    val dirs = mutableSetOf<String>()
    val deleted = mutableListOf<String>()
    var listFilesCalls = 0

    override fun listFiles(
        roots: List<String>,
        exts: Set<String>,
        onProgress: (Int) -> Unit,
    ): Sequence<FileRef> = sequence {
        listFilesCalls++
        val wanted = exts.map { it.lowercase() }.toSet()
        var found = 0
        refs.forEach { ref ->
            // 必须尊重 roots：不认 roots 的话，外部歌词目录与同源目录会互相污染，
            // LrcCandidate.origin 的判定就失真了
            val underRoot = roots.any { ref.path.startsWith("$it/") }
            if (underRoot && AudioFormats.formatOf(ref.name) in wanted) {
                found++
                onProgress(found)
                yield(ref)
            }
        }
    }

    override fun exists(path: String) = path in existing
    override fun isDirectory(path: String) = path in dirs
    override fun listDirectories(parent: String) = emptyList<FileRef>()
    override fun size(path: String) = 0L
    override fun readBytes(path: String, maxBytes: Int) = ByteArray(0)
    override fun canWrite() = true

    override fun delete(path: String): Boolean {
        deleted += path
        return true
    }
}

private class FakeAccess(var level: StorageAccessLevel) : StorageAccessChecker {
    override fun currentLevel(context: Context) = level
    override fun allFilesAccessIntent(context: Context) = Intent()
    override fun runtimePermissions() = emptyArray<String>()
}

private class FakeMediaStore : MediaStoreAudioSource {
    val entries = mutableListOf<MediaStoreAudioEntry>()
    var calls = 0

    override fun query(onProgress: (Int) -> Unit): List<MediaStoreAudioEntry> {
        calls++
        entries.forEachIndexed { i, _ -> onProgress(i + 1) }
        return entries
    }
}

private class RecordingTrigger : AnalysisTrigger {
    val runIds = mutableListOf<Long>()
    override suspend fun analyzePending(runId: Long) {
        runIds += runId
    }
}

private class RecordingHandoff : LyricHandoff {
    val ingested = mutableListOf<LrcCandidate>()
    override suspend fun ingest(candidates: List<LrcCandidate>) {
        ingested += candidates
    }
}
