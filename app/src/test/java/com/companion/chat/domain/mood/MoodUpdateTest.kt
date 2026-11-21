package com.companion.chat.domain.mood

import com.companion.chat.data.local.entity.MessageEntity
import com.companion.chat.data.local.entity.MessageRole
import com.companion.chat.domain.reminder.ActiveFollowup
import com.companion.chat.domain.reminder.FollowupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mood 解析 / 计数 / 提示词 纯逻辑单测。任务 5.1 */
class MoodUpdateTest {

    @Test
    fun parses_valid_json() {
        val u = MoodUpdateParser.parse("""{"mood":"happy","reason":"升职了"}""")
        assertEquals("happy", u!!.mood)
        assertEquals("升职了", u.reason)
    }

    @Test
    fun tolerant_of_code_fence_and_extra_text() {
        val raw = "好的，结果如下：\n```json\n{\"mood\":\"low\",\"reason\":\"加班累\"}\n```"
        assertEquals("low", MoodUpdateParser.parse(raw)!!.mood)
    }

    @Test
    fun rejects_unknown_mood() {
        assertNull(MoodUpdateParser.parse("""{"mood":"angry"}"""))
    }

    @Test
    fun rejects_invalid_json() {
        assertNull(MoodUpdateParser.parse("今天心情不错")) // 无花括号
        assertNull(MoodUpdateParser.parse("""{"mood":"""))   // 截断
    }

    @Test
    fun mood_counter_fires_every_five() {
        assertFalse(MoodCounter.shouldUpdate(0))
        assertFalse(MoodCounter.shouldUpdate(4))
        assertTrue(MoodCounter.shouldUpdate(5))
        assertFalse(MoodCounter.shouldUpdate(6))
        assertTrue(MoodCounter.shouldUpdate(10))
        assertFalse(MoodCounter.shouldUpdate(5, every = 0)) // 非法阈值不触发
    }

    @Test
    fun prompt_contains_current_mood_and_recent() {
        val recent = listOf(
            MessageEntity(conversationId = 1, role = MessageRole.USER, content = "好累啊", timestamp = 0),
            MessageEntity(conversationId = 1, role = MessageRole.ASSISTANT, content = "抱抱", timestamp = 1)
        )
        val user = MoodPromptBuilder.build(recent, "low")
        assertTrue(user.contains("当前心情：low"))
        assertTrue(user.contains("用户：好累啊"))
        assertTrue(user.contains("你：抱抱"))
        assertTrue(MoodPromptBuilder.system().contains("normal|happy|low"))
    }

    // V2.2.2 #9：withFollowup=true 追加两字段；默认 system() 与 5.1 逐字一致
    @Test
    fun system_with_followup_adds_fields_and_default_is_unchanged() {
        val legacy = "你是情绪分析器。根据下面的对话判断「用户」此刻的心情。" +
            "\n只输出一个 JSON，不要任何解释或代码块标记，格式：" +
            "{\"mood\":\"normal|happy|low\",\"reason\":\"简短原因\"}" +
            "\nmood 只能是 normal（平静）、happy（开心）、low（低落/疲惫/烦躁）之一。"
        assertEquals(legacy, MoodPromptBuilder.system())
        val withFollowup = MoodPromptBuilder.system(withFollowup = true)
        // 2026-11-20 多链：格式从单个 followup_status 改成带 id 的 followups 数组
        assertTrue(withFollowup.contains("followups"))
        assertTrue(withFollowup.contains("\"id\""))
        assertTrue(withFollowup.contains("followup_reason"))
        assertTrue(withFollowup.contains("completed|continue|abandoned"))
    }

    // V2.2.2 #10：build 带 ActiveFollowup → user 内容含任务/停止条件；null → 「未指定」
    @Test
    fun build_includes_active_followup_context() {
        val withStop = MoodPromptBuilder.build(
            emptyList(), "normal", ActiveFollowup(1L, "吃药", "用户说吃完了")
        )
        assertTrue(withStop.contains("吃药"))
        assertTrue(withStop.contains("用户说吃完了"))
        val noStop = MoodPromptBuilder.build(
            emptyList(), "normal", ActiveFollowup(1L, "吃药", null)
        )
        assertTrue(noStop.contains("未指定"))
    }

    // ---- 2026-11-20 多链：followups 数组解析 ----

    @Test
    fun parses_followups_array_into_id_map() {
        val raw = """{"mood":"normal","reason":"r","followups":[
            {"id":3,"status":"completed"},{"id":8,"status":"abandoned"},{"id":9,"status":"continue"}]}"""
        val u = MoodUpdateParser.parse(raw)!!
        assertEquals(3, u.followupStatuses.size)
        assertEquals(FollowupStatus.COMPLETED, u.followupStatuses[3L])
        assertEquals(FollowupStatus.ABANDONED, u.followupStatuses[8L])
        assertEquals(FollowupStatus.CONTINUE, u.followupStatuses[9L])
        // 老字段缺失 → null，不影响 mood
        assertNull(u.followupStatus)
    }

    @Test
    fun bad_array_elements_are_skipped_not_fatal() {
        // 缺 id、id 非正、status 不认识、元素不是对象 —— 全部跳过，mood 仍有效
        val raw = """{"mood":"happy","reason":"r","followups":[
            {"status":"completed"},{"id":0,"status":"continue"},{"id":5,"status":"啥"},{"id":6,"status":"completed"},7]}"""
        val u = MoodUpdateParser.parse(raw)!!
        assertEquals("happy", u.mood)
        assertEquals(mapOf(6L to FollowupStatus.COMPLETED), u.followupStatuses)
    }

    @Test
    fun duplicate_ids_keep_the_first_status() {
        val raw = """{"mood":"normal","followups":[{"id":3,"status":"completed"},{"id":3,"status":"continue"}]}"""
        val u = MoodUpdateParser.parse(raw)!!
        assertEquals(FollowupStatus.COMPLETED, u.followupStatuses[3L])
    }

    @Test
    fun legacy_single_status_still_parsed() {
        // 旧格式必须还能读：升级瞬间模型仍可能按老提示词返回
        val u = MoodUpdateParser.parse("""{"mood":"normal","followup_status":"completed"}""")!!
        assertEquals(FollowupStatus.COMPLETED, u.followupStatus)
        assertTrue(u.followupStatuses.isEmpty())
    }

    @Test
    fun multi_build_lists_every_chain_with_its_id() {
        val prompt = MoodPromptBuilder.build(
            emptyList(), "normal",
            listOf(
                ActiveFollowup(3L, "喝水", "用户说喝完了"),
                ActiveFollowup(8L, "背单词", null)
            )
        )
        assertTrue(prompt.contains("id=3"))
        assertTrue(prompt.contains("id=8"))
        assertTrue(prompt.contains("喝水"))
        assertTrue(prompt.contains("2 个待追踪的任务"))
    }
}