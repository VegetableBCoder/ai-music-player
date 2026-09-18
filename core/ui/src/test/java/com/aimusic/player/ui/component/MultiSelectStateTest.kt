package com.aimusic.player.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 多选态（`09 §4.3.3`）：勾选受 `selectableIds` 约束，全选只作用于可选集。
 *
 * 与计划 Task 1.2 示例的唯一差别：类型参数显式写成 `<Long>` —— `MultiSelectState` 已放宽为
 * 泛型、而 **Kotlin 没有类型参数默认值**，裸写 `MultiSelectState()` 会因 `T` 无从推断而编译失败。
 */
class MultiSelectStateTest {

    @Test
    fun 不可选项不会进入选中集() {
        val s = MultiSelectState<Long>(isActive = true, selectableIds = setOf(1L, 2L))
        assertThat(s.toggle(9L).selectedIds).isEmpty()
    }

    @Test
    fun 全选只作用于可选集() {
        val s = MultiSelectState<Long>(isActive = true, selectedIds = setOf(1L), selectableIds = setOf(1L, 2L, 3L))
        assertThat(s.selectAll().selectedIds).containsExactly(1L, 2L, 3L)
        assertThat(s.selectAll().allSelected).isTrue()
        // 有不可选项时不参与
        assertThat(MultiSelectState<Long>(isActive = true, selectableIds = setOf(2L)).toggle(2L).allSelected).isTrue()
    }

    @Test
    fun 全选在可选集为空时为假() {
        assertThat(MultiSelectState<Long>(isActive = true).allSelected).isFalse()
    }

    @Test
    fun 勾选与取消勾选可往返() {
        val s = MultiSelectState<Long>(isActive = true, selectableIds = setOf(1L))
        assertThat(s.toggle(1L).toggle(1L).selectedIds).isEmpty()
    }

    @Test
    fun 行状态三态映射正确() {
        assertThat(MultiSelectState<Long>().rowStateFor(1L)).isEqualTo(RowSelectionState.Hidden)
        assertThat(MultiSelectState<Long>(isActive = true, selectedIds = setOf(1L), selectableIds = setOf(1L)).rowStateFor(1L))
            .isEqualTo(RowSelectionState.Visible(selected = true, enabled = true))
        // 不可选项：显示但禁用（`09 §5.2`「不可用歌曲勾选圈 enabled = false」）
        assertThat(MultiSelectState<Long>(isActive = true, selectableIds = setOf(2L)).rowStateFor(1L))
            .isEqualTo(RowSelectionState.Visible(selected = false, enabled = false))
    }

    /**
     * 泛型化的回归保护：行标识为**标签名**（`String`）时同一套多选逻辑照样成立。
     *
     * `09 §3.2.5` 要求某分类下的标签列表「勾选=标签名」，这正是把 `MultiSelectState` 从
     * `Set<Long>` 放宽成 `Set<T>` 的唯一理由。若日后有人把它改回 `Long`，本条会先红。
     */
    @Test
    fun 行标识为字符串时同样可用() {
        val s = MultiSelectState<String>(isActive = true, selectableIds = setOf("摇滚", "民谣"))
        assertThat(s.toggle("摇滚").selectedIds).containsExactly("摇滚")
        assertThat(s.selectAll().selectedIds).containsExactly("摇滚", "民谣")
        // 不在可选集里的标签名（有歌的受保护标签，I12）仍不可勾选
        assertThat(s.toggle("有歌的标签").selectedIds).isEmpty()
    }
}
