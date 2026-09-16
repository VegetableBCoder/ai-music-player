package com.aimusic.player.storage

import com.aimusic.player.common.model.AudioMetadata

/** 音频元数据读取抽象（`02 §5.2`）。实现 `MmrMetadataReader` 在 Phase 3。 */
fun interface MetadataReader {
    fun read(ref: FileRef): AudioMetadata
}

