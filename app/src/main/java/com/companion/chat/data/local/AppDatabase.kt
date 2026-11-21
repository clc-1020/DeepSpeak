package com.companion.chat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.dao.ConversationDao
import com.companion.chat.data.local.dao.MemoryDailyDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.dao.MessageDao
import com.companion.chat.data.local.dao.PendingMessageDao
import com.companion.chat.data.local.dao.ReminderDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.ConversationEntity
import com.companion.chat.data.local.entity.MemoryDailyEntity
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.MemoryShortEntity
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.PendingMessageEntity
import com.companion.chat.data.local.entity.ReminderEntity

/**
 * 应用 Room 数据库。
 * 8 张表：conversations / messages / memory_short / memory_long / memory_daily / reminders / pending_messages / app_state。
 * 任务 1.1 / 3.1
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryShortEntity::class,
        MemoryLongEntity::class,
        MemoryDailyEntity::class,
        ReminderEntity::class,
        PendingMessageEntity::class,
        AppStateEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryShortDao(): MemoryShortDao
    abstract fun memoryLongDao(): MemoryLongDao
    abstract fun memoryDailyDao(): MemoryDailyDao
    abstract fun reminderDao(): ReminderDao
    abstract fun pendingMessageDao(): PendingMessageDao
    abstract fun appStateDao(): AppStateDao

    companion object {
        private const val DB_NAME = "companion.db"

        /**
         * v6 → v7：只给 reminders（复数！）加两列。
         * DEFAULT 必须与 entity 的 @ColumnInfo(defaultValue) 配对，否则 Room 迁移后 schema 校验失败。
         * 见 §3.1.1 第 1/2 条。
         */
        // internal：供 instrumented 迁移测试直接引用（androidTest 同模块可见）
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reminders ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reminders ADD COLUMN maxAttempts INTEGER NOT NULL DEFAULT 5")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_6_7)
                    // 已验证：6→7 显式迁移 + instrumented 测试全绿，移除破坏式回退（§3.1 / 步骤 7）
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
