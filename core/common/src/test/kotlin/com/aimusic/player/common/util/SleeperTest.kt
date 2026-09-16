package com.aimusic.player.common.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * `Sleeper` 是退避层唯一「真的花时间」的地方（05 §4.7）。
 * 这里用 runBlocking（不是 runTest）—— 要的是真实时间，且要能验证协程取消。
 */
class SleeperTest {

    @Test
    fun `RealSleeper 至少等待请求的时长`() {
        val start = System.nanoTime()

        runBlocking { RealSleeper.sleep(40) }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertThat(elapsedMs).isAtLeast(35L)
    }

    @Test
    fun `RealSleeper 可被协程取消`() = runBlocking {
        val job = launch { RealSleeper.sleep(10_000) }
        delay(20)

        job.cancel()
        job.join()

        assertThat(job.isCancelled).isTrue()
    }
}