package com.aimusic.player.data.deletion

import androidx.room.withTransaction
import com.aimusic.player.data.db.MusicDatabase
import com.aimusic.player.data.error.DeleteFileResult
import com.aimusic.player.storage.StorageSource

/**
 * 删除服务（`02 §5.6`、`03 §4.4`）。
 *
 * 三条路径的差别是本服务的全部要点：
 * - `deleteSong`：**不触碰磁盘**（I8）。实体没了，磁盘上的音频与 `lrc` 都留着，
 *   否则用户会在「我只是从库里删掉」之后发现文件也没了。
 * - `unlinkFile`：只删记录，磁盘保留；实体若因此空了，按删除实体处理。
 * - `deleteFilePhysically`：**先删磁盘、成功后才改库**。顺序不能反 —— 反了就会出现
 *   「库已删、磁盘残留」，用户在 App 里再也看不到这个文件，也就失去了清理入口。
 *
 * 跨表事务用 `db.withTransaction`：`03 §4` 说这些是「数据层的唯一写入口」，但文件删除
 * 还需要 `StorageSource`，而 DAO 由 Room 实例化、注入不了协作者。`07 §4.6.2` 自己也是用
 * `db.withTransaction` 组织跨 DAO 事务的。
 */
class DeletionService(
    private val db: MusicDatabase,
    private val storage: StorageSource,
) {

    /** 删除实体：外键级联清掉文件 / 演唱者 / 标签关联 / 歌词 / 历史 / 队列项。 */
    suspend fun deleteSong(entityId: Long) = db.withTransaction {
        db.songDao().deleteSongById(entityId)
    }

    /** 移除文件（磁盘保留）。实体已无文件时，连带删除实体。 */
    suspend fun unlinkFile(fileId: Long) = db.withTransaction {
        val entityId = db.musicFileDao().entityIdOf(fileId) ?: return@withTransaction
        db.musicFileDao().deleteById(fileId)

        if (db.musicFileDao().countForEntity(entityId) == 0) {
            db.songDao().deleteSongById(entityId)
        } else {
            db.songDao().reelectRepresentative(entityId)
        }
    }

    /**
     * 物理删除：先删磁盘，失败则**原样返回、库不动**；成功后才走 `unlinkFile` 的逻辑。
     *
     * 展示字段（专辑 / 封面）的刷新不在这里：它要以新代表文件的内嵌元数据回填，
     * 而 `03 §1` 给 `:core:data` 的存储职责只有「物理删除 + 读封面缓存路径」，
     * 不含读元数据 —— 取值由上层完成后调 `SongDao.refreshDisplayFields`。
     */
    suspend fun deleteFilePhysically(fileId: Long): DeleteFileResult {
        val path = db.musicFileDao().pathOf(fileId) ?: return DeleteFileResult.Deleted

        if (!storage.delete(path)) return DeleteFileResult.DiskDeleteFailed(path)

        unlinkFile(fileId)
        return DeleteFileResult.Deleted
    }
}
