package com.aimusic.player.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.aimusic.player.library.LibraryDimension
import com.aimusic.player.library.albums.LibraryAlbumsScreen
import com.aimusic.player.library.artists.LibraryArtistsScreen
import com.aimusic.player.library.detail.SongDetailScreen
import com.aimusic.player.library.songs.AlbumSongsScreen
import com.aimusic.player.library.songs.ArtistSongsScreen
import com.aimusic.player.library.songs.LibrarySongsScreen
import com.aimusic.player.library.songs.TagSongsScreen
import com.aimusic.player.library.tags.CategoriesScreen
import com.aimusic.player.library.tags.CategoryTagsScreen
import com.aimusic.player.mine.MineScreen
import com.aimusic.player.mine.analysis.AnalysisRunScreen
import com.aimusic.player.mine.scan.ScanScreen
import com.aimusic.player.mine.settings.SettingsScreen
import com.aimusic.player.search.SearchScreen
import kotlinx.coroutines.launch

/**
 * 导航图（`09 §4.1`）。
 *
 * **偏离 `09 §4.1.2` 一处**（原 Phase 3e 起）：文档示例把 `NavHostController` 直接传进屏幕
 * （`LibrarySongsScreen(navController)`）。这里改为传**回调** —— 屏幕不该知道路由是谁，
 * 更不该 import `:app` 的类型；而 `:feature:*` 不能依赖 `:app`（`02 §2` 的依赖方向）。
 *
 * 起点是 `SongsRoute`（音乐库）：Phase 5 交付后，打开 App 就该看到自己的音乐，
 * 而不是先落到「我的」再往里面点。底部导航按 `09 §4.4` 给三项，
 * 覆盖页（歌曲详情）在顶层、推入后底部栏由所在屏自行决定是否显示。
 */
@Composable
fun AppNavHost(
    innerPadding: PaddingValues,
    onRequestAllFilesAccess: () -> Unit,
    onRequestMediaPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /**
     * 维度切换（`页面设计 §2`）。
     *
     * 四个维度是四个平级路由，切换即导航；已在目标维度上时不重复入栈
     * （`launchSingleTop`，与底栏同一语义）。这是**发现这三个维度的唯一入口** ——
     * 缺了它，`ArtistsRoute` / `AlbumsRoute` / `TagsHubRoute` 就没有任何调用点。
     */
    val selectDimension: (LibraryDimension) -> Unit = { dimension ->
        val route: Any = when (dimension) {
            LibraryDimension.SONGS -> SongsRoute
            LibraryDimension.ARTISTS -> ArtistsRoute
            LibraryDimension.ALBUMS -> AlbumsRoute
            LibraryDimension.TAGS -> TagsHubRoute
        }
        navController.navigate(route) { launchSingleTop = true }
    }

    Scaffold(
        modifier = modifier.padding(innerPadding),
        bottomBar = { AppBottomBar(navController) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = SongsRoute,
            modifier = Modifier.padding(inner),
        ) {
            // —— 音乐库 ——

            composable<SongsRoute> {
                LibrarySongsScreen(
                    currentDimension = LibraryDimension.SONGS,
                    onSelectDimension = selectDimension,
                    snackbar = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                    onOpenSongDetail = { navController.navigate(SongDetailRoute(it)) },
                    onNavigateToScan = { navController.navigate(ScanRoute) },
                )
            }

            composable<ArtistsRoute> {
                LibraryArtistsScreen(
                    onSelectDimension = selectDimension,
                    onOpenArtist = { navController.navigate(ArtistSongsRoute(it)) },
                )
            }

            composable<AlbumsRoute> {
                LibraryAlbumsScreen(
                    onSelectDimension = selectDimension,
                    onOpenAlbum = { name, artist -> navController.navigate(AlbumSongsRoute(name, artist)) },
                )
            }

            composable<TagsHubRoute> {
                CategoriesScreen(
                    onSelectDimension = selectDimension,
                    onOpenCategory = { navController.navigate(CategoryTagsRoute(it)) },
                    onOpenTag = { navController.navigate(TagSongsRoute(it)) },
                )
            }

            composable<ArtistSongsRoute> { entry ->
                val route = entry.toRoute<ArtistSongsRoute>()
                ArtistSongsScreen(
                    artistName = route.artistName,
                    onOpenSongDetail = { navController.navigate(SongDetailRoute(it)) },
                )
            }

            composable<AlbumSongsRoute> { entry ->
                val route = entry.toRoute<AlbumSongsRoute>()
                AlbumSongsScreen(
                    albumName = route.albumName,
                    albumArtist = route.albumArtist,
                    onOpenSongDetail = { navController.navigate(SongDetailRoute(it)) },
                )
            }

            composable<CategoryTagsRoute> { entry ->
                val route = entry.toRoute<CategoryTagsRoute>()
                CategoryTagsScreen(
                    categoryId = route.categoryId,
                    onOpenTag = { navController.navigate(TagSongsRoute(it)) },
                )
            }

            composable<TagSongsRoute> { entry ->
                val route = entry.toRoute<TagSongsRoute>()
                TagSongsScreen(
                    tagName = route.tagName,
                    onOpenSongDetail = { navController.navigate(SongDetailRoute(it)) },
                )
            }

            // —— 搜索 ——

            composable<SearchRoute> {
                SearchScreen(
                    onOpenSongDetail = { navController.navigate(SongDetailRoute(it)) },
                )
            }

            // —— 我的（Phase 3/4 已交付的几条）——

            composable<MineRoute> {
                MineScreen(
                    onOpenScan = { navController.navigate(ScanRoute) },
                    onOpenAnalysis = { navController.navigate(AnalysisRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }

            composable<AnalysisRoute> {
                AnalysisRunScreen(
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onOpenSong = { navController.navigate(SongDetailRoute(it)) },
                )
            }

            composable<SettingsRoute> {
                SettingsScreen()
            }

            composable<ScanRoute> {
                ScanScreen(
                    onRequestAllFilesAccess = onRequestAllFilesAccess,
                    onRequestMediaPermission = onRequestMediaPermission,
                    onNavigateToAnalysis = { navController.navigate(AnalysisRoute) },
                )
            }

            // —— 覆盖页（顶层，`09 §4.1.4` 选独立路由而非 ModalBottomSheet）——

            composable<SongDetailRoute> { entry ->
                val route = entry.toRoute<SongDetailRoute>()
                SongDetailScreen(entityId = route.entityId)
            }
        }
    }
}

/**
 * 底部导航（`09 §4.4`）：音乐库 / 搜索 / 我的。
 *
 * 三项对应三个真实 Tab；mini 播放条随 Phase 6 的播放器一起落在它上方。
 * 覆盖页（歌曲详情）时不显示 —— 它是从列表推入的临时页，用户在那里应当只有「返回」这一个出口。
 */
@Composable
private fun AppBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // 覆盖页不显示底部导航（`09 §4.4.3`）
    val isOverlay = currentDestination?.hasRoute(SongDetailRoute::class) == true
    if (isOverlay) return

    NavigationBar {
        NavigationBarItem(
            selected = currentDestination?.hasRoute(SongsRoute::class) == true ||
                currentDestination?.hasRoute(ArtistsRoute::class) == true ||
                currentDestination?.hasRoute(AlbumsRoute::class) == true ||
                currentDestination?.hasRoute(TagsHubRoute::class) == true,
            onClick = {
                // 已在库内就别重复入栈（`launchSingleTop` 语义）
                navController.navigate(SongsRoute) { launchSingleTop = true }
            },
            icon = { Text("♪") },
            label = { Text("音乐库") },
        )
        NavigationBarItem(
            selected = currentDestination?.hasRoute(SearchRoute::class) == true,
            onClick = { navController.navigate(SearchRoute) { launchSingleTop = true } },
            icon = { Text("⌕") },
            label = { Text("搜索") },
        )
        NavigationBarItem(
            selected = currentDestination?.hasRoute(MineRoute::class) == true,
            onClick = { navController.navigate(MineRoute) { launchSingleTop = true } },
            icon = { Text("☰") },
            label = { Text("我的") },
        )
    }
}
