package com.aimusic.player.mine.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aimusic.player.llm.ProtocolKind
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/** 只测无状态 Content（09 §8）。函数名反引号不含空格（minSdk 26 → DEX 039）。 */
class SettingsScreenContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: SettingsUiState,
        onSelectPreset: (String) -> Unit = {},
        onSave: () -> Unit = {},
    ) {
        composeRule.setContent {
            SettingsScreenContent(state, onSelectPreset = onSelectPreset, onSave = onSave)
        }
    }

    @Test
    fun `四项未填全_保存禁用_并逐项标出缺哪一项`() {
        render(SettingsUiState())

        composeRule.onNodeWithText("保存").assertIsNotEnabled()
        composeRule.onNodeWithText("协议（缺）").assertIsDisplayed()
        composeRule.onNodeWithText("Base URL（缺）").assertIsDisplayed()
        composeRule.onNodeWithText("模型（缺）").assertIsDisplayed()
        composeRule.onNodeWithText("API Key（缺）").assertIsDisplayed()
    }

    @Test
    fun `填全四项_保存可用_且缺项标记消失`() {
        render(
            SettingsUiState(
                protocol = ProtocolKind.OPENAI,
                baseUrl = "https://api.deepseek.com/",
                model = "deepseek-flash",
                hasApiKey = true,
                apiKeyMasked = "sk-****",
            ),
        )

        composeRule.onNodeWithText("保存").assertIsEnabled()
        composeRule.onNodeWithText("协议").assertIsDisplayed()
        composeRule.onNodeWithText("协议（缺）").assertDoesNotExist()
    }

    @Test
    fun `api_key_只显示掩码_不回显明文`() {
        render(
            SettingsUiState(
                protocol = ProtocolKind.OPENAI, baseUrl = "https://x/", model = "m",
                hasApiKey = true, apiKeyMasked = "sk-****",
            ),
        )

        composeRule.onNodeWithText("sk-****").assertIsDisplayed()
    }

    @Test
    fun `选中预设_回调带出预设_id`() {
        var selected: String? = null
        render(SettingsUiState(presets = com.aimusic.player.llm.preset.ProviderPresets.ALL), onSelectPreset = { selected = it })

        composeRule.onNodeWithText("DeepSeek 官方").performClick()

        assertThat(selected).isEqualTo("deepseek")
    }

    @Test
    fun `高级项默认折叠`() {
        render(SettingsUiState(advancedExpanded = false))

        composeRule.onNodeWithText("最大重试次数", substring = true).assertDoesNotExist()
    }

    @Test
    fun `高级项展开后才出现`() {
        render(SettingsUiState(advancedExpanded = true))

        // 界面上是"最大重试次数：3"，onNodeWithText 默认完全匹配，故用 substring
        composeRule.onNodeWithText("最大重试次数", substring = true).assertIsDisplayed()
    }
}
