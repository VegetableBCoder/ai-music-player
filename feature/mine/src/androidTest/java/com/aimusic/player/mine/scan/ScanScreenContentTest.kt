package com.aimusic.player.mine.scan

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.data.scan.ScanPhase
import com.aimusic.player.storage.FileRef
import com.aimusic.player.storage.StorageAccessLevel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 扫描页的渲染测试（`09 §8`、`11 §8.3`）。
 *
 * 只测**无状态的 `ScanScreenContent`**：喂一个 `ScanUiState`，断言它渲染出什么、哪个按钮触发
 * 哪个回调。不碰 DI、不碰协程、不碰 ViewModel —— 需要真机只是因为 Compose 需要一个 Android
 * 环境（与 Room 测试走真机是同一个决定，`11 §8.2`）。
 *
 * 状态里的数字一律取**奇异值**（29/3/1、7），这样断言万一漏看了参数就能从数量级上看出张冠李戴。
 */
@RunWith(AndroidJUnit4::class)
class ScanScreenContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Suppress("LongParameterList")
    private fun renderContent(
        state: ScanUiState,
        onStartScan: () -> Unit = {},
        onCancelScan: () -> Unit = {},
        onConfirmImport: () -> Unit = {},
        onDiscard: () -> Unit = {},
        onToggleDurationRule: (Boolean) -> Unit = {},
        onToggleSizeRule: (Boolean) -> Unit = {},
        onRequestAllFilesAccess: () -> Unit = {},
        onPickDirectory: (String) -> Unit = {},
        listDirectories: suspend (String) -> List<FileRef> = { emptyList() },
        primaryRootPath: String = ROOT,
    ) {
        composeRule.setContent {
            MaterialTheme {
                ScanScreenContent(
                    state = state,
                    onStartScan = onStartScan,
                    onCancelScan = onCancelScan,
                    onConfirmImport = onConfirmImport,
                    onDiscard = onDiscard,
                    onToggleDurationRule = onToggleDurationRule,
                    onToggleSizeRule = onToggleSizeRule,
                    onRequestAllFilesAccess = onRequestAllFilesAccess,
                    onPickDirectory = onPickDirectory,
                    listDirectories = listDirectories,
                    primaryRootPath = primaryRootPath,
                )
            }
        }
    }

    // —— 空状态与权限（11 §8.3） ——

    @Test
    fun `无权限_渲染去授权文案与入口_点击触发回调`() {
        var requested = false
        renderContent(
            state = ScanUiState(accessLevel = StorageAccessLevel.NONE),
            onRequestAllFilesAccess = { requested = true },
        )

        composeRule.onNodeWithText(ErrorText.resolve("perm.denied")).assertIsDisplayed()
        composeRule.onNodeWithText("去授权").performClick()

        assertThat(requested).isTrue()
    }

    @Test
    fun `没有来源_渲染请先选择来源的提示`() {
        renderContent(state = ScanUiState(accessLevel = StorageAccessLevel.FULL))

        composeRule.onNodeWithText(ErrorText.resolve("scan.need_source")).assertIsDisplayed()
    }

    @Test
    fun `降级通道_自定义扫描置灰_并给出降级提示与去授权入口`() {
        var requested = false
        renderContent(
            state = ScanUiState(accessLevel = StorageAccessLevel.MEDIA_LIBRARY_ONLY),
            onRequestAllFilesAccess = { requested = true },
        )

        // 10 §4.3：降级时「任意目录来源选择」置灰，且要给「去授权」入口 —— 不能只是灰着
        composeRule.onNodeWithText("自定义扫描").assertIsNotEnabled()
        composeRule.onNodeWithText("仅扫描媒体库").assertIsDisplayed()
        composeRule.onNodeWithText("去授权").performClick()

        assertThat(requested).isTrue()
    }

    // —— 相位驱动的渲染与主按钮 ——

    @Test
    fun `扫描中_渲染已发现数_主按钮是取消`() {
        var cancelled = false
        renderContent(
            state = ScanUiState(
                accessLevel = StorageAccessLevel.FULL,
                phase = ScanPhase.SCANNING,
                discoveredCount = 7,
            ),
            onCancelScan = { cancelled = true },
        )

        composeRule.onNodeWithText(ErrorText.resolve("scan.running", mapOf("new" to 7)))
            .assertIsDisplayed()
        composeRule.onNodeWithText("取消").performClick()

        assertThat(cancelled).isTrue()
    }

    @Test
    fun `有差异_三项计数各自渲染_两个按钮各触发自己的回调`() {
        var committed = false
        var discarded = false
        renderContent(
            state = ScanUiState(
                accessLevel = StorageAccessLevel.FULL,
                phase = ScanPhase.AWAITING_USER,
                diff = ScanDiffUi(newCount = 29, skippedCount = 3, cleanedCount = 1),
            ),
            onConfirmImport = { committed = true },
            onDiscard = { discarded = true },
        )

        // 三个数分开断言：混成一个「有差异」的断言会让「数字串位」溜过去
        composeRule.onNodeWithText("新增 29", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("已存在跳过 3", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("已删除清理 1", substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("分析并添加").performClick()
        composeRule.onNodeWithText("放弃").performClick()

        assertThat(committed).isTrue()
        assertThat(discarded).isTrue()
    }

    // —— 扫描设置（需求 01 §2.5：两条规则相互独立） ——

    @Test
    fun `扫描设置_两条规则各自独立开关_关一条不动另一条`() {
        var duration: Boolean? = null
        var size: Boolean? = null
        renderContent(
            state = ScanUiState(
                accessLevel = StorageAccessLevel.FULL,
                scanMinDurationMs = 60_000L,
                scanMinSizeBytes = 0L,
            ),
            onToggleDurationRule = { duration = it },
            onToggleSizeRule = { size = it },
        )

        composeRule.onNodeWithText("扫描设置").performClick()

        // 时长规则当前开着 → 点它给 false；体积规则当前关着 → 点它给 true
        composeRule.onNodeWithText("不扫描短于 60 秒的音频").performClick()
        assertThat(duration).isFalse()
        // 关键：关时长那一条**不能**顺带动到体积那条
        assertThat(size).isNull()

        composeRule.onNodeWithText("不扫描小于 100 KB 的文件").performClick()
        assertThat(size).isTrue()
        assertThat(duration).isFalse()
    }

    // —— 目录浏览器（需求 01 §2.4：目录选择在应用内完成） ——

    @Test
    fun `目录浏览器_下钻与上一级_确认时回传当前目录`() {
        var picked: String? = null
        val tree = mapOf(
            ROOT to listOf(FileRef("$ROOT/Music", "Music", 0L, 0L)),
            "$ROOT/Music" to listOf(FileRef("$ROOT/Music/周杰伦", "周杰伦", 0L, 0L)),
        )
        renderContent(
            state = ScanUiState(accessLevel = StorageAccessLevel.FULL),
            onPickDirectory = { picked = it },
            listDirectories = { tree[it].orEmpty() },
        )

        composeRule.onNodeWithText("自定义扫描").performClick()

        // 起点是主存储根，只能看到它下面的 Music
        composeRule.onNodeWithText("Music").performClick()
        composeRule.onNodeWithText("周杰伦").assertIsDisplayed()

        // 上一级回到根，再下去一次，确认时回传的是**深层路径**而不是起点
        composeRule.onNodeWithText("上一级").performClick()
        composeRule.onNodeWithText("Music").performClick()
        composeRule.onNodeWithText("选择音乐来源").performClick()

        assertThat(picked).isEqualTo("$ROOT/Music")
    }

    @Test
    fun `目录浏览器_空目录给出提示_而不是一片空白`() {
        renderContent(
            state = ScanUiState(accessLevel = StorageAccessLevel.FULL),
            listDirectories = { emptyList() },
        )

        composeRule.onNodeWithText("自定义扫描").performClick()

        composeRule.onNodeWithText("该目录下没有子目录").assertIsDisplayed()
    }

    private companion object {
        const val ROOT = "/storage/emulated/0"
    }
}
