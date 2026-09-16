package com.aimusic.player.common.util

import kotlinx.coroutines.delay

/**
 * 可注入的等待（05 §4.7）。
 *
 * 退避层用它等待；测试注入假实现，**绝不真 sleep**。`suspend` 有两个理由：
 * 等待期间让出线程，且响应协程取消（用户点「取消」要能立刻中断退避）。
 */
fun interface Sleeper {
    suspend fun sleep(ms: Long)
}

/** 生产实现：真等，但可被协程取消。 */
object RealSleeper : Sleeper {
    override suspend fun sleep(ms: Long) = delay(ms)
}