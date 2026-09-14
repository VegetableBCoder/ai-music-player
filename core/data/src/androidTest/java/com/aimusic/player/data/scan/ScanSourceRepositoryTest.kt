package com.aimusic.player.data.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.MusicFileEntity
import com.aimusic.player.data.model.SourceKind
import com.aimusic.player.storage.FileStorageSource
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 扫描来源管理（`04 §3.4`）。
 *
 * 用**真实目录**而不是假路径：`add` 的拒绝理由里有两条依赖真实文件系统（不存在 / 不是目录），
 * 假路径测不到它们。
 */
@RunWith(AndroidJUnit4::class)
class ScanSourceRepositoryTest {

    private lateinit var db: MusicDatabase
    private lateinit var repo: ScanSourceRepository
    private lateinit var root: File
    private lateinit var musicDir: File
    private lateinit var notADir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "scan-source-test").apply { mkdirs() }
        musicDir = File(root, "Music").apply { mkdirs() }
        notADir = File(root, "a.mp3").apply { writeText("x") }

        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ScanSourceRepositoryImpl(db, FileStorageSource(primaryRoot = root.absolutePath))
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private suspend fun sourcesOf(kind: SourceKind) = repo.observeSources(kind).first()

    @Test
    fun `添加目录来源后能在订阅里看到`() = runDbTest {
        val result = repo.add(SourceKind.MUSIC, musicDir.absolutePath)

        assertThat(result).isInstanceOf(AddSourceResult.Added::class.java)
        val sources = sourcesOf(SourceKind.MUSIC)
        assertThat(sources.map { it.path }).containsExactly(musicDir.absolutePath)
        assertThat(sources.single().enabled).isTrue()
    }

    @Test
    fun `重复添加同一路径返回已存在的_id_且不新建行`() = runDbTest {
        val first = repo.add(SourceKind.MUSIC, musicDir.absolutePath) as AddSourceResult.Added

        val second = repo.add(SourceKind.MUSIC, musicDir.absolutePath)

        assertThat(second).isEqualTo(AddSourceResult.AlreadyExists(first.id))
        assertThat(sourcesOf(SourceKind.MUSIC)).hasSize(1)
    }

    @Test
    fun `两种_kind_各自独立_同一路径可同时是音乐来源与歌词来源`() = runDbTest {
        assertThat(repo.add(SourceKind.MUSIC, musicDir.absolutePath))
            .isInstanceOf(AddSourceResult.Added::class.java)
        assertThat(repo.add(SourceKind.LYRICS, musicDir.absolutePath))
            .isInstanceOf(AddSourceResult.Added::class.java)

        assertThat(sourcesOf(SourceKind.MUSIC)).hasSize(1)
        assertThat(sourcesOf(SourceKind.LYRICS)).hasSize(1)
    }

    @Test
    fun `路径不存在时拒绝`() = runDbTest {
        val result = repo.add(SourceKind.MUSIC, File(root, "并不存在").absolutePath)

        assertThat(result).isInstanceOf(AddSourceResult.Invalid::class.java)
        assertThat(sourcesOf(SourceKind.MUSIC)).isEmpty()
    }

    @Test
    fun `路径是文件而不是目录时拒绝`() = runDbTest {
        val result = repo.add(SourceKind.MUSIC, notADir.absolutePath)

        assertThat(result).isInstanceOf(AddSourceResult.Invalid::class.java)
        assertThat(sourcesOf(SourceKind.MUSIC)).isEmpty()
    }

    @Test
    fun `空路径与相对路径被拒`() = runDbTest {
        assertThat(repo.add(SourceKind.MUSIC, "   "))
            .isInstanceOf(AddSourceResult.Invalid::class.java)
        assertThat(repo.add(SourceKind.MUSIC, "Music"))
            .isInstanceOf(AddSourceResult.Invalid::class.java)

        assertThat(sourcesOf(SourceKind.MUSIC)).isEmpty()
    }

    @Test
    fun `路径前后空白被裁掉`() = runDbTest {
        repo.add(SourceKind.MUSIC, "  ${musicDir.absolutePath}  ")

        assertThat(sourcesOf(SourceKind.MUSIC).single().path).isEqualTo(musicDir.absolutePath)
    }

    @Test
    fun `enabledSources_只给启用中的来源_两个_kind_都在`() = runDbTest {
        val music = repo.add(SourceKind.MUSIC, musicDir.absolutePath) as AddSourceResult.Added
        repo.add(SourceKind.LYRICS, musicDir.absolutePath)
        repo.setEnabled(music.id, false)

        val enabled = repo.enabledSources()

        // 签名按 04 §3.4 就是无参的：编排器自己按 kind 分流（§4.1 的守卫要 MUSIC，§4.7 要 LYRICS）
        assertThat(enabled.map { it.kind }).containsExactly(SourceKind.LYRICS)
    }

    @Test
    fun `停用后仍能在订阅里看到_只是不在_enabledSources`() = runDbTest {
        val music = repo.add(SourceKind.MUSIC, musicDir.absolutePath) as AddSourceResult.Added

        repo.setEnabled(music.id, false)

        assertThat(sourcesOf(SourceKind.MUSIC).single().enabled).isFalse()
        assertThat(repo.enabledSources()).isEmpty()
    }

    @Test
    fun `移除来源不影响已入库的文件`() = runDbTest {
        val added = repo.add(SourceKind.MUSIC, musicDir.absolutePath) as AddSourceResult.Added
        db.musicFileDao().insertIgnoreAll(
            listOf(
                MusicFileEntity(
                    path = "${musicDir.absolutePath}/a.mp3",
                    fileName = "a.mp3",
                    size = 10,
                    format = "mp3",
                    addedAt = 0,
                ),
            ),
        )

        repo.remove(added.id)

        assertThat(sourcesOf(SourceKind.MUSIC)).isEmpty()
        // 扫描不重建实体、也不清库（04 §1.2）：来源删了，文件记录留着
        assertThat(db.musicFileDao().allPaths()).containsExactly("${musicDir.absolutePath}/a.mp3")
    }

    @Test
    fun `移除与停用不存在的_id_不抛异常`() = runDbTest {
        repo.remove(999L)
        repo.setEnabled(999L, false)

        assertThat(sourcesOf(SourceKind.MUSIC)).isEmpty()
    }
}
