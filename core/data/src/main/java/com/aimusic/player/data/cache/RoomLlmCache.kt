package com.aimusic.player.data.cache

import com.aimusic.player.common.model.NormalizeResult
import com.aimusic.player.common.model.TagAssignment
import com.aimusic.player.data.dao.LlmCacheDao
import com.aimusic.player.data.entity.LlmCacheEntity
import com.aimusic.player.llm.LlmCache
import org.json.JSONArray
import org.json.JSONObject

/**
 * [LlmCache] 的 Room 实现（spec §2 / 05 §4.8）：把 `NormalizeResult` 序列化进 `llm_cache.result_json`。
 *
 * `llm_cache` 是内容寻址的**可复用资产**、不参与业务级联删除（03 §3.8）。
 * 解析失败（老版本/脏数据）视为**未命中**并放行，不抛异常 —— 缓存不是真相来源，坏一行不该让整次分析崩掉。
 */
class RoomLlmCache(
    private val dao: LlmCacheDao,
    private val now: () -> Long = System::currentTimeMillis,
) : LlmCache {

    override suspend fun get(key: String): NormalizeResult? {
        val json = dao.get(key) ?: return null
        return runCatching { decode(json) }.getOrNull()
    }

    override suspend fun put(key: String, result: NormalizeResult) {
        dao.put(LlmCacheEntity(cacheKey = key, resultJson = encode(result), createdAt = now()))
    }

    private fun encode(result: NormalizeResult): String = JSONObject()
        .put("canonicalTitle", result.canonicalTitle)
        .put("artists", JSONArray(result.artists))
        .put(
            "tagAssignments",
            JSONArray().apply {
                result.tagAssignments.forEach { a ->
                    put(JSONObject().put("category", a.category).put("name", a.name))
                }
            },
        )
        .toString()

    private fun decode(json: String): NormalizeResult {
        val obj = JSONObject(json)
        val artistsArr = obj.getJSONArray("artists")
        val artists = (0 until artistsArr.length()).map { artistsArr.getString(it) }
        val tagsArr = obj.getJSONArray("tagAssignments")
        val tags = (0 until tagsArr.length()).map { i ->
            val t = tagsArr.getJSONObject(i)
            TagAssignment(category = t.getString("category"), name = t.getString("name"))
        }
        return NormalizeResult(
            canonicalTitle = obj.getString("canonicalTitle"),
            artists = artists,
            tagAssignments = tags,
        )
    }
}