package com.companion.chat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.dao.ConversationDao
import com.companion.chat.data.local.dao.DailyTaskDao
import com.companion.chat.data.local.dao.MemoryDailyDao
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.dao.MemoryShortDao
import com.companion.chat.data.local.dao.MessageDao
import com.companion.chat.data.local.dao.PendingMessageDao
import com.companion.chat.data.local.dao.ReminderDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.ConversationEntity
import com.companion.chat.data.local.entity.DailyTaskEntity
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
        AppStateEntity::class,
        DailyTaskEntity::class
    ],
    version = 10,
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
    abstract fun dailyTaskDao(): DailyTaskDao

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

        /**
         * v7 → v8（1.9.4.0）：memory_long 加 4 列，情绪记忆拟人化。
         * 全部 NOT NULL + DEFAULT，旧行自动填默认值（intensity=0 即"非情绪记忆"，零行为变化）。
         * Boolean 在 SQLite 里存 INTEGER 0/1，与 entity 的 @ColumnInfo(defaultValue="0") 配对。
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_long ADD COLUMN intensity REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_long ADD COLUMN boost REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_long ADD COLUMN pivotal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_long ADD COLUMN occurrenceCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE memory_long ADD COLUMN lastInjectedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_long ADD COLUMN lastInjectedDay TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE memory_long ADD COLUMN coolingUntil INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_long ADD COLUMN dislikeCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8 → v9（2026-11-23 联网引用）：messages 加 citations 列（可空 TEXT）。
         * 可空 → 旧行自动 NULL（等价"这条消息没有联网引用"），零行为变化。
         * 存 JSON 字符串而不是建表：引用是整条回复的附属品，不查询/不去重/不更新。
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN citations TEXT")
            }
        }

        /**
         * v9 → v10（2026-11-23 每日任务）：新建 daily_tasks 表（默认空）。
         * CREATE 语句必须与 Room 导出的 10.json 完全一致，否则迁移后 schema 校验失败。
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_tasks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`hour` INTEGER NOT NULL, " +
                        "`minute` INTEGER NOT NULL, " +
                        "`mode` TEXT NOT NULL, " +
                        "`voiceEnabled` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`conversationId` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
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
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    // 已验证：6→7 显式迁移 + instrumented 测试全绿，移除破坏式回退（§3.1 / 步骤 7）
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
