package com.aimusic.player.library.songs

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.data.model.SongFilter
import com.aimusic.player.data.model.SongListItem
import com.aimusic.player.data.model.SongSort
import com.aimusic.player.library.LibraryDimension
import com.aimusic.player.ui.component.ActionSheetModel
import com.aimusic.player.ui.component.ConfirmKind
import com.aimusic.player.ui.component.ConfirmRequest
import com.aimusic.player.ui.component.ListUiState
import com.aimusic.player.ui.component.MultiSelectState
import com.aimusic.player.ui.component.SongAction
import com.aimusic.player.ui.component.label
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 歌曲库 Content 的渲染测试（`09 §8` 的 `MultiSelectTest` / `ConfirmDialogTest` / `EmptyFilteredTest`）。
 *
 * 只测**无状态 `LibrarySongsContent`**：喂一个 `LibrarySongsUiState`，断言渲染与回调。
 * 需要真机只是因为 Compose 需要 Android 环境（与 Room 测试走真机同一决定）。
 * 文案一律用 `substring = true` 匹配片段，不硬编码整句。
 *
 * 函数名反引号不含空格（minSdk 26 → DEX 039）。
 */
@RunWith(AndroidJUnit4::class)
class LibrarySongsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun song(id: Long, title: String = "歌$id", playable: Boolean = true) = SongListItem(
        entityId = id,
        title = title,
        displayArtists = "歌手$id",
        artistNames = listOf("歌手$id"),
        albumName = "专辑$id",
        albumArtist = "歌手$id",
        coverCachePath = null,
        tags = emptyList(),
        isPlayable = playable,
    )

    private fun contentState(
        items: List<SongListItem>,
        multiSelect: MultiSelectState<Long> = MultiSelectState(),
        confirmation: ConfirmRequest? = null,
        actionSheet: ActionSheetModel? = null,
        sheetItem: SongListItem? = null,
        filter: SongFilter = SongFilter(),
    ) = LibrarySongsUiState(
        listState = ListUiState.Content(items),
        totalCount = items.size,
        sort = SongSort.NAME,
        filter = filter,
        multiSelect = multiSelect,
        actionSheet = actionSheet,
        currentSheetItem = sheetItem,
        confirmation = confirmation,
    )

    // —— 基本渲染 ————

    @Test
    fun `列表渲染每首歌的行`() {
        composeRule.setContent {
            MaterialTheme { LibrarySongsContent(state = contentState(listOf(song(1L, "晴天"), song(2L, "吻别")))) }
        }

        composeRule.onNodeWithText("晴天").assertIsDisplayed()
        composeRule.onNodeWithText("吻别").assertIsDisplayed()
    }

    /** `全部播放(N)` 的 N 随列表条数变（`09 §4.5.1`）。 */
    @Test
    fun `表头显示全部播放与条数`() {
        composeRule.setContent {
            MaterialTheme { LibrarySongsContent(state = contentState(listOf(song(1L), song(2L), song(3L)))) }
        }

        composeRule.onNodeWithText("全部播放(3)", substring = true).assertIsDisplayed()
    }

    /** 四个维度的切换控件在库页可见（这是三个子维度唯一的入口）。 */
    @Test
    fun `维度切换控件可见且点击回调收到目标维度`() {
        var selected: LibraryDimension? = null
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = contentState(listOf(song(1L))),
                    onSelectDimension = { selected = it },
                )
            }
        }

        composeRule.onNodeWithText("歌手").performClick()
        assertThat(selected).isEqualTo(LibraryDimension.ARTISTS)
    }

    // —— EmptyFiltered（`09 §8` 的 EmptyFilteredTest）————

    /** 筛选无命中 → 文案 +「清除筛选」出口（`09 §5.1`）。 */
    @Test
    fun `筛选无命中时给出清除筛选出口`() {
        var cleared = 0
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = contentState(
                        items = emptyList(),
                        filter = SongFilter(titleQuery = "不存在"),
                    ).copy(listState = ListUiState.EmptyFiltered),
                    onClearFilter = { cleared++ },
                )
            }
        }

        composeRule.onNodeWithText(ErrorText.resolve("library.filter.empty"), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("清除筛选").performClick()
        assertThat(cleared).isEqualTo(1)
    }

    /** 库空（无筛选）→ 引导去扫描（`09 §5.1`），**不是**「清除筛选」。 */
    @Test
    fun `库空时给出扫描音乐入口`() {
        var scanned = 0
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = LibrarySongsUiState(listState = ListUiState.Empty),
                    onNavigateToScan = { scanned++ },
                )
            }
        }

        composeRule.onNodeWithText(ErrorText.resolve("library.empty"), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("扫描音乐").performClick()
        assertThat(scanned).isEqualTo(1)
    }

    // —— MultiSelect（`09 §8` 的 MultiSelectTest）————

    /** 多选态：顶栏换成「已选 N / 全选」，`＋` 与 `⋯` 让位（`09 §4.3.1`）。 */
    @Test
    fun `多选态显示顶栏并隐藏行内快捷动作`() {
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = contentState(
                        items = listOf(song(1L)),
                        multiSelect = MultiSelectState(
                            isActive = true,
                            selectedIds = setOf(1L),
                            selectableIds = setOf(1L),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("已选 1", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("添加到队列").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("更多操作").assertDoesNotExist()
    }

    /** 多选态底部出现批量条，动作触发对应回调（`09 §4.3.3`）。 */
    @Test
    fun `多选态底部批量条的动作触发回调`() {
        var addToQueue = 0
        var delete = 0
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = contentState(
                        items = listOf(song(1L)),
                        multiSelect = MultiSelectState(
                            isActive = true,
                            selectedIds = setOf(1L),
                            selectableIds = setOf(1L),
                        ),
                    ),
                    onBatchAddToQueue = { addToQueue++ },
                    onBatchDelete = { delete++ },
                )
            }
        }

        composeRule.onNodeWithText(BATCH_ADD_TO_QUEUE).performClick()
        composeRule.onNodeWithText(BATCH_DELETE).performClick()

        assertThat(addToQueue).isEqualTo(1)
        assertThat(delete).isEqualTo(1)
    }

    /** 多选态点行体 = 勾选，**不触发播放**（`09 §3.2.1` 的交互）。 */
    @Test
    fun `多选态点击行体只勾选`() {
        var toggled: Long? = null
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = contentState(
                        items = listOf(song(7L, "晴天")),
                        multiSelect = MultiSelectState(
                            isActive = true,
                            selectableIds = setOf(7L),
                        ),
                    ),
                    onToggleSelect = { toggled = it },
                )
            }
        }

        composeRule.onNodeWithText("晴天").performClick()

        assertThat(toggled).isEqualTo(7L)
    }

    /** 非多选态点行体 = 播放（`09 §3.2.1`）。 */
    @Test
    fun `非多选态点击行体触发播放`() {
        var played: Long? = null
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = contentState(listOf(song(3L, "吻别"))),
                    onRowClick = { played = it.entityId },
                )
            }
        }

        composeRule.onNodeWithText("吻别").performClick()

        assertThat(played).isEqualTo(3L)
    }

    // —— ConfirmDialog（`09 §8` 的 ConfirmDialogTest）————

    /** 确认对话框显示数量与文案；确认后回调收到同一个 request。 */
    @Test
    fun `二次确认对话框显示并在确认后回调`() {
        val request = ConfirmRequest.deleteSongs(listOf(1L, 2L, 3L))
        var confirmed: ConfirmRequest? = null
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = contentState(listOf(song(1L)), confirmation = request),
                    onConfirm = { confirmed = it },
                )
            }
        }

        composeRule.onNodeWithText(request.title).assertIsDisplayed()
        composeRule.onNodeWithText(request.confirmLabel).performClick()
        assertThat(confirmed?.kind).isEqualTo(ConfirmKind.DELETE_SONGS)
    }

    /** 取消对话框**不**触发确认回调（`09 §8`：取消不调 deletionService）。 */
    @Test
    fun `取消二次确认不触发确认回调`() {
        var confirmed = 0
        var dismissed = 0
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = contentState(
                        listOf(song(1L)),
                        confirmation = ConfirmRequest.deleteSongs(listOf(1L)),
                    ),
                    onConfirm = { confirmed++ },
                    onDismissConfirm = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText("取消").performClick()

        assertThat(confirmed).isEqualTo(0)
        assertThat(dismissed).isEqualTo(1)
    }

    // —— 面板 ————

    /** 面板动作落到「打开面板的那一行」（由 `currentSheetItem` 定位）。 */
    @Test
    fun `面板动作带上打开面板的那一行`() {
        val target = song(5L, "菊花台")
        var acted: Pair<Long, SongAction>? = null
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = contentState(
                        items = listOf(target),
                        actionSheet = ActionSheetModel.forSong(target),
                        sheetItem = target,
                    ),
                    onAction = { item, action -> acted = item.entityId to action },
                )
            }
        }

        composeRule.onNodeWithText(SongAction.VIEW_DETAIL.label()).performClick()

        assertThat(acted).isEqualTo(5L to SongAction.VIEW_DETAIL)
    }

    /** 不可用歌曲的面板**不提供**播放相关的三项（`09 §5.3` / `§8`）。 */
    @Test
    fun `不可用歌曲的面板无播放三项`() {
        val target = song(6L, "无文件", playable = false)
        composeRule.setContent {
            MaterialTheme {
                LibrarySongsContent(
                    state = contentState(
                        items = listOf(target),
                        actionSheet = ActionSheetModel.forSong(target),
                        sheetItem = target,
                    ),
                )
            }
        }

        composeRule.onNodeWithText(SongAction.PLAY.label()).assertDoesNotExist()
        composeRule.onNodeWithText(SongAction.PLAY_NEXT.label()).assertDoesNotExist()
        composeRule.onNodeWithText(SongAction.ADD_TO_QUEUE.label()).assertDoesNotExist()
        // 详情与删除仍保留 —— 那正是用户想弄清状况或清掉它的场景
        composeRule.onNodeWithText(SongAction.VIEW_DETAIL.label()).assertIsDisplayed()
        composeRule.onNodeWithText(SongAction.DELETE.label()).assertIsDisplayed()
    }
}
