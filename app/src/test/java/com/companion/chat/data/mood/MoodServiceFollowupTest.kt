package com.companion.chat.data.mood

import com.companion.chat.data.alarm.AlarmKind
import com.companion.chat.data.alarm.AlarmScheduling
import com.companion.chat.data.alarm.FollowupAlarms
import com.companion.chat.data.local.dao.AppStateDao
import com.companion.chat.data.local.dao.FakeMemoryShortDao
import com.companion.chat.data.local.dao.FakePendingMessageDao
import com.companion.chat.data.local.dao.FakeReminderDao
import com.companion.chat.data.local.entity.AppStateEntity
import com.companion.chat.data.local.entity.ReminderEntity
import com.companion.chat.data.local.entity.ReminderState
import com.companion.chat.data.local.entity.ReminderType
import com.companion.chat.data.pending.PendingMessageQueue
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.data.reminder.FollowupPortImpl
import com.companion.chat.domain.reminder.ActiveFollowup
import com.companion.chat.domain.reminder.FollowupPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V2.2.2：追问判断合并进 mood 调用的分支单测。 */
class MoodServiceFollowupTest {

    private class FakeAppState : AppStateDao {
        val map = HashMap<String, String>()
        override suspend fun put(state: AppStateEntity) { map[state.key] = state.value }
        override suspend fun get(key: String): String? = map[key]
        override fun observeAll(): Flow<List<AppStateEntity>> = emptyFlow()
        override suspend fun getAll(): List<AppStateEntity> = map.map { AppStateEntity(it.key, it.value) }
        override suspend fun delete(key: String) { map.remove(key) }
        override suspend fun deleteAll() { map.clear() }
    }

    /** 记录式假 port（项目无 Mockito，不引依赖）。 */
    private class FakeFollowupPort : FollowupPort {
        val called = mutableListOf<String>()
        val advanced get() = called.filter { it.startsWith("advance:") }
        var activeResult: ActiveFollowup? = null

        /** 多链用例：与 [activeResult] 一起返回（前面的先判）。 */
        var extraActives: List<ActiveFollowup> = emptyList()

        override suspend fun activeAll(): List<ActiveFollowup> =
            listOfNotNull(activeResult) + extraActives

        override suspend fun complete(reminderId: Long) { called += "complete:$reminderId" }
        override suspend fun abandon(reminderId: Long) { called += "abandon:$reminderId" }
        override suspend fun advance(reminderId: Long) { called += "advance:$reminderId" }
        override suspend fun taskOf(reminderId: Long): String? = "t"
    }

    /** 录制式闹钟（写在本测试文件里，不动 ReminderServiceTest.FakeScheduler）。 */
    private class RecordingScheduler : AlarmScheduling {
        val events = mutableListOf<String>()
        override fun schedule(
            requestCode: Int, triggerAtMillis: Long, taskDesc: String,
            reminderId: Long?, kind: AlarmKind
        ) { events += "schedule:$requestCode:$kind" }
        override fun cancel(requestCode: Int) { events += "cancel:$requestCode" }
    }

    private val cfg = ApiConfig(providerId = "deepseek", baseUrl = "", apiKey = "k", model = "m")

    private fun service(
        dao: FakeAppState, json: String, port: FollowupPort? = null, onCall: (() -> Unit)? = null
    ) = MoodService(
        appStateDao = dao,
        callJson = { _, _ -> onCall?.invoke(); Result.success(json) },
        followup = port
    )

    // 1. active 非空 + turnCount % 5 != 0 → 确实调了模型
    @Test
    fun active_followup_bypasses_every_five_gate() = runBlocking {
        val port = FakeFollowupPort().apply { activeResult = ActiveFollowup(7L, "吃药", null) }
        var calls = 0
        val svc = MoodService(
            appStateDao = FakeAppState(),
            callJson = { _, _ ->
                calls++
                Result.success("""{"mood":"happy","reason":"ok","followup_status":"continue"}""")
            },
            followup = port
        )
        assertTrue(svc.onTurnCompleted(cfg, emptyList())) // count=1，非 5 的倍数
        assertEquals(1, calls)
    }

    // 2. active 为 null + 未到 5 轮 → 不调模型、返回 false（5.1 不回归）
    @Test
    fun no_active_before_threshold_does_not_call_model() = runBlocking {
        val dao = FakeAppState()
        var calls = 0
        val port = FakeFollowupPort()
        val svc = MoodService(dao, { _, _ -> calls++; Result.success("{}") }, followup = port)
        repeat(4) { assertFalse(svc.onTurnCompleted(cfg, emptyList())) }
        assertEquals(0, calls)
        assertEquals(4, svc.turnCount())
        assertTrue(port.called.isEmpty())
    }

    // 3. completed → 只调 complete，不调 advance / abandon
    @Test
    fun completed_only_completes() = runBlocking {
        val port = FakeFollowupPort().apply { activeResult = ActiveFollowup(1L, "吃药", null) }
        val svc = service(FakeAppState(), """{"mood":"normal","reason":"r","followup_status":"completed"}""", port)
        assertTrue(svc.onTurnCompleted(cfg, emptyList()))
        assertEquals(listOf("complete:1"), port.called)
    }

    // 4. abandoned → 只调 abandon，且 DAO 里状态是 ABANDONED（不是 DONE）
    @Test
    fun abandoned_only_abandons_and_persists_abandoned() = runBlocking {
        val reminderDao = FakeReminderDao()
        val scheduler = RecordingScheduler()
        reminderDao.insert(
            ReminderEntity(
                id = 9L, type = ReminderType.FOLLOWUP, taskDesc = "吃药",
                triggerTime = 0L, state = ReminderState.PENDING, attemptCount = 1
            )
        )
        val port = FollowupPortImpl(
            reminderDao,
            FollowupAlarms(scheduler),
            PendingMessageQueue(FakePendingMessageDao()),
            memoryShortDao = FakeMemoryShortDao(),
            nowMillis = { 0L }
        )
        val svc = service(FakeAppState(), """{"mood":"normal","reason":"r","followup_status":"abandoned"}""", port)
        assertTrue(svc.onTurnCompleted(cfg, emptyList()))
        assertEquals(ReminderState.ABANDONED, reminderDao.items.single().state)
        assertEquals(3, scheduler.events.count { it.startsWith("cancel:") })
        assertTrue(scheduler.events.none { it.startsWith("schedule:") }) // 没有 advance
    }

    // 5. continue → 只调 advance
    @Test
    fun continue_only_advances() = runBlocking {
        val port = FakeFollowupPort().apply { activeResult = ActiveFollowup(3L, "t", "cond") }
        val svc = service(FakeAppState(), """{"mood":"normal","reason":"r","followup_status":"continue"}""", port)
        assertTrue(svc.onTurnCompleted(cfg, emptyList()))
        assertEquals(listOf("advance:3"), port.called)
    }

    // 6. followup_status 缺失 → 调 advance（不是 no-op）
    @Test
    fun missing_followup_status_advances_not_noop() = runBlocking {
        val port = FakeFollowupPort().apply { activeResult = ActiveFollowup(4L, "t", null) }
        val svc = service(FakeAppState(), """{"mood":"normal","reason":"r"}""", port)
        assertTrue(svc.onTurnCompleted(cfg, emptyList()))
        assertTrue(port.advanced.isNotEmpty())
    }

    // 7. 模型返回非法 JSON 但 active 非空 → 调了 advance，且 mood 未被改写
    @Test
    fun invalid_json_with_active_advances_and_keeps_mood() = runBlocking {
        val dao = FakeAppState().apply { map[AppStateEntity.KEY_MOOD] = "happy" }
        val port = FakeFollowupPort().apply { activeResult = ActiveFollowup(5L, "t", null) }
        val svc = service(dao, "not json", port)
        assertTrue(svc.onTurnCompleted(cfg, emptyList()))
        assertEquals(listOf("advance:5"), port.called)
        assertEquals("happy", svc.currentMood())
    }

    // 8. callJson 返回 Result.failure → 不改 mood、不调 advance
    @Test
    fun call_failure_does_not_advance() = runBlocking {
        val dao = FakeAppState().apply { map[AppStateEntity.KEY_MOOD] = "low" }
        val port = FakeFollowupPort().apply { activeResult = ActiveFollowup(6L, "t", null) }
        var calls = 0
        val svc = MoodService(
            appStateDao = dao,
            callJson = { _, _ -> calls++; Result.failure(RuntimeException("网络")) },
            followup = port
        )
        assertTrue(svc.onTurnCompleted(cfg, emptyList()))
        assertEquals(1, calls)
        assertTrue(port.called.isEmpty())
        assertEquals("low", svc.currentMood())
    }

    // ---- 2026-11-20 多链：按 id 逐条分派，不再「只认最早那条」 ----

    @Test
    fun multi_chain_dispatches_each_id_separately() = runBlocking {
        val port = FakeFollowupPort().apply {
            activeResult = ActiveFollowup(3L, "喝水", "用户说喝完了")
            extraActives = listOf(ActiveFollowup(8L, "背单词", null))
        }
        val json = """{"mood":"normal","reason":"r","followups":[
            {"id":3,"status":"completed"},{"id":8,"status":"continue"}]}"""
        val svc = service(FakeAppState(), json, port)

        assertTrue(svc.onTurnCompleted(cfg, emptyList()))

        assertEquals(listOf("complete:3", "advance:8"), port.called)
    }

    @Test
    fun multi_chain_missing_id_falls_back_to_advance_for_that_chain() = runBlocking {
        // 模型漏了 id=8 → 那条按 continue 处理（不重排就是永久卡死）
        val port = FakeFollowupPort().apply {
            activeResult = ActiveFollowup(3L, "喝水", null)
            extraActives = listOf(ActiveFollowup(8L, "背单词", null))
        }
        val json = """{"mood":"normal","followups":[{"id":3,"status":"completed"}]}"""
        val svc = service(FakeAppState(), json, port)

        svc.onTurnCompleted(cfg, emptyList())

        assertEquals(listOf("complete:3", "advance:8"), port.called)
    }

    @Test
    fun legacy_single_status_is_ignored_when_two_chains_are_active() = runBlocking {
        // 旧格式没有 id，分不清是哪条 → 多链时一律 advance，绝不瞎套
        val port = FakeFollowupPort().apply {
            activeResult = ActiveFollowup(3L, "喝水", null)
            extraActives = listOf(ActiveFollowup(8L, "背单词", null))
        }
        val json = """{"mood":"normal","followup_status":"completed"}"""
        val svc = service(FakeAppState(), json, port)

        svc.onTurnCompleted(cfg, emptyList())

        assertEquals(listOf("advance:3", "advance:8"), port.called)
    }

    @Test
    fun legacy_single_status_still_works_with_one_chain() = runBlocking {
        val port = FakeFollowupPort().apply { activeResult = ActiveFollowup(5L, "t", null) }
        val svc = service(FakeAppState(), """{"mood":"normal","followup_status":"completed"}""", port)

        svc.onTurnCompleted(cfg, emptyList())

        assertEquals(listOf("complete:5"), port.called)
    }
}
