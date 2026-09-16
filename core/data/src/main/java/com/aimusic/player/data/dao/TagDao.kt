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
import com.aimusic.player.data.error.TagRejectReason
import com.aimusic.player.data.model.EntityTagRow
import com.aimusic.player.data.model.TagListItem
import kotlinx.coroutines.flow.Flow
import com.aimusic.player.llm.TagRef

@Dao
abstract class TagDao {

    // —— 查询（03 §3.4） ——

    @Query(
        """SELECT t.name AS name, t.category_id AS categoryId, t.is_builtin AS isBuiltin,
                  COUNT(et.entity_id) AS songCount
           FROM tag t LEFT JOIN entity_tag et ON et.tag_id = t.name
           WHERE t.category_id = :categoryId
           GROUP BY t.name ORDER BY t.name""",
    )
    abstract fun observeTags(categoryId: Long): Flow<List<TagListItem>>

    /** 批量取若干实体的标签；`onlyMain = true` 时只取主要分类下的（I6）。 */
    @Query(
        """SELECT et.entity_id AS entityId, t.name AS name, t.category_id AS categoryId,
                  c.name AS categoryName
           FROM entity_tag et
           JOIN tag t ON t.name = et.tag_id
           JOIN category c ON c.id = t.category_id
           WHERE et.entity_id IN (:entityIds) AND (:onlyMain = 0 OR c.is_main = 1)""",
    )
    abstract suspend fun tagsOfSongs(entityIds: List<Long>, onlyMain: Boolean): List<EntityTagRow>

    /** Flow 版批量取标签（`06 §3.3` 的 DAO 侧）；上面那个 suspend 版供写事务内使用。 */
    @Query(
        """SELECT et.entity_id AS entityId, t.name AS name, t.category_id AS categoryId,
                  c.name AS categoryName
           FROM entity_tag et
           JOIN tag t ON t.name = et.tag_id
           JOIN category c ON c.id = t.category_id
           WHERE et.entity_id IN (:entityIds) AND (:onlyMain = 0 OR c.is_main = 1)""",
    )
    abstract fun observeTagsOfSongs(entityIds: List<Long>, onlyMain: Boolean): Flow<List<EntityTagRow>>

    /** 标签搜索（仅「分类 & 标签」页使用）；直接返回投影，省得仓储再查一轮补字段。 */
    @Query(
        """SELECT t.name AS name, t.category_id AS categoryId, t.is_builtin AS isBuiltin,
                  (SELECT COUNT(*) FROM entity_tag et WHERE et.tag_id = t.name) AS songCount
           FROM tag t WHERE t.name LIKE '%' || :q || '%' ORDER BY t.name""",
    )
    abstract fun searchTags(q: String): Flow<List<TagListItem>>

    @Query("SELECT COUNT(*) FROM entity_tag WHERE tag_id = :name")
    abstract suspend fun songCountOfTag(name: String): Int

    @Query("SELECT category_id FROM tag WHERE name = :name")
    abstract suspend fun categoryIdOfTag(name: String): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM tag WHERE name = :name)")
    abstract suspend fun tagExists(name: String): Boolean

    @Query("SELECT name FROM category WHERE id = :id")
    abstract suspend fun categoryNameById(id: Long): String?

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
        if (name.isBlank()) return AddTagResult.Rejected(TagRejectReason.EMPTY_NAME)
        if (categoryNameById(categoryId) == null) {
            return AddTagResult.Rejected(TagRejectReason.MISSING_CATEGORY)
        }

        // 11 §7：不额外查询判定冲突，直接用插入返回码区分（-1 = 被 IGNORE 掉）
        val rowId = insertTagIgnoring(TagEntity(name = name, categoryId = categoryId, createdAt = now))
        if (rowId != -1L) return AddTagResult.Added(name)

        // 只在「已存在」这条分支上读回原分类，供 UI 引导复用
        val existingCategoryId = categoryIdOfTag(name) ?: return AddTagResult.Added(name)
        val existingCategoryName = categoryNameById(existingCategoryId)
            ?: return AddTagResult.Added(name)
        return AddTagResult.ReuseExisting(name, existingCategoryId, existingCategoryName)
    }

    @Transaction
    open suspend fun deleteTag(name: String): DeleteTagResult {
        if (!tagExists(name)) return DeleteTagResult.NotFound

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

    /** 当前有效标签全量。分类**名**投影为 `TagRef.category`（两字段契约，决定 #3）。 */
    @Query(
        """SELECT t.name AS name, c.name AS category
           FROM tag t JOIN category c ON c.id = t.category_id ORDER BY t.name""",
    )
    abstract suspend fun currentTagRefs(): List<TagRef>
}
