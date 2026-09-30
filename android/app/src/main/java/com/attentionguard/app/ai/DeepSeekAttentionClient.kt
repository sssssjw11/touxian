package com.attentionguard.app.ai

import com.attentionguard.app.core.AttentionEvent
import com.attentionguard.app.core.ChatSnapshot
import com.attentionguard.app.core.EventCategory
import com.attentionguard.app.core.EventPriority
import com.attentionguard.app.core.EventStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DeepSeek-only structured enrichment for an already-gated event. */
class DeepSeekAttentionClient internal constructor(
    private val key: String,
    private val model: String,
    private val openConnection: () -> HttpURLConnection
) {
    constructor(key: String, model: String) : this(key, model, {
        URL(ENDPOINT).openConnection() as HttpURLConnection
    })

    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; activeConnection?.disconnect() }

    fun enrich(snapshot: ChatSnapshot, base: AttentionEvent, context: String): AttentionEvent {
        val system = """
            你是“偷闲”的校园群聊事件抽取器。
            只根据输入消息判断，不补充不存在的事实。返回 JSON 对象，不要 markdown。
            字段必须完整：title, summary, category, priority, status, attention_score,
            due_label, action_label, consequence, source_person。
            category 只能是 academic_admin, course, employment, competition, activity, meeting。
            priority 只能是 P0, P1, P2, P3。不得仅因为措辞强烈就提高等级，必须有对应证据。
            status 只能是 action_required, monitoring, confirmed。只有用户可以标记完成。
            attention_score 是 1 到 99 的整数；没有明确截止时间时 due_label 为空字符串。
        """.trimIndent()
        val messages = JSONArray().apply {
            snapshot.messages.filter { message ->
                base.evidence.any { message.text.startsWith(it) }
            }.takeLast(12).forEach { message ->
                put(JSONObject().apply {
                    put("sender", message.sender ?: if (message.side == "me") "我" else "群成员")
                    put("side", message.side)
                    put("text", message.text.take(2000))
                    put("mentions", JSONArray().apply {
                        message.mentions.forEach { mention -> put(mention) }
                    })
                })
            }
        }
        val user = JSONObject().apply {
            put("group", snapshot.title)
            put("context", context.take(2000))
            put("current_time", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
            put("heuristic_event", JSONObject().apply {
                put("title", base.title)
                put("priority", base.priority.name)
                put("attention_score", base.attentionScore)
            })
            put("messages", messages)
        }
        val result = postJson(system, user.toString())
        val proposed = priorityOf(clean(result.optString("priority")), base.priority)
        val priority = if (proposed.ordinal < base.priority.ordinal) base.priority else proposed
        val proposedDue = clean(result.getString("due_label")).takeIf { it.isNotBlank() }
        return base.copy(
            analysisSource = "DeepSeek",
            title = clean(result.optString("title")).ifBlank { base.title },
            summary = clean(result.optString("summary")).ifBlank { base.summary },
            category = categoryOf(clean(result.optString("category")), base.category),
            priority = priority,
            status = if (base.priority == EventPriority.P3) base.status else statusOf(clean(result.optString("status")), base.status),
            attentionScore = result.optInt("attention_score", base.attentionScore).coerceIn(1, 99),
            dueLabel = if (proposedDue == null) null else base.dueLabel,
            actionLabel = if (base.priority == EventPriority.P3) base.actionLabel else clean(result.getString("action_label")).takeIf { it.isNotBlank() },
            consequence = clean(result.getString("consequence")).takeIf { it.isNotBlank() },
            sourcePerson = clean(result.optString("source_person")).ifBlank { base.sourcePerson },
            reviewNotes = base.reviewNotes + buildList {
                if (priority != proposed) add("模型建议升级，但本地证据门槛未满足，保留本地等级")
                if (proposedDue != null && proposedDue != base.dueLabel) add("模型时间未替换原始消息的本地日期校验结果")
            }
        )
    }

    private fun clean(value: String): String = value.trim().takeUnless { it == "null" }.orEmpty()

    internal fun postJson(system: String, user: String, maxTokens: Int = 700, validateEvent: Boolean = true): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", 0.1)
            .put("max_tokens", maxTokens)
        checkActive()
        var connection: HttpURLConnection? = null
        try {
            connection = openConnection().apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 22000
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
            }
            activeConnection = connection
            checkActive()
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.outputStream.use { output: OutputStream -> output.write(bytes) }
            val code = connection.responseCode
            checkActive()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                val response = StringBuilder()
                while (true) {
                    checkActive()
                    val count = reader.read(buffer)
                    if (count == -1) break
                    require(response.length + count <= MAX_RESPONSE_CHARS) { "invalid_response" }
                    response.append(buffer, 0, count)
                }
                response.toString()
            }
            checkActive()
            return parseResponse(text, validateEvent)
        } finally {
            connection?.disconnect()
            activeConnection = null
        }
    }

    private fun checkActive() {
        check(!cancelled && !Thread.currentThread().isInterrupted) { "cancelled" }
    }

    private fun parseResponse(text: String, validateEvent: Boolean = true): JSONObject {
        try {
            val choice = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            require(choice.getString("finish_reason") == "stop")
            val content = choice.getJSONObject("message").get("content")
            require(content is String && content.isNotBlank())
            return parseObject(content).also { result ->
                if (!validateEvent) return@also
                val limits = mapOf("title" to 160, "summary" to 2000, "category" to 32,
                    "priority" to 2, "status" to 32, "due_label" to 160, "action_label" to 500,
                    "consequence" to 1000, "source_person" to 160)
                limits.forEach { (field, max) ->
                    val value = result.get(field)
                    require(value is String && value.length <= max)
                }
                require(clean(result.getString("title")).isNotBlank())
                require(clean(result.getString("summary")).isNotBlank())
                require(result.getString("category") in setOf("academic_admin", "course", "employment", "competition", "activity", "meeting"))
                require(result.getString("priority") in setOf("P0", "P1", "P2", "P3"))
                require(result.getString("status") in setOf("action_required", "monitoring", "confirmed"))
                val score = result.get("attention_score")
                require(score is Number && score.toDouble() in 1.0..99.0 && score.toDouble() == score.toInt().toDouble())
            }
        } catch (_: Exception) {
            // JSON exceptions can include provider content. Never expose their cause.
            throw IllegalStateException("invalid_response")
        }
    }

    private fun parseObject(content: String): JSONObject {
        val trimmed = content.trim()
        runCatching { return JSONObject(trimmed) }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return JSONObject(trimmed.substring(start, end + 1))
        throw IllegalStateException("DeepSeek 返回了无效 JSON")
    }

    private fun categoryOf(value: String, fallback: EventCategory): EventCategory = when (value) {
        "academic_admin" -> EventCategory.ACADEMIC_ADMIN
        "course" -> EventCategory.COURSE
        "employment" -> EventCategory.EMPLOYMENT
        "competition" -> EventCategory.COMPETITION
        "activity" -> EventCategory.ACTIVITY
        "meeting" -> EventCategory.MEETING
        else -> fallback
    }

    private fun priorityOf(value: String, fallback: EventPriority): EventPriority = when (value) {
        "P0" -> EventPriority.P0
        "P1" -> EventPriority.P1
        "P2" -> EventPriority.P2
        "P3" -> EventPriority.P3
        else -> fallback
    }

    private fun statusOf(value: String, fallback: EventStatus): EventStatus = when (value) {
        "action_required" -> EventStatus.ACTION_REQUIRED
        "monitoring" -> EventStatus.MONITORING
        "confirmed" -> EventStatus.CONFIRMED
        else -> fallback
    }

    companion object {
        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
        private const val MAX_RESPONSE_CHARS = 65_536
        fun readableError(error: Throwable?): String {
            val message = error?.message.orEmpty()
            return when {
                message == "cancelled" -> "请求已取消，可重新测试"
                message.contains("401") -> "密钥无效，请检查 API Key"
                message.contains("402") -> "账户余额不足，请检查 DeepSeek 账户"
                message.contains("429") -> "请求频繁，请稍后重试"
                message.contains("400") || message.contains("404") || message.contains("422") -> "模型或请求不可用，请检查模型名称"
                error is java.net.SocketTimeoutException -> "连接超时，请重试"
                error is java.net.UnknownHostException -> "网络不可用，请检查网络后重试"
                message.contains("JSON", true) || message.contains("invalid_json") || message.contains("invalid_response") -> "模型返回格式异常，请重试"
                else -> "连接失败，请检查网络和模型设置后重试"
            }
        }
    }
}
