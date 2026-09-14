package com.aimusic.player.storage

import java.io.File
import java.text.Normalizer

/**
 * 路径规范化的**唯一实现**（`04 §4.6`）：差异比对的键由它产生（`music_file.path` 即此值）。
 *
 * `primaryRoot` 是参数而不是读 `Environment`：`Environment` 是 Android API，一读这套规则
 * 就没法在 JVM 上测，而别名归并恰恰是最容易写错的一段。调用方（`FileStorageSource`）
 * 在真机上把 `Environment.getExternalStorageDirectory()` 的值传进来即可。
 */
object PathNormalizer {

    /** 主存储根的历史别名（`04 §4.6` 步骤 5）。 */
    private val ALIASES = listOf("/storage/self/primary", "/mnt/sdcard", "/sdcard")

    private val REPEATED_SLASH = Regex("/{2,}")

    fun normalize(path: String, primaryRoot: String): String {
        if (path.isEmpty()) return ""

        var p = path.replace('\\', '/')
        p = Normalizer.normalize(p, Normalizer.Form.NFC)
        p = p.replace(REPEATED_SLASH, "/")
        if (p.length > 1) p = p.trimEnd('/').ifEmpty { "/" }

        // 别名按**整段前缀**替换：/sdcardbackup 不能命中 /sdcard
        val alias = ALIASES.firstOrNull { p == it || p.startsWith("$it/") }
        if (alias != null) p = primaryRoot + p.removePrefix(alias)

        // 软链解析（best-effort）：只对绝对路径做。相对路径在这里 canonicalize 会
        // 悄悄拼上进程的工作目录，把一个相对路径变成绝对路径 —— 那不是归一化。
        if (!p.startsWith("/")) return p
        return runCatching { File(p).canonicalPath }.getOrDefault(p)
    }
}
