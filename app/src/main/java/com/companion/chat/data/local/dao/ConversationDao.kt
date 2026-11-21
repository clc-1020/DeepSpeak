package com.companion.chat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.companion.chat.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/** 会话列表项：标题 + 最后一条消息摘要 + 时间。任务 1.7 */
data class ConversationSummary(
    val id: Long,
    val title: String,
    val lastMessageTime: Long,
    val lastMessagePreview: String
)

/** 会话 DAO。任务 1.1 */
@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC")
    suspend fun getAll(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    @Query("UPDATE conversations SET lastMessageTime = :time WHERE id = :id")
    suspend fun updateLastMessageTime(id: Long, time: Long)

    /** 写入会话标题（首条请求 title 生成后落库，或失败时用 user 前缀兜底）。 */
    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    /**
     * 会话列表（含最后一条消息摘要），按最近活跃排序。任务 1.7
     * lastMessagePreview 用相关子查询取该会话最后一条消息的 content（无消息则为空串）。
     */
    @Query(
        "SELECT c.id AS id, c.title AS title, c.lastMessageTime AS lastMessageTime, " +
            "COALESCE((" +
            "  SELECT m.content FROM messages m WHERE m.conversationId = c.id " +
            "  ORDER BY m.timestamp DESC, m.id DESC LIMIT 1" +
            "), '') AS lastMessagePreview " +
            "FROM conversations c ORDER BY c.lastMessageTime DESC"
    )
    fun observeSummaries(): Flow<List<ConversationSummary>>

    /** 按 id 删除会话（消息通过外键级联删除）。任务 1.7 */
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}
