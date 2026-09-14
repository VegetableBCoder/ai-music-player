package com.aimusic.player.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/** 三级门禁（`04 §3.3`）。 */
enum class StorageAccessLevel {
    /** 「所有文件访问」已授予（或 API<30 的 `READ_EXTERNAL_STORAGE`）：任意目录 + 同源 `.lrc` + 物理删除 */
    FULL,

    /** 仅有 `READ_MEDIA_AUDIO`（<33 为 `READ_EXTERNAL_STORAGE`）：只扫媒体库，部分功能置灰 */
    MEDIA_LIBRARY_ONLY,

    /** 无任何读取权限：不扫描，引导授权 */
    NONE,
}

/** 判定所需的三个事实。抽出来是为了让**决策**在 JVM 上可测，平台读取留给真机。 */
data class StorageFacts(
    val sdkInt: Int,
    val isExternalStorageManager: Boolean,
    val hasMediaRead: Boolean,
)

/** 权限决策的纯函数部分（`04 §4.2`）。 */
object StorageAccess {

    const val API_R = 30
    const val API_TIRAMISU = 33

    fun decideLevel(facts: StorageFacts): StorageAccessLevel = when {
        // API 30+ 才有「所有文件访问」
        facts.sdkInt >= API_R -> when {
            facts.isExternalStorageManager -> StorageAccessLevel.FULL
            facts.hasMediaRead -> StorageAccessLevel.MEDIA_LIBRARY_ONLY
            else -> StorageAccessLevel.NONE
        }
        // API 26–29：没有 MANAGE_EXTERNAL_STORAGE 概念，广读即 FULL
        facts.hasMediaRead -> StorageAccessLevel.FULL
        else -> StorageAccessLevel.NONE
    }

    fun runtimePermissions(sdkInt: Int): Array<String> = if (sdkInt >= API_TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

interface StorageAccessChecker {
    fun currentLevel(context: Context): StorageAccessLevel

    fun allFilesAccessIntent(context: Context): Intent

    /** 按 SDK 返回需请求的运行时权限 */
    fun runtimePermissions(): Array<String>
}

/**
 * 平台读取实现。
 *
 * 只判**存储**级别；通知权限与 `permission_hint_shown` 的抑制逻辑属 `:app` 的
 * `PermissionChecker`（`10 §4.2`，Phase 9）—— 两处各写一份存储判定迟早会分叉。
 */
class AndroidStorageAccessChecker(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : StorageAccessChecker {

    override fun currentLevel(context: Context): StorageAccessLevel = StorageAccess.decideLevel(
        StorageFacts(
            sdkInt = sdkInt,
            isExternalStorageManager = isExternalStorageManager(),
            hasMediaRead = hasMediaRead(context),
        ),
    )

    override fun allFilesAccessIntent(context: Context): Intent = if (sdkInt >= StorageAccess.API_R) {
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.fromParts("package", context.packageName, null))
    } else {
        // API 26–29 没有该设置页，回退到应用详情页（10 §4.2.2）
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
    }

    override fun runtimePermissions(): Array<String> = StorageAccess.runtimePermissions(sdkInt)

    private fun isExternalStorageManager(): Boolean =
        sdkInt >= StorageAccess.API_R && Environment.isExternalStorageManager()

    private fun hasMediaRead(context: Context): Boolean {
        val permission = if (sdkInt >= StorageAccess.API_TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
