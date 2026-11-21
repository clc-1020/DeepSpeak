package com.companion.chat.data.environment

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * [HttpText] 的 OkHttp 实现。任务 V2.1.1。
 *
 * 用 `enqueue` + `suspendCancellableCoroutine` 而不是 `execute()`：
 * - 不在调用线程上阻塞（调用方可能是 `Dispatchers.IO`，也可能在 WorkManager 的协程里）
 * - 协程取消时能真的把请求掐掉（`invokeOnCancellation` → `call.cancel()`），否则
 *   用户在设置页快速连点「查城市」会攒一堆在途请求
 *
 * **刻意不依赖 `Dispatchers`**：本类内部不做线程切换，也就不会在纯 JVM 单测里
 * 触发 `Dispatchers.Main` 去加载 Android 调度器工厂。
 */
class OkHttpHttpText(
    private val client: OkHttpClient = defaultWeatherClient(),
) : HttpText {

    override suspend fun get(url: String): HttpTextResult = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val call = client.newCall(request)
        cont.invokeOnCancellation { runCatching { call.cancel() } }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resume(HttpTextResult.Failure(null, e.message ?: e::class.java.simpleName))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = runCatching { r.body?.string() }.getOrNull()
                    val result = if (r.isSuccessful && body != null) {
                        HttpTextResult.Ok(body)
                    } else {
                        HttpTextResult.Failure(r.code, r.message.ifBlank { "HTTP ${r.code}" })
                    }
                    if (cont.isActive) cont.resume(result)
                }
            }
        })
    }

    companion object {
        const val USER_AGENT = "CompanionChat/1.0 (Android)"

        /**
         * 天气用的客户端：超时比聊天客户端短得多。
         *
         * 聊天要等流式输出所以 `readTimeout` 120s；天气是几百字节的 JSON，20s 还拿不到就是网络
         * 有问题，继续挂着只会让「每小时的环境检查」堆积。
         */
        fun defaultWeatherClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}