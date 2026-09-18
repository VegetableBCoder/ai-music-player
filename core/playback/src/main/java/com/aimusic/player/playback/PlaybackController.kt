package com.aimusic.player.playback

import com.aimusic.player.playback.model.PlaybackUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放控制（`07 §3.4`）：feature 模块与播放器之间的**唯一入口**。
 *
 * 三个入队入口都必须做**不可用歌曲过滤（I3）**，这是 `06 §8` 点名要在本阶段验的行为：
 * - [playFromList]：用实体 id 列表新建队列，并从 `startIndex` 开始播。
 * - [appendToQueue]：追加到队列末尾。
 * - [insertNext]：强制插队到「当前项 position + 1」（`07 §4.4`；同一首可插多次，不去重）。
 *
 * 实现方可以是真实播放器（Phase 6 的 `PlaybackControllerImpl`），也可以是过渡实现 ——
 * 调用方只依赖本接口，替换实现时无需改动。
 */
interface PlaybackController {

    fun playFromList(songs: List<Long>, startIndex: Int)

    fun appendToQueue(songId: Long)

    fun insertNext(songId: Long)

    val state: StateFlow<PlaybackUiState>
}
