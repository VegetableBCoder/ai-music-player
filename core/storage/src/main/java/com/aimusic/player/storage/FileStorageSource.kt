package com.aimusic.player.storage

import java.io.File
import java.io.IOException

/**
 * 目录遍历（`04 §4.3`）。
 *
 * 三条不可退让的规则：
 * - **软链成环不能死循环**：`visited` 记 `canonicalPath`，配合深度上限双保险。
 * - **单个目录出错不能终止整次扫描**：`SecurityException` / `IOException` 记警告后 `continue`。
 * - **惰性**：返回 `Sequence`，与上层的元数据流水线交错消费（`04 §4.5`），
 *   不先把 N 万条路径读进内存。
 */
class FileStorageSource(
    private val primaryRoot: String,
    private val maxDepth: Int = MAX_DEPTH,
    private val onWarning: (String) -> Unit = {},
) : StorageSource {

    override fun listFiles(
        roots: List<String>,
        exts: Set<String>,
        onProgress: (Int) -> Unit,
    ): Sequence<FileRef> = sequence {
        val extSet = exts.mapTo(HashSet()) { it.lowercase() }
        val visited = HashSet<String>()
        val stack = ArrayDeque<Pair<File, Int>>()
        roots.forEach { r -> File(r).takeIf { it.isDirectory }?.let { stack.addLast(it to 0) } }

        var found = 0
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            if (depth > maxDepth) continue
            if (dir.name.startsWith(".")) continue
            if (isSystemDir(dir)) continue

            val canon = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
            if (!visited.add(canon)) continue

            val children = try {
                dir.listFiles()
            } catch (e: SecurityException) {
                onWarning("不可读目录（权限）：${dir.absolutePath}（${e.javaClass.simpleName}）")
                null
            } catch (e: IOException) {
                onWarning("跳过目录：${dir.absolutePath}（${e.javaClass.simpleName}）")
                null
            }
            if (children == null) {
                onWarning("不可读目录：${dir.absolutePath}")
                continue
            }

            for (f in children) {
                if (f.name.startsWith(".")) continue
                when {
                    f.isDirectory -> if (!isSystemDir(f)) stack.addLast(f to depth + 1)
                    f.isFile -> {
                        val ext = f.name.substringAfterLast('.', "").lowercase()
                        if (ext in extSet) {
                            found++
                            onProgress(found)
                            yield(FileRef(f.absolutePath, f.name, f.length(), f.lastModified()))
                        }
                    }
                    // FIFO / 设备文件等非普通文件，忽略
                    else -> Unit
                }
            }
        }
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    /**
     * 直属子目录（目录浏览器用）。
     *
     * 复用 `listFiles` 的同一套黑名单（隐藏目录 + 系统目录），否则浏览器里会出现一堆
     * 用户点进去也看不懂、扫也扫不出东西的目录。**不递归**、按名称排序、出错返回空。
     */
    override fun listDirectories(parent: String): List<FileRef> {
        val dir = File(parent)
        if (!dir.isDirectory) return emptyList()

        val children = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            onWarning("不可读目录（权限）：${dir.absolutePath}（${e.javaClass.simpleName}）")
            null
        } catch (e: IOException) {
            onWarning("跳过目录：${dir.absolutePath}（${e.javaClass.simpleName}）")
            null
        } ?: return emptyList()

        return children
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") && !isSystemDir(it) }
            .map { FileRef(path = it.absolutePath, name = it.name, size = 0L, lastModified = it.lastModified()) }
            .sortedBy { it.name }
            .toList()
    }

    override fun size(path: String): Long = File(path).length()

    override fun readBytes(path: String, maxBytes: Int): ByteArray {
        val file = File(path)
        return file.inputStream().use { input ->
            val buffer = ByteArray(maxBytes)
            var read = 0
            while (read < maxBytes) {
                val n = input.read(buffer, read, maxBytes - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    }

    override fun delete(path: String): Boolean = File(path).delete()

    /**
     * 权限自检（`10 §4.3` 用它判断能否写）。
     *
     * 只探测主存储根：这是「所有文件访问」授权后一定能写、未授权时一定写不了的位置，
     * 拿任意来源目录去探测会把「权限不足」和「这个目录恰好不可写」混为一谈。
     */
    override fun canWrite(): Boolean = File(primaryRoot).canWrite()

    private fun isSystemDir(dir: File): Boolean {
        if (dir.name in SYSTEM_DIR_NAMES) return true
        val path = dir.absolutePath
        return SYSTEM_DIR_SUFFIXES.any { path.endsWith(it) }
    }

    companion object {
        const val MAX_DEPTH = 12

        /** 按目录名匹配的黑名单。 */
        private val SYSTEM_DIR_NAMES = setOf(".thumbnails", ".trash", "LOST.DIR")

        /** 按相对根路径匹配的黑名单（两层以上，名字看不出归属）。 */
        private val SYSTEM_DIR_SUFFIXES = setOf("/Android/data", "/Android/obb")
    }
}
