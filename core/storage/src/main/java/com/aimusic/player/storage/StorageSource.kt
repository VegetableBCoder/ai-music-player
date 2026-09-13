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
    fun size(path: String): Long
    fun readBytes(path: String, maxBytes: Int = 1 shl 20): ByteArray

    /** 物理删除 */
    fun delete(path: String): Boolean

    /** 权限自检 */
    fun canWrite(): Boolean
}

data class FileRef(val path: String, val name: String, val size: Long, val lastModified: Long)
