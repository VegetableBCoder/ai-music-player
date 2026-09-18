package com.aimusic.player.playback.model

import com.aimusic.player.data.model.PlayMode

/**
 * 播放器对 UI 暴露的状态。
 *
 * **Phase 5 的过渡子集**：本阶段列表页只需要两件事 —— 高亮当前曲目（`currentEntityId`，
 * `09 §3.3.1` 的 `LibrarySongsViewModel` 正是读它）与队列入口的计数。
 * Phase 6 接上 Media3 时按 `07 §3.5` 补全（`positionMs` / `durationMs` / `queue` /
 * `currentQueueItemId` / `isBuffering` / `nowPlayingSong` 等）—— **只增字段，不改已有字段语义**。
 *
 * 字段名取 `currentEntityId` 而非 `07 §3.5` 的 `currentQueueItemId`：本阶段没有真实队列推进，
 * 有意义的只是「哪个实体在播」；沿用 `07` 的名字会暗示一个本阶段并不存在的语义。
 *
 * 不加 `@Immutable`：`:core:playback` 不依赖 Compose，加它就得为注解引入 Compose 依赖。
 */
data class PlaybackUiState(
    val currentEntityId: Long? = null,
    val isPlaying: Boolean = false,
    val mode: PlayMode = PlayMode.LIST_LOOP,
    val queueSize: Int = 0,
)
