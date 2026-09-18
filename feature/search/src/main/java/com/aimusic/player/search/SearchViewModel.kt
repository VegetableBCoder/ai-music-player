package com.aimusic.player.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aimusic.player.data.repository.LibraryRepository
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
import kotlinx.coroutines.flow.stateIn

/**
 * 搜索（`09 §3.2.7`）。
 *
 * 签名以 `06 §3.3` 为准：`observeSearch(query)` —— 早期文档写成了 `search(query)`，
 * 已按模块文档更正。结果由仓储侧合并歌名与歌手两路并去重（`06 §4.4`）。
 *
 * 搜索历史（`06 §4.4` 定为 DataStore 的 `search_history`）随批次 3 落地，
 * 本骨架先只做结果列表 —— 历史不是「能搜到东西」的前提。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val multi = MutableStateFlow(MultiSelectState<Long>())

    private val results = query.flatMapLatest { current ->
        // 空查询不查库：仓储对空串返回空列表，但少一次订阅更省
        if (current.isBlank()) {
            kotlinx.coroutines.flow.flowOf(emptyList())
        } else {
            libraryRepository.observeSearch(current)
        }
    }

    val state: StateFlow<SearchUiState> = combine(
        query,
        results,
        multi,
    ) { currentQuery, items, selection ->
        SearchUiState(
            query = currentQuery,
            history = emptyList(),
            resultState = when {
                currentQuery.isBlank() -> ListUiState.Empty
                items.isEmpty() -> ListUiState.Empty
                else -> ListUiState.Content(items)
            },
            showHistory = currentQuery.isBlank(),
            multiSelect = selection.copy(
                // 搜索页批量只支持「加入队列」，可播项才可选（I3）
                selectableIds = items.filter { it.isPlayable }.map { it.entityId }.toSet(),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(next: String) {
        query.value = next
    }

    fun onClearQuery() {
        query.value = ""
        multi.value = MultiSelectState<Long>()
    }
}
