package com.aimusic.player.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.aimusic.player.storage.StorageAccessChecker
import com.aimusic.player.storage.StorageAccessLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 权限状态快照（`10 §3.2`）。 */
data class PermissionSnapshot(
    val storageAccess: StorageAccessLevel,
    val notificationsGranted: Boolean,
    /** 由 DataStore `permission_hint_shown` 派生（`10 §4.3.1`：置 true 后不再主动弹窗） */
    val hintAlreadyShown: Boolean,
)

/**
 * 权限检查（`10 §4.2.1`）。
 *
 * **存储判定不在这里实现**：委托给 `:core:storage` 的 `StorageAccessChecker`。
 * `10 §4.2.1` 的示例把判定内联在 `:app` 里，而 `:core:storage` 的 `AndroidStorageAccessChecker`
 * 已经做完了同一件事 —— 两处各写一份「什么是 FULL」迟早分叉，而扫描模块是按
 * `StorageAccessChecker.currentLevel()` 选通道的（`04 §4.2`），分叉的后果是两边对同一个权限
 * 状态给出不同结论。`:app` 这边只补存储层不该管的**通知**权限。
 */
@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessChecker: StorageAccessChecker,
) {

    fun snapshot(hintAlreadyShown: Boolean): PermissionSnapshot = PermissionSnapshot(
        storageAccess = accessChecker.currentLevel(context),
        notificationsGranted = notificationsGranted(),
        hintAlreadyShown = hintAlreadyShown,
    )

    /** API < 33 没有该运行时权限，恒为已授予（`10 §4.2.1`）。 */
    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /** 按 SDK 返回需请求的媒体读取权限（13+ 为 `READ_MEDIA_AUDIO`，否则 `READ_EXTERNAL_STORAGE`）。 */
    fun mediaPermissions(): Array<String> = accessChecker.runtimePermissions()

    /** 「所有文件访问」没有运行时弹窗，只能跳系统设置页（`10 §4.2.2`）。 */
    fun allFilesAccessIntent(): Intent = accessChecker.allFilesAccessIntent(context)
}
