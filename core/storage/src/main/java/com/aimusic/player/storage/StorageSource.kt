package com.aimusic.player.storage

/**
 * 文件系统访问抽象（`02 §5.1`）。
 *
 * 实现 `FileStorageSource` 在 Phase 3；本期只固定契约，让 `:core:data`
 * 能在不触碰真实文件系统的前提下完成物理删除与封面路径读取。
 */
interface StorageSource {
    fun listFiles(roots: List<String>, exts: Set<String>, onProgress: (Int) -> Unit): Sequence<FileRef>
    fun exists(path: String): Boolean

    /**
     * 目录判定。
     *
     * 加这一条是为了 `04 §3.4` 的 `add`：它要区分「路径不存在」与「路径存在但不是目录」，
     * 给用户两种不同的提示。没有它，业务层就只能自己摸 `java.io.File`，而 `04 §1.2`
     * 明令禁止业务层直接碰文件系统。
     */
    fun isDirectory(path: String): Boolean
    fun size(path: String): Long
    fun readBytes(path: String, maxBytes: Int = 1 shl 20): ByteArray

    /** 物理删除 */
    fun delete(path: String): Boolean

    /** 权限自检 */
    fun canWrite(): Boolean
}

data class FileRef(val path: String, val name: String, val size: Long, val lastModified: Long)
