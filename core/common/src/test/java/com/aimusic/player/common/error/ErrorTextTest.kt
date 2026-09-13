package com.aimusic.player.common.error

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 文案表是需求文档的唯一落地处：**原文案是需求的唯一权威**，模块内不得改写风格
 * （`11 §5.1`）。因此这里的期望值全部是从 `需求文档/05-空状态与异常处理.md`
 * 与 `11 §5.1 / §5.4` 手抄的字面量，不使用任何会被实现影响的推导。
 */
class ErrorTextTest {

    private val fallback = "出错了，请查看详情"

    @Test
    fun `§5_1 无参数文案逐条一致`() {
        assertThat(ErrorText.resolve("scan.need_source"))
            .isEqualTo("请先选择要扫描的音乐来源（目录）")
        assertThat(ErrorText.resolve("perm.read_media.denied"))
            .isEqualTo("需要「读取媒体」权限才能扫描本地音乐")
        assertThat(ErrorText.resolve("scan.diff.none")).isEqualTo("未发现新文件")
        assertThat(ErrorText.resolve("scan.empty")).isEqualTo("没有扫描到音乐")
        assertThat(ErrorText.resolve("analysis.all_failed.network")).isEqualTo("无网络，分析未完成")
        assertThat(ErrorText.resolve("analysis.all_failed.server")).isEqualTo("服务暂时不可用")
        assertThat(ErrorText.resolve("analysis.all_failed.parse")).isEqualTo("服务返回内容异常")
        assertThat(ErrorText.resolve("analysis.no_tags")).isEqualTo("暂无标签")
        assertThat(ErrorText.resolve("library.empty")).isEqualTo("音乐库还是空的")
        assertThat(ErrorText.resolve("library.filter.empty")).isEqualTo("没有符合筛选条件的歌曲")
        assertThat(ErrorText.resolve("song.unavailable")).isEqualTo("无可用文件")
        assertThat(ErrorText.resolve("tag.category.empty")).isEqualTo("该分类还没有标签")
        assertThat(ErrorText.resolve("tag.songs.empty")).isEqualTo("该标签下还没有歌曲")
        assertThat(ErrorText.resolve("song.tags.empty")).isEqualTo("暂无标签")
        assertThat(ErrorText.resolve("lyric.linked.none")).isEqualTo("未关联歌词")
        assertThat(ErrorText.resolve("lyric.file.invalid")).isEqualTo("歌词文件已失效")
        assertThat(ErrorText.resolve("playback.file.missing")).isEqualTo("文件不存在或已被移动")
    }

    @Test
    fun `§5_1 带参数文案插值后一致`() {
        assertThat(ErrorText.resolve("scan.running", mapOf("new" to 12)))
            .isEqualTo("正在扫描，已发现 12 首")
        assertThat(ErrorText.resolve("scan.diff.found", mapOf("new" to 3, "skipped" to 5, "cleaned" to 1)))
            .isEqualTo("发现 3 个新文件（新增 3 / 已存在跳过 5 / 已删除清理 1）")
        assertThat(ErrorText.resolve("analysis.running", mapOf("done" to 2, "total" to 9)))
            .isEqualTo("正在分析（2/9）")
        assertThat(ErrorText.resolve("analysis.partial", mapOf("ok" to 7, "failed" to 2)))
            .isEqualTo("分析完成：成功 7 / 失败 2")
        assertThat(ErrorText.resolve("llm.retrying", mapOf("attempt" to 2, "max" to 3)))
            .isEqualTo("请求过于频繁，重试中（第 2/3 次）")
        assertThat(ErrorText.resolve("search.empty", mapOf("query" to "青花瓷")))
            .isEqualTo("未找到与「青花瓷」相关的内容")
    }

    @Test
    fun `§5_4 冲突态文案与文档一致`() {
        assertThat(ErrorText.resolve("tag.reuse_existing")).isEqualTo("该标签已存在，是否复用？")
        assertThat(ErrorText.resolve("tag.delete.blocked")).isEqualTo("该标签下还有歌曲，无法删除")
        assertThat(ErrorText.resolve("category.delete.blocked")).isEqualTo("该分类下还有标签，无法删除")
    }

    @Test
    fun `无 key 与未知 key 都回落为 unknown 文案`() {
        assertThat(ErrorText.resolve(null)).isEqualTo(fallback)
        assertThat(ErrorText.resolve("no.such.key")).isEqualTo(fallback)
        assertThat(ErrorText.resolve("unknown")).isEqualTo(fallback)
    }

    @Test
    fun `每个错误子类的默认文案键都不是悬空的`() {
        // 默认键打错字、或加了子类却忘了往文案表里加键，都会在这里失败
        val errors = listOf(
            PermissionError(PermissionScope.ALL_FILES),
            NetworkError(NetworkReason.TIMEOUT),
            RateLimitError(RateLimitSource.HTTP_429, retryAfterMs = 1_000, attempt = 1),
            AuthError(httpStatus = 401),
            ServerError(httpStatus = 503),
            ParseError(ParseTarget.LLM_JSON),
            IoError(IoOperation.SCAN),
            NotFoundError(MissingEntity.MUSIC_FILE),
        )

        errors.forEach { error ->
            assertThat(error.userMessageKey).isNotNull()
            assertThat(ErrorText.resolve(error.userMessageKey))
                .isNotEqualTo(fallback)
        }
    }

    @Test
    fun `未提供的参数保持占位符原样，便于在界面上暴露调用错误`() {
        assertThat(ErrorText.resolve("search.empty"))
            .isEqualTo("未找到与「{query}」相关的内容")
    }
}
