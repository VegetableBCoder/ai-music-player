package com.aimusic.player.common.error

/**
 * `userMessageKey` → 中文文案（模板 + `{name}` 参数插值）。
 *
 * 用 Kotlin 常量表而不是 Android 字符串资源：文案与 `AppError` 是同一份契约，
 * 放一起才能在**纯 JVM 单测**里逐条断言（`11 §8.1` 要求文案逐条有测试）。
 * 应用仅中文，无 i18n 需求。
 *
 * 字面一律来自 `需求文档/05-空状态与异常处理.md` 与 `11 §5.1 / §5.4`，不得改写风格。
 */
object ErrorText {

    private const val KEY_UNKNOWN = "unknown"

    private val PLACEHOLDER = Regex("""\{(\w+)\}""")

    private val texts: Map<String, String> = mapOf(
        // —— 扫描 / 权限（05 §1、05 §7）——
        "scan.need_source" to "请先选择要扫描的音乐来源（目录）",
        "perm.read_media.denied" to "需要「读取媒体」权限才能扫描本地音乐",
        "scan.running" to "正在扫描，已发现 {new} 首",
        "scan.diff.found" to "发现 {new} 个新文件（新增 {new} / 已存在跳过 {skipped} / 已删除清理 {cleaned}）",
        "scan.diff.none" to "未发现新文件",
        "scan.empty" to "没有扫描到音乐",
        "scan.one_click.empty" to "没有找到常见的音乐目录，请手动选择",
        "scan.committed" to "已提交 {count} 首，分析待后续版本",
        "scan.commit.cancelled" to "提交已取消，已添加的文件保留",

        // —— 分析（05 §2）——
        "analysis.running" to "正在分析（{done}/{total}）",
        "analysis.partial" to "分析完成：成功 {ok} / 失败 {failed}",
        "analysis.all_failed.network" to "无网络，分析未完成",
        "analysis.all_failed.server" to "服务暂时不可用",
        "analysis.all_failed.parse" to "服务返回内容异常",
        "analysis.no_tags" to "暂无标签",
        "llm.retrying" to "请求过于频繁，重试中（第 {attempt}/{max} 次）",

        // —— 歌曲库（05 §3）——
        "library.empty" to "音乐库还是空的",
        "search.empty" to "未找到与「{query}」相关的内容",
        "library.filter.empty" to "没有符合筛选条件的歌曲",
        "song.unavailable" to "无可用文件",

        // —— 标签（05 §4）——
        "tag.category.empty" to "该分类还没有标签",
        "tag.songs.empty" to "该标签下还没有歌曲",
        "song.tags.empty" to "暂无标签",

        // —— 歌词（05 §5）——
        "lyric.linked.none" to "未关联歌词",
        "lyric.file.invalid" to "歌词文件已失效",

        // —— 文件失效（05 §6）——
        "playback.file.missing" to "文件不存在或已被移动",

        // —— 冲突态（11 §5.4）——
        "tag.reuse_existing" to "该标签已存在，是否复用？",
        "tag.delete.blocked" to "该标签下还有歌曲，无法删除",
        "category.delete.blocked" to "该分类下还有标签，无法删除",

        // —— AppError 各子类的默认键（11 §3.1）——
        "perm.denied" to "需要授予权限才能继续",
        "net.error" to "无网络，请检查网络后重试",
        "llm.auth" to "API Key 无效，请到设置检查",
        "llm.server" to "服务暂时不可用",
        "llm.parse" to "服务返回内容异常",
        "io.error" to "文件操作失败，请重试",
        "file.missing" to "文件不存在或已被移动",
        KEY_UNKNOWN to "出错了，请查看详情",
    )

    /**
     * 取文案并插值。
     *
     * - `key` 为 null 或未收录 → 回落 `unknown`
     * - 未提供的参数 → 保留占位符原样（宁可在界面上看见 `{query}`，也不要静默显示空串）
     */
    fun resolve(key: String?, args: Map<String, Any?> = emptyMap()): String {
        val template = key?.let(texts::get) ?: texts.getValue(KEY_UNKNOWN)
        if (args.isEmpty()) return template

        return PLACEHOLDER.replace(template) { match ->
            args[match.groupValues[1]]?.toString() ?: match.value
        }
    }
}
