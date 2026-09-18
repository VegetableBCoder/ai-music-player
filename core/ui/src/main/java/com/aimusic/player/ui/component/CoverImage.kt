package com.aimusic.player.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 封面的加载入参（`09 §4.5.4`）。
 *
 * `cachePath` 是 `song_entity.cover_cache_path`（代表文件内嵌图提取后的缓存路径，`02 §4` 决策 4）。
 * `entityId` 参与 Coil 的 `memoryCacheKey`，故缓存路径为空时同一实体仍能复用内存缓存。
 *
 * **封面来源是文件内嵌图，不走网络**（`需求文档 01 §7.2`；`06 §4.9` 的提取路径）。
 * 联网检索歌曲信息属远期规划（`需求文档 00 §8`），与封面无关。
 */
@Immutable
data class CoverModel(
    val cachePath: String?,
    val entityId: Long,
)

/**
 * 封面图（`09 §4.5.4`）。
 *
 * **本阶段是占位实现**：只画一个底色方块，不接 Coil。
 *
 * 为什么先占位 —— `09 §4.5.4` 的契约要求 Coil 的 `CoverFetcher` 负责「未命中则提取并回写」，
 * 而那个提取能力属 `CoverRepository`（`06 §4.9`），排在**批次 3 的 Task 3.1**。
 * 本批（Task 2.1）的验收是 `SongRow` 的三条渲染断言（置灰 / 多选态隐藏 `＋` 与 `⋯` / 点击回调），
 * **都不涉及封面内容**，故先把位置与尺寸占住，Task 3.1 落地后在此处换成 `AsyncImage` 即可，
 * 调用方（`SongRow`）无需改动。
 */
@Composable
fun CoverImage(
    model: CoverModel?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            // 占位底色用容器的次表面色，避免纯色方块在深色主题下过亮
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        // 真实封面随批次 3 落地（Task 3.1 的 CoverRepository + Coil CoverFetcher）
    }
}
