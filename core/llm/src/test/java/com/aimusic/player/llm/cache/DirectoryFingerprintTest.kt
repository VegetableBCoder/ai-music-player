package com.aimusic.player.llm.cache

import com.aimusic.player.llm.TagRef
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DirectoryFingerprintTest {

    private val categories = listOf("音乐类型", "情绪")
    private val tags = listOf(
        TagRef("流行", "音乐类型"),
        TagRef("ACG", "音乐类型"),
        TagRef("怀旧", "情绪"),
    )

    @Test
    fun `相同目录得到相同指纹`() {
        assertThat(DirectoryFingerprint.of(categories, tags))
            .isEqualTo(DirectoryFingerprint.of(categories, tags))
    }

    @Test
    fun `指纹是 64 位小写十六进制`() {
        assertThat(DirectoryFingerprint.of(categories, tags)).matches("[0-9a-f]{64}")
    }

    @Test
    fun `顺序不影响指纹`() {
        assertThat(DirectoryFingerprint.of(categories.reversed(), tags.reversed()))
            .isEqualTo(DirectoryFingerprint.of(categories, tags))
    }

    @Test
    fun `新增一个分类即改变指纹`() {
        assertThat(DirectoryFingerprint.of(categories + "主题", tags))
            .isNotEqualTo(DirectoryFingerprint.of(categories, tags))
    }

    @Test
    fun `新增一个标签即改变指纹`() {
        assertThat(DirectoryFingerprint.of(categories, tags + TagRef("摇滚", "音乐类型")))
            .isNotEqualTo(DirectoryFingerprint.of(categories, tags))
    }

    @Test
    fun `大小写与全半角按 TextNormalizer 归一到同一个指纹`() {
        val halfWidth = listOf(TagRef("acg", "音乐类型"))
        val fullWidth = listOf(TagRef("ＡＣＧ", "音乐类型"))
        assertThat(DirectoryFingerprint.of(categories, halfWidth))
            .isEqualTo(DirectoryFingerprint.of(categories, fullWidth))
    }
}
