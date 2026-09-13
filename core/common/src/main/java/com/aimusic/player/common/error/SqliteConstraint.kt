package com.aimusic.player.common.error

/** 解析 SQLite 约束报文，例如 "UNIQUE constraint failed: music_file.path" */
data class SqliteConstraint(val type: ConflictType, val detail: String) {
    companion object {
        private val RE = Regex(
            """(?:UNIQUE|PRIMARY KEY|FOREIGN KEY|NOT NULL|CHECK) constraint failed:\s*(.+)"""
        )
        private val IDENT = Regex("""(music_file\.path|lyric\.path|tag\.name|entity_tag|scan_source|song_entity)""")

        fun parse(message: String?): SqliteConstraint? {
            val m = message?.let(RE::find) ?: return null
            val cols = m.groupValues[1].trim()
            val type = when {
                "music_file.path" in cols -> ConflictType.DUPLICATE_FILE
                "lyric.path" in cols      -> ConflictType.DUPLICATE_LYRIC
                "tag.name" in cols        -> ConflictType.DUPLICATE_TAG
                "entity_tag" in cols      -> ConflictType.DUPLICATE_TAG
                "scan_source" in cols     -> ConflictType.DUPLICATE_SOURCE
                else                      -> ConflictType.DUPLICATE_FILE
            }
            // detail 仅保留表/列名，剔除可能含路径的值
            return SqliteConstraint(type, IDENT.find(cols)?.value ?: "constraint")
        }
    }
}
