package com.aimusic.player.library.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aimusic.player.data.repository.TagRepository
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
 * 某分类下标签列表（`09 §3.2.5`）。
 *
 * `categoryId` 由界面通过 [setCategoryId] 注入，**不走 `SavedStateHandle`**：本工程此前
 * 没有任何带参导航，为骨架阶段引入「参数名必须与路由属性名对得上」这层隐式约定，
 * 失败时是运行期崩溃（`checkNotNull`），而收益仅省一次显式传参。写在 Screen 上看得见、也能单测。
 *
 * 分类名从分类流里现查 —— 它随分类改名而变，单独存一份会留旧值。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoryTagsViewModel @Inject constructor(
    private val tagRepository: TagRepository,
) : ViewModel() {

    private val categoryId = MutableStateFlow<Long?>(null)

    // 标签列表的「行标识」是**标签名**（`09 §3.2.5`「勾选=标签名」），故泛型参数为 String
    private val multi = MutableStateFlow(MultiSelectState<String>())
    private val newTagDialog = MutableStateFlow(false)

    /** 分类未设定时不查库（`0` 不可能是有效分类 id）。 */
    private val tags = categoryId.flatMapLatest { id ->
        if (id == null) flowOf(null) else tagRepository.observeTags(id)
    }

    val state: StateFlow<CategoryTagsUiState> = combine(
        categoryId,
        tagRepository.observeCategories(),
        tags,
        multi,
        newTagDialog,
    ) { id, categories, tagList, selection, dialog ->
        CategoryTagsUiState(
            categoryName = categories.firstOrNull { it.id == id }?.name.orEmpty(),
            listState = when {
                tagList == null -> ListUiState.Loading
                tagList.isEmpty() -> ListUiState.Empty
                else -> ListUiState.Content(tagList)
            },
            // I12：有歌的标签不可删，故不可勾选（`09 §5.2`「受保护项不可选」）
            multiSelect = selection.copy(
                selectableIds = tagList.orEmpty().filter { it.songCount == 0 }.map { it.name }.toSet(),
            ),
            newTagDialog = dialog,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryTagsUiState())

    /** 由界面在进入时调用（幂等）。 */
    fun setCategoryId(id: Long) {
        categoryId.value = id
    }

    fun onToggleSelect(name: String) {
        multi.value = multi.value.toggle(name)
    }

    fun onOpenNewTagDialog() {
        newTagDialog.value = true
    }

    fun onDismissNewTagDialog() {
        newTagDialog.value = false
    }
}
