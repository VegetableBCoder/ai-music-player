package com.aimusic.player.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.aimusic.player.data.entity.CategoryEntity
import com.aimusic.player.data.error.DeleteCategoryResult
import com.aimusic.player.data.model.CategoryListItem
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CategoryDao {

    @Query(
        """SELECT c.id AS id, c.name AS name, c.is_main AS isMain, c.is_builtin AS isBuiltin,
                  c.sort_order AS sortOrder,
                  (SELECT COUNT(*) FROM tag t WHERE t.category_id = c.id) AS tagCount
           FROM category c ORDER BY c.sort_order, c.name""",
    )
    abstract fun observeCategories(): Flow<List<CategoryListItem>>

    @Query("SELECT id FROM category WHERE name = :name")
    abstract suspend fun categoryIdByName(name: String): Long?

    @Query("SELECT COUNT(*) FROM tag WHERE category_id = :id")
    abstract suspend fun tagCountOfCategory(id: Long): Int

    @Insert
    abstract suspend fun insertCategory(category: CategoryEntity): Long

    @Query("DELETE FROM category WHERE id = :id")
    abstract suspend fun deleteCategoryById(id: Long)

    @Query("UPDATE category SET is_main = :isMain WHERE id = :id")
    abstract suspend fun setMain(id: Long, isMain: Boolean)

    // —— 事务（03 §4.5） ——

    /** 用户新建的分类一律 `is_builtin = 0`；重名由 Repository 侧翻译成冲突提示。 */
    @Transaction
    open suspend fun addCategory(name: String): Long =
        insertCategory(CategoryEntity(name = name))

    @Query("SELECT EXISTS(SELECT 1 FROM category WHERE id = :id)")
    abstract suspend fun categoryExists(id: Long): Boolean

    @Transaction
    open suspend fun deleteCategory(id: Long): DeleteCategoryResult {
        if (!categoryExists(id)) return DeleteCategoryResult.NotFound

        val tagCount = tagCountOfCategory(id)
        if (tagCount > 0) return DeleteCategoryResult.Blocked(tagCount)

        deleteCategoryById(id)
        return DeleteCategoryResult.Deleted
    }

    /** 主要分类**不互斥**，可多选（`04`）。 */
    @Transaction
    open suspend fun setMainCategory(id: Long, isMain: Boolean) = setMain(id, isMain)

    /** 当前有效分类名（每批实时读取一次，非快照 —— `04 §3.3`、spec §10）。 */
    @Query("SELECT name FROM category ORDER BY sort_order, name")
    abstract suspend fun currentNames(): List<String>
}
