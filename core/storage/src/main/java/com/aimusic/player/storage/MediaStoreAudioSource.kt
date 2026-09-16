package com.aimusic.player.storage

import com.aimusic.player.common.model.AudioMetadata

/** 媒体库通道的一条结果：路径与元数据一起给出（`04 §4.2`）。 */
data class MediaStoreAudioEntry(val ref: FileRef, val metadata: AudioMetadata)

/**
 * 降级通道（`04 §4.2`）：只有 `READ_MEDIA_AUDIO` 时，媒体库一次游标查询就能同时给出
 * 路径与元数据，因此**不必**再走 `MetadataReader`（省一次解码）。
 *
 * 放在 `:core:storage` 而不是 `:core:data`：实现要摸 `ContentResolver`，而依赖方向是
 * 单向的（`:core:data` → `:core:storage`），接口必须跟着实现在下位。
 *
 * 之所以做成独立协作者、而不是藏进 `StorageSource` 的实现里：`02 §5.1` 的 `listFiles`
 * 只返回 `FileRef`，带不出元数据，所以「这次不读元数据」必须有人在流水线之外知道。
 * 分支只在 `ScanOrchestrator.scan()` 里出现一次。
 */
fun interface MediaStoreAudioSource {
    fun query(onProgress: (Int) -> Unit): List<MediaStoreAudioEntry>
}
