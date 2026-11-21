package com.companion.chat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 长期记忆：关于用户的稳定事实/偏好（如「对花生过敏」）。
 * - category：分类（如 fact / preference / allergy / relationship），便于记忆管理界面分组。
 * - embedding：任务 2.x 语义去重用的向量，先预留。
 * 任务 1.1；1.9.4.0 情绪记忆拟人化新增 8 列（全部有默认值，旧代码构造点零改动）。
 */
@Entity(tableName = "memory_long")
data class MemoryLongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val category: String,
    val createTime: Long,
    /** 语义向量，任务 2.x 使用 */
    val embedding: ByteArray? = null,

    // ── 1.9.4.0 情绪记忆拟人化（只对 category=relationship 且 intensity>0 的条目有意义）──

    /** 写入时的情绪强度（0~1）。0 = 非情绪记忆（普通事实/偏好），旧数据自动归此类。 */
    @ColumnInfo(defaultValue = "0")
    val intensity: Double = 0.0,

    /** 回忆加分 / 厌恶扣分，范围 -0.2..0.2。影响打分，不重置衰减时钟。 */
    @ColumnInfo(defaultValue = "0")
    val boost: Double = 0.0,

    /** 闪光灯记忆（每种情绪最多 1 条）：12h + 每日一次冷却，不参与打分淘汰。 */
    @ColumnInfo(defaultValue = "0")
    val pivotal: Boolean = false,

    /** 同类事件合并次数（正文里会带「共N次」）。 */
    @ColumnInfo(defaultValue = "1")
    val occurrenceCount: Int = 1,

    /** 上次注入主对话的时刻（epoch millis，0 = 从未）。冷却用。 */
    @ColumnInfo(defaultValue = "0")
    val lastInjectedAt: Long = 0L,

    /** 上次注入的本地日期（yyyy-MM-dd，空 = 从未）。pivotal 的"每日一次"用。 */
    @ColumnInfo(defaultValue = "''")
    val lastInjectedDay: String = "",

    /** 厌恶冷却到期时刻（epoch millis，0 = 无冷却）。Long.MAX_VALUE 等效永久拉黑。 */
    @ColumnInfo(defaultValue = "0")
    val coolingUntil: Long = 0L,

    /** 被用户明确表示厌恶的次数（0/1/2+）。第 2 次起永久拉黑。 */
    @ColumnInfo(defaultValue = "0")
    val dislikeCount: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryLongEntity) return false
        return id == other.id &&
            content == other.content &&
            category == other.category &&
            createTime == other.createTime &&
            intensity == other.intensity && boost == other.boost &&
            pivotal == other.pivotal && occurrenceCount == other.occurrenceCount &&
            lastInjectedAt == other.lastInjectedAt && lastInjectedDay == other.lastInjectedDay &&
            coolingUntil == other.coolingUntil && dislikeCount == other.dislikeCount &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + createTime.hashCode()
        result = 31 * result + intensity.hashCode()
        result = 31 * result + boost.hashCode()
        result = 31 * result + pivotal.hashCode()
        result = 31 * result + occurrenceCount
        result = 31 * result + lastInjectedAt.hashCode()
        result = 31 * result + lastInjectedDay.hashCode()
        result = 31 * result + coolingUntil.hashCode()
        result = 31 * result + dislikeCount
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
