package com.aimusic.player.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 二次确认（`09 §4.6`）：批量删除要显示**数量**，物理删除标破坏性。
 *
 * 纯数据类 + 工厂方法，JVM 单测即可，不必上真机。
 */
class ConfirmRequestTest {

    @Test
    fun 批量删除的标题带数量() {
        assertThat(ConfirmRequest.deleteSongs(listOf(1L, 2L, 3L)).title).contains("3")
    }

    @Test
    fun 单条删除的标题用这一首的措辞() {
        assertThat(ConfirmRequest.deleteSongs(listOf(1L)).title).isEqualTo("删除这首歌？")
    }

    @Test
    fun 删除歌曲的提示写明不碰磁盘() {
        // 00-总览 §5：删除歌曲实体不删磁盘文件 —— 这句承诺必须在确认文案里讲清楚，
        // 否则用户会因为害怕丢文件而不敢删。
        assertThat(ConfirmRequest.deleteSongs(listOf(1L)).message).contains("不会被删除")
    }

    @Test
    fun 物理删除标破坏性且提示不可恢复() {
        val request = ConfirmRequest.deleteFilePhysically(fileId = 9L)

        assertThat(request.kind).isEqualTo(ConfirmKind.DELETE_FILE_PHYSICALLY)
        assertThat(request.destructive).isTrue()
        assertThat(request.ids).containsExactly(9L)
        assertThat(request.message).contains("不可恢复")
    }

    @Test
    fun 两种确认的种类不同以便分发到不同执行分支() {
        assertThat(ConfirmRequest.deleteSongs(listOf(1L)).kind).isEqualTo(ConfirmKind.DELETE_SONGS)
        assertThat(ConfirmRequest.deleteFilePhysically(1L).kind)
            .isNotEqualTo(ConfirmRequest.deleteSongs(listOf(1L)).kind)
    }
}
