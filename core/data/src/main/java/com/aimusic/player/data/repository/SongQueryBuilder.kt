package com.aimusic.player.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.aimusic.player.data.model.SongFilter
import com.aimusic.player.data.model.SongScope
import com.aimusic.player.data.model.SongSort

/**
 * 把「维度 + 排序 + 筛选」翻译成一条 SQL（`03 §5` 的排序/筛选映射表）。
 *
 * 为什么拼 SQL 而不是写几个固定 `@Query`：维度 5 种 × 排序 3 种 × 筛选 2 项 = 30 种组合，
 * 逐个写会立刻失控。拼出来只有一份排序规则、一份筛选规则，`03 §9` 要测的也正是这唯一的一份。
 *
 * 排序一律在 SQL 里做（`03 §5` 明确「不用内存排序」）：内存排序无法对全表生效，
 * 一旦将来加分页就会算错。
 */
internal object SongQueryBuilder {

    private const val FROM = "SELECT s.* FROM song_entity s"

    fun build(scope: SongScope, sort: SongSort, filter: SongFilter): SupportSQLiteQuery {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        when (scope) {
            SongScope.Songs -> Unit

            is SongScope.Artist -> {
                conditions += "EXISTS (SELECT 1 FROM song_artist a " +
                    "WHERE a.entity_id = s.id AND a.artist_name = ?)"
                args += scope.name
            }

            is SongScope.Album -> {
                conditions += "s.album_name = ?"
                args += scope.albumName
                if (scope.albumArtist != null) {
                    conditions += "s.album_artist = ?"
                    args += scope.albumArtist
                }
            }

            is SongScope.Tag -> {
                conditions += "EXISTS (SELECT 1 FROM entity_tag et " +
                    "WHERE et.entity_id = s.id AND et.tag_id = ?)"
                args += scope.name
            }

            is SongScope.SearchResult -> {
                conditions += "(s.canonical_title LIKE '%' || ? || '%' OR " +
                    "EXISTS (SELECT 1 FROM song_artist a WHERE a.entity_id = s.id " +
                    "AND a.artist_name LIKE '%' || ? || '%'))"
                args += scope.query
                args += scope.query
            }
        }

        filter.titleQuery?.takeIf { it.isNotBlank() }?.let {
            conditions += "s.canonical_title LIKE '%' || ? || '%'"
            args += it
        }

        if (filter.hasLyricOnly) {
            conditions += "EXISTS (SELECT 1 FROM lyric l WHERE l.entity_id = s.id)"
        }

        val sql = buildString {
            append(FROM)
            if (conditions.isNotEmpty()) append(" WHERE ").append(conditions.joinToString(" AND "))
            append(" ORDER BY ").append(orderBy(sort))
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /** 搜索：歌名与歌手名合并命中，`DISTINCT` 防止 join 出重复行。 */
    fun search(query: String): SupportSQLiteQuery {
        val sql = "SELECT DISTINCT s.* FROM song_entity s " +
            "LEFT JOIN song_artist a ON a.entity_id = s.id " +
            "WHERE s.canonical_title LIKE '%' || ? || '%' " +
            "OR a.artist_name LIKE '%' || ? || '%' " +
            "ORDER BY s.canonical_title COLLATE NOCASE, s.id"
        return SimpleSQLiteQuery(sql, arrayOf<Any?>(query, query))
    }

    /**
     * 排序规则（`03 §5`）。两个细节都是被真实行为逼出来的：
     * - `COLLATE NOCASE`：不写它时 `Apple` 会排到 `banana` 之后（SQLite 默认按码位比大写字母小）。
     * - `last_played_at IS NULL` 要放在 `DESC` **之前**：SQLite 没有 `NULLS LAST` 语法，
     *   而 `DESC` 默认把 NULL 排在最前 —— 那会让「最近播放」里全是没播过的歌。
     */
    private fun orderBy(sort: SongSort): String = when (sort) {
        SongSort.NAME -> "s.canonical_title COLLATE NOCASE, s.id"

        SongSort.RECENT_PLAYED ->
            "s.last_played_at IS NULL, s.last_played_at DESC, s.id"

        SongSort.COMPLETE_COUNT ->
            "s.complete_count DESC, s.canonical_title COLLATE NOCASE, s.id"
    }
}
