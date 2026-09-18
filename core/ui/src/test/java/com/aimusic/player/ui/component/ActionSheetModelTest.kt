package com.aimusic.player.ui.component

import com.aimusic.player.data.model.SongListItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 面板裁剪与顺序（`09 §8` 的 `ActionSheetCroppingTest` 契约、`§4.3.2`）。
 *
 * 裁剪规则被真实用户路径逼出来过：不可用歌曲若还留着「播放」，点了只是弹提示，
 * 等于给了一个必然失败的入口。
 */
class ActionSheetModelTest {

    private fun song(playable: Boolean = true) = SongListItem(
        entityId = 1L,
        title = "晴天",
        displayArtists = "周杰伦",
        artistNames = listOf("周杰伦"),
        albumName = "叶惠美",
        albumArtist = "周杰伦",
        coverCachePath = null,
        tags = emptyList(),
        isPlayable = playable,
    )

    @Test
    fun 歌曲列表为全集七项且顺序固定() {
        assertThat(ActionSheetModel.forSong(song()).actions).containsExactly(
            SongAction.PLAY,
            SongAction.PLAY_NEXT,
            SongAction.ADD_TO_QUEUE,
            SongAction.ADD_TO_TAG,
            SongAction.VIEW_DETAIL,
            SongAction.MULTI_SELECT,
            SongAction.DELETE,
        ).inOrder()
    }

    @Test
    fun 不可用歌曲无播放相关三项且保留详情与删除() {
        val actions = ActionSheetModel.forSong(song(playable = false)).actions

        // 三项都裁剪：09 §5.3 与 §8 的文字都写的是「播放 / 下一首播放 / 添加到队列 不出现」
        assertThat(actions).doesNotContain(SongAction.PLAY)
        assertThat(actions).doesNotContain(SongAction.PLAY_NEXT)
        assertThat(actions).doesNotContain(SongAction.ADD_TO_QUEUE)
        // 但仍保留详情与删除 —— 「文件没了」正是要看详情或清掉它的场景
        assertThat(actions).contains(SongAction.VIEW_DETAIL)
        assertThat(actions).contains(SongAction.DELETE)
    }

    @Test
    fun 标签歌曲列表裁掉删除歌曲并加从标签移除() {
        val actions = ActionSheetModel.forTagSongs(song()).actions

        assertThat(actions).doesNotContain(SongAction.DELETE)
        assertThat(actions).contains(SongAction.REMOVE_FROM_TAG)
        // 反过来：歌曲列表不给 REMOVE_FROM_TAG（它只属于标签维度）
        assertThat(ActionSheetModel.forSong(song()).actions).doesNotContain(SongAction.REMOVE_FROM_TAG)
    }

    @Test
    fun 搜索结果保留全集() {
        assertThat(ActionSheetModel.forSearch(song()).actions)
            .isEqualTo(ActionSheetModel.forSong(song()).actions)
    }

    @Test
    fun 面板标题为歌名() {
        assertThat(ActionSheetModel.forSong(song()).title).isEqualTo("晴天")
    }
}
