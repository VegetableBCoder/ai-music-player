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
 * `SettingsRepository`（`03 §6` 的 10 个 key）。
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
        // 扫描过滤两条规则**默认开启**（需求 01 §2.5）：默认值不是 0，而是阈值本身
        assertThat(settings.scanMinDurationMs)
            .isEqualTo(SettingsRepository.DEFAULT_SCAN_MIN_DURATION_MS)
        assertThat(settings.scanMinSizeBytes)
            .isEqualTo(SettingsRepository.DEFAULT_SCAN_MIN_SIZE_BYTES)
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
        repo.setScanFilter(minDurationMs = 0L, minSizeBytes = 5_000L)

        val settings = repo.settings.first()

        assertThat(settings.sortPreference).isEqualTo(SongSort.RECENT_PLAYED)
        assertThat(settings.lastScanAt).isEqualTo(1_700_000_000_000L)
        assertThat(settings.llmProvider).isEqualTo("openai")
        assertThat(settings.llmBaseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(settings.llmModel).isEqualTo("gpt-4o-mini")
        assertThat(settings.llmSupportsJsonSchema).isTrue()
        assertThat(settings.llmMaxRetries).isEqualTo(5)
        assertThat(settings.permissionHintShown).isTrue()
        assertThat(settings.scanMinDurationMs).isEqualTo(0L)
        assertThat(settings.scanMinSizeBytes).isEqualTo(5_000L)
    }

    @Test
    fun `扫描过滤_默认两条都开_边界为等于即通过_0_表示不启用`() = runDbTest {
        val filter = repo.scanFilter.first()
        assertThat(filter.minDurationMs).isEqualTo(60_000L)
        assertThat(filter.minSizeBytes).isEqualTo(102_400L)

        // 需求措辞是「不扫描**短于**60 秒」「不扫描**小于**100 KB」→ 等于阈值应当通过
        assertThat(filter.acceptsDuration(59_999L)).isFalse()
        assertThat(filter.acceptsDuration(60_000L)).isTrue()
        assertThat(filter.acceptsSize(102_399L)).isFalse()
        assertThat(filter.acceptsSize(102_400L)).isTrue()

        // 关掉一条就把该条写成 0：这条不再设限，另一条照旧（两条相互独立）
        repo.setScanFilter(minDurationMs = 0L, minSizeBytes = 102_400L)
        val durationOff = repo.scanFilter.first()
        assertThat(durationOff.acceptsDuration(1L)).isTrue()
        assertThat(durationOff.acceptsSize(1L)).isFalse()

        repo.setScanFilter(minDurationMs = 60_000L, minSizeBytes = 0L)
        val sizeOff = repo.scanFilter.first()
        assertThat(sizeOff.acceptsSize(1L)).isTrue()
        assertThat(sizeOff.acceptsDuration(1L)).isFalse()
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
