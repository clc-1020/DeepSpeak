package com.companion.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 长期记忆：关于用户的稳定事实/偏好（如「对花生过敏」）。
 * - category：分类（如 fact / preference / allergy / relationship），便于记忆管理界面分组。
 * - embedding：任务 2.x 语义去重用的向量，先预留。
 * 任务 1.1
 */
@Entity(tableName = "memory_long")
data class MemoryLongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val category: String,
    val createTime: Long,
    /** 语义向量，任务 2.x 使用 */
    val embedding: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryLongEntity) return false
        return id == other.id &&
            content == other.content &&
            category == other.category &&
            createTime == other.createTime &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + createTime.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
