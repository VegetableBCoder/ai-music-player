package com.aimusic.player.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * 在 instrumented 测试里跑 suspend 代码（Room 的 suspend DAO 等）。
 *
 * 存在的理由：**JUnit4 要求测试方法返回 `void`**，而 `= runBlocking { ... }` 的返回类型
 * 是 lambda 最后一条表达式的类型。Truth 的部分断言（如 `containsExactly(...)`）返回
 * `Ordered` 而不是 `Unit`，于是方法签名不是 void，JUnit 会直接判定整个测试类无效：
 *
 * ```
 * Invalid test class 'XxxTest': 1. Method foo() should be void
 * ```
 *
 * 这个 helper 把返回类型钉死为 `Unit`，让写测试的人不必记住哪几个断言返回什么。
 */
fun runDbTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking(block = block)
