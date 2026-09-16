package com.aimusic.player.data.analysis

import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.entity.AnalysisRunEntity
import com.aimusic.player.data.model.RunFileRow
import kotlinx.coroutines.flow.Flow

/**
 * 「最近分析记录」页的读模型（`09 §3.2.10`）。
 *
 * 三个动作刻意都很窄：订阅最近批次、订阅某批次的文件清单、移除记录（**只解绑批次，不删文件**）。
 * 删除文件是另一条序列（`03 §4.4`），这里不碰 —— 否则「移除记录」会顺手删掉用户的音乐。
 */
class AnalysisRunRepository(private val db: MusicDatabase) {

    /** 没有任何批次时发 `null`（界面据此显示空态，而不是等一个永远不来的值）。 */
    fun observeLatest(): Flow<AnalysisRunEntity?> = db.analysisRunDao().observeLatest()

    fun observeRunFiles(runId: Long): Flow<List<RunFileRow>> = db.analysisRunDao().observeRunFiles(runId)

    /** 「移除记录」：只解绑批次清单，**不删 music_file**。 */
    suspend fun removeRecords(runId: Long, fileIds: List<Long>) {
        db.analysisRunDao().unlinkFiles(runId, fileIds)
    }
}
