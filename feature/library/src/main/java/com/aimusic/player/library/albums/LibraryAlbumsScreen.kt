package com.aimusic.player.library.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aimusic.player.common.error.ErrorText
import com.aimusic.player.library.LibraryDimension
import com.aimusic.player.library.LibraryDimensionTabs
import com.aimusic.player.ui.component.CoverImage
import com.aimusic.player.ui.component.CoverModel
import com.aimusic.player.ui.component.ListSurface

/** 有状态外壳（`09 §8`）。 */
@Composable
fun LibraryAlbumsScreen(
    onSelectDimension: (LibraryDimension) -> Unit = {},
    onOpenAlbum: (String, String?) -> Unit = { _, _ -> },
    vm: LibraryAlbumsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LibraryAlbumsContent(
        state = state,
        onSelectDimension = onSelectDimension,
        onOpenAlbum = onOpenAlbum,
    )
}

/**
 * 无状态内容：只吃 UiState 与回调。
 *
 * 行尾**无 `⋯`、不支持多选**（`09 §3.2.3` / `§4.3.3` 清单：专辑列表是纯导航入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryAlbumsContent(
    state: LibraryAlbumsUiState,
    onSelectDimension: (LibraryDimension) -> Unit = {},
    onOpenAlbum: (String, String?) -> Unit = { _, _ -> },
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(LibraryDimension.ALBUMS.label) })
        LibraryDimensionTabs(
            current = LibraryDimension.ALBUMS,
            onSelect = onSelectDimension,
        )

        ListSurface(
            state = state.listState,
            // 专辑由「专辑名 + 专辑歌手」共同确定（`06 §4.2.1`），故 key 用组合（`09 §4.5.3`）
            key = { it.albumName to it.albumArtist },
            contentType = { "album" },
            emptyTitle = ErrorText.resolve("library.empty"),
            modifier = Modifier.weight(1f),
        ) { album ->
            // 纯导航行：整行可点、无行尾动作（`09 §3.2.3`）。
            // 不用 `Row` 再包一层 —— `ListItem` 自带纵向布局，嵌套只会多一次测量。
            ListItem(
                headlineContent = { Text(album.albumName) },
                supportingContent = {
                    // 发行日期缺省时不显示空段（`06 §3.1`：releaseDate 可为 null）
                    Text(
                        listOfNotNull(album.albumArtist, album.releaseDate, "${album.songCount} 首")
                            .joinToString(" · "),
                    )
                },
                leadingContent = {
                    CoverImage(
                        // 专辑维度没有单一实体，entityId 传 0：内存缓存键退化为「按路径」，
                        // 同封面共用一份，正是我们要的
                        model = CoverModel(cachePath = album.coverCachePath, entityId = 0L),
                        size = 48.dp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)),
                    )
                },
                modifier = Modifier
                    .clickable { onOpenAlbum(album.albumName, album.albumArtist) }
                    .fillMaxWidth()
                    .heightIn(min = 48.dp), // 触控目标 ≥48dp（`09 §4.7`）
            )
        }
    }
}
