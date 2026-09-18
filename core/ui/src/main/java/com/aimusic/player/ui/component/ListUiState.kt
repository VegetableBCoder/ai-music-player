package com.aimusic.player.ui.component

import com.aimusic.player.common.error.AppError

/**
 * 列表四态（`09 §3.3.1`、`§4.5.2`）。
 *
 * `Empty` 与 `EmptyFiltered` 分开，是因为两者的**动作不同**（`09 §5.1`）：
 * 库空 → 引导去扫描；有筛选但无命中 → 引导清除筛选。
 */
sealed interface ListUiState<out T> {

    data object Loading : ListUiState<Nothing>

    data class Content<T>(val items: List<T>) : ListUiState<T>

    /** 库为空（无筛选）。 */
    data object Empty : ListUiState<Nothing>

    /** 有筛选但无命中。 */
    data object EmptyFiltered : ListUiState<Nothing>

    data class Error(val error: AppError) : ListUiState<Nothing>
}
