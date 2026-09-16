package com.aimusic.player.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 我的（`09 §3.2.8`）。
 *
 * 本期（3e）只放**已经能用**的入口：文件扫描。其余四项（最近分析记录 / 外部歌词目录 /
 * 最近播放 / 设置）各自的屏还没交付，先不放 —— 放一个点不动的入口比没有入口更糟。
 * 每项随它的阶段补进来。
 *
 * 屏幕收回调而不是 `NavHostController`：`:feature:*` 不能依赖 `:app`（`02 §2` 的依赖方向），
 * 路由定义在 `:app`。该偏离已在 `09 §4.1.2` 记录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(
    onOpenScan: () -> Unit,
    onOpenAnalysis: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    Scaffold(topBar = { TopAppBar(title = { Text("我的") }) }) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            EntryRow("文件扫描", onOpenScan)
            EntryRow("最近分析记录", onOpenAnalysis)
            EntryRow("设置", onOpenSettings)
            HorizontalDivider()
        }
    }
}

@Composable
private fun EntryRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 20.dp),
    )
}
