package com.companion.chat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 短期记忆：最近 30 天的事，带淡忘分。
 * - weight：权重，越高越容易被召回。
 * - fadeScore：淡忘分，随时间增长，越大越难召回（但不删除）。
 * - lastMentioned：上次被主动提起的时间（epoch millis），用于「久未提及加分」。
 * - dismissCount：「别提这个」被点次数（1=冷却，≥2=拉黑）。
 * - coolingUntil：冷却截止时间（epoch millis），null=未冷却；now < coolingUntil 期间不召回。
 * - blacklisted：是否永久拉黑（用户点「别提这个」第 2 次触发）；拉黑记忆永不主动召回，但仍留在库中。
 * - embedding：任务 2.x 语义去重用的向量（384 维 float 序列化），此处先预留列，避免后续改表迁移。
 * 任务 1.1 / 2.8
 */
@Entity(tableName = "memory_short", indices = [Index("fadeScore")])
data class MemoryShortEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    /** 事件/记忆时间（epoch millis，写入前已把「明天」等相对时间换算成绝对值） */
    val timestamp: Long,
    @ColumnInfo(defaultValue = "1.0")
    val weight: Double = 1.0,
    @ColumnInfo(defaultValue = "0.0")
    val fadeScore: Double = 0.0,
    /** 上次被主动提起时间（epoch millis），null = 从未提起 */
    val lastMentioned: Long? = null,
    /** 「别提这个」被点次数：1 次→冷却 30 天；≥2 次→永久拉黑。任务 2.8 */
    @ColumnInfo(defaultValue = "0")
    val dismissCount: Int = 0,
    /** 冷却截止时间（epoch millis），null=未冷却。任务 2.8 */
    val coolingUntil: Long? = null,
    /** 是否永久拉黑（不主动召回，但仍在库）。任务 2.8 */
    @ColumnInfo(defaultValue = "0")
    val blacklisted: Boolean = false,
    /** 语义向量（FloatArray 序列化后的字节），任务 2.x 使用 */
    val embedding: ByteArray? = null
) {
    // data class 含 ByteArray 时需手写 equals/hashCode，避免引用比较
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryShortEntity) return false
        return id == other.id &&
            content == other.content &&
            timestamp == other.timestamp &&
            weight == other.weight &&
            fadeScore == other.fadeScore &&
            lastMentioned == other.lastMentioned &&
            dismissCount == other.dismissCount &&
            coolingUntil == other.coolingUntil &&
            blacklisted == other.blacklisted &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + weight.hashCode()
        result = 31 * result + fadeScore.hashCode()
        result = 31 * result + (lastMentioned?.hashCode() ?: 0)
        result = 31 * result + dismissCount
        result = 31 * result + (coolingUntil?.hashCode() ?: 0)
        result = 31 * result + blacklisted.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
