package com.aimusic.player.ui.component

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aimusic.player.data.model.SongListItem
import com.aimusic.player.data.model.TagProjection

/**
 * 歌曲行（`09 §4.3.1`）。四个维度的歌曲列表、搜索结果、标签歌曲列表共用。
 *
 * **无状态**：`isPlaying` / `selection` 由调用方从 `UiState` 取，四个回调直接转发到
 * ViewModel 方法；本组件不持有任何选择状态（`09 §4.3.1` 的「状态提升」）。
 *
 * 与契约示例的三处出入都是照实际类型 / 图标集修正的：
 * 1. 封面入参是 `item.coverCachePath`，投影里**没有** `coverModel` 字段 —— 由本行组装
 *    [CoverModel]（`09 §4.5.4`）。
 * 2. 副标题用 `item.albumName`，投影里没有示例写的 `item.album`。
 * 3. 「添加到队列」用 `Icons.Default.Add` —— `material-icons-core` 里**没有**
 *    `Icons.Outlined.PlaylistAdd`（该图标集仅约 50 个），语义最接近的可用项是 `＋`。
 */
@Composable
fun SongRow(
    item: SongListItem,
    isPlaying: Boolean,
    selection: RowSelectionState = RowSelectionState.Hidden,
    onRowClick: () -> Unit = {},
    onQuickAddToQueue: () -> Unit = {},
    onOpenActionSheet: () -> Unit = {},
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onRowClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) {
                if (selection is RowSelectionState.Visible) {
                    selected = selection.selected
                    stateDescription = if (selection.selected) "已选中" else "未选中"
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ① 多选勾选圈：仅多选态显示；命中区由整行承担，故此处不再单独放大
        if (selection is RowSelectionState.Visible) {
            Checkbox(
                checked = selection.selected,
                onCheckedChange = null,
                enabled = selection.enabled,
            )
            Spacer(Modifier.width(8.dp))
        }

        // ② 封面
        CoverImage(
            model = CoverModel(cachePath = item.coverCachePath, entityId = item.entityId),
            size = 48.dp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))

        // ③ 文本：歌名 / 歌手 · 专辑 / 标签（仅主要分类，I6）
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 不可用歌曲置灰但**保留文本**：颜色不是唯一信息（`09 §4.7`、`05 §6`）
                color = if (item.isPlayable) {
                    LocalContentColor.current
                } else {
                    LocalContentColor.current.copy(alpha = 0.38f)
                },
            )
            Text(
                text = listOfNotNull(item.displayArtists, item.albumName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.tags.isNotEmpty()) {
                TagChips(item.tags)
            }
        }

        // ④ 行内唯一快捷动作：＋ 添加到队列（多选态隐藏；不可用歌曲不出现，I3）
        if (selection == RowSelectionState.Hidden && item.isPlayable) {
            IconButton(onClick = onQuickAddToQueue, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Add, contentDescription = "添加到队列")
            }
        }

        // ⑤ 行尾 ⋯（多选态隐藏）—— 行级操作的唯一入口（`09 §4.7` 不用左滑）
        if (selection == RowSelectionState.Hidden) {
            IconButton(onClick = onOpenActionSheet, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
            }
        }
    }
}

/**
 * 标签小片（`09 §4.3.1` 的 `TagChips`）。
 *
 * 只显示**标签名**：列表行用的是「仅主要分类下」的标签（I6），分类信息在详情页才需要。
 */
@Composable
fun TagChips(tags: List<TagProjection>, modifier: Modifier = Modifier) {
    Text(
        text = tags.joinToString(" ") { it.name },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
