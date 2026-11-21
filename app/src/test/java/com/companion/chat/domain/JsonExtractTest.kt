package com.companion.chat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JsonExtract 单测：合法/非法/截断/空 都安全返回。任务 1.2 增补 */
class JsonExtractTest {

    @Test
    fun valid_object_parsed() {
        val obj = JsonExtract.parseObject("""{"mood":"happy","reason":"聊得开心"}""")
        assertEquals("happy", JsonExtract.string(obj, "mood"))
        assertEquals("聊得开心", JsonExtract.string(obj, "reason"))
    }

    @Test
    fun invalid_json_returns_null() {
        assertNull(JsonExtract.parseObject("这不是 JSON"))
        assertNull(JsonExtract.parseObject("""{"mood":"happy", 截断"""))
        assertNull(JsonExtract.parseObject(""))
        assertNull(JsonExtract.parseObject(null))
    }

    @Test
    fun non_object_json_returns_null() {
        assertNull(JsonExtract.parseObject("[1,2,3]"))
        assertNull(JsonExtract.parseObject("\"just a string\""))
    }
}
