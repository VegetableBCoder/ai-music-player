package com.aimusic.player.mine.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aimusic.player.llm.ProtocolKind

/**
 * 设置页（`09 §3.2.13`）。无状态 Content —— 只吃 state 与回调，可在真机上直接渲染断言。
 *
 * 三条界面规矩：
 * 1. 四项必填**逐项标出缺哪个**（`协议（缺）`），而不是只在保存键上体现；
 * 2. api key **只显示掩码**，明文只在输入框里由用户新输入；
 * 3. 预设是填表助手：点一下就把协议/baseUrl/模型填好，用户可继续改。
 *
 * 静态标签（协议 / Base URL / 模型 / …）是界面文案，用字面量；错误与状态文案一律走 `ErrorText`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    state: SettingsUiState,
    onProtocolChange: (ProtocolKind) -> Unit = {},
    onBaseUrlChange: (String) -> Unit = {},
    onModelChange: (String) -> Unit = {},
    onApiKeyChange: (String) -> Unit = {},
    onSelectPreset: (String) -> Unit = {},
    onToggleAdvanced: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (state.presets.isNotEmpty()) {
                Text("内置提供商（点一下填好，可继续修改）", style = MaterialTheme.typography.labelLarge)
                Row {
                    state.presets.forEach { preset ->
                        OutlinedButton(
                            onClick = { onSelectPreset(preset.id) },
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text(preset.displayName) }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            RequiredFieldLabel("协议", RequiredField.PROTOCOL in state.missing)
            Row {
                ProtocolKind.entries.forEach { kind ->
                    TextButton(onClick = { onProtocolChange(kind) }) {
                        Text(if (kind == state.protocol) "● ${kind.name.lowercase()}" else kind.name.lowercase())
                    }
                }
            }

            RequiredFieldLabel("Base URL", RequiredField.BASE_URL in state.missing)
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            RequiredFieldLabel("模型", RequiredField.MODEL in state.missing)
            OutlinedTextField(
                value = state.model,
                onValueChange = onModelChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            RequiredFieldLabel("API Key", RequiredField.API_KEY in state.missing)
            if (state.apiKeyMasked != null) {
                Text(state.apiKeyMasked, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                value = state.apiKeyInput,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(if (state.hasApiKey) "已保存，留空则不改" else "粘贴你的 key") },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            TextButton(onClick = onToggleAdvanced) {
                Text(if (state.advancedExpanded) "高级（收起）" else "高级")
            }
            if (state.advancedExpanded) {
                Text("最大重试次数：${state.maxRetries}")
                Text("连接超时：${state.connectTimeoutMs} ms ／ 读取超时：${state.readTimeoutMs} ms")
                Text("max tokens：${state.maxTokens}　批大小：${state.batchSize}")
                Row {
                    Text("支持 JSON Schema")
                    Switch(checked = state.supportsJsonSchema, onCheckedChange = null)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Button(
                onClick = onSave,
                enabled = state.saveEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存") }
        }
    }
}

@Composable
private fun RequiredFieldLabel(label: String, missing: Boolean) {
    Text(
        text = if (missing) "$label（缺）" else label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp),
    )
}
