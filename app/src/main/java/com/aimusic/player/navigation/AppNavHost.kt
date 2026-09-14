package com.aimusic.player.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aimusic.player.mine.MineScreen
import com.aimusic.player.mine.scan.ScanScreen

/**
 * 导航图（`09 §4.1`）。
 *
 * **本期（3e）只有一个图**：`我的 → 文件扫描`。底部导航与 mini 播放条（`09 §4.4`）需要
 * 至少两个真实 Tab 才有意义（音乐库/搜索属 Phase 5），此时加只会多两个空壳。
 *
 * **偏离 `09 §4.1.2` 的一处**：文档示例把 `NavHostController` 直接传进屏幕
 * （`LibrarySongsScreen(navController)`）。这里改为传**回调** —— 屏幕不该知道路由是谁，
 * 更不该 import `:app` 的类型；而 `:feature:*` 不能依赖 `:app`（`02 §2` 的依赖方向），
 * 所以「路由定义在 `:app`、屏幕只收 lambda」是唯一能通过依赖守卫的形态。
 * 该偏离已记录在 `09 §4.1.2`。
 */
@Composable
fun AppNavHost(
    innerPadding: PaddingValues,
    onRequestAllFilesAccess: () -> Unit,
    onRequestMediaPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = MineRoute,
        modifier = modifier.padding(innerPadding),
    ) {
        composable<MineRoute> {
            MineScreen(onOpenScan = { navController.navigate(ScanRoute) })
        }

        composable<ScanRoute> {
            ScanScreen(
                onRequestAllFilesAccess = onRequestAllFilesAccess,
                onRequestMediaPermission = onRequestMediaPermission,
            )
        }
    }
}
