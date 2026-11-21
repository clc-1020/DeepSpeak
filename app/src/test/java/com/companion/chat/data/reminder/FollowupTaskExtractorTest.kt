package com.companion.chat.data.reminder

import com.companion.chat.data.alarm.AlarmCodes
import com.companion.chat.data.alarm.AlarmKind
import com.companion.chat.data.alarm.AlarmScheduling
import com.companion.chat.data.alarm.FollowupAlarms
import com.companion.chat.data.local.dao.FakeReminderDao
import com.companion.chat.data.local.dao.ReminderDao
import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.local.entity.ReminderType
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.data.remote.ChatApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** V2.2.3：FollowupTaskExtractor 的 DAO/闹钟/调用次数单测（FakeDao + 录制式 Scheduler + 计数 lambda）。 */
class FollowupTaskExtractorTest {

    private class RecordingScheduler : AlarmScheduling {
        data class Event(
            val requestCode: Int,
            val triggerAtMillis: Long,
            val taskDesc: String,
            val reminderId: Long?,
            val kind: AlarmKind
        )

        val scheduled = mutableListOf<Event>()
        val cancelled = mutableListOf<Int>()

        override fun schedule(
            requestCode: Int,
            triggerAtMillis: Long,
            taskDesc: String,
            reminderId: Long?,
            kind: AlarmKind
        ) {
            scheduled += Event(requestCode, triggerAtMillis, taskDesc, reminderId, kind)
        }

        override fun cancel(requestCode: Int) {
            cancelled += requestCode
        }
    }

    /**
     * 在「查」与「建」之间插入真实挂起点的 DAO 包装。
     *
     * 生产里 Room 的 suspend 查询/插入会切到 DB 线程池，查与建之间确实存在挂起缝隙；
     * 不加挂起点的话，runBlocking 下单线程把一条协程整段跑完，mutex 根本没被争用，
     * 并发用例就退化成「顺序调两次」——那属于假绿。这里刻意用 delay 复现缝隙。
     */
    private class SlowDao(private val delegate: FakeReminderDao) : ReminderDao by delegate {
        override suspend fun insert(reminder: ReminderEntity): Long {
            delay(10)
            return delegate.insert(reminder)
        }

        override suspend fun pendingFollowupByTask(task: String): ReminderEntity? {
            delay(10)
            return delegate.pendingFollowupByTask(task)
        }
    }

    private val zone = ZoneId.systemDefault()
    private val refDate = LocalDate.of(2026, 11, 20)
    /** 固定「现在」= 参照日 10:00（本地时区）。 */
    private val now = refDate.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
    private val cfg = ApiConfig(providerId = "deepseek", baseUrl = "", apiKey = "k", model = "m")

    private val fullJson =
        """{"is_followup":true,"task":"背30个单词","interval_minutes":15,"stop_condition":"用户说背完了"}"""

    private fun recent(userText: String = "监督我每天背单词") = listOf(
        MessageEntity(id = 1, conversationId = 1, role = MessageRole.USER, content = userText, timestamp = now - 1_000),
        MessageEntity(id = 2, conversationId = 1, role = MessageRole.ASSISTANT, content = "好的，我盯着你", timestamp = now)
    )

    /** 返回 extractor 与「读取 callJson 调用次数」的句柄。 */
    private fun fixture(
        dao: FakeReminderDao = FakeReminderDao(),
        scheduler: RecordingScheduler = RecordingScheduler(),
        raw: String = fullJson,
        failure: Throwable? = null,
        extractorDao: ReminderDao = dao
    ): Triple<FollowupTaskExtractor, () -> Int, FakeReminderDao> {
        var calls = 0
        val extractor = FollowupTaskExtractor(
            apiClient = ChatApiClient(), // 绝不真连网：callJson 注入点覆盖
            dao = extractorDao,
            alarms = FollowupAlarms(scheduler),
            now = { now },
            referenceDate = { refDate },
            zone = zone,
            callJson = { _, _, _ ->
                calls++
                if (failure != null) Result.failure(failure) else Result.success(raw)
            }
        )
        return Triple(extractor, { calls }, dao)
    }

    @Test
    fun created_row_has_all_expected_fields() = runBlocking {
        val scheduler = RecordingScheduler()
        val (extractor, calls, dao) = fixture(scheduler = scheduler)

        val result = extractor.extractAndSchedule(cfg, recent())

        assertTrue(result.isSuccess)
        assertEquals(1, calls())
        assertEquals(1, dao.items.size)
        val row = dao.items[0]
        assertEquals(ReminderType.FOLLOWUP, row.type)
        assertEquals("背30个单词", row.taskDesc)
        assertEquals(4, row.priority)
        assertEquals(15, row.followupInterval)
        assertEquals("用户说背完了", row.stopCondition)
        assertEquals(5, row.maxAttempts)
        assertEquals(0, row.attemptCount)
        assertEquals(ReminderState.PENDING, row.state)
    }

    @Test
    fun first_alarm_is_followup_code_and_kind() = runBlocking {
        val scheduler = RecordingScheduler()
        val (extractor, _, _) = fixture(scheduler = scheduler)

        val id = extractor.extractAndSchedule(cfg, recent()).getOrNull()!!

        assertEquals(1, scheduler.scheduled.size)
        val e = scheduler.scheduled[0]
        assertEquals(AlarmKind.FOLLOWUP, e.kind)
        assertEquals(AlarmCodes.followup(id), e.requestCode)
        assertNotEquals(AlarmCodes.first(id), e.requestCode) // 坑 A 自检：绝不能是首钟
    }

    @Test
    fun missing_or_unparseable_start_falls_back_to_now_plus_interval() = runBlocking {
        val (a, _, daoA) = fixture()
        a.extractAndSchedule(cfg, recent())
        assertEquals(now + 15 * 60_000L, daoA.items[0].triggerTime)

        val (b, _, daoB) = fixture(
            raw = """{"is_followup":true,"task":"x","interval_minutes":30,"start":"不知道啥时候"}"""
        )
        b.extractAndSchedule(cfg, recent())
        assertEquals(now + 30 * 60_000L, daoB.items[0].triggerTime)
    }

    @Test
    fun start_hint_tomorrow_morning_eight() = runBlocking {
        val raw = """{"is_followup":true,"task":"x","interval_minutes":15,"start":"明天早上8点"}"""
        val (extractor, _, dao) = fixture(raw = raw)

        extractor.extractAndSchedule(cfg, recent())

        val expected = refDate.plusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, dao.items[0].triggerTime)
    }

    @Test
    fun past_start_hint_falls_back() = runBlocking {
        // 参照日 10:00，start 指向今天 08:00（已过去）→ 退回 now + interval
        val raw = """{"is_followup":true,"task":"x","interval_minutes":15,"start":"今天早上8点"}"""
        val (extractor, _, dao) = fixture(raw = raw)

        extractor.extractAndSchedule(cfg, recent())

        assertEquals(now + 15 * 60_000L, dao.items[0].triggerTime)
    }

    // ---- 2026-11-20 真机事故：start=「1分钟后」被静默忽略，落到 now+interval ----

    @Test
    fun start_hint_one_minute_later_uses_relative_offset_not_interval() = runBlocking {
        // 用户说「1分钟后开始，每5分钟问一次」→ 首钟必须是 +1 分钟，不是 +5 分钟
        val raw = """{"is_followup":true,"task":"背30个单词","interval_minutes":5,"start":"1分钟后"}"""
        val (extractor, _, dao) = fixture(raw = raw)

        extractor.extractAndSchedule(cfg, recent())

        assertEquals("相对时长优先于 interval 兜底", now + 60_000L, dao.items[0].triggerTime)
        assertEquals("间隔仍按模型给的值存", 5, dao.items[0].followupInterval)
    }

    @Test
    fun start_hint_half_hour_later() = runBlocking {
        val raw = """{"is_followup":true,"task":"x","interval_minutes":15,"start":"半小时后"}"""
        val (extractor, _, dao) = fixture(raw = raw)

        extractor.extractAndSchedule(cfg, recent())

        assertEquals(now + 30 * 60_000L, dao.items[0].triggerTime)
    }

    @Test
    fun start_hint_chinese_hours_later() = runBlocking {
        val raw = """{"is_followup":true,"task":"x","interval_minutes":15,"start":"3个小时后"}"""
        val (extractor, _, dao) = fixture(raw = raw)

        extractor.extractAndSchedule(cfg, recent())

        assertEquals(now + 3 * 3_600_000L, dao.items[0].triggerTime)
    }

    @Test
    fun absolute_start_hint_is_not_eaten_by_relative_parsing() = runBlocking {
        // 「7点30分」里有「分」但没有「后」→ 必须仍走绝对时刻分支（相对解析不能误吞）
        val raw = """{"is_followup":true,"task":"x","interval_minutes":15,"start":"今晚7点30分"}"""
        val (extractor, _, dao) = fixture(raw = raw)

        extractor.extractAndSchedule(cfg, recent())

        val expected = refDate.atTime(19, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, dao.items[0].triggerTime)
    }

    @Test
    fun same_task_pending_chain_blocks_duplicate_even_with_zero_attempts() = runBlocking {
        // 专防误用 activeFollowup：attemptCount=0 的刚建链也必须算「已有」。
        // 2026-11-20 起去重键是**任务文本**，所以这条链必须与本次提取出的 task 同名才会被拦。
        val dao = FakeReminderDao()
        dao.insert(
            ReminderEntity(
                id = 99L, type = ReminderType.FOLLOWUP, taskDesc = "背30个单词",
                triggerTime = now + 60_000L, state = ReminderState.PENDING,
                followupInterval = 15, attemptCount = 0
            )
        )
        val scheduler = RecordingScheduler()
        val (extractor, calls, _) = fixture(dao = dao, scheduler = scheduler)

        val result = extractor.extractAndSchedule(cfg, recent())

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        // 预检已移除（要先知道任务名才能判重），所以这次模型会被调用一次；
        // 正确性由锁内的 pendingFollowupByTask 兜住。
        assertEquals(1, calls())
        assertEquals(1, dao.items.size)
        assertEquals(99L, dao.items[0].id)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun different_task_creates_a_second_chain() = runBlocking {
        // 2026-11-20 真机事故回归：已有「背单词」链时再说「监督我喝水」，
        // 旧实现用 anyPendingFollowup 全局拦截 → 新请求被静默丢弃（用户等到天亮没消息）。
        val dao = FakeReminderDao()
        dao.insert(
            ReminderEntity(
                id = 99L, type = ReminderType.FOLLOWUP, taskDesc = "背30个单词",
                triggerTime = now + 60_000L, state = ReminderState.PENDING,
                followupInterval = 15, attemptCount = 0
            )
        )
        val scheduler = RecordingScheduler()
        val raw = """{"is_followup":true,"task":"喝水","interval_minutes":1,"start":"1分钟后"}"""
        val (extractor, _, _) = fixture(dao = dao, scheduler = scheduler, raw = raw)

        val result = extractor.extractAndSchedule(cfg, recent("从现在起监督我喝水，一分钟后开始"))

        assertEquals("必须真的建出第二条链", "喝水", dao.items.first { it.id != 99L }.taskDesc)
        assertEquals(2, dao.items.size)
        assertTrue(scheduler.scheduled.isNotEmpty())
        assertEquals("新链的 id 要返回给调用方", dao.items.first { it.id != 99L }.id, result.getOrNull())
    }

    @Test
    fun two_sequential_calls_create_only_one() = runBlocking {
        val scheduler = RecordingScheduler()
        val (extractor, calls, dao) = fixture(scheduler = scheduler)

        extractor.extractAndSchedule(cfg, recent())
        val second = extractor.extractAndSchedule(cfg, recent())

        assertEquals(1, dao.items.size)
        assertNull(second.getOrNull())
        // 预检移除后两次都会调模型；第二次在锁内被「同任务已有链」拦下
        assertEquals(2, calls())
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test
    fun concurrent_calls_create_only_one() = runBlocking {
        // 真并发：两条协程都在 Dispatchers.Default 上跑，DAO 的查/建各挂起 10ms 模拟
        // Room 的线程池切换；callJson 里的 barrier 保证两边都越过预检、同时进入模型调用，
        // 然后同时去争同一把 mutex。若把 mutex 去掉，这条会建出 2 条 → 失败。
        val fake = FakeReminderDao()
        val scheduler = RecordingScheduler()
        val slow = SlowDao(fake)
        val barrier = CompletableDeferred<Unit>()
        val entered = AtomicInteger(0)
        val extractor = FollowupTaskExtractor(
            apiClient = ChatApiClient(),
            dao = slow,
            alarms = FollowupAlarms(scheduler),
            now = { now },
            referenceDate = { refDate },
            zone = zone,
            callJson = { _, _, _ ->
                if (entered.incrementAndGet() == 2) barrier.complete(Unit)
                barrier.await() // 两边都到齐才继续，确保双方都已越过预检
                Result.success(fullJson)
            }
        )

        val results = withTimeout(5_000) {
            coroutineScope {
                val a = async(Dispatchers.Default) { extractor.extractAndSchedule(cfg, recent()) }
                val b = async(Dispatchers.Default) { extractor.extractAndSchedule(cfg, recent()) }
                a.await() to b.await()
            }
        }

        assertEquals(2, entered.get())          // 两条都过了预检、都调了模型
        assertEquals(1, fake.items.size)        // 但只有一条落库
        assertEquals(1, scheduler.scheduled.size)
        assertEquals(1, listOf(results.first, results.second).count { it.getOrNull() != null })
    }

    @Test
    fun no_intent_skips_model_call_entirely() = runBlocking {
        val scheduler = RecordingScheduler()
        val (extractor, calls, dao) = fixture(scheduler = scheduler)

        val result = extractor.extractAndSchedule(cfg, recent("今天好累"))

        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
        assertEquals(0, calls())
        assertTrue(dao.items.isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun call_failure_creates_nothing_and_schedules_nothing() = runBlocking {
        val scheduler = RecordingScheduler()
        val boom = IllegalStateException("网络挂了")
        val (extractor, calls, dao) = fixture(scheduler = scheduler, failure = boom)

        val result = extractor.extractAndSchedule(cfg, recent())

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
        assertEquals(1, calls())
        assertTrue(dao.items.isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
        assertTrue(scheduler.cancelled.isEmpty())
    }
}
