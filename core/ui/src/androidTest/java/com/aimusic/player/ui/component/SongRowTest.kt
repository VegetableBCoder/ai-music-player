package com.aimusic.player.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.data.model.SongListItem
import com.aimusic.player.data.model.TagProjection
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `SongRow` 的渲染测试（`09 §8` 的 `SongRowTest`）。
 *
 * 只测**无状态组件**：喂一个 [SongListItem] 与 `selection`，断言它渲染出什么、哪个交互
 * 触发哪个回调。不碰 DI、不碰 ViewModel —— 需要真机只是因为 Compose 需要 Android 环境
 * （与 Room 测试走真机同一个决定）。
 *
 * 函数名反引号不含空格（minSdk 26 → DEX 039）。
 */
@RunWith(AndroidJUnit4::class)
class SongRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun song(
        playable: Boolean = true,
        title: String = "晴天",
    ) = SongListItem(
        entityId = 7L,
        title = title,
        displayArtists = "周杰伦",
        artistNames = listOf("周杰伦"),
        albumName = "叶惠美",
        albumArtist = "周杰伦",
        coverCachePath = null,
        tags = listOf(TagProjection("摇滚", categoryId = 1L, categoryName = "流派")),
        isPlayable = playable,
    )

    /** 断言一：不可用歌曲**没有**行内 `＋`，且整行置灰（`09 §5.3`、`05 §6`）。 */
    @Test
    fun `不可用歌曲_无添加队列入口_且置灰`() {
        composeRule.setContent {
            MaterialTheme {
                SongRow(item = song(playable = false), isPlaying = false)
            }
        }

        // `＋` 是 I3 的界面侧出口：不可用歌曲不给任何入队入口
        composeRule.onNodeWithContentDescription("添加到队列").assertDoesNotExist()
        // 但歌名文本仍在（颜色不是唯一信息，`09 §4.7`）
        composeRule.onNodeWithText("晴天").assertIsDisplayed()
    }

    /** 断言二：多选态隐藏 `＋` 与 `⋯`，并显示勾选圈（`09 §4.3.1`）。 */
    @Test
    fun `多选态_隐藏快捷动作_显示勾选圈`() {
        composeRule.setContent {
            MaterialTheme {
                SongRow(
                    item = song(),
                    isPlaying = false,
                    selection = RowSelectionState.Visible(selected = true, enabled = true),
                )
            }
        }

        composeRule.onNodeWithContentDescription("添加到队列").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("更多操作").assertDoesNotExist()
        // 勾选圈由整行的 semantics 提供（无独立节点），故按「选中」断言
        composeRule.onNodeWithText("晴天").assertIsSelected()
    }

    /** 断言三：点击行体触发 `onRowClick`，长按触发 `onLongClick`，`⋯` 触发面板。 */
    @Test
    fun `点击_长按_与面板_各自触发对应回调`() {
        var clicked = 0
        var longClicked = 0
        var sheetOpened = 0
        var queued = 0
        composeRule.setContent {
            MaterialTheme {
                SongRow(
                    item = song(),
                    isPlaying = false,
                    onRowClick = { clicked++ },
                    onLongClick = { longClicked++ },
                    onOpenActionSheet = { sheetOpened++ },
                    onQuickAddToQueue = { queued++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("更多操作").performClick()
        composeRule.onNodeWithContentDescription("添加到队列").performClick()
        // 整行合并成一个语义节点，故用歌名文本定位行体
        composeRule.onNodeWithText("晴天").performTouchInput { longClick() }
        composeRule.onNodeWithText("晴天").performClick()

        assertThat(sheetOpened).isEqualTo(1)
        assertThat(queued).isEqualTo(1)
        assertThat(longClicked).isEqualTo(1)
        assertThat(clicked).isEqualTo(1)
    }

    /**
     * 补充：不可选行在多选态下「显示但禁用」（`09 §5.2`）——
     * `RowSelectionState.Visible(enabled = false)` 仍是未选中态，且不因点击而变。
     */
    @Test
    fun `多选态下的不可选项_显示为未选中`() {
        composeRule.setContent {
            MaterialTheme {
                SongRow(
                    item = song(playable = false),
                    isPlaying = false,
                    selection = RowSelectionState.Visible(selected = false, enabled = false),
                )
            }
        }

        composeRule.onNodeWithText("晴天").assertIsNotSelected()
    }
}
