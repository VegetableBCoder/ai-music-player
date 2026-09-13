package com.aimusic.player.common.error

enum class ErrorLevel { INFO, WARNING, ERROR }

data class ErrorUiModel(
    val text: String,                      // 已本地化的用户文案
    val actions: List<ErrorAction>,        // 可操作按钮
    val level: ErrorLevel,
)

sealed interface ErrorAction {
    data object GrantPermission : ErrorAction   // 去授权
    data object GoSettings : ErrorAction        // 去设置（改 Key）
    data object Retry : ErrorAction             // 重试（单文件）
    data object RetryAll : ErrorAction          // 全部重试
    data object ViewDetails : ErrorAction       // 查看详情
    data object Rescan : ErrorAction            // 重新扫描
    data object ReselectSource : ErrorAction    // 重新选择来源
    data object SelectSource : ErrorAction      // 选择音乐来源
    data object Cancel : ErrorAction            // 取消
    data object ClearFilter : ErrorAction       // 清除筛选
    data object ClearQuery : ErrorAction        // 清空关键词
    data object NewTag : ErrorAction            // 新建标签
    data object UnlinkLyric : ErrorAction       // 移除关联
    data object DeleteSong : ErrorAction        // 删除歌曲
    data object KeepUnavailable : ErrorAction   // 保留并标记不可用
    data object ChangeKey : ErrorAction         // 换 Key（402/鉴权场景）
}
