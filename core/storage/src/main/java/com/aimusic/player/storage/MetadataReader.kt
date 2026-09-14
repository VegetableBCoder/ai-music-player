package com.aimusic.player.storage

/** 音频元数据读取抽象（`02 §5.2`）。实现 `MmrMetadataReader` 在 Phase 3。 */
fun interface MetadataReader {
    fun read(ref: FileRef): AudioMetadata
}

data class AudioMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val date: String?,
    val durationMs: Long,
    val hasEmbeddedPicture: Boolean,
)
