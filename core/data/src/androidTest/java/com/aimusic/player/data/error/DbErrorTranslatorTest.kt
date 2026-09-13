package com.aimusic.player.data.error

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.common.error.ConflictType
import com.aimusic.player.data.dao.CategoryDao
import com.aimusic.player.data.dao.TagDao
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `11 §6.2`：`SQLiteConstraintException` 是实现细节，绝不允许穿透到 Repository 之上。
 *
 * 这里在**真机上撞真实的约束**（不是构造假报文），验证翻译结果；`SQLiteConstraintException`
 * 在 JVM 的 android.jar 桩上是不可构造的（构造函数抛 `Stub!`），所以只能放 androidTest。
 */
@RunWith(AndroidJUnit4::class)
class DbErrorTranslatorTest {

    private lateinit var db: MusicDatabase
    private lateinit var tagDao: TagDao
    private lateinit var categoryDao: CategoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tagDao = db.tagDao()
        categoryDao = db.categoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun rawInsertTag(name: String, categoryId: Long) =
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO tag (name, category_id, is_builtin, created_at) " +
                "VALUES ('$name', $categoryId, 0, 0)"
        )

    @Test
    fun `真实的重复标签主键冲突被翻译成重复标签`() = runDbTest {
        val category = categoryDao.addCategory("曲风")
        rawInsertTag("抒情", category)

        // 绕开 IGNORE 策略再撞一次主键 —— 真机 SQLite 会抛 SQLiteConstraintException
        val type = DbErrorTranslator.translate(
            block = {
                rawInsertTag("抒情", category)
                error("不该走到这里：第二次插入本应被主键拒绝")
            },
            onConflict = { it },
        )

        assertThat(type).isEqualTo(ConflictType.DUPLICATE_TAG)
    }

    @Test
    fun `无法识别的约束报文原样抛出，不编造语义`() = runDbTest {
        val odd = SQLiteConstraintException("unheard-of constraint failure")

        val thrown = runCatching {
            DbErrorTranslator.translate(
                block = { throw odd },
                onConflict = { ConflictType.DUPLICATE_TAG },
            )
        }.exceptionOrNull()

        assertThat(thrown).isSameInstanceAs(odd)
    }

    @Test
    fun `没有抛出时原样返回结果`() = runDbTest {
        val result = DbErrorTranslator.translate(
            block = { tagDao.songCountOfTag("不存在的标签") },
            onConflict = { -1 },
        )

        assertThat(result).isEqualTo(0)
    }
}
