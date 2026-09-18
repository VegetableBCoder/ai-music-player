package com.aimusic.player.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 音乐库的四个浏览维度（`页面设计 §2`：`歌曲 | 歌手 | 专辑 | 标签`）。 */
enum class LibraryDimension(val label: String) {
    SONGS("歌曲"),
    ARTISTS("歌手"),
    ALBUMS("专辑"),
    TAGS("标签"),
}

/**
 * 音乐库顶部维度切换（`页面设计 §2` 第 43 行）。
 *
 * **为什么必须有这个控件** —— 它不只是「方便」：四个维度各有独立路由
 * （`09 §3.1` 的 `library/songs|artists|albums|tags`），而**发现它们的唯一入口就在这里**。
 * 批次 1 交付导航时缺了它，导致 `ArtistsRoute` / `AlbumsRoute` / `TagsHubRoute`
 * 三条路由注册了却**没有任何调用点**、真机进不去（见计划里 Task 1.5 的验收勘误）。
 *
 * 四个维度的屏各自渲染一份（而不是由某个容器统一持有），因为它们本就是四个独立路由 —— 
 * 这样每个维度都能独立返回、独立恢复状态，也不必为一个 Tab 引入嵌套图。
 *
 * 无状态：只吃当前维度与切换回调，路由跳转由调用方（`AppNavHost`）负责。
 */
@Composable
fun LibraryDimensionTabs(
    current: LibraryDimension,
    onSelect: (LibraryDimension) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(selectedTabIndex = current.ordinal, modifier = modifier.fillMaxWidth()) {
        LibraryDimension.entries.forEach { dimension ->
            Tab(
                selected = dimension == current,
                onClick = { if (dimension != current) onSelect(dimension) },
                text = { Text(dimension.label) },
            )
        }
    }
}
