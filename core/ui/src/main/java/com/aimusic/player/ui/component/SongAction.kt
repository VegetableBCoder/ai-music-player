package com.aimusic.player.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.aimusic.player.data.model.SongListItem

/**
 * 操作项：**全局固定顺序**，渲染时按能力裁剪，顺序不变（`09 §4.3.2`）。
 *
 * `REMOVE_FROM_TAG` 只出现在标签歌曲列表（`09 §3.2.6`），故排在 `DELETE` 之后 ——
 * 它不参与歌曲列表的裁剪，放在末尾就不会打乱前七项的固定次序。
 */
enum class SongAction {
    PLAY,
    PLAY_NEXT,
    ADD_TO_QUEUE,
    ADD_TO_TAG,
    VIEW_DETAIL,
    MULTI_SELECT,
    DELETE,
    REMOVE_FROM_TAG,
}

/**
 * 面板项文案（`页面设计 §9.2` 的 `播放 / 下一首播放 / 添加到队列 / 添加到标签 /
 * 查看歌曲详情 / 多选 / 删除歌曲` 逐字）。
 *
 * 放在代码里而不是 `ErrorText`：与既有界面文案同规矩 —— `ErrorText` 管**提示 / 状态 / 错误**，
 * 按钮与菜单标签直接写字面（如扫描页的 `Text("去授权")`）。这里的字面仍取自
 * `页面设计 §9.2`，不自造词面。
 */
fun SongAction.label(): String = when (this) {
    SongAction.PLAY -> "播放"
    SongAction.PLAY_NEXT -> "下一首播放"
    SongAction.ADD_TO_QUEUE -> "添加到队列"
    SongAction.ADD_TO_TAG -> "添加到标签"
    SongAction.VIEW_DETAIL -> "查看歌曲详情"
    SongAction.MULTI_SELECT -> "多选"
    SongAction.DELETE -> "删除歌曲"
    // 09 §3.2.6 的措辞（标签歌曲列表专有）
    SongAction.REMOVE_FROM_TAG -> "从「标签」移除"
}

/**
 * 面板项图标。
 *
 * **只用 `material-icons-core` 集**（约 50 个）：`09 §4.3.1` 示例里的 `Icons.Outlined.PlaylistAdd`
 * 属 extended 集（数千个图标、体积大），为一个图标引入整包不划算，
 * 故「添加到队列」用 core 集语义最接近的 `Icons.Default.Add`（`＋`）。
 */
fun SongAction.icon(): ImageVector = when (this) {
    SongAction.PLAY -> Icons.Default.PlayArrow
    SongAction.PLAY_NEXT -> Icons.Default.KeyboardArrowUp
    SongAction.ADD_TO_QUEUE -> Icons.Default.Add
    SongAction.ADD_TO_TAG -> Icons.Default.Star
    SongAction.VIEW_DETAIL -> Icons.Default.Info
    SongAction.MULTI_SELECT -> Icons.Default.CheckCircle
    SongAction.DELETE -> Icons.Default.Delete
    SongAction.REMOVE_FROM_TAG -> Icons.Default.KeyboardArrowDown
}

/**
 * 底部操作面板模型（`09 §4.3.2`）。已按 `页面设计 §9.2` 的顺序裁剪。
 *
 * **不可用歌曲（I3）不给播放相关的三项**（`播放 / 下一首播放 / 添加到队列`）——
 * 点了必然失败，不如不给入口；`查看歌曲详情` 与 `删除歌曲` 仍然保留，
 * 因为「文件没了」正是用户想进去看清状况、或干脆清掉它的场景。
 * 该规则见 `09 §5.3` 与 `§8` 的 `ActionSheetCroppingTest`，两处都写的是**三项**。
 */
@Immutable
data class ActionSheetModel(
    val title: String?,
    val actions: List<SongAction>,
) {
    companion object {

        /** 歌曲列表：全集。 */
        fun forSong(item: SongListItem) = ActionSheetModel(
            title = item.title,
            actions = buildList {
                if (item.isPlayable) {
                    add(SongAction.PLAY)
                    add(SongAction.PLAY_NEXT)
                    add(SongAction.ADD_TO_QUEUE)
                }
                add(SongAction.ADD_TO_TAG)
                add(SongAction.VIEW_DETAIL)
                add(SongAction.MULTI_SELECT)
                add(SongAction.DELETE)
            },
        )

        /** 搜索结果：与歌曲列表同（`09 §3.2.7`「行尾 ⋯ 同歌曲库」）。 */
        fun forSearch(item: SongListItem) = forSong(item)

        /**
         * 标签歌曲列表：**不提供「删除歌曲」**，改为「从『标签』移除」（`09 §3.2.6`）。
         *
         * 在这里删歌会连实体一起删掉，而用户此刻的心智是「管理这个标签下的内容」——
         * 该给的出口是「把这歌从这个标签移走」，不是「删掉这首歌」。
         */
        fun forTagSongs(item: SongListItem) = ActionSheetModel(
            title = item.title,
            actions = buildList {
                if (item.isPlayable) {
                    add(SongAction.PLAY)
                    add(SongAction.PLAY_NEXT)
                    add(SongAction.ADD_TO_QUEUE)
                }
                add(SongAction.VIEW_DETAIL)
                add(SongAction.MULTI_SELECT)
                add(SongAction.REMOVE_FROM_TAG)
            },
        )
    }
}
