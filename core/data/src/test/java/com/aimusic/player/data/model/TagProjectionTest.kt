package com.aimusic.player.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 三字段标签投影：改名（`TagRef` → `TagProjection`）与归位（`:core:common` → `:core:data`）
 * 后，字段顺序与含义仍与 `06 §3.1` 逐字一致。
 *
 * 纯数据类，用 JVM 单测钉住即可 —— 它不碰 Android 框架，没必要上真机。
 */
class TagProjectionTest {

    @Test
    fun 字段与含义符合06契约() {
        val projection = TagProjection(
            name = "摇滚",
            categoryId = 7L,
            categoryName = "音乐类型",
        )

        assertThat(projection.name).isEqualTo("摇滚")
        assertThat(projection.categoryId).isEqualTo(7L)
        assertThat(projection.categoryName).isEqualTo("音乐类型")
    }

    @Test
    fun 同字段同值即相等供列表去重与测试断言使用() {
        val a = TagProjection("摇滚", 7L, "音乐类型")
        val b = TagProjection("摇滚", 7L, "音乐类型")
        val differentCategory = TagProjection("摇滚", 8L, "情绪")

        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(differentCategory)
    }

    @Test
    fun 与core_llm的两字段TagRef形状不同() {
        // 两者同名不同形：本类型多带 categoryId，:core:llm 的 TagRef 只有 name + category。
        // 这里把两个类型同时摆出来，钉住「各是各的、没有任何继承或转换关系」——
        // 若将来有人把 :core:llm 的 TagRef 直接塞进列表装配，本处的类型不匹配会在编译期报错。
        val projection: TagProjection = TagProjection("摇滚", 7L, "音乐类型")
        val llmRef: com.aimusic.player.llm.TagRef =
            com.aimusic.player.llm.TagRef(name = "摇滚", category = "音乐类型")

        assertThat(projection.name).isEqualTo(llmRef.name)
        assertThat(projection.categoryName).isEqualTo(llmRef.category)
        // 本类型有 categoryId，llmRef 没有 —— 这就是两者不能互相替代的原因
        assertThat(projection.categoryId).isEqualTo(7L)
    }
}
