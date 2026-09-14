package com.aimusic.player.navigation

import kotlinx.serialization.Serializable

/**
 * 类型安全路由（`09 §4.1.1`）。
 *
 * 只定义本期（3e）真正会用到的那些；其余路由随各自阶段落地 —— 先声明一堆没人能导航到的
 * 目的地，只会让「哪些屏已交付」变得看不出来。
 */
@Serializable
data object MineRoute

@Serializable
data object ScanRoute
