package com.aimusic.player.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 权限门禁的**判定逻辑**（`04 §4.2`）。
 *
 * 这里刻意只测「给定三个事实 → 得到哪个级别」的纯函数。真机上再验证「三个事实读得对」
 * —— 把两者混在一起测的话，权限一变测试就红了，而红的原因却可能是环境而不是代码。
 */
class StorageAccessTest {

    private fun level(sdk: Int, allFiles: Boolean = false, mediaRead: Boolean = false) =
        StorageAccess.decideLevel(
            StorageFacts(sdkInt = sdk, isExternalStorageManager = allFiles, hasMediaRead = mediaRead),
        )

    @Test
    fun `API30_以上_拿到所有文件访问就是_FULL`() {
        assertThat(level(30, allFiles = true)).isEqualTo(StorageAccessLevel.FULL)
        assertThat(level(34, allFiles = true, mediaRead = true)).isEqualTo(StorageAccessLevel.FULL)
    }

    @Test
    fun `API30_以上_只有媒体读取就是_降级`() {
        assertThat(level(30, mediaRead = true)).isEqualTo(StorageAccessLevel.MEDIA_LIBRARY_ONLY)
        assertThat(level(33, mediaRead = true)).isEqualTo(StorageAccessLevel.MEDIA_LIBRARY_ONLY)
    }

    @Test
    fun `API30_以上_两者都没有就是_NONE`() {
        assertThat(level(30)).isEqualTo(StorageAccessLevel.NONE)
        assertThat(level(34)).isEqualTo(StorageAccessLevel.NONE)
    }

    @Test
    fun `API26_到_29_没有_MANAGE_EXTERNAL_STORAGE_概念_广读即_FULL`() {
        assertThat(level(26, mediaRead = true)).isEqualTo(StorageAccessLevel.FULL)
        assertThat(level(29, mediaRead = true)).isEqualTo(StorageAccessLevel.FULL)
        assertThat(level(29)).isEqualTo(StorageAccessLevel.NONE)
    }

    @Test
    fun `API30_是分界线_29_与_30_判定不同`() {
        // 同样是「只有媒体读取」，29 是 FULL、30 是降级 —— 这条差异只由版本决定
        assertThat(level(29, mediaRead = true)).isEqualTo(StorageAccessLevel.FULL)
        assertThat(level(30, mediaRead = true)).isEqualTo(StorageAccessLevel.MEDIA_LIBRARY_ONLY)
    }

    @Test
    fun `API30_以下即使_hasExternalStorageManager_为真也不越级`() {
        // API30 以下没有这个能力，平台谓词恒为 false；万一被传成 true 也不能当成 FULL 的理由
        assertThat(level(28, allFiles = true)).isEqualTo(StorageAccessLevel.NONE)
    }

    @Test
    fun `运行时权限按版次给_13_起用_READ_MEDIA_AUDIO`() {
        assertThat(StorageAccess.runtimePermissions(33))
            .asList().containsExactly("android.permission.READ_MEDIA_AUDIO")
        assertThat(StorageAccess.runtimePermissions(34))
            .asList().containsExactly("android.permission.READ_MEDIA_AUDIO")
    }

    @Test
    fun `运行时权限_13_以下用_READ_EXTERNAL_STORAGE`() {
        assertThat(StorageAccess.runtimePermissions(26))
            .asList().containsExactly("android.permission.READ_EXTERNAL_STORAGE")
        assertThat(StorageAccess.runtimePermissions(32))
            .asList().containsExactly("android.permission.READ_EXTERNAL_STORAGE")
    }
}
