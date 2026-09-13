package com.aimusic.player

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 0.4 技术探针：确认单测栈（JUnit + coroutines-test + Turbine + MockK）可用。
 * 三个测试分别压各自的库，缺任何一个都会红。
 */
class ProbeUnitTest {

    interface Greeter {
        fun greet(): String
    }

    @Test
    fun `runTest 使用虚拟时间`() = runTest {
        val start = testScheduler.currentTime
        delay(5_000)
        // runTest 走虚拟时间：真实耗时接近 0，而调度器时间推进了 5s
        assertEquals(5_000L, testScheduler.currentTime - start)
    }

    @Test
    fun `Turbine 能断言 Flow 序列`() = runTest {
        flowOf(1, 2, 3).test {
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            assertEquals(3, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `MockK 能打桩`() {
        val greeter = mockk<Greeter>()
        every { greeter.greet() } returns "hi"
        assertEquals("hi", greeter.greet())
    }
}
