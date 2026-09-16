package com.aimusic.player.llm

import com.aimusic.player.common.model.NormalizeResult

/**
 * 记录每次调用收到的请求，并按脚本返回（越界后固定用最后一项）。
 * 「网络请求次数」在装饰器单测里就等于这个 `calls.size`。
 */
class ScriptedLlmNormalizer(
    private val script: List<List<NormalizeOutcome>>,
) : LlmNormalizer {

    val calls = mutableListOf<List<NormalizeRequest>>()

    override suspend fun normalize(requests: List<NormalizeRequest>): List<NormalizeOutcome> {
        calls += requests
        return script[minOf(calls.size - 1, script.lastIndex)]
    }
}

/** 声明式构造：对每个请求都返回同一个成功结果。 */
fun successFor(request: NormalizeRequest): NormalizeOutcome =
    NormalizeOutcome.Success(
        NormalizeResult(
            canonicalTitle = request.fileName.substringAfterLast('/').substringBeforeLast('.'),
            artists = listOf("未知艺术家"),
            tagAssignments = emptyList(),
        ),
    )
