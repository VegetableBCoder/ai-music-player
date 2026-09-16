package com.aimusic.player.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.model.TagAssignment
import com.aimusic.player.data.countOf
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.exec
import com.aimusic.player.data.scalar
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLlmCacheTest {

    private lateinit var db: MusicDatabase
    private lateinit var cache: RoomLlmCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        cache = RoomLlmCache(db.llmCacheDao(), now = { 1_000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val result = NormalizeResult(
        canonicalTitle = "晴天 Live",
        artists = listOf("周杰伦"),
        tagAssignments = listOf(TagAssignment("情绪", "怀旧")),
    )

    @Test
    fun `put_后_get_原样取回`() = runDbTest {
        cache.put("k1", result)

        assertThat(cache.get("k1")).isEqualTo(result)
    }

    @Test
    fun `未写入的_key_返回_null`() = runDbTest {
        assertThat(cache.get("missing")).isNull()
    }

    @Test
    fun `同_key_覆盖写入只留一行`() = runDbTest {
        cache.put("k1", result)
        cache.put("k1", NormalizeResult("改名", listOf("A"), emptyList()))

        assertThat(cache.get("k1")!!.canonicalTitle).isEqualTo("改名")
        assertThat(db.countOf("llm_cache")).isEqualTo(1)
    }

    @Test
    fun `created_at_来自注入的时钟`() = runDbTest {
        cache.put("k1", result)

        assertThat(db.scalar("SELECT created_at FROM llm_cache WHERE cache_key = 'k1'")).isEqualTo("1000")
    }

    @Test
    fun `脏_json_视为未命中而不抛异常`() = runDbTest {
        db.exec("INSERT INTO llm_cache (cache_key, result_json, created_at) VALUES ('bad', 'not json', 0)")

        assertThat(cache.get("bad")).isNull()
    }
}