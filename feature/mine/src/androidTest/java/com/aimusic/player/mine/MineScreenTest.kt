package com.aimusic.player.mine

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** 我的页三个入口的接线（T12）。函数名反引号不含空格（DEX 039）。 */
class MineScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `三个入口都能点_且各回调收到`() {
        var scan = false
        var analysis = false
        var settings = false
        composeRule.setContent {
            MaterialTheme {
                MineScreen(
                    onOpenScan = { scan = true },
                    onOpenAnalysis = { analysis = true },
                    onOpenSettings = { settings = true },
                )
            }
        }

        composeRule.onNodeWithText("文件扫描").performClick()
        composeRule.onNodeWithText("最近分析记录").performClick()
        composeRule.onNodeWithText("设置").performClick()

        assertThat(scan).isTrue()
        assertThat(analysis).isTrue()
        assertThat(settings).isTrue()
    }
}
