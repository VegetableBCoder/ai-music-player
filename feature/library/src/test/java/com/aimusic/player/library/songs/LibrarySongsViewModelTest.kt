package com.aimusic.player.library.songs

import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.data.deletion.DeletionService
import com.aimusic.player.data.model.SongFilter
import com.aimusic.player.data.model.SongListItem
import com.aimusic.player.data.model.SongScope
import com.aimusic.player.data.model.SongSort
import com.aimusic.player.data.repository.LibraryRepository
import com.aimusic.player.data.repository.TagRepository
import com.aimusic.player.data.settings.SettingsRepository
import com.aimusic.player.playback.PlaybackController
import com.aimusic.player.playback.model.PlaybackUiState
import com.aimusic.player.ui.component.ListUiState
import com.aimusic.player.ui.component.SongAction
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * 歌曲库 VM 的单元测试（JVM，`09 §8` 的落地补充）。
 *
 * 要钉住的是**跨模块才暴露的编排逻辑** —— 尤其是 I3：不可用歌曲的任何入队 / 播放入口
 * 都不得调用 `PlaybackController`（`06 §8`、`09 §8` 的「不变量相关」行）。
 * 渲染层由 `LibrarySongsContentTest` 管，这些用例不需要真机。
 *
 * ⚠️ `state` 是 `stateIn(WhileSubscribed)` —— **必须让它热起来**再断言。
 * 直接读 `vm.state.value` 拿到的是 `initialValue`（`listState = Loading`），
 * 那样不管理用歌曲还是不可用歌曲都「没调用控制器」，测试会**假绿**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySongsViewModelTest {

    private val main = UnconfinedTestDispatcher()

    private val libraryRepository: LibraryRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val tagRepository: TagRepository = mockk(relaxed = true)
    private val playbackController: PlaybackController = mockk(relaxed = true)
    private val deletionService: DeletionService = mockk(relaxed = true)

    private val songs = MutableStateFlow<List<SongListItem>>(emptyList())

    /**
     * 播放器状态必须是**真实的 `MutableStateFlow`**。
     *
     * relaxed mockk 造出来的 `StateFlow` 只是空壳、**从不发出任何值**，而它参与 `state` 的
     * `combine` —— 有一路不发出，`combine` 就永不产出，`state` 永远停在 `initialValue`（`Loading`），
     * 于是所有断言一起假绿（这条踩过一次：14 条里 10 条误红）。
     * `feature/mine` 的 `ScanViewModelTest` 对 `orchestrator.state` 用的也是同一手法。
     */
    private val playbackState = MutableStateFlow(PlaybackUiState())

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        every { settingsRepository.sortPreference } returns flowOf(SongSort.NAME)
        every { playbackController.state } returns playbackState
        every {
            libraryRepository.observeSongs(any(), any(), any())
        } returns songs
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun newVm() = LibrarySongsViewModel(
        libraryRepository = libraryRepository,
        settingsRepository = settingsRepository,
        tagRepository = tagRepository,
        playbackController = playbackController,
        deletionService = deletionService,
    )

    private fun song(id: Long, playable: Boolean = true, title: String = "歌$id") = SongListItem(
        entityId = id,
        title = title,
        displayArtists = "歌手$id",
        artistNames = listOf("歌手$id"),
        albumName = null,
        albumArtist = null,
        coverCachePath = null,
        tags = emptyList(),
        isPlayable = playable,
    )

    /**
     * 让 `state` 热起来，避免拿 `initialValue` 断言（假绿）。`TestScope` 才有 `backgroundScope`。
     *
     * **必须指定 `UnconfinedTestDispatcher`**：`backgroundScope` 默认用 `StandardTestDispatcher`，
     * 它的 `launch` 是**惰性**的，不跑一帧就不会真正订阅，`stateIn(WhileSubscribed)` 上游因此
     * 永不启动 —— 于是读到的是 `Loading`，所有断言一起假绿（这条踩过一次，11/14 条误红）。
     */
    private fun TestScope.hotState(vm: LibrarySongsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
    }

    @Test
    fun 不可用歌曲的行内加队列不调用控制器() = runTest {
        val vm = newVm()
        hotState(vm)
        val unmatched = song(9L, playable = false)

        vm.onQuickAddToQueue(unmatched)

        verify(exactly = 0) { playbackController.appendToQueue(any()) }
    }

    @Test
    fun 不可用歌曲的点击不触发播放() = runTest {
        val vm = newVm()
        hotState(vm)
        val unmatched = song(9L, playable = false)

        vm.onRowClick(unmatched)

        verify(exactly = 0) { playbackController.playFromList(any(), any()) }
    }

    /**
     * 反证：**可用**歌曲的点击必须真的触发播放。
     *
     * 没有这条，上面两条「没调用」的断言可能只是因为整条链路压根没通 ——
     * 两条「否定断言」加一条「肯定断言」，才说明 I3 的过滤是**有判别力**的。
     */
    @Test
    fun 可用歌曲的点击触发播放_且起点是它在列表中的位置() = runTest {
        val vm = newVm()
        songs.value = listOf(song(1L), song(2L), song(3L))
        hotState(vm)

        vm.onRowClick(song(2L))

        // 列表按 [1,2,3] 顺序，第 2 首的下标是 1 —— 传错下标会从别的歌开始播
        verify(exactly = 1) {
            playbackController.playFromList(listOf(1L, 2L, 3L), 1)
        }
    }

    /** 多选态下点行体是**勾选**，不是播放（`09 §3.2.1` 的交互）。 */
    @Test
    fun 多选态下点击行体只勾选不播放() = runTest {
        val vm = newVm()
        songs.value = listOf(song(1L), song(2L))
        hotState(vm)

        vm.enterMultiSelect(null)
        vm.onRowClick(song(1L))

        verify(exactly = 0) { playbackController.playFromList(any(), any()) }
        assertThat(vm.state.value.multiSelect.selectedIds).containsExactly(1L)
    }

    /** 排序 / 筛选变更即清空多选（`页面设计 §9.3`）。 */
    @Test
    fun 排序与筛选变更会清空多选() = runTest {
        val vm = newVm()
        songs.value = listOf(song(1L))
        hotState(vm)

        vm.enterMultiSelect(1L)
        assertThat(vm.state.value.multiSelect.isActive).isTrue()

        vm.onSortChange(SongSort.RECENT_PLAYED)
        assertThat(vm.state.value.multiSelect.selectedIds).isEmpty()

        vm.enterMultiSelect(1L)
        vm.onFilterChange(SongFilter(titleQuery = "歌"))
        assertThat(vm.state.value.multiSelect.selectedIds).isEmpty()
    }

    /** 空列表 + 无筛选 → `Empty`（库空），与 `EmptyFiltered` 的动作不同（`09 §5.1`）。 */
    @Test
    fun 无筛选且无歌曲时是库空态() = runTest {
        val vm = newVm()
        songs.value = emptyList()
        hotState(vm)

        assertThat(vm.state.value.listState).isEqualTo(ListUiState.Empty)
    }

    /** 空列表 + 有筛选 → `EmptyFiltered`（筛选无命中）。 */
    @Test
    fun 有筛选且无结果时是筛选无命中态() = runTest {
        val vm = newVm()
        songs.value = emptyList()
        hotState(vm)

        vm.onFilterChange(SongFilter(titleQuery = "不存在的歌"))

        assertThat(vm.state.value.listState).isEqualTo(ListUiState.EmptyFiltered)
    }

    /** I3 的提示文案取自 `ErrorText`（不得新造词面）。 */
    @Test
    fun 不可用歌曲点击后发出一条提示事件() = runTest {
        val vm = newVm()
        hotState(vm)
        val events = mutableListOf<LibrarySongsEvent>()
        // 收集协程同样要用 Unconfined 才会立即订阅（否则 Channel 里的事件没人收）
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }

        vm.onRowClick(song(9L, playable = false))

        val message = events.filterIsInstance<LibrarySongsEvent.ShowMessage>().single()
        assertThat(message.message.text).isEqualTo(ErrorText.resolve("song.unavailable"))
    }

    /** 面板动作「删除」走二次确认，**不直接删**（`09 §4.6`）。 */
    @Test
    fun 面板删除先弹二次确认而不直接删() = runTest {
        val vm = newVm()
        songs.value = listOf(song(1L))
        hotState(vm)

        vm.onAction(song(1L), SongAction.DELETE)

        assertThat(vm.state.value.confirmation?.ids).containsExactly(1L)
        // 只弹确认、还没真删（deleteSong 是 suspend，用 coVerify）
        coVerify(exactly = 0) { deletionService.deleteSong(any()) }
    }

    /** 确认后才真删，并退出多选（`09 §4.6` / `§8` 的 `ConfirmDialogTest` 语义）。 */
    @Test
    fun 确认后调用删除并退出多选() = runTest {
        val vm = newVm()
        songs.value = listOf(song(1L))
        hotState(vm)

        vm.enterMultiSelect(1L)
        vm.onBatchDelete()
        vm.onConfirm(vm.state.value.confirmation!!)

        // deleteSong 是 suspend 的，必须用 coVerify（verify 里不能调挂起函数）
        coVerify(exactly = 1) { deletionService.deleteSong(1L) }
        assertThat(vm.state.value.multiSelect.selectedIds).isEmpty()
        assertThat(vm.state.value.confirmation).isNull()
    }

    /** 可选集只含**可播**的歌：不可用歌曲不可勾选（I3）。 */
    @Test
    fun 可选集排除不可用歌曲() = runTest {
        val vm = newVm()
        songs.value = listOf(song(1L, playable = true), song(2L, playable = false))
        hotState(vm)

        assertThat(vm.state.value.multiSelect.selectableIds).containsExactly(1L)
    }

    /** `全部播放` 用的是当前列表的全部 id（含不可用？不 —— 入队过滤在控制器侧，见 07 §4.10）。 */
    @Test
    fun 全部播放把当前列表的id按顺序交给控制器() = runTest {
        val vm = newVm()
        songs.value = listOf(song(1L), song(2L))
        hotState(vm)

        vm.onPlayAll()

        verify(exactly = 1) { playbackController.playFromList(listOf(1L, 2L), 0) }
    }

    /** 空列表时 `全部播放` 不该发起调用（没有可播的东西）。 */
    @Test
    fun 空列表时全部播放不调用控制器() = runTest {
        val vm = newVm()
        songs.value = emptyList()
        hotState(vm)

        vm.onPlayAll()

        verify(exactly = 0) { playbackController.playFromList(any(), any()) }
    }

    /** 歌曲库固定用 `SongScope.Songs` 查（不是歌手 / 专辑 / 标签）。 */
    @Test
    fun 歌曲库按Songs维度查询() = runTest {
        val vm = newVm()
        hotState(vm)

        vm.onSortChange(SongSort.NAME)

        verify { libraryRepository.observeSongs(SongScope.Songs, SongSort.NAME, any()) }
    }
}
