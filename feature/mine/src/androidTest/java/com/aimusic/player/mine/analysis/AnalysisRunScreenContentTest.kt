package com.aimusic.player.mine.analysis

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.common.error.FailureKind
import com.aimusic.player.data.analysis.AnalysisProgress
import com.aimusic.player.data.model.AnalysisStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** 只测无状态 Content（09 §8）。函数名反引号不含空格（minSdk 26 → DEX 039）。 */
class AnalysisRunScreenContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun renderContent(
        state: AnalysisRunUiState,
        onRetry: (Long) -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            AnalysisRunScreenContent(state, onRetry = onRetry, onOpenSettings = onOpenSettings)
        }
    }

    @Test
    fun `分析进行中_渲染已分析数_与进度条`() {
        renderContent(AnalysisRunUiState(runId = 1, running = true, total = 20, done = 7, ok = 6, failed = 1))

        composeRule.onNodeWithText(
            ErrorText.resolve("analysis.running", mapOf("done" to 7, "total" to 20)),
        ).assertIsDisplayed()
    }

    @Test
    fun `逐文件状态_每行渲染自己的标签`() {
        renderContent(
            AnalysisRunUiState(
                runId = 1, total = 3, done = 3, ok = 2, failed = 1,
                rows = listOf(
                    AnalysisFileUi(1L, "晴天.mp3", AnalysisStatus.LINKED, 11L, null, retrying = false),
                    AnalysisFileUi(2L, "吻别.mp3", AnalysisStatus.FAILED, null, FailureKind.PARSE.name, retrying = false),
                    AnalysisFileUi(3L, "菊花台.mp3", AnalysisStatus.UNANALYZED, null, null, retrying = false),
                ),
            ),
        )

        composeRule.onNodeWithText("晴天.mp3").assertIsDisplayed()
        composeRule.onNodeWithText(ErrorText.resolve("analysis.row.failed"), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(ErrorText.resolve("analysis.row.pending")).assertIsDisplayed()
    }

    @Test
    fun `整批失败_渲染服务返回内容异常_给重试`() {
        var retried: Long? = null
        renderContent(
            AnalysisRunUiState(
                runId = 1, total = 2, done = 2, ok = 0, failed = 2,
                rows = listOf(
                    AnalysisFileUi(2L, "吻别.mp3", AnalysisStatus.FAILED, null, FailureKind.PARSE.name, false),
                ),
            ),
            onRetry = { retried = it },
        )

        composeRule.onNodeWithText(ErrorText.resolve("analysis.all_failed.parse")).assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        assertThat(retried).isEqualTo(2L)
    }

    @Test
    fun `重试中_该行显示重试中_不计失败`() {
        renderContent(
            AnalysisRunUiState(
                runId = 1, running = true, total = 20, done = 3, ok = 3, failed = 0,
                retrying = AnalysisProgress.Retrying(fileId = 3L, attempt = 2, delayMs = 2000L),
                rows = listOf(
                    AnalysisFileUi(3L, "菊花台.mp3", AnalysisStatus.ANALYZING, null, null, retrying = true),
                ),
            ),
        )

        composeRule.onNodeWithText(ErrorText.resolve("analysis.row.retrying")).assertIsDisplayed()
    }

    @Test
    fun `认证失败_给去设置入口`() {
        var toSettings = false
        renderContent(
            AnalysisRunUiState(
                runId = 1, total = 1, done = 1, ok = 0, failed = 1,
                rows = listOf(
                    AnalysisFileUi(9L, "a.mp3", AnalysisStatus.FAILED, null, FailureKind.AUTH.name, false),
                ),
            ),
            onOpenSettings = { toSettings = true },
        )

        composeRule.onNodeWithText(ErrorText.resolve("llm.auth")).assertIsDisplayed()
        composeRule.onNodeWithText("去设置").performClick()
        assertThat(toSettings).isTrue()
    }
}
