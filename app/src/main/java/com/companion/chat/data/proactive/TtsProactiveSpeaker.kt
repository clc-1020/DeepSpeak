package com.companion.chat.data.proactive

import com.companion.chat.data.tts.ReadAloudController
import com.companion.chat.domain.proactive.ProactiveSpeaker

/**
 * 主动消息语音播报（任务 4.3）：复用 [ReadAloudController]（自带 WakeLock + 播放完成才释放），
 * 通知弹出后在后台直接把消息念出来，无需点开通知。
 *
 * 5 分钟上限由 [ProactiveDeliveryRunner] 的 `withTimeout` 控制，超时即结束并释放锁。
 */
class TtsProactiveSpeaker(private val readAloud: ReadAloudController) : ProactiveSpeaker {
    override suspend fun speak(text: String) {
        readAloud.speak(text)
    }
}