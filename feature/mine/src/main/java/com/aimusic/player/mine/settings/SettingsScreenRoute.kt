package com.aimusic.player.mine.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 有状态外壳（`09 §8`）：收集 VM、把回调转进去，渲染交给无状态 Content。 */
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    SettingsScreenContent(
        state = state,
        onProtocolChange = vm::onProtocolChange,
        onBaseUrlChange = vm::onBaseUrlChange,
        onModelChange = vm::onModelChange,
        onApiKeyChange = vm::onApiKeyChange,
        onSelectPreset = vm::onSelectPreset,
        onToggleAdvanced = vm::onToggleAdvanced,
        onSave = vm::onSave,
    )
}
