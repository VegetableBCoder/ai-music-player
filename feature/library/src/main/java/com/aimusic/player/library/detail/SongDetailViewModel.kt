package com.aimusic.player.library.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aimusic.player.data.model.SongDetail
import com.aimusic.player.data.repository.LibraryRepository
import com.aimusic.player.ui.component.ConfirmRequest
import com.aimusic.player.ui.component.ListUiState
import com.aimusic.player.ui.component.MultiSelectState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * 歌曲详情（`09 §3.2.17`）。
 *
 * 数据几乎都从 `observeSongDetail(entityId)` 一个流来 —— 仓储侧已经把实体、全部署名、
 * **完整标签**（`onlyMain = false`）、歌词关联与文件列表装配好了（`06 §4.5`）。
 * 详情页因此不需要自己发多次查询，也没必要在这里再拼一遍。
 *
 * `entityId` 由界面通过 [setEntityId] 注入（理由见
 * [com.aimusic.player.library.tags.CategoryTagsViewModel] 的 KDoc）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SongDetailViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val entityId = MutableStateFlow<Long?>(null)

    // 文件列表多选：勾选 = fileId
    private val multi = MutableStateFlow(MultiSelectState<Long>())
    private val tagEditor = MutableStateFlow<TagEditorState?>(null)
    private val confirm = MutableStateFlow<ConfirmRequest?>(null)

    private val detail: kotlinx.coroutines.flow.Flow<SongDetail?> = entityId.flatMapLatest { id ->
        if (id == null) flowOf(null) else libraryRepository.observeSongDetail(id)
    }

    val state: StateFlow<SongDetailUiState> = combine(
        detail,
        multi,
        tagEditor,
        confirm,
    ) { song, selection, editor, confirmation ->
        if (song == null) {
            SongDetailUiState(
                multiSelect = selection,
                tagEditor = editor,
                confirmation = confirmation,
            )
        } else {
            SongDetailUiState(
                song = SongDetailUi(
                    entityId = song.entity.id,
                    title = song.entity.canonicalTitle,
                    displayArtists = song.entity.displayArtists,
                    albumName = song.entity.albumName,
                    coverCachePath = song.entity.coverCachePath,
                ),
                // 详情展示**完整标签**（不按主要分类折叠）
                tags = song.tags.map { it.name },
                fileListState = if (song.files.isEmpty()) {
                    ListUiState.Empty
                } else {
                    ListUiState.Content(song.files.map { it.toUi() })
                },
                lyricStatus = when {
                    song.lyric == null -> LyricStatus.NotLinked
                    // 「文件已失效」由 VM 用 StorageSource 判定（`06 §3.1` 的注释）——
                    // 骨架期先按「有记录即已关联」，随批次 3 接 StorageSource
                    else -> LyricStatus.Linked
                },
                // 文件列表多选：勾选 = fileId（队列允许重复，但文件不会）
                multiSelect = selection.copy(
                    selectableIds = song.files.map { it.fileId }.toSet(),
                ),
                tagEditor = editor,
                confirmation = confirmation,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongDetailUiState())

    private fun com.aimusic.player.data.model.MusicFileItem.toUi() = MusicFileUi(
        fileId = fileId,
        fileName = fileName,
        path = path,
        size = size,
        format = format,
        durationMs = durationMs,
        isRepresentative = isRepresentative,
        analysisStatus = analysisStatus.name,
    )

    /** 界面在进入时调用（幂等）。 */
    fun setEntityId(id: Long) {
        if (entityId.value != id) {
            entityId.value = id
            multi.value = MultiSelectState<Long>()
        }
    }

    /**
     * 文件列表的勾选（勾选 = `fileId`）。
     *
     * 判定基准取 `state.value.multiSelect`（只有它带 `selectableIds` = 当前文件集）——
     * 拿 `multi.value` 去 toggle 会因 `selectableIds` 恒为空而静默失效。
     * 理由详见 `LibrarySongsViewModel.toggleSelect` 的 KDoc。
     */
    fun onToggleFileSelect(fileId: Long) {
        val applied = state.value.multiSelect.toggle(fileId)
        multi.value = multi.value.copy(selectedIds = applied.selectedIds)
    }

    /** 全选只作用于当前文件集。 */
    fun selectAllFiles() {
        val applied = state.value.multiSelect.selectAll()
        multi.value = multi.value.copy(selectedIds = applied.selectedIds)
    }

    fun enterMultiSelect(seed: Long? = null) {
        multi.value = MultiSelectState<Long>(isActive = true, selectedIds = setOfNotNull(seed))
    }

    fun exitMultiSelect() {
        multi.value = MultiSelectState<Long>()
    }

    fun onOpenTagEditor() {
        tagEditor.value = TagEditorState()
    }

    fun onDismissTagEditor() {
        tagEditor.value = null
    }

    fun onDismissConfirm() {
        confirm.value = null
    }
}
