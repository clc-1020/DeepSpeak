package com.companion.chat.data.proactive

import com.companion.chat.data.calendar.FestivalProvider
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.domain.proactive.SpecialDateHit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 特殊日期钩子服务单测：DAO → 组装 → 匹配。任务 V2.1.3。
 *
 * 用假 MemoryLongDao（纯内存），不碰 Room。
 */
class SpecialDateHookServiceTest {

    private val day = LocalDate.of(2026, 11, 20)

    private val festivalsJson = """
        {
          "lunar": [{"name": "中秋", "dates": ["2026-09-25"]}],
          "solar": [{"name": "国庆", "month": 10, "day": 1}]
        }
    """.trimIndent()

    /** 内存假 DAO：直接喂内容。 */
    private fun dao(vararg contents: String): MemoryLongDao = object : MemoryLongDao {
        private val rows = contents.mapIndexed { i, c ->
            MemoryLongEntity(id = i + 1L, content = c, category = "special_date", createTime = 0L)
        }

        override suspend fun getByCategory(category: String): List<MemoryLongEntity> =
            if (category == "special_date") rows else emptyList()

        // 其余方法用不到，留空
        override suspend fun insert(memory: MemoryLongEntity): Long = 1L
        override suspend fun update(memory: MemoryLongEntity) {}
        override suspend fun delete(memory: MemoryLongEntity) {}
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<MemoryLongEntity>())
        override suspend fun getAll(): List<MemoryLongEntity> = rows
        override suspend fun getById(id: Long): MemoryLongEntity? = rows.firstOrNull { it.id == id }
        override suspend fun deleteAll() {}
    }

    private fun service(vararg contents: String) = SpecialDateHookService(
        memoryDao = dao(*contents),
        festivals = FestivalProvider(loader = { festivalsJson }),
    )

    /** 「生日 2026-11-20」在 2027 年 11-20 仍命中（按月日、忽略年份）。 */
    @Test
    fun birthday_2026_matches_2027() = runBlocking {
        val hits = service("生日 2026-11-20").specialDatesOn(LocalDate.of(2027, 11, 20))

        assertEquals(listOf<SpecialDateHit>(SpecialDateHit.User("生日")), hits)
    }

    @Test
    fun birthday_2026_matches_2028() = runBlocking {
        val hits = service("生日 2026-11-20").specialDatesOn(LocalDate.of(2028, 11, 20))
        assertEquals(listOf<SpecialDateHit>(SpecialDateHit.User("生日")), hits)
    }

    @Test
    fun all_formats_match() = runBlocking {
        val service = service(
            "生日 11-20",
            "纪念日 11/20",
            "注册 11月20日",
        )
        val hits = service.specialDatesOn(day)

        assertEquals(
            listOf(
                SpecialDateHit.User("生日"),
                SpecialDateHit.User("纪念日"),
                SpecialDateHit.User("注册"),
            ),
            hits,
        )
    }

    /** 无法解析的内容被忽略，不崩。 */
    @Test
    fun unparseable_content_is_ignored() = runBlocking {
        val hits = service("坏数据 不知道啥").specialDatesOn(day)
        assertTrue(hits.isEmpty())
    }

    /** 无名称（纯日期）跳过，防去重键为空。 */
    @Test
    fun nameless_entry_is_ignored() = runBlocking {
        val hits = service("11-20").specialDatesOn(day)
        assertTrue(hits.isEmpty())
    }

    /** 内置节日与用户日期同一天，都返回（顺序：用户在前，节日在后）。 */
    @Test
    fun festival_and_user_both_returned() = runBlocking {
        val service = service("生日 09-25")
        val hits = service.specialDatesOn(LocalDate.of(2026, 9, 25))

        assertEquals(
            listOf(
                SpecialDateHit.User("生日"),
                SpecialDateHit.Festival("中秋"),
            ),
            hits,
        )
    }

    /** 无任何记忆 → 只有内置节日。 */
    @Test
    fun no_memories_still_returns_festivals() = runBlocking {
        val hits = service().specialDatesOn(LocalDate.of(2026, 10, 1))
        assertEquals(listOf<SpecialDateHit>(SpecialDateHit.Festival("国庆")), hits)
    }
}