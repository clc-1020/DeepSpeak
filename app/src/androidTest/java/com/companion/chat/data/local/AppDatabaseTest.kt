package com.companion.chat.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.ConversationEntity
import com.companion.chat.data.local.entity.MemoryDailyEntity
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.data.local.entity.MemoryShortEntity
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库读写测试（任务 1.1 验收：各表可正常读写）。
 * 需要真机/模拟器运行：./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun conversation_and_message_crud() = runBlocking {
        val convId = db.conversationDao().insert(
            ConversationEntity(title = "测试会话", createTime = 1_000L, lastMessageTime = 2_000L)
        )
        db.messageDao().insert(
            MessageEntity(
                conversationId = convId,
                role = MessageRole.USER,
                content = "你好",
                timestamp = 1_500L
            )
        )
        db.messageDao().insert(
            MessageEntity(
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = "你好呀",
                timestamp = 2_000L,
                tokPerSec = 42.0,
                cacheHit = 0.5,
                contextUsage = 0.1
            )
        )

        val msgs = db.messageDao().getByConversation(convId)
        assertEquals(2, msgs.size)
        assertEquals("你好", msgs[0].content)
        assertEquals(42.0, msgs[1].tokPerSec!!, 0.001)
    }

    @Test
    fun memory_short_crud() = runBlocking {
        val id = db.memoryShortDao().insert(
            MemoryShortEntity(content = "明天面试", timestamp = 10_000L)
        )
        db.memoryShortDao().updateWeight(id, 2.5)
        val mem = db.memoryShortDao().getById(id)
        assertNotNull(mem)
        assertEquals(2.5, mem!!.weight, 0.001)
        assertEquals(1, db.memoryShortDao().getAll().size)
    }

    @Test
    fun memory_long_crud() = runBlocking {
        db.memoryLongDao().insert(
            MemoryLongEntity(content = "对花生过敏", category = "allergy", createTime = 5_000L)
        )
        val list = db.memoryLongDao().getByCategory("allergy")
        assertEquals(1, list.size)
        assertEquals("对花生过敏", list[0].content)
    }

    @Test
    fun memory_daily_crud() = runBlocking {
        db.memoryDailyDao().insert(
            MemoryDailyEntity(date = "2026-11-20", events = "[\"去了公司\"]")
        )
        val log = db.memoryDailyDao().getByDate("2026-11-20")
        assertNotNull(log)
        assertEquals("[\"去了公司\"]", log!!.events)
    }

    @Test
    fun reminder_crud() = runBlocking {
        db.reminderDao().insert(
            ReminderEntity(
                type = ReminderType.INSTRUCTION,
                taskDesc = "吃早餐",
                triggerTime = 20_000L,
                priority = 5
            )
        )
        val pending = db.reminderDao().getPending()
        assertEquals(1, pending.size)
        assertEquals("吃早餐", pending[0].taskDesc)
        val due = db.reminderDao().getDue(now = 30_000L)
        assertEquals(1, due.size)
    }

    @Test
    fun app_state_crud() = runBlocking {
        db.appStateDao().put(AppStateEntity(AppStateEntity.KEY_MOOD, "happy"))
        assertEquals("happy", db.appStateDao().get(AppStateEntity.KEY_MOOD))
        db.appStateDao().put(AppStateEntity(AppStateEntity.KEY_MOOD, "low"))
        assertEquals("low", db.appStateDao().get(AppStateEntity.KEY_MOOD))
    }
}
