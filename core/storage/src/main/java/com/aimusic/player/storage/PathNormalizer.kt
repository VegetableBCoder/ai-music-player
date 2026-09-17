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

        // 软链解析（best-effort）：只对**磁盘上确实存在**的绝对路径做。相对路径在这里
        // canonicalize 会悄悄拼上进程的工作目录，把相对路径变成绝对路径 —— 那不是归一化。
        //
        // 为什么必须先判 `exists()`：`File.getCanonicalPath()` 对**不存在**的路径**不抛异常**，
        // 而是把整串当成相对于当前工作目录 —— 在非 Android 宿主上会把
        // `/storage/emulated/0/a.mp3` 变成 `D:\storage\emulated\0\a.mp3`。那是宿主平台泄漏进
        // 结果，不是 `04 §4.6` 六步里的任何一步（软链解析本就只对真实存在的路径成立）。
        // 这也正是本文件顶部说"root 参数化是为了能在 JVM 上测"的前提：不判存在就测不了。
        //
        // Android 上此判定恒为真（媒体库路径必然存在），所以这是**平台无关化**而非行为变更；
        // 真正被它修好的是"这条规则终于能在 JVM 上测"。
        if (!p.startsWith("/")) return p
        // 根路径单独放行：它没有软链可解，而宿主会把它映射成盘根（Windows 上 "/" → "D:\"）。
        // 在 Android 上 canonicalPath("/") 本就返回 "/"，故此处是恒等替换。
        if (p == "/") return p
        val file = File(p)
        if (!file.exists()) return p
        return runCatching { file.canonicalPath }.getOrDefault(p)
    }
}
