package com.aimusic.player.common.model

/**
 * 音频元数据（`02 §5.2`）。
 *
 * **归属说明**：原先定义在 `:core:storage`。Phase 4 需要它 —— `05 §3.1` 锁定的
 * `NormalizeRequest.metadata: AudioMetadata?` 位于 `:core:llm`，而依赖守卫只允许
 * `:core:llm -> :core:common`。它本身是纯数据类，搬到 common 让契约可达、与存储实现解耦。
 */
data class AudioMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val date: String?,
    val durationMs: Long,
    val hasEmbeddedPicture: Boolean,
)
