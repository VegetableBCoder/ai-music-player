package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aimusic.player.data.entity.EntityTagCrossRef
import com.aimusic.player.data.entity.TagEntity
import com.aimusic.player.data.error.AddTagResult
import com.aimusic.player.data.error.DeleteTagResult
import com.aimusic.player.data.model.EntityTagRow
import com.aimusic.player.data.model.TagListItem
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TagDao {

    // —— 查询（03 §3.4） ——

    @Query(
        """SELECT t.name AS name, t.is_builtin AS isBuiltin, COUNT(et.entity_id) AS songCount
           FROM tag t LEFT JOIN entity_tag et ON et.tag_id = t.name
           WHERE t.category_id = :categoryId
           GROUP BY t.name ORDER BY t.name""",
    )
    abstract fun observeTags(categoryId: Long): Flow<List<TagListItem>>

    /** 批量取若干实体的标签；`onlyMain = true` 时只取主要分类下的。 */
    @Query(
        """SELECT et.entity_id AS entityId, t.name AS name, t.category_id AS categoryId
           FROM entity_tag et
           JOIN tag t ON t.name = et.tag_id
           JOIN category c ON c.id = t.category_id
           WHERE et.entity_id IN (:entityIds) AND (:onlyMain = 0 OR c.is_main = 1)""",
    )
    abstract suspend fun tagsOfSongs(entityIds: List<Long>, onlyMain: Boolean): List<EntityTagRow>

    @Query("SELECT name FROM tag WHERE name LIKE '%' || :q || '%' ORDER BY name")
    abstract fun searchTags(q: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM entity_tag WHERE tag_id = :name")
    abstract suspend fun songCountOfTag(name: String): Int

    @Query("SELECT category_id FROM tag WHERE name = :name")
    abstract suspend fun categoryIdOfTag(name: String): Long?

    @Query("SELECT name FROM tag WHERE name IN (:names)")
    abstract suspend fun existingNames(names: List<String>): List<String>

    // —— 写入 ——

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertTagIgnoring(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEntityTags(rows: List<EntityTagCrossRef>)

    @Query("DELETE FROM tag WHERE name = :name")
    abstract suspend fun deleteTagByName(name: String)

    @Query("DELETE FROM entity_tag WHERE entity_id IN (:entityIds) AND tag_id IN (:tagIds)")
    abstract suspend fun detach(entityIds: List<Long>, tagIds: List<String>)

    // —— 事务（03 §4.5） ——

    @Transaction
    open suspend fun addTag(categoryId: Long, name: String, now: Long): AddTagResult {
        // 11 §7：不额外查询判定冲突，直接用插入返回码区分（-1 = 被 IGNORE 掉）
        val rowId = insertTagIgnoring(TagEntity(name = name, categoryId = categoryId, createdAt = now))
        if (rowId != -1L) return AddTagResult.Added(categoryId)

        // 只在「已存在」这条分支上读回分类，避免 TOCTOU 之外的额外 IO
        val existingCategoryId = categoryIdOfTag(name) ?: return AddTagResult.Added(categoryId)
        return AddTagResult.ReuseExisting(existingCategoryId)
    }

    @Transaction
    open suspend fun deleteTag(name: String): DeleteTagResult {
        val songCount = songCountOfTag(name)
        if (songCount > 0) return DeleteTagResult.Blocked(songCount)

        deleteTagByName(name)
        return DeleteTagResult.Deleted
    }

    /** 挂靠标签：不存在的标签名直接忽略（外键约束下插不进去，也不该报错）。 */
    @Transaction
    open suspend fun attachTags(entityIds: List<Long>, tagNames: List<String>, now: Long) {
        if (entityIds.isEmpty() || tagNames.isEmpty()) return

        val validNames = existingNames(tagNames)
        if (validNames.isEmpty()) return

        insertEntityTags(
            entityIds.flatMap { entityId ->
                validNames.map { name -> EntityTagCrossRef(entityId, name, now) }
            },
        )
    }

    @Transaction
    open suspend fun detachTags(entityIds: List<Long>, tagNames: List<String>) {
        if (entityIds.isEmpty() || tagNames.isEmpty()) return

        detach(entityIds, tagNames)
    }
}
