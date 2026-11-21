package com.companion.chat.data.environment

/** 纯 JVM 假实现集合。任务 V2.1.1。 */

/** 内存键值存储，替代 Room / SharedPreferences。 */
class FakeKeyValueStore(
    private val initial: Map<String, String> = emptyMap(),
) : KeyValueStore {

    val data: MutableMap<String, String> = LinkedHashMap(initial)

    override suspend fun get(key: String): String? = data[key]

    override suspend fun put(key: String, value: String) {
        data[key] = value
    }

    override suspend fun remove(key: String) {
        data.remove(key)
    }
}

/** 假定位：按 provider 返回预设坐标，并记录调用顺序（用来断言「没调定位」）。 */
class FakeLocationPort(
    private val points: Map<String, GeoPoint> = emptyMap(),
) : LocationPort {

    val calls: MutableList<String> = mutableListOf()

    override fun lastKnown(provider: String): GeoPoint? {
        calls += provider
        return points[provider]
    }
}

/** 假 HTTP：由 lambda 决定返回什么，并记录请求过的 URL。 */
class FakeHttpText(
    private val handler: (String) -> HttpTextResult,
) : HttpText {

    val urls: MutableList<String> = mutableListOf()

    override suspend fun get(url: String): HttpTextResult {
        urls += url
        return handler(url)
    }

    companion object {
        fun ok(body: String) = FakeHttpText { HttpTextResult.Ok(body) }
        fun failure(code: Int? = null, message: String = "boom") =
            FakeHttpText { HttpTextResult.Failure(code, message) }
    }
}