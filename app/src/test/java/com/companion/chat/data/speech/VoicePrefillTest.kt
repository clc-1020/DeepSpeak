package com.companion.chat.data.speech

import com.companion.chat.ui.speech.normalizeVoiceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 语音识别结果入库前的归一规则（V2.3.6）：
 * 空串 / 全空白一律归一为 null，调用方据此不写 app_state，避免空串占着 voice_pending_text。
 */
class VoicePrefillTest {

    @Test
    fun nullRaw_isDropped() {
        assertNull(normalizeVoiceText(null))
    }

    @Test
    fun blankRaw_isDropped() {
        assertNull(normalizeVoiceText("   "))
        assertNull(normalizeVoiceText(""))
        assertNull(normalizeVoiceText("\n\t  "))
    }

    @Test
    fun nonBlankRaw_isTrimmed() {
        assertEquals("你好", normalizeVoiceText("  你好  "))
    }
}
