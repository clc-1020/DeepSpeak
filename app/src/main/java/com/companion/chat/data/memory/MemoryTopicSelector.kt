package com.companion.chat.data.memory

import android.util.Log
import com.companion.chat.data.remote.ApiConfig
import com.companion.chat.data.remote.ChatApiClient
import com.companion.chat.data.remote.dto.ChatMessageDto
import com.companion.chat.domain.JsonExtract
import com.companion.chat.domain.memory.ScoredMemory

/**
 * 用大模型从 TOP 候选记忆里挑一个当主动话题。任务 2.6
 *
 * 输入 TOP3 记忆（编号 1..N），要求模型输出 `{"suitable":true,"id":<编号>}`；若都不合适输出
 * `{"suitable":false}`。失败/解析不了 → 退回得分最高（第 1 条），并打日志便于真机排查「为何总选第 1 条」。
 */
class MemoryTopicSelector(private val apiClient: ChatApiClient) {

    /** @return 选中的记忆 id；null = 模型明确认为都不合适（suitable=false / id=0）或候选为空。 */
    suspend fun choose(config: ApiConfig, candidates: List<ScoredMemory>): Long? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates[0].id
        val listText = candidates.mapIndexed { i, c -> "${i + 1}. ${c.content}" }.joinToString("\n")

        val result = apiClient.callJson(
            config = config,
            messages = listOf(ChatMessageDto("user", "候选记忆：\n$listText")),
            schemaHint = SYSTEM
        )
        val raw = result.getOrNull()
        if (raw == null) {
            Log.w(TAG, "选择话题 API 失败，退回第 1 条：${result.exceptionOrNull()?.message}")
            return candidates[0].id
        }
        val idx = parseChoice(raw)
        if (idx == null) {
            Log.w(TAG, "选择结果无法解析，退回第 1 条：$raw")
            return candidates[0].id
        }
        return when {
            idx == 0 -> {
                Log.i(TAG, "模型判断无合适话题(suitable=false)")
                null
            }
            idx in 1..candidates.size -> candidates[idx - 1].id
            else -> {
                Log.w(TAG, "选择编号越界($idx)，退回第 1 条")
                candidates[0].id
            }
        }
    }

    companion object {
        private const val TAG = "MemoryTopicSelector"

        val SYSTEM: String = """你是一个主动话题选择器。下面是候选记忆，请选一条最适合现在主动开启话题的记忆。
只输出一个 JSON 对象：
- 有合适的话题：{"suitable":true,"id":<编号>}（编号 1..N）
- 都不合适：{"suitable":false}
不要输出任何解释或多余内容。"""

        /**
         * 解析模型返回：优先看显式 `suitable` 字段（false→0 表示都不合适），再取 id/index。
         * @return 正整数=候选编号；0=都不合适；null=无法解析（调用方退回第 1 条）。
         */
        fun parseChoice(raw: String?): Int? {
            val obj = JsonExtract.parseObject(raw) ?: return null
            // 显式 suitable 字段优先
            val suitable = obj.get("suitable")
            if (suitable != null && suitable.isJsonPrimitive) {
                val ok = try { suitable.asBoolean } catch (_: Exception) { true }
                if (!ok) return 0
            }
            val el = obj.get("id") ?: obj.get("index") ?: return null
            if (!el.isJsonPrimitive) return null
            return try {
                el.asInt
            } catch (_: Exception) {
                null
            }
        }
    }
}
