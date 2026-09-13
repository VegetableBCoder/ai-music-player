package com.aimusic.player.data.error

/**
 * 物理删除的结果（`03 §4.4`：磁盘删除失败则中止，数据库不得改动）。
 *
 * 之所以要返回值而不是抛异常：失败是**预期内**的正常分支（文件已被用户删掉 / 权限变化），
 * 调用方要据此提示「删除失败」并保留记录，UI 才能给出清理入口。
 */
sealed interface DeleteFileResult {
    data object Deleted : DeleteFileResult

    /** 磁盘删除失败，数据库**未做任何改动**。`path` 供日志与提示使用。 */
    data class DiskDeleteFailed(val path: String) : DeleteFileResult
}
