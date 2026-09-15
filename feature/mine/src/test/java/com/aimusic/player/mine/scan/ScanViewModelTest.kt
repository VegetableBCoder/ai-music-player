package com.aimusic.player.mine.scan

import android.content.Context
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.data.entity.ScanSourceEntity
import com.aimusic.player.data.model.SourceKind
import com.aimusic.player.data.scan.AddSourceResult
import com.aimusic.player.data.scan.CommitResult
import com.aimusic.player.data.scan.ScanOrchestrator
import com.aimusic.player.data.scan.ScanOutcome
import com.aimusic.player.data.scan.ScanSessionState
import com.aimusic.player.data.scan.ScanSourceRepository
import com.aimusic.player.data.scan.ScanSummary
import com.aimusic.player.data.settings.ScanFilter
import com.aimusic.player.data.settings.SettingsRepository
import com.aimusic.player.storage.StorageAccessLevel
import com.aimusic.player.storage.StorageSource
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 扫描页 ViewModel 的单元测试（JVM，`11 §8.1`）。
 *
 * 三个协作者全部用 MockK 顶掉；要钉住的是**跨模块才暴露的编排逻辑**：权限守卫、
 * 预设来源只采纳真实存在的、提交流程的文案、以及两条过滤规则是否真的互不干扰。
 * 渲染层由 `ScanScreenContentTest` 管，这些用例不需要真机。
 *
 * `ScanSummary` 直接 mock：NoChanges 这一支里 ViewModel 根本不看它（它读的是
 * `state.discovered`），为了造一个夹具去猜它的字段反而是给测试加没用的耦合。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

    private val main = UnconfinedTestDispatcher()

    private val context: Context = mockk(relaxed = true)
    private val orchestrator: ScanOrchestrator = mockk(relaxed = true)
    private val sources: ScanSourceRepository = mockk(relaxed = true)
    private val settings: SettingsRepository = mockk(relaxed = true)
    private val storage: StorageSource = mockk(relaxed = true)
    private val summary: ScanSummary = mockk(relaxed = true)

    private val session = MutableStateFlow(ScanSessionState(accessLevel = StorageAccessLevel.FULL))
    private val sourceList = MutableStateFlow<List<ScanSourceEntity>>(emptyList())
    private val filter = MutableStateFlow(ScanFilter(minDurationMs = 0L, minSizeBytes = 0L))

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        every { orchestrator.state } returns session
        every { sources.observeSources(any()) } returns sourceList
        every { settings.scanFilter } returns filter
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * 建 VM 并让 `state` 热起来。
     *
     * `stateIn(WhileSubscribed)` 没有订阅者时值一直停在 `initialValue`，断言就会读到默认值 ——
     * 这种"看起来在断言、其实读的是初值"的假绿，比没有测试更糟。
     */
    private fun TestScope.newViewModel(): ScanViewModel {
        val vm = ScanViewModel(context, orchestrator, sources, settings, storage, ROOT)
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    /** 扫描真的跑起来的前提：有来源。没有来源时会走「一键扫描」，压根到不了 `scan()`。 */
    private fun givenSource() {
        sourceList.value = listOf(
            ScanSourceEntity(id = 9L, kind = SourceKind.MUSIC, path = "$ROOT/Music", createdAt = 0L),
        )
    }

    // —— 权限守卫（10 §4.2 / 09 §5.1 第一行） ——

    @Test
    fun `无权限时开始扫描_只请求授权_不发起扫描`() = runTest(main) {
        session.value = session.value.copy(accessLevel = StorageAccessLevel.NONE)
        val vm = newViewModel()

        vm.onStartScan()

        assertThat(vm.events.first()).isEqualTo(ScanEvent.RequestAllFilesPermission)
        coVerify(exactly = 0) { orchestrator.scan() }
    }

    @Test
    fun `构造时查一次权限_刷新时再查一次`() = runTest(main) {
        val vm = newViewModel()
        // 10 §4.2.1：启动时就要知道门禁状态，否则「去授权」按钮永远不出现
        verify(exactly = 1) { orchestrator.refreshAccessLevel(context) }

        vm.refreshPermission()

        verify(exactly = 2) { orchestrator.refreshAccessLevel(context) }
    }

    // —— 一键扫描：没有来源时自动补预设，且只补真实存在的（01 §2.4） ——

    @Test
    fun `没有来源时_自动补上真实存在的预设目录再扫`() = runTest(main) {
        coEvery { storage.exists(any()) } returns false
        coEvery { storage.exists("$ROOT/Music") } returns true
        coEvery { storage.isDirectory("$ROOT/Music") } returns true
        // 真实仓库写完库之后 observeSources 会推新值；照实模拟，否则第二次判空仍成立、不会开扫
        coEvery { sources.add(SourceKind.MUSIC, any()) } answers {
            val path = invocation.args[1] as String
            sourceList.value = sourceList.value + ScanSourceEntity(
                id = 1L,
                kind = SourceKind.MUSIC,
                path = path,
                createdAt = 0L,
            )
            AddSourceResult.Added(1L)
        }
        coEvery { orchestrator.scan() } returns ScanOutcome.AlreadyRunning
        val vm = newViewModel()

        vm.onStartScan()

        coVerify(exactly = 1) { sources.add(SourceKind.MUSIC, "$ROOT/Music") }
        // 不存在的预设目录一个都不许写进去
        coVerify(exactly = 0) { sources.add(SourceKind.MUSIC, "$ROOT/Download") }
        coVerify(exactly = 0) { sources.add(SourceKind.MUSIC, "$ROOT/netease/cloudmusic/Music") }
        coVerify(exactly = 1) { orchestrator.scan() }
    }

    @Test
    fun `一个预设目录都不存在_给提示且不扫描`() = runTest(main) {
        coEvery { storage.exists(any()) } returns false
        val vm = newViewModel()

        vm.onStartScan()

        assertThat(message(vm)).isEqualTo(ErrorText.resolve("scan.one_click.empty"))
        coVerify(exactly = 0) { orchestrator.scan() }
    }

    @Test
    fun `已有来源时不补预设_直接扫`() = runTest(main) {
        givenSource()
        coEvery { orchestrator.scan() } returns ScanOutcome.AlreadyRunning
        val vm = newViewModel()

        vm.onStartScan()

        coVerify(exactly = 0) { sources.add(any(), any()) }
        coVerify(exactly = 1) { orchestrator.scan() }
    }

    // —— 空状态（09 §5.1） ——

    @Test
    fun `一首都没扫到_落成没有扫到音乐`() = runTest(main) {
        givenSource()
        coEvery { orchestrator.scan() } returns ScanOutcome.NoChanges(summary)
        val vm = newViewModel()

        vm.onStartScan()

        assertThat(vm.state.value.emptyNotice).isEqualTo(ScanEmptyNotice.NO_MUSIC_FOUND)
    }

    @Test
    fun `扫到了但都是旧的_落成没有新文件`() = runTest(main) {
        givenSource()
        coEvery { orchestrator.scan() } returns ScanOutcome.NoChanges(summary)
        val vm = newViewModel()
        // discovered > 0：扫到过东西，只是没有新增
        session.value = session.value.copy(discovered = 42)

        vm.onStartScan()

        assertThat(vm.state.value.emptyNotice).isEqualTo(ScanEmptyNotice.NO_NEW_FILES)
    }

    @Test
    fun `重新扫描会清掉上一次的空状态`() = runTest(main) {
        givenSource()
        coEvery { orchestrator.scan() } returns ScanOutcome.NoChanges(summary)
        val vm = newViewModel()
        vm.onStartScan()
        assertThat(vm.state.value.emptyNotice).isNotNull()

        // 这一趟返回 AlreadyRunning：它不会设空状态，所以留下的只能是"清干净了"
        coEvery { orchestrator.scan() } returns ScanOutcome.AlreadyRunning
        vm.onStartScan()

        assertThat(vm.state.value.emptyNotice).isNull()
    }

    // —— 提交流程（11 §8.3 的文案） ——

    @Test
    fun `导入成功_报数量并清掉空状态`() = runTest(main) {
        givenSource()
        coEvery { orchestrator.scan() } returns ScanOutcome.NoChanges(summary)
        coEvery { orchestrator.commit() } returns CommitResult.Committed(runId = 1L, inserted = 3, cleaned = 0)
        val vm = newViewModel()
        vm.onStartScan()
        assertThat(vm.state.value.emptyNotice).isNotNull()

        vm.onConfirmImport()

        assertThat(message(vm)).isEqualTo(ErrorText.resolve("scan.committed", mapOf("count" to 3)))
        assertThat(vm.state.value.emptyNotice).isNull()
    }

    @Test
    fun `没有可提交的内容_把原因原样告诉用户`() = runTest(main) {
        coEvery { orchestrator.commit() } returns CommitResult.NothingToCommit("没有需要添加的文件")
        val vm = newViewModel()

        vm.onConfirmImport()

        assertThat(message(vm)).isEqualTo("没有需要添加的文件")
    }

    @Test
    fun `加来源被拒_把理由原样告诉用户`() = runTest(main) {
        coEvery { sources.add(SourceKind.MUSIC, "/nope") } returns AddSourceResult.Invalid("路径不存在")
        val vm = newViewModel()

        vm.onAddSource("/nope")

        assertThat(message(vm)).isEqualTo("路径不存在")
    }

    // —— 两条过滤规则必须互不干扰（需求 01 §2.5） ——

    @Test
    fun `关掉时长规则_不能顺手把体积规则清零`() = runTest(main) {
        filter.value = ScanFilter(
            minDurationMs = SettingsRepository.DEFAULT_SCAN_MIN_DURATION_MS,
            minSizeBytes = SettingsRepository.DEFAULT_SCAN_MIN_SIZE_BYTES,
        )
        val vm = newViewModel()

        vm.onToggleDurationRule(false)

        // 典型"跨模块才暴露"：两个字段写在同一个 setter 里，很容易一起清掉
        coVerify(exactly = 1) {
            settings.setScanFilter(
                minDurationMs = 0L,
                minSizeBytes = SettingsRepository.DEFAULT_SCAN_MIN_SIZE_BYTES,
            )
        }
    }

    @Test
    fun `关掉体积规则_不能顺手把时长规则清零`() = runTest(main) {
        filter.value = ScanFilter(
            minDurationMs = SettingsRepository.DEFAULT_SCAN_MIN_DURATION_MS,
            minSizeBytes = SettingsRepository.DEFAULT_SCAN_MIN_SIZE_BYTES,
        )
        val vm = newViewModel()

        vm.onToggleSizeRule(false)

        coVerify(exactly = 1) {
            settings.setScanFilter(
                minDurationMs = SettingsRepository.DEFAULT_SCAN_MIN_DURATION_MS,
                minSizeBytes = 0L,
            )
        }
    }

    @Test
    fun `打开时长规则_写入默认阈值而不是当前值`() = runTest(main) {
        filter.value = ScanFilter(minDurationMs = 0L, minSizeBytes = 0L)
        val vm = newViewModel()

        vm.onToggleDurationRule(true)

        coVerify(exactly = 1) {
            settings.setScanFilter(
                minDurationMs = SettingsRepository.DEFAULT_SCAN_MIN_DURATION_MS,
                minSizeBytes = 0L,
            )
        }
    }

    // —— 失败路径 ——

    @Test
    fun `扫描因权限失败_重新请求授权而不是报错`() = runTest(main) {
        givenSource()
        coEvery { orchestrator.scan() } returns ScanOutcome.Failed(FailureKind.PERMISSION, null)
        val vm = newViewModel()

        vm.onStartScan()

        assertThat(vm.events.first()).isEqualTo(ScanEvent.RequestAllFilesPermission)
    }

    @Test
    fun `放弃_把差异丢掉且不写库`() = runTest(main) {
        val vm = newViewModel()

        vm.onDiscard()

        coVerify(exactly = 1) { orchestrator.discard() }
    }

    private suspend fun message(vm: ScanViewModel): String =
        (vm.events.first() as ScanEvent.ShowMessage).text

    private companion object {
        const val ROOT = "/storage/emulated/0"
    }
}
