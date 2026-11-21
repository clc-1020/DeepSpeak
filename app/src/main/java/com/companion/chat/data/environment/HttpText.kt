package com.companion.chat.data.environment

/**
 * 一次纯文本 GET 的结果。任务 V2.1.1。
 *
 * 只有两态：成功拿到 body / 失败。**HTTP 状态码非 2xx 一律算失败**——天气和地理编码的
 * 4xx/5xx 返回体对调用方毫无用处，硬解析只会把错误当数据。
 */
sealed class HttpTextResult {
    data class Ok(val body: String) : HttpTextResult()

    /** @param statusCode 拿到响应但非 2xx 时有值；连接失败/超时/取消时为 null */
    data class Failure(val statusCode: Int?, val message: String) : HttpTextResult()
}

/**
 * HTTP 端口。任务 V2.1.1。
 *
 * 抽象出来的目的：单测里可以塞假实现直接构造三种结果（命中 / 空结果 / 网络失败），
 * 不用起服务器；需要验证真实解析时再配合 MockWebServer + [OkHttpHttpText]。
 */
fun interface HttpText {
    suspend fun get(url: String): HttpTextResult
}