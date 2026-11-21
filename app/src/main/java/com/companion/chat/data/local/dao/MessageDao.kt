package com.companion.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.companion.chat.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/** 消息 DAO。任务 1.1 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>): List<Long>

    @Delete
    suspend fun delete(message: MessageEntity)

    /** 某会话全部消息，按时间正序（给 UI 列表用） */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeByConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    suspend fun getByConversation(conversationId: Long): List<MessageEntity>

    /** 最近 n 条（倒序取再正序），用于拼上下文 */
    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC"
    )
    suspend fun getRecent(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countByConversation(conversationId: Long): Int

    /**
     * P3（2026-11-21）：关系阶段判定用 —— 全库消息总数。
     * **只加查询、不加列**，不涉及 DB 迁移。
     */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countAll(): Int

    /** P3：最早一条消息的时间戳（空库返回 null）。 */
    @Query("SELECT MIN(timestamp) FROM messages")
    suspend fun firstTimestamp(): Long?

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
