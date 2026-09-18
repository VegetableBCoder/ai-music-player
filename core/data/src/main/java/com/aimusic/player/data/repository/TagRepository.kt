package com.aimusic.player.data.repository

import com.aimusic.player.data.error.AddTagResult
import com.aimusic.player.data.error.DeleteCategoryResult
import com.aimusic.player.data.error.DeleteTagResult
import com.aimusic.player.data.model.CategoryListItem
import com.aimusic.player.data.model.TagListItem
import com.aimusic.player.data.model.TagProjection
import kotlinx.coroutines.flow.Flow

/**
 * 标签读写的业务接口（`06 §3.4`，即 `02 §5.4` 的 `TagRepository`）。
 *
 * 写操作**全部**走 DAO 的事务方法，仓储只做参数校验与结果封装（`03 §5`）。
 * 注意这里的签名没有 `now` —— 时间由实现注入，调用方不该关心。
 */
interface TagRepository {

    fun observeCategories(): Flow<List<CategoryListItem>>

    fun observeTags(categoryId: Long): Flow<List<TagListItem>>

    /** 批量取标签并**按实体分组**，供列表页一次装配（避免 N+1）。 */
    fun observeTagsOfSongs(entityIds: List<Long>, onlyMain: Boolean): Flow<Map<Long, List<TagProjection>>>

    /** 仅「分类 & 标签」页使用。 */
    fun searchTags(query: String): Flow<List<TagListItem>>

    suspend fun addTag(categoryId: Long, name: String): AddTagResult

    suspend fun deleteTag(name: String): DeleteTagResult

    suspend fun addCategory(name: String): Long

    suspend fun deleteCategory(id: Long): DeleteCategoryResult

    suspend fun setMainCategory(id: Long, isMain: Boolean)

    suspend fun attachTags(entityIds: List<Long>, tagNames: List<String>)

    suspend fun detachTags(entityIds: List<Long>, tagNames: List<String>)
}
