package com.aimusic.player.data.error

import android.database.sqlite.SQLiteConstraintException
import com.aimusic.player.common.error.ConflictType
import com.aimusic.player.common.error.SqliteConstraint

/**
 * 所有写操作经此翻译（`11 §6.2`）；Repository 之上只见业务结果，不见 SQLite 异常。
 *
 * `inline` 是必需的：`block` 里往往是 suspend 的 DAO 调用，只有内联才能让 lambda
 * 继承调用方的 suspend 上下文（`11 §6.2` 的示例正是这么用的）。
 */
object DbErrorTranslator {

    inline fun <T> translate(block: () -> T, onConflict: (ConflictType) -> T): T = try {
        block()
    } catch (t: SQLiteConstraintException) {
        val constraint = SqliteConstraint.parse(t.message) ?: throw t
        onConflict(constraint.type)
    }
}
