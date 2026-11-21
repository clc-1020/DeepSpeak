package com.companion.chat.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆块拼装与预算裁剪单测（2026-11-21）。
 *
 * 这是「每轮都要花 token」的地方，四条铁律必须钉死：
 *  1. 没相关内容 → 一个字都不注入（null）；
 *  2. 短期低于相似度阈值不注入（否则模型会硬凑）；
 *  3. 超预算按「日记 → 短期 → 长期」整段砍（长期记忆最不该丢）；
 *  4. 日记只取 3 天、空事件行过滤。
 */
class MemoryContextBlockTest {

    private fun short(text: String, score: Float?) = MemoryContextBlock.ShortEntry(text, score)

    @Test
    fun empty_everything_returns_null() {
        assertNull(MemoryContextBlock.build(emptyList(), emptyList(), emptyList(), budgetChars = 1000))
    }

    @Test
    fun short_below_threshold_is_not_injected() {
        val block = MemoryContextBlock.build(
            short = listOf(short("用户明天面试", 0.10f)),
            long = emptyList(),
            daily = emptyList(),
            budgetChars = 1000
        )
        assertNull("无关的短期记忆不许注入", block)
    }

    @Test
    fun short_with_unknown_score_is_not_injected() {
        // 相似度没算出来（模型未就绪）→ 宁可不提
        assertNull(
            MemoryContextBlock.build(
                short = listOf(short("用户明天面试", null)),
                long = emptyList(),
                daily = emptyList(),
                budgetChars = 1000
            )
        )
    }

    @Test
    fun short_keeps_only_top_four_above_threshold() {
        val block = MemoryContextBlock.build(
            short = (1..8).map { short("记忆$it", 0.9f) },
            long = emptyList(),
            daily = emptyList(),
            budgetChars = 5000
        )!!
        assertTrue(block.contains("记忆1"))
        assertTrue(block.contains("记忆4"))
        assertTrue("超过 4 条要截断", !block.contains("记忆5"))
    }

    @Test
    fun long_is_deduplicated_and_capped() {
        // 20 条重复 + 15 条唯一 → 去重后 16 条，再按上限截到 12 条
        val long = List(20) { "喜欢安静" } + (1..15).map { "事实$it" }
        val block = MemoryContextBlock.build(emptyList(), long, emptyList(), budgetChars = 5000)!!

        assertEquals("「喜欢安静」只应出现一次", 1, Regex("喜欢安静").findAll(block).count())
        assertTrue(block.contains("事实11"))
        assertTrue("长期上限 12 条（含去重后的首条）", !block.contains("事实12"))
    }

    @Test
    fun over_budget_drops_daily_first_then_short_then_long() {
        val short = listOf(short("A".repeat(20), 0.9f))
        val long = listOf("L".repeat(20))
        val daily = listOf(MemoryContextBlock.DailyEntry("2026-11-21", "D".repeat(20), 0.9f))

        // 基准：三段都装得下
        val full = MemoryContextBlock.build(short, long, daily, budgetChars = 5000)!!
        assertTrue(full.contains("前几天"))

        // 收紧 5 字 → 砍最后一段（日记）
        val noDaily = MemoryContextBlock.build(short, long, daily, budgetChars = full.length - 5)!!
        assertTrue("先砍日记", !noDaily.contains("前几天"))
        assertTrue(noDaily.contains("最近"))
        assertTrue(noDaily.contains("关于你"))

        // 再收紧 → 砍短期
        val noShort = MemoryContextBlock.build(short, long, daily, budgetChars = noDaily.length - 5)!!
        assertTrue("再砍短期", !noShort.contains("最近"))
        assertTrue("长期留到最后", noShort.contains("关于你"))

        // 预算连 header 都装不下 → 不注入
        assertNull("预算 40 字连 header + 长期段都装不下", MemoryContextBlock.build(short, long, daily, budgetChars = 40))
    }

    @Test
    fun daily_takes_three_days_and_skips_blank_events() {
        val daily = listOf(
            MemoryContextBlock.DailyEntry("2026-11-21", "去公司", 0.9f),
            MemoryContextBlock.DailyEntry("2026-11-20", "", 0.9f),
            MemoryContextBlock.DailyEntry("2026-11-19", "面试通过", 0.9f),
            MemoryContextBlock.DailyEntry("2026-11-18", "看电影", 0.9f),
            MemoryContextBlock.DailyEntry("2026-11-17", "太早了不该出现", 0.9f)
        )
        val block = MemoryContextBlock.build(emptyList(), emptyList(), daily, budgetChars = 5000)!!

        assertTrue(block.contains("去公司"))
        assertTrue(block.contains("面试通过"))
        assertTrue(block.contains("看电影"))
        assertTrue("空事件行不注入", !block.contains("2026-11-20"))
        assertTrue("超过 3 天不注入", !block.contains("太早了"))
    }

    @Test
    fun header_always_carries_the_do_not_ramble_instruction() {
        val block = MemoryContextBlock.build(
            short = listOf(short("用户明天面试", 0.9f)),
            long = emptyList(),
            daily = emptyList(),
            budgetChars = 2000
        )!!
        assertTrue("必须要求模型别罗列", block.contains("别罗列"))
        assertTrue("必须要求自然带一句", block.contains("自然提一句"))
        assertTrue("必须要求不相关别硬提", block.contains("不相关别硬提"))
    }
    // ---- 2026-11-21 真机发现：日记 events 是 JSON 数组原文 ----

    @Test
    fun daily_json_events_are_flattened_to_sentences() {
        val daily = listOf(
            MemoryContextBlock.DailyEntry(
                "2026-11-20",
                """["用户让助手督促背单词","用户要求助手监督自己喝水","用户说明其实是在测试"]""",
                0.9f
            )
        )
        val block = MemoryContextBlock.build(emptyList(), emptyList(), daily, budgetChars = 5000)!!

        assertTrue("不该把 JSON 原样注入", !block.contains("[") && !block.contains("\""))
        assertTrue(block.contains("督促背单词；用户要求助手监督自己喝水；用户说明其实是在测试"))
    }

    @Test
    fun long_single_event_is_truncated() {
        val daily = listOf(
            MemoryContextBlock.DailyEntry("2026-11-20", "A".repeat(200), 0.9f)
        )
        val block = MemoryContextBlock.build(emptyList(), emptyList(), daily, budgetChars = 5000)!!

        assertTrue("单条事件应截断到 60 字", !block.contains("A".repeat(61)))
        assertTrue(block.contains("A".repeat(60)))
    }

    @Test
    fun non_json_events_are_kept_as_is() {
        val daily = listOf(MemoryContextBlock.DailyEntry("2026-11-20", "普通的一句话", 0.9f))
        val block = MemoryContextBlock.build(emptyList(), emptyList(), daily, budgetChars = 5000)!!
        assertTrue(block.contains("普通的一句话"))
    }
    @Test
    fun daily_below_threshold_is_not_injected() {
        // 2026-11-21 用户要求：日记也走「相关才注入」，闲聊轮次直接省掉整段
        val daily = listOf(
            MemoryContextBlock.DailyEntry("2026-11-20", "去公司", 0.10f),
            MemoryContextBlock.DailyEntry("2026-11-19", "面试通过", null)
        )
        assertNull(MemoryContextBlock.build(emptyList(), emptyList(), daily, budgetChars = 5000))
    }

    @Test
    fun header_is_compressed_to_save_tokens() {
        val block = MemoryContextBlock.build(
            short = listOf(short("用户明天面试", 0.9f)),
            long = emptyList(), daily = emptyList(), budgetChars = 2000
        )!!
        val header = block.substringBefore("\n")
        assertTrue("提示语必须压到 30 字内（原来 69 字白花 40+ token）：实际 ${header.length}", header.length <= 30)
        assertTrue("压缩后仍要传达「相关才提、别罗列」", header.contains("相关") && header.contains("别罗列"))
    }

    // ---- 2026-11-21：短期同主题只留一条（修「库里有 4 条背单词记忆 → 记忆块变成单一话题广播」）----

    @Test
    fun same_topic_short_memories_collapse_to_one() {
        val block = MemoryContextBlock.build(
            short = listOf(
                short("用户从当前起要求助手每5分钟提醒一次背单词（一分钟后开始）", 0.72f),
                short("现在我要背单词，提醒我。每隔5分钟提醒一次", 0.70f),
                short("用户喜欢用调侃的方式和助手互动", 0.66f),
            ),
            long = emptyList(),
            daily = emptyList(),
            budgetChars = 1000,
            shortLimit = 4,
        )!!
        // 两条背单词合成一条 + 调侃一条
        assertEquals(1, Regex("背单词").findAll(block).count())
        assertTrue(block.contains("调侃"))
    }

    @Test
    fun different_topics_are_all_kept() {
        val block = MemoryContextBlock.build(
            short = listOf(
                short("用户明天要面试", 0.80f),
                short("用户对花生过敏", 0.75f),
                short("用户养了一只叫豆豆的猫", 0.70f),
            ),
            long = emptyList(),
            daily = emptyList(),
            budgetChars = 1000,
            shortLimit = 4,
        )!!
        assertTrue(block.contains("面试"))
        assertTrue(block.contains("花生"))
        assertTrue(block.contains("豆豆"))
    }

    @Test
    fun dedupe_by_topic_keeps_the_higher_one() {
        val out = MemoryContextBlock.dedupeByTopic(
            listOf(
                "每5分钟提醒一次背单词",
                "每隔5分钟提醒一次背单词",
                "提醒我喝水",
            )
        )
        assertEquals(2, out.size)
        assertEquals("每5分钟提醒一次背单词", out[0])
        assertEquals("提醒我喝水", out[1])
    }
}