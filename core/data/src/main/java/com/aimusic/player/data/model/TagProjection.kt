package com.aimusic.player.data.model

/**
 * 标签引用投影（列表 / 详情共用）—— `06 §3.1`、`02 §5.3`。
 *
 * **勿与 `:core:llm` 契约的 `TagRef` 混淆**：那是 `05 §3.1` 锁定的**两字段**形态
 * （`name` + `category` 分类名）。因依赖守卫禁止 `:core:llm → :core:data`，它拿不到 `categoryId`；
 * 本类型多带 `categoryId`，供列表 / 详情按分类定位与渲染。
 *
 * 放在 `:core:data` 而非 `:core:common`：它是列表 / 详情的域投影，与归一化无关，
 * 只被 `:core:data` 及上层 UI 消费（`06 §3.1`）。此前它误称为 `TagRef` 并与 `:core:llm`
 * 的同名类型互相矛盾（`core/common` 的 KDoc 还自称「全项目唯一的标签展示类型」），
 * 正是 `02 §5.3` 决定 #3 要避免的混淆 —— 本次按该决定改名归位。
 */
data class TagProjection(
    val name: String,
    val categoryId: Long,
    val categoryName: String,
)
