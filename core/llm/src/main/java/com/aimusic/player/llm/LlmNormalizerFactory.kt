package com.aimusic.player.llm

import com.aimusic.player.common.retry.RetryPolicy
import com.aimusic.player.common.util.RealSleeper
import com.aimusic.player.common.util.Sleeper
import kotlin.random.Random

/**
 * 组装装饰链：`Caching( Retrying( Direct ) )`（spec §2 / 05 §4.2）。
 *
 * 顺序**不可交换**：
 * 1. 缓存最外层 → 命中即零网络（保住 01 §3.3「重扫不花 token」）；
 * 2. 退避紧贴网络 → 只包裹真实 HTTP，缓存层不感知 429；
 * 3. 只缓存成功 → 退避在缓存之内，写进缓存的自然是「退避之后」的最终成功。
 *
 * `direct` 由 P1 提供（`DirectProvider`）；`promptHash` 由 P2 的 `PromptBuilder` 提供，`dirFingerprint` 由 P2 的 `DirectoryFingerprint.of(categories, tags)` 适配。
 */
fun buildLlmNormalizer(
    direct: LlmNormalizer,
    cache: LlmCache,
    model: String,
    promptHash: () -> String,
    dirFingerprint: (NormalizeRequest) -> String,
    policy: RetryPolicy,
    sleeper: Sleeper = RealSleeper,
    random: Random = Random.Default,
    onBackoff: (BackoffEvent) -> Unit = {},
): LlmNormalizer = CachingLlmNormalizer(
    delegate = RetryingLlmNormalizer(
        delegate = direct,
        policy = policy,
        sleeper = sleeper,
        random = random,
        onBackoff = onBackoff,
    ),
    cache = cache,
    model = model,
    promptHash = promptHash,
    dirFingerprint = dirFingerprint,
)