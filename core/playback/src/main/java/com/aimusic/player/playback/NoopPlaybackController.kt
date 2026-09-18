package com.aimusic.player.playback

import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.playback.model.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * Phase 5 的**过渡实现**：不接 ExoPlayer，只把「入队过滤（I3）＋ 当前曲目」落地。
 *
 * 存在的理由：`09 §3.3.1` 的 `LibrarySongsViewModel` 契约直接调 `PlaybackController`，
 * 而真实实现属 Phase 6。没有它，歌曲库连编译都过不了，`06 §8` 里「三条入队路径
 * 均过滤不可用歌曲」这条也没有被测对象。
 *
 * **Phase 6 会换成真实实现**（Media3 `ExoPlayer` + `MediaSessionService` + `QueueEngine`，
 * `07 §4`）。届时删掉本类即可 —— 调用方只依赖 [PlaybackController] 接口。
 *
 * 构造不用 `@Inject`：`scope` 需要 `@ApplicationScope` 限定的那个、`now` 是测试用的接缝，
 * 二者都该由 `PlaybackModule` 的 `@Provides` 显式装配（与 `RepositoryModule` 里
 * `AnalysisOrchestrator` 等同一写法）—— `@Inject` 构造器拿不到限定符，Dagger 也不认默认值。
 */
@Singleton
class NoopPlaybackController(
    private val db: MusicDatabase,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : PlaybackController {

    private val _state = MutableStateFlow(PlaybackUiState())
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /**
     * 新建队列并从 `startIndex` 播。
     *
     * 注意**不能用过滤后的下标**取起点：`songs` 里可能混着不可用的歌，
     * 过滤会把下标前移，用原下标去过滤后列表必然取错（甚至越界）。
     * 这里先按原下标定位到具体那首，再看它是否可用。
     */
    override fun playFromList(songs: List<Long>, startIndex: Int) {
        scope.launch {
            val playable = db.musicFileDao().playableIds(songs).toSet()
            val startId = songs.getOrNull(startIndex)
            // 起点那首不可用就不播（但其余可用的歌仍照常入队）
            val current = startId?.takeIf { it in playable }
            _state.value = _state.value.copy(
                currentEntityId = current,
                isPlaying = current != null,
                queueSize = songs.count { it in playable },
            )
        }
    }

    override fun appendToQueue(songId: Long) {
        scope.launch {
            // I3：不可用歌曲不入队（`07 §4.10` 的入队过滤唯一入口）
            if (db.musicFileDao().playableIds(listOf(songId)).isEmpty()) return@launch

            db.queueDao().appendToQueue(songId, now())
            _state.value = _state.value.copy(queueSize = _state.value.queueSize + 1)
        }
    }

    override fun insertNext(songId: Long) {
        scope.launch {
            if (db.musicFileDao().playableIds(listOf(songId)).isEmpty()) return@launch

            // 当前位置由真实实现按播放状态给出；过渡实现没有播放中的项，故传 null（= 插到最前）
            db.queueDao().insertNext(songId, null, now())
            _state.value = _state.value.copy(queueSize = _state.value.queueSize + 1)
        }
    }
}
