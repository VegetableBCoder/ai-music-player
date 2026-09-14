package com.aimusic.player.data.repository

import com.aimusic.player.common.model.TagRef
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.error.AddTagResult
import com.aimusic.player.data.error.DeleteCategoryResult
import com.aimusic.player.data.error.DeleteTagResult
import com.aimusic.player.data.model.CategoryListItem
import com.aimusic.player.data.model.TagListItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 仓储只做两件事：把 DAO 的扁平行**装配**成上层要的形状，以及**注入时间**。
 * 所有写逻辑仍在 DAO 的 `@Transaction` 方法里（`03 §5`：写操作全部走 DAO 的事务方法）。
 */
class TagRepositoryImpl(
    private val db: MusicDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : TagRepository {

    private val tagDao = db.tagDao()
    private val categoryDao = db.categoryDao()

    override fun observeCategories(): Flow<List<CategoryListItem>> = categoryDao.observeCategories()

    override fun observeTags(categoryId: Long): Flow<List<TagListItem>> =
        tagDao.observeTags(categoryId)

    override fun observeTagsOfSongs(
        entityIds: List<Long>,
        onlyMain: Boolean,
    ): Flow<Map<Long, List<TagRef>>> {
        if (entityIds.isEmpty()) return flowOf(emptyMap())

        return tagDao.observeTagsOfSongs(entityIds, onlyMain).map { rows ->
            rows.groupBy({ it.entityId }) { TagRef(it.name, it.categoryId, it.categoryName) }
        }
    }

    override fun searchTags(query: String): Flow<List<TagListItem>> = tagDao.searchTags(query)

    override suspend fun addTag(categoryId: Long, name: String): AddTagResult =
        tagDao.addTag(categoryId, name, now())

    override suspend fun deleteTag(name: String): DeleteTagResult = tagDao.deleteTag(name)

    override suspend fun addCategory(name: String): Long = categoryDao.addCategory(name)

    override suspend fun deleteCategory(id: Long): DeleteCategoryResult =
        categoryDao.deleteCategory(id)

    override suspend fun setMainCategory(id: Long, isMain: Boolean) =
        categoryDao.setMainCategory(id, isMain)

    override suspend fun attachTags(entityIds: List<Long>, tagNames: List<String>) =
        tagDao.attachTags(entityIds, tagNames, now())

    override suspend fun detachTags(entityIds: List<Long>, tagNames: List<String>) =
        tagDao.detachTags(entityIds, tagNames)
}
