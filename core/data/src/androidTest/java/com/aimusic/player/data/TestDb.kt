package com.aimusic.player.data

import com.aimusic.player.data.db.MusicDatabase

/**
 * instrumented 测试共享的夹具工具。
 *
 * 夹具一律用**原生 SQL**插入：断言走真实事务，避免测试依赖自己正要验证的 DAO 查询
 * （否则查询写错时测试会跟着一起错，双双通过）。
 */
internal fun MusicDatabase.exec(sql: String) = openHelper.writableDatabase.execSQL(sql)

internal fun MusicDatabase.column(sql: String): List<String> =
    openHelper.readableDatabase.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0) ?: "") }
    }

internal fun MusicDatabase.countOf(table: String): Int =
    column("SELECT COUNT(*) FROM $table").single().toInt()

internal fun MusicDatabase.scalar(sql: String): String? =
    column(sql).singleOrNull()?.takeIf { it.isNotEmpty() }

internal fun MusicDatabase.insertSong(
    title: String,
    artistsKey: String,
    albumName: String? = null,
): Long {
    val album = albumName?.let { "'$it'" } ?: "NULL"
    exec(
        "INSERT INTO song_entity (canonical_title, artists_key, display_artists, album_name, created_at) " +
            "VALUES ('$title', '$artistsKey', '$artistsKey', $album, 0)"
    )
    return column("SELECT id FROM song_entity WHERE canonical_title = '$title'").single().toLong()
}

internal fun MusicDatabase.insertFile(
    path: String,
    size: Long,
    status: String = "UNANALYZED",
    durationMs: Long? = null,
): Long {
    val duration = durationMs?.toString() ?: "NULL"
    exec(
        "INSERT INTO music_file (path, file_name, size, format, duration_ms, analysis_status, added_at) " +
            "VALUES ('$path', '${path.substringAfterLast('/')}', $size, 'mp3', $duration, '$status', 0)"
    )
    return column("SELECT id FROM music_file WHERE path = '$path'").single().toLong()
}

internal fun MusicDatabase.insertCategory(name: String, isMain: Boolean = false): Long {
    exec(
        "INSERT INTO category (name, is_main, is_builtin, sort_order) " +
            "VALUES ('$name', ${if (isMain) 1 else 0}, 0, 0)"
    )
    return column("SELECT id FROM category WHERE name = '$name'").single().toLong()
}

internal fun MusicDatabase.insertTag(name: String, categoryId: Long) =
    exec("INSERT INTO tag (name, category_id, is_builtin, created_at) VALUES ('$name', $categoryId, 0, 0)")

/**
 * 挂靠文件到实体。
 *
 * **刻意不动 `analysis_status`**：挂靠（entity_id 有值）与分析完成（status = LINKED）
 * 是两件事。夹具里把它们混为一谈，会让「可播性由 LINKED 派生」这类断言失去判别力 ——
 * 曾经因此把一首未分析的歌判成可播。需要 LINKED 时用 `insertFile(status = "LINKED")`。
 */
internal fun MusicDatabase.linkFile(fileId: Long, entityId: Long, isRepresentative: Boolean = false) =
    exec(
        "UPDATE music_file SET entity_id = $entityId, " +
            "is_representative = ${if (isRepresentative) 1 else "NULL"} WHERE id = $fileId"
    )

internal fun MusicDatabase.representativeIdOf(entityId: Long): Long? =
    scalar("SELECT id FROM music_file WHERE entity_id = $entityId AND is_representative = 1")?.toLong()
