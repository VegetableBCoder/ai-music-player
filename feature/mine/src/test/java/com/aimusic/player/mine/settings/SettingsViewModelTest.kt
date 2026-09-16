package com.aimusic.player.mine.settings

import com.aimusic.player.data.settings.ApiKeyStore
import com.aimusic.player.data.settings.AppSettings
import com.aimusic.player.data.settings.SettingsRepository
import com.aimusic.player.data.model.SongSort
import com.aimusic.player.llm.ProtocolKind
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 设置页 VM 的单元测试（JVM）。
 *
 * ⚠ state 是 stateIn(WhileSubscribed) —— 别立刻读 .value，先 first { 就绪 }（T10 栽过这条）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun stored(
        provider: String? = null,
        baseUrl: String? = null,
        model: String? = null,
    ) = AppSettings(
        sortPreference = SongSort.NAME,
        lastScanAt = null,
        llmProvider = provider,
        llmBaseUrl = baseUrl,
        llmModel = model,
        llmSupportsJsonSchema = false,
        llmMaxRetries = 3,
        llmMaxTokens = 8_192,
        llmConnectTimeoutMs = 15_000L,
        llmReadTimeoutMs = 90_000L,
        llmBatchSize = 20,
        permissionHintShown = false,
        scanMinDurationMs = 60_000L,
        scanMinSizeBytes = 102_400L,
    )

    private fun newViewModel(
        settings: AppSettings = stored(),
        masked: String? = null,
    ): Pair<SettingsViewModel, SettingsRepository> {
        val repo = mockk<SettingsRepository>(relaxed = true)
        every { repo.settings } returns flowOf(settings)
        val keys = mockk<ApiKeyStore>(relaxed = true)
        every { keys.masked() } returns masked
        every { keys.read() } returns masked?.let { "sk-real" }
        return SettingsViewModel(repo, keys) to repo
    }

    @Test
    fun `四项未填全_保存禁用_并逐项标出缺哪一项`() {
        runTest {
            val (vm, _) = newViewModel()
            val ready = vm.state.first { it.presets.isNotEmpty() }

            assertThat(ready.saveEnabled).isFalse()
            assertThat(ready.missing).containsExactly(
                RequiredField.PROTOCOL, RequiredField.BASE_URL,
                RequiredField.MODEL, RequiredField.API_KEY,
            )
        }
    }

    @Test
    fun `填全四项_保存可用`() {
        runTest {
            val (vm, _) = newViewModel(
                settings = stored("OPENAI", "https://api.deepseek.com/", "deepseek-flash"),
                masked = "sk-****",
            )

            val ready = vm.state.first { it.saveEnabled }

            assertThat(ready.missing).isEmpty()
            assertThat(ready.apiKeyMasked).isEqualTo("sk-****")
        }
    }

    @Test
    fun `选中预设_只填三项_不落 presetId`() {
        runTest {
            val (vm, _) = newViewModel()
            vm.state.first { it.presets.isNotEmpty() }

            vm.onSelectPreset("deepseek")

            val after = vm.state.first { it.protocol != null }
            assertThat(after.protocol).isEqualTo(ProtocolKind.OPENAI)
            assertThat(after.baseUrl).isEqualTo("https://api.deepseek.com/")
            assertThat(after.model).isEqualTo("deepseek-flash")
            // 只填三项：key 仍缺（预设不含 key）
            assertThat(after.missing).contains(RequiredField.API_KEY)
        }
    }

    @Test
    fun `未填全时点保存_不写任何配置`() {
        runTest {
            val (vm, repo) = newViewModel()
            vm.state.first { it.presets.isNotEmpty() }

            vm.onSave()

            coVerify(exactly = 0) { repo.setLlmConfig(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }
}
