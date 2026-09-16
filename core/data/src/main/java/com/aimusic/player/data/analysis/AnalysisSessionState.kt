package com.aimusic.player.data.analysis

/**
 * 分析会话的热镜像：UI 只读它。
 *
 * 为什么要有它（而不是让 UI 直接收集冷 Flow）：冷 Flow 会被 AppScope 的触发器独占消费，
 * 第二个收集者什么也收不到；而取消之后编排器还能通过它广播终态（`Finished(aborted = true)` 不会被丢）。
 *
 * 不加 `@Immutable`：那是 Compose 的注解，而 `:core:data` 不该为此引入 compose-runtime 依赖
 * （计划 Interfaces 里带了它，执行时按依赖纪律去掉）。若将来 UI 侧出现稳定性问题再议。
 */
data class AnalysisSessionState(
    val running: Boolean = false,
    val runId: Long? = null,
    val total: Int = 0,
    val done: Int = 0,
    val ok: Int = 0,
    val failed: Int = 0,
    val batchIndex: Int = 0,
    val batchCount: Int = 0,
    val retrying: AnalysisProgress.Retrying? = null,
    val aborted: Boolean = false,
) {
    val hasProgress: Boolean get() = total > 0
}
