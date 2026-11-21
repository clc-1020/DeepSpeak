package com.companion.chat.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v6 → v7 显式迁移的 instrumented 测试（V2.2.1 验收，需真机/模拟器）。
 *
 * 断言一律用 org.junit.Assert.*：androidTest（ART）默认关闭断言，裸 assert 会静默跳过 → 假绿。
 * INSERT 只用 v6 真实存在的列，见 §3.1.1 第 6 条。
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList() // 无 AutoMigrationSpec（本项目全用显式 Migration）
    )

    /** v6 建库 → 插入旧数据 → 迁移到 7 → 旧数据仍在、两个新列默认值正确。 */
    @Test
    fun migrate6To7_preservesDataAndAddsDefaults() {
        val dbName = "migration-test-basic"
        val v6 = helper.createDatabase(dbName, 6)
        // 注意：priority 在 v6 是 NOT NULL 且没有 SQL DEFAULT（"= 5" 只是 Kotlin 侧默认值），
        // 原生 execSQL 必须显式给值；否则 NOT NULL constraint failed。
        v6.execSQL(
            "INSERT INTO reminders (id, type, taskDesc, triggerTime, state, priority) " +
                "VALUES (1, 'instruction', '吃早餐', 20000, 'pending', 5)"
        )
        v6.close()

        val v7 = helper.runMigrationsAndValidate(
            dbName, 7, true, AppDatabase.MIGRATION_6_7
        )

        v7.query("SELECT * FROM reminders WHERE id = 1").use { c ->
            assertTrue("旧行必须还在", c.moveToFirst())
            assertEquals(1L, c.getLong(c.getColumnIndexOrThrow("id")))
            assertEquals("instruction", c.getString(c.getColumnIndexOrThrow("type")))
            assertEquals("吃早餐", c.getString(c.getColumnIndexOrThrow("taskDesc")))
            assertEquals(20000L, c.getLong(c.getColumnIndexOrThrow("triggerTime")))
            assertEquals("pending", c.getString(c.getColumnIndexOrThrow("state")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("attemptCount")))
            assertEquals(5, c.getInt(c.getColumnIndexOrThrow("maxAttempts")))
        }
        v7.close()
    }

    /** v6 的全部 8 列都要原样保留（尤其 nullable 列与 priority）。 */
    @Test
    fun migrate6To7_preservesAllLegacyColumns() {
        val dbName = "migration-test-full"
        val v6 = helper.createDatabase(dbName, 6)
        v6.execSQL(
            "INSERT INTO reminders " +
                "(id, type, taskDesc, triggerTime, state, followupInterval, stopCondition, priority) " +
                "VALUES (2, 'followup', '吃药了吗', 30000, 'pending', 15, '用户说吃完了', 4)"
        )
        v6.close()

        val v7 = helper.runMigrationsAndValidate(
            dbName, 7, true, AppDatabase.MIGRATION_6_7
        )

        v7.query("SELECT * FROM reminders WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("followup", c.getString(c.getColumnIndexOrThrow("type")))
            assertEquals("吃药了吗", c.getString(c.getColumnIndexOrThrow("taskDesc")))
            assertEquals(15, c.getInt(c.getColumnIndexOrThrow("followupInterval")))
            assertEquals("用户说吃完了", c.getString(c.getColumnIndexOrThrow("stopCondition")))
            assertEquals(4, c.getInt(c.getColumnIndexOrThrow("priority")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("attemptCount")))
            assertEquals(5, c.getInt(c.getColumnIndexOrThrow("maxAttempts")))
        }
        v7.close()
    }
}
