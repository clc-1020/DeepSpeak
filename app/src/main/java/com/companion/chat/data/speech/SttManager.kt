package com.companion.chat.data.speech

/**
 * STT 门面：先过本地开关，再转调 [AsrPort]。任务 V2.3.3
 *
 * [enabled] 必须是 **lambda 而不是 Boolean**：设置页改了开关要立刻生效，
 * 构造时缓存 Boolean 会导致本次进程内一直维持旧状态。
 * 每轮现调一次；关闭时直接返回 Failure，连请求都不构造。
 * 空数组也在这里前置拦掉：换 AsrPort 实现时不会漏掉「什么都没录到」的提示。
 */
class SttManager(
    private val asr: AsrPort,
    private val enabled: () -> Boolean,
) {

    /**
     * 开关当前值（每轮现查，不缓存）。
     * UI 在按下麦克风前先判这个：关闭时直接不开麦、不弹录音浮层，
     * 而不是录完一整段才在 transcribe 里拿到「语音已关闭」。
     */
    fun isEnabled(): Boolean = enabled()

    suspend fun transcribe(wav: ByteArray): AsrResult {
        if (!enabled()) return AsrResult.Failure("语音已关闭")
        if (wav.isEmpty()) return AsrResult.Failure("没录到声音，请重试")
        return asr.transcribe(wav)
    }
}
