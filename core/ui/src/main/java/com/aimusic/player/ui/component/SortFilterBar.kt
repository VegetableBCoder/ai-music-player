package com.aimusic.player.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aimusic.player.data.model.SongFilter
import com.aimusic.player.data.model.SongSort

/** 排序选项的中文标签（`09 §4.5.1`：`名称 / 最近播放 / 完播次数`）。 */
fun SongSort.label(): String = when (this) {
    SongSort.NAME -> "名称"
    SongSort.RECENT_PLAYED -> "最近播放"
    SongSort.COMPLETE_COUNT -> "完播次数"
}

/**
 * 列表表头（`09 §4.5.1`）：`全部播放(N)` + 排序菜单 + 筛选。
 *
 * 四个维度（歌曲 / 歌手 / 专辑 / 标签歌曲）与搜索结果共用 —— 与 `SongRow` 同属「列表通用件」。
 *
 * **排序 / 筛选变更由调用方负责重置多选**（`页面设计 §9.3`）：本组件只发新值，
 * 状态与连带影响都在 ViewModel（`09 §4.5.1` 的末条）。
 *
 * 排序菜单文案是界面标签，按既有惯例直接写字面（`09 §4.5.1` 已锁定这三个词）。
 */
@Composable
fun SongScopeHeader(
    totalCount: Int,
    sort: SongSort,
    filter: SongFilter,
    onPlayAll: () -> Unit = {},
    onSortChange: (SongSort) -> Unit = {},
    onFilterChange: (SongFilter) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    // 本地文本只服务于输入框；外部 filter 变化（如「清除筛选」）时随之复位
    var query by remember(filter.titleQuery) { mutableStateOf(filter.titleQuery.orEmpty()) }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onPlayAll,
                enabled = totalCount > 0,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("全部播放($totalCount)")
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 排序菜单
                TextButton(
                    onClick = { sortMenuOpen = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(sort.label())
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "排序")
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    SongSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label()) },
                            onClick = {
                                sortMenuOpen = false
                                onSortChange(option)
                            },
                        )
                    }
                }

                // 筛选开关（展开后出现名称输入与歌词开关）
                TextButton(
                    onClick = { filterExpanded = !filterExpanded },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (filter.isEmpty) "筛选" else "筛选 ·")
                }
            }
        }

        if (filterExpanded) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        onFilterChange(filter.copy(titleQuery = it.ifBlank { null }))
                    },
                    label = { Text("按名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterChip(
                    selected = filter.hasLyricOnly,
                    onClick = { onFilterChange(filter.copy(hasLyricOnly = !filter.hasLyricOnly)) },
                    label = { Text("按歌词") },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (!filter.isEmpty) {
            Text(
                text = "已应用筛选",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
