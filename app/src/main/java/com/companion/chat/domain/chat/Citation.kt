package com.companion.chat.domain.chat

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import java.net.URI

/**
 * 联网搜索引用（2026-11-23）。DeepSeek 服务端工具 `deepseek_web_search` 在流式返回里通过
 * `delta.annotations` 给出的来源。这里只存「这条回复用到了哪些网页」，跟着消息一起落库。
 *
 * - [url]     来源链接（必有，空 url 的注解在解析/解码时直接丢弃）
 * - [title]   网页标题（可能没有）
 * - [siteName] 站点名（服务端若给了 site_name 就存，没有则展示时用 [host] 兜底）
 *
 * [host] 是**计算属性**（无 backing field），Gson 序列化时不会带上它，解码也不依赖它。
 */
data class Citation(
    val url: String,
    val title: String? = null,
    val siteName: String? = null
) {
    /** 展示用域名：去掉 `www.`、转小写；解析失败就退回原始 url。 */
    val host: String
        get() = runCatching {
            val u = if (url.contains("://")) url else "https://$url"
            URI(u).host?.removePrefix("www.")?.lowercase()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

    /** 折叠状态下详情行显示的主文案：优先标题，其次站点名，最后域名。 */
    val displayLabel: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: siteName?.takeIf { it.isNotBlank() }
            ?: host
}

/**
 * 引用的 JSON 编解码（存进 `messages.citations` TEXT 列）。
 *
 * 设计原则：**一条脏数据不能炸掉整条消息**。坏 JSON / 老数据 / 空串 / 无 url 的项，
 * 一律当「没有引用」，绝不抛异常。
 */
object CitationCodec {

    private val gson = Gson()

    /** 空列表返回 null（DB 里存 NULL，和「老消息没有引用」同义）。 */
    fun encode(list: List<Citation>): String? {
        if (list.isEmpty()) return null
        return runCatching { gson.toJson(list) }.getOrNull()
    }

    /** 容错解码：任何异常或畸形输入都返回空列表。 */
    fun decode(raw: String?): List<Citation> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<Citation>>() {}.type
            val parsed: List<Citation>? = gson.fromJson(raw, type)
            parsed.orEmpty().filter { it.url.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * 从流式 chunk 的 `delta.annotations`（JsonElement，结构由服务端决定）里提取引用。
 *
 * 用宽松的 JsonElement 解析而不是强类型 DTO：注解字段形状万一变了，也只是**这一帧没引用**，
 * 绝不会因为类型不匹配把整帧（连同正文 delta）一起丢掉。
 * 兼容 `annotations: [ {url,title,site_name,type,...}, ... ]` 这种数组结构，逐项取需要的字段。
 */
object CitationParser {

    fun parse(annotations: JsonElement?): List<Citation> {
        if (annotations == null || !annotations.isJsonArray) return emptyList()
        val out = ArrayList<Citation>()
        annotations.asJsonArray.forEach { el ->
            if (el != null && el.isJsonObject) {
                val obj = el.asJsonObject
                // 服务端为 Chat Completions 流式的**嵌套结构**（2026-11-23 真机实测）：
                //   {"type":"url_citation","url_citation":{"url","title","start_index","end_index"}}
                // 兼容两种形状：优先取 `url_citation` 子对象，取不到再回退到扁平对象本身。
                val inner = obj.get("url_citation")?.takeIf { it.isJsonObject }?.asJsonObject
                val src = inner ?: obj
                val url = (src.get("url") ?: obj.get("url"))
                    ?.takeIf { it.isJsonPrimitive }?.asString
                if (!url.isNullOrBlank()) {
                    val title = (src.get("title") ?: obj.get("title"))
                        ?.takeIf { it.isJsonPrimitive }?.asString
                    val site = (src.get("site_name") ?: obj.get("site_name"))
                        ?.takeIf { it.isJsonPrimitive }?.asString
                    out.add(Citation(url = url, title = title, siteName = site))
                }
            }
        }
        return out
    }
}
