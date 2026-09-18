package com.aimusic.player.library.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aimusic.player.data.repository.TagRepository
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
 * 分类 & 标签页（`09 §3.2.4`）。
 *
 * 分类行尾的删除项受 **I12** 约束：有标签的分类不可删（`06 §4.7.2`）——
 * DAO 侧返回 `DeleteCategoryResult.Blocked`，界面按 `tagCount` 灰置。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    tagRepository: TagRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val newCategoryDialog = MutableStateFlow(false)

    /** 只在查询非空时才去查；空串直接给空结果，避免白跑一次查询。 */
    private val tagResults = searchQuery.flatMapLatest { query ->
        if (query.isBlank()) flowOf(emptyList()) else tagRepository.searchTags(query)
    }

    val state: StateFlow<CategoriesUiState> = combine(
        tagRepository.observeCategories(),
        searchQuery,
        tagResults,
        newCategoryDialog,
    ) { categories, query, results, dialog ->
        CategoriesUiState(
            categories = categories,
            searchQuery = query,
            tagResults = results,
            isSearching = query.isNotBlank(),
            newCategoryDialog = dialog,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onOpenNewCategoryDialog() {
        newCategoryDialog.value = true
    }

    fun onDismissNewCategoryDialog() {
        newCategoryDialog.value = false
    }
}
