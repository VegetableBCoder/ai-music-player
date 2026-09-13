package com.aimusic.player.common.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 身份键是实体归组的唯一依据（`00 §1.2`、`I1`），也是持久化列 `song_entity.canonical_title`
 * / `artists_key` 的来源。`TextNormalizer` 是它的**唯一实现**（`08 §4.2`）。
 *
 * 期望值按 `03 §4.5` 的明确约定推导：不做拼音排序，中文按 Unicode 码位排序。
 */
class TextNormalizerTest {

    @Test
    fun `统一大小写与全半角`() {
        assertThat(TextNormalizer.normalizeToken("Hello World")).isEqualTo("hello world")
        assertThat(TextNormalizer.normalizeToken("Ｊａｙ")).isEqualTo("jay")
    }

    @Test
    fun `去首尾空格并折叠内部空白`() {
        assertThat(TextNormalizer.normalizeToken("  小  酒窝  ")).isEqualTo("小 酒窝")
    }

    @Test
    fun `标点两侧的空格去掉，标点本身保留`() {
        assertThat(TextNormalizer.normalizeToken("晴天 (Live)")).isEqualTo("晴天(live)")
        assertThat(TextNormalizer.normalizeToken("A - B")).isEqualTo("a-b")
    }

    @Test
    fun `全角括号与半角括号归一到同一个键`() {
        assertThat(TextNormalizer.normalizeToken("晴天（Live）"))
            .isEqualTo(TextNormalizer.normalizeToken("晴天 (Live)"))
    }

    @Test
    fun `版本语义词不剥离，因此与无版本词不相等`() {
        val live = TextNormalizer.normalizeTitle("晴天 (Live)")
        val plain = TextNormalizer.normalizeTitle("晴天")

        assertThat(live).isEqualTo("晴天(live)")
        assertThat(plain).isEqualTo("晴天")
        assertThat(live).isNotEqualTo(plain)
    }

    @Test
    fun `按分隔符与角色词拆分演唱者`() {
        assertThat(TextNormalizer.splitArtists("林俊杰 & 蔡卓妍"))
            .containsExactly("林俊杰", "蔡卓妍").inOrder()
        assertThat(TextNormalizer.splitArtists("蔡卓妍、林俊杰"))
            .containsExactly("林俊杰", "蔡卓妍").inOrder()
    }

    @Test
    fun `feat 这类角色词也作为分隔符`() {
        assertThat(TextNormalizer.splitArtists("Jay Chou feat. 袁咏琳"))
            .containsExactly("jay chou", "袁咏琳").inOrder()
    }

    @Test
    fun `拆分后去重，空白项被丢弃`() {
        assertThat(TextNormalizer.splitArtists("周杰伦 & 周杰伦")).containsExactly("周杰伦")
        assertThat(TextNormalizer.splitArtists("   ")).isEmpty()
    }

    @Test
    fun `artistsKey 用 U+001F 连接排序去重后的演唱者`() {
        assertThat(TextNormalizer.artistsKey(listOf("周杰伦"))).isEqualTo("周杰伦")
        assertThat(TextNormalizer.artistsKey(listOf("蔡卓妍", "林俊杰")))
            .isEqualTo("林俊杰\u001F蔡卓妍")
    }

    @Test
    fun `同一位歌手集合的不同写法归一到同一个 artistsKey`() {
        val ampersandForm = TextNormalizer.artistsKey(TextNormalizer.splitArtists("林俊杰 & 蔡卓妍"))
        val dunForm = TextNormalizer.artistsKey(TextNormalizer.splitArtists("蔡卓妍、林俊杰"))

        assertThat(ampersandForm).isEqualTo(dunForm)
    }

    @Test
    fun `同一首歌的不同文件写法归一到同一实体键`() {
        fun entityKeyOf(title: String, artists: String) =
            TextNormalizer.normalizeTitle(title) to
                TextNormalizer.artistsKey(TextNormalizer.splitArtists(artists))

        // 00 §1.2 的核心承诺：写法不同、实体相同
        assertThat(entityKeyOf("小酒窝", "林俊杰 & 蔡卓妍"))
            .isEqualTo(entityKeyOf("小酒窝", "蔡卓妍、林俊杰"))

        // 演唱者不同 → 不同实体
        assertThat(entityKeyOf("晴天", "周杰伦"))
            .isNotEqualTo(entityKeyOf("晴天", "张三"))
    }
}
