package com.aimusic.player.storage

import com.aimusic.player.common.model.AudioMetadata

import android.content.Context
import android.provider.MediaStore

/**
 * 降级通道的真机实现（`04 §4.2`）：`MediaStore.Audio` 一次查询同时给出路径与元数据，
 * `IS_MUSIC != 0` 过滤。
 *
 * 这一层是**平台胶水**：不含决策。分支与降级语义都在 `ScanOrchestrator.scan()` 里，
 * 所以它不参与单测 —— 被测的是「通道被选中且不再调用 listFiles / MetadataReader」，
 * 用假实现测（见 `ScanOrchestratorTest`）。
 */
class AndroidMediaStoreAudioSource(
    private val context: Context,
) : MediaStoreAudioSource {

    override fun query(onProgress: (Int) -> Unit): List<MediaStoreAudioEntry> {
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
        )

        val result = mutableListOf<MediaStoreAudioEntry>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { cursor ->
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumArtistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)

            while (cursor.moveToNext()) {
                // 拿不到真实路径的行跳过：music_file.path 是唯一的差异键，
                // 拿 content:// 顶替会污染库（宁可漏，不可脏）
                val path = cursor.getString(dataCol) ?: continue

                result += MediaStoreAudioEntry(
                    ref = FileRef(
                        path = path,
                        name = cursor.getString(nameCol) ?: path.substringAfterLast('/'),
                        size = cursor.getLong(sizeCol),
                        // MediaStore 的 DATE_MODIFIED 是秒
                        lastModified = cursor.getLong(modCol) * 1000,
                    ),
                    metadata = AudioMetadata(
                        title = cursor.getString(titleCol),
                        artist = cursor.getString(artistCol),
                        album = cursor.getString(albumCol),
                        albumArtist = cursor.getString(albumArtistCol),
                        date = cursor.getString(yearCol),
                        durationMs = cursor.getLong(durationCol),
                        hasEmbeddedPicture = false,
                    ),
                )
                onProgress(result.size)
            }
        }
        return result
    }
}
