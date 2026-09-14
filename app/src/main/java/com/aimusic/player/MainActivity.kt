package com.aimusic.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import com.aimusic.player.navigation.AppNavHost
import com.aimusic.player.permission.PermissionChecker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 单 Activity 宿主（`01 §2`）。
 *
 * 权限的**申请动作**留在 Activity：`registerForActivityResult` 必须在 Activity 创建期注册，
 * 且结果回调只有 Activity 能拿（`10 §4.2.2`）。判定逻辑在 `PermissionChecker`，
 * 屏幕只负责发起（收回调、不依赖 Activity）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var permissionChecker: PermissionChecker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                // 结果不在这里处理：用户从系统设置/弹窗回来后，扫描页会重新查一次门禁
                // （`10 §4.2.1`「启动、回到前台、扫描前各检查一次」）
                val mediaPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { }

                Scaffold { innerPadding ->
                    AppNavHost(
                        innerPadding = innerPadding,
                        onRequestAllFilesAccess = {
                            startActivity(permissionChecker.allFilesAccessIntent())
                        },
                        onRequestMediaPermission = {
                            mediaPermissionLauncher.launch(permissionChecker.mediaPermissions())
                        },
                    )
                }
            }
        }
    }
}
