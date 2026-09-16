package com.aimusic.player.llm.crypto

import java.security.MessageDigest

/** sha256 十六进制小写。用于 prompt 文件哈希与缓存 key 组装（`05 §4.8`）。 */
fun sha256Hex(text: String): String = sha256Hex(text.encodeToByteArray())

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
