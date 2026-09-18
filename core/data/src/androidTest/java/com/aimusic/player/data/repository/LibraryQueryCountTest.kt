package com.aimusic.player.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.exec
import com.aimusic.player.data.insertFile
import com.aimusic.player.data.insertSong
import com.aimusic.player.data.linkFile
import com.aimusic.player.data.model.SongScope
import com.aimusic.player.data.model.SongSort
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 防 N+1（`06 §8` 首行、`06 §4.1`）：列表装配必须**固定次数**查询，与歌曲数量无关。
 *
 * **只统计业务 `SELECT`**。Room 的 `QueryCallback` 会连它自己的内部语句一起报上来
 * （`CREATE TEMP TRIGGER room_table_modification_trigger_*`、`DROP TRIGGER`、
 * `INSERT INTO room_table_modification_log`、`BEGIN/END TRANSACTION`、建表 …），
 * 而这些**随被观察的表集变化而增减** —— 真机上实测：1 首时共 34 条、20 首时 48 条，
 * 差额全来自 trigger 重建，业务查询其实都是 3 条。不过滤就会得到一个
 * 「随行数增长」的假阳性，把一条好实现判成 N+1（这个坑踩过一次）。
 *
 * **断言「不随行数增长」，而不是钉死某个次数**：具体次数是装配实现的细节（改一次实现就变），
 * 拿它当断言会天天红。而「1 首与 20 首的业务查询条数相等」是**稳定的不变量** ——
 * 它精确表达了 `06 §4.1` 的三步装配（Step 1 取实体 + Step 2/3 按 id 批量），
 * 并且对将来可能的优化（并发调那几个批量查询）依然成立：并发只改变耗时，不改变查询条数。
 */
@RunWith(AndroidJUnit4::class)
class LibraryQueryCountTest {

    private lateinit var db: MusicDatabase
    private val queryCount = AtomicInteger()

    /** 记录业务 SQL，失败时能一眼看出混进了什么（诊断用）。 */
    private val recorded: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            // Room 2.8 的查询回调挂在 **Builder** 上：`setQueryCallback(QueryCallback, Executor)`。
            // 计划里写的 `SupportSQLiteDatabase.addQueryCallback` 是旧版 Room（2.6 之前）的 API，
            // 本工程 Room 2.8.5 的 SupportSQLiteDatabase 上**没有**该方法（已 javap 核实）。
            // 同步执行（标准库没有 Guava 的 directExecutor，直接跑 Runnable 等价）
            .setQueryCallback(
                object : RoomDatabase.QueryCallback {
                    override fun onQuery(sql: String, args: List<Any?>) {
                        if (isBusinessQuery(sql)) {
                            recorded += sql
                            queryCount.incrementAndGet()
                        }
                    }
                },
                Executor { it.run() },
            )
            .build()
    }

    @After
    fun tearDown() = db.close()

    /**
     * 是否是一条**业务查询**。
     *
     * 判据：是 `SELECT` 且不碰 Room 的内部表/机制。宁可漏统计（少算一条业务查询），
     * 也不能把 trigger / 事务算进来 —— 后者会随表集变化，制造出「次数增长」的假象。
     */
    private fun isBusinessQuery(sql: String): Boolean {
        val s = sql.trimStart()
        if (!s.startsWith("SELECT", ignoreCase = true)) return false
        // Room 的失效追踪基础设施（不是业务查询）
        return !s.contains("room_table_modification", ignoreCase = true) &&
            !s.contains("room_master_table", ignoreCase = true)
    }

    @Test
    fun 装配查询次数不随歌曲数增长() = runDbTest {
        val repo = LibraryRepositoryImpl(db)

        insertLinkedSong(0)
        queryCount.set(0)
        recorded.clear()
        repo.observeSongs(SongScope.Songs, SongSort.NAME).first()
        val forOne = queryCount.get()

        // 先证明确实统计到了业务查询：否则 0 == 0 会让下面的断言假绿
        assertThat(forOne).isGreaterThan(0)

        for (i in 1 until 20) insertLinkedSong(i)
        queryCount.set(0)
        recorded.clear()
        repo.observeSongs(SongScope.Songs, SongSort.NAME).first()
        val forTwenty = queryCount.get()

        // ① 条数不随行数增长（若逐行查，这里会变成 1 的 20 倍）
        assertThat(forTwenty).isEqualTo(forOne)
        // ② 没有哪条 SQL 被重复执行 —— 这才是 N+1 的直接特征。
        //    （不比对 SQL 字面是否逐字相同：批量查询的 `IN (?, ?, …)` 会随 id 数量变化，
        //     那是参数个数差异，不是 N+1。）
        assertThat(recorded.toList()).containsNoDuplicates()
    }

    /**
     * 维度路径同样按批量走。
     *
     * `SongScope.Artist` 比 `Songs` 多一层「先解析出该歌手下的实体」的条件，但**那一层也在同一条
     * `SELECT` 里**（`EXISTS ... artist_name = :name`，`06 §4.2.1`），不是逐行 —— 故同样不随行数增长。
     * 单独测一遍，因为这两条 SQL 走 `SongQueryBuilder` 的不同分支。
     */
    @Test
    fun 歌手维度的装配次数同样不随歌曲数增长() = runDbTest {
        val repo = LibraryRepositoryImpl(db)

        insertLinkedSong(0, artist = "梁静茹")
        queryCount.set(0)
        val one = repo.observeSongs(SongScope.Artist("梁静茹"), SongSort.NAME).first()
        val forOne = queryCount.get()

        // **必须断言查到了歌**：歌手维度查的是 `song_artist` 关联表（`06 §4.2.1`），
        // 而 `insertSong` 只写 `song_entity.artists_key`。夹具若不显式插关联行，
        // `entities` 会为空 → `assemble` 提前返回 → 只跑 1 条 SQL，
        // 断言退化成 1 == 1 **恒真**（这条测试第一版就是这么失效的，变异抽查才暴露出来）。
        assertThat(one).hasSize(1)

        for (i in 1 until 20) insertLinkedSong(i, artist = "梁静茹")
        queryCount.set(0)
        val twenty = repo.observeSongs(SongScope.Artist("梁静茹"), SongSort.NAME).first()
        val forTwenty = queryCount.get()

        assertThat(twenty).hasSize(20)
        assertThat(forTwenty).isEqualTo(forOne)
    }

    /**
     * 一首「已挂靠且有 LINKED 文件」的歌。
     *
     * 必须有 LINKED 文件：可播性与标签都是按 id 批量查的，若一件文件都没有，装配会走
     * `entities.isEmpty()` 之外的空结果分支，测不到真正的装配路径。
     *
     * 同时显式插入 `song_artist` 关联行 —— 歌手维度（`EXISTS ... artist_name`）查的是那张表，
     * 只写 `song_entity.artists_key` 的话歌手维度查不到任何歌。
     */
    private fun insertLinkedSong(index: Int, artist: String = "歌手$index") {
        val songId = db.insertSong(title = "歌$index", artistsKey = artist)
        db.exec(
            "INSERT INTO song_artist (entity_id, artist_name, position) VALUES ($songId, '$artist', 0)",
        )
        val fileId = db.insertFile(path = "/m/song$index.mp3", size = 1024, status = "LINKED")
        db.linkFile(fileId, songId, isRepresentative = true)
    }
}
