package com.aimusic.player.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.model.SongSort
import com.aimusic.player.testing.runDbTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * `SettingsRepository`（`03 §6` 的 8 个 key）。
 *
 * 空存储时的默认值也要钉住：设置页依赖它们渲染，且「没设过」与「设成默认值」在 UI 上
 * 应当没有区别。
 *
 * 文件名**每轮随机**：DataStore 落在 `filesDir/datastore/` 下，删文件容易删错路径，
 * 剩下上一次的残留会把「空存储时给出默认值」顶掉（踩过一次）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fileName = "settings_test_${UUID.randomUUID().toString().replace("-", "")}.preferences_pb"
        repo = SettingsRepository(context) { File(it.filesDir, "datastore/$fileName") }
    }

    @Test
    fun `空存储时给出默认值`() = runDbTest {
        val settings = repo.settings.first()

        assertThat(settings.sortPreference).isEqualTo(SongSort.NAME)
        assertThat(settings.lastScanAt).isNull()
        assertThat(settings.llmProvider).isNull()
        assertThat(settings.llmBaseUrl).isNull()
        assertThat(settings.llmModel).isNull()
        assertThat(settings.llmSupportsJsonSchema).isFalse()
        assertThat(settings.llmMaxRetries).isEqualTo(SettingsRepository.DEFAULT_MAX_RETRIES)
        assertThat(settings.permissionHintShown).isFalse()
    }

    @Test
    fun `写入后能读回_且是持久化而不是内存`() = runDbTest {
        repo.setSortPreference(SongSort.RECENT_PLAYED)
        repo.setLastScanAt(1_700_000_000_000L)
        repo.setLlmConfig(
            provider = "openai",
            baseUrl = "https://api.example.com/v1",
            model = "gpt-4o-mini",
            supportsJsonSchema = true,
            maxRetries = 5,
        )
        repo.setPermissionHintShown(true)

        val settings = repo.settings.first()

        assertThat(settings.sortPreference).isEqualTo(SongSort.RECENT_PLAYED)
        assertThat(settings.lastScanAt).isEqualTo(1_700_000_000_000L)
        assertThat(settings.llmProvider).isEqualTo("openai")
        assertThat(settings.llmBaseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(settings.llmModel).isEqualTo("gpt-4o-mini")
        assertThat(settings.llmSupportsJsonSchema).isTrue()
        assertThat(settings.llmMaxRetries).isEqualTo(5)
        assertThat(settings.permissionHintShown).isTrue()
    }

    @Test
    fun `排序偏好能单独订阅_供列表页使用`() = runDbTest {
        assertThat(repo.sortPreference.first()).isEqualTo(SongSort.NAME)

        repo.setSortPreference(SongSort.COMPLETE_COUNT)

        assertThat(repo.sortPreference.first()).isEqualTo(SongSort.COMPLETE_COUNT)
    }

    @Test
    fun `上次扫描时间能单独订阅_未扫描时为_null`() = runDbTest {
        assertThat(repo.lastScanAt.first()).isNull()

        repo.setLastScanAt(42L)

        assertThat(repo.lastScanAt.first()).isEqualTo(42L)
    }

    @Test
    fun `损坏的排序值回落默认而不是抛异常`() = runDbTest {
        // 直接往底层写一个枚举里没有的值：读的时候不能崩，设置页还要能打开
        repo.putRawSortPreference("NOT_A_SORT")

        assertThat(repo.sortPreference.first()).isEqualTo(SongSort.NAME)
    }
}
