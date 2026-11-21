package com.companion.chat.data.proactive

import com.companion.chat.data.calendar.FestivalProvider
import com.companion.chat.data.local.dao.MemoryLongDao
import com.companion.chat.data.local.entity.MemoryLongEntity
import com.companion.chat.domain.proactive.SpecialDateHit
import com.companion.chat.domain.proactive.SpecialDateMatcher
import java.time.LocalDate
import java.time.MonthDay

/**
 * 特殊日期钩子服务：把「长期记忆里 category=special_date 的内容」+「内置节日」揉成
 * 「今天该不该发祝福」。任务 V2.1.3。
 *
 * 组装层（data/），真正的匹配逻辑在 [SpecialDateMatcher]（纯函数）。本类负责：
 * 1. `MemoryLongDao.getByCategory("special_date")` 取出用户存的日期（**已存在，不需新增 DAO**）
 * 2. 把 `content` 拆成 `Pair(名称, 月日)`——格式「名称 + 空格 + 日期」，如「生日 11-20」
 * 3. 交给 matcher 得出 [SpecialDateHit] 列表
 *
 * 命名规则（计划书 §2 模块 2）：`名称 日期`。分隔用第一个空格。
 * 「名称」和「日期」都可以缺：无名称跳过该条（V2.1.7 去重键要用名字，没名字没法去重）；
 * 无日期=整条解析失败 → matcher 里忽略。**任何一条烂数据都不崩**。
 */
class SpecialDateHookService(
    private val memoryDao: MemoryLongDao,
    private val festivals: FestivalProvider,
    private val matcher: SpecialDateMatcher = SpecialDateMatcher(),
) {

    /**
     * @param today 今天（注入以便测试）
     * @return 今天命中的特殊日期；可能为空
     */
    suspend fun specialDatesOn(today: LocalDate): List<SpecialDateHit> {
        val memories: List<MemoryLongEntity> = memoryDao.getByCategory(CATEGORY)

        val userDates = ArrayList<Pair<String, MonthDay?>>(memories.size)
        for (m in memories) {
            val name = nameOf(m.content)
            if (name == null) continue
            userDates += Pair(name, SpecialDateMatcher.parseDate(datePart(m.content)))
        }

        return matcher.match(
            today = today,
            userDates = userDates,
            festivals = festivals.getTodayFestivals(today),
        )
    }

    companion object {
        const val CATEGORY = "special_date"

        /**
         * 取名称：第一个空格前。
         *
         * 「生日 11-20」→「生日」；「11-20」（无空格，整条是日期）→ null（跳过，防去重键为空）；
         * 「  」→ null。无空格时**不能**把整条当名称，否则纯日期会伪装成有名字的条目。
         */
        private fun nameOf(content: String): String? {
            val trimmed = content.trim()
            val space = trimmed.indexOf(' ')
            if (space < 0) return null
            return trimmed.substring(0, space).trim().takeIf { it.isNotEmpty() }
        }

        /** 取日期部分：第一个空格后。无空格 → 整条（调用方已通过 nameOf 过滤，不会实际用到该返回值）。 */
        private fun datePart(content: String): String {
            val trimmed = content.trim()
            val space = trimmed.indexOf(' ')
            return if (space < 0) trimmed else trimmed.substring(space + 1)
        }
    }
}