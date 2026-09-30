package com.attentionguard.app.capture

import android.content.Context
import android.content.ComponentName
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.attentionguard.app.BuildConfig
import com.attentionguard.app.core.AttentionEvent
import com.attentionguard.app.core.CaptureMode
import com.attentionguard.app.core.EventStatus
import com.attentionguard.app.core.EventStore
import com.attentionguard.app.core.MessageArchive
import com.attentionguard.app.core.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CaptureDiagnosticSnapshot(
    val connected: Boolean,
    val state: String,
    val inspectedAt: Long?,
    val readResult: String,
    val nodes: Int,
    val knownNodes: Int,
    val structuralNodes: Int,
    val messages: Int,
    val savedAt: Long?,
    val storage: String,
    val overlay: String,
    val overlayAt: Long?,
    val overlayResult: String,
    val keepAlive: String,
    val weChatVersion: String,
    val device: String
)

/** Bounded metadata only: no chat title, body, screenshot or API key. */
class CaptureDiagnostics(private val context: Context) {
    private val sp = context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE)
    private val weChatVersion = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo("com.tencent.mm", 0).versionName
    }.getOrNull() ?: "未能获取"
    fun heartbeat(connected: Boolean) = sp.edit().putBoolean("connected", connected)
        .putLong("heartbeat", System.currentTimeMillis()).apply()
    fun state(value: String) { if (sp.getString("state", "") != value) sp.edit().putString("state", value).apply() }
    fun inspected(nodes: Int, known: Int, structural: Int, messages: Int, reason: String) {
        sp.edit().putInt("nodes", nodes).putInt("known", known).putInt("structural", structural)
            .putInt("messages", messages).putString("read", reason).putLong("inspected", System.currentTimeMillis()).apply()
    }
    fun saved(added: Int) = sp.edit().putInt("saved", sp.getInt("saved", 0) + added)
        .putLong("saved_at", System.currentTimeMillis()).putString("storage", "正常").apply()
    fun storageError() = sp.edit().putString("storage", "保存失败，未覆盖已有消息").apply()
    fun analyzed(mode: String, messages: Int, events: Int, category: String, score: Int) =
        sp.edit().putString("analysis", "$mode · $messages 条文字 · $events 件事项 · $category · $score")
            .putLong("analyzed_at", System.currentTimeMillis()).apply()

    fun pipeline(stage: String, result: String, events: Int = 0, errorClass: String? = null) = synchronized(TRACE_LOCK) {
        if (stage in setOf("LOCAL_SAVE", "EVENT_SAVE") && result == "FAILED")
            sp.edit().putString("event_storage", "事件保存失败").apply()
        val traces = runCatching { JSONArray(sp.getString("pipeline", "[]")) }.getOrDefault(JSONArray())
        val entry = JSONObject().put("at", System.currentTimeMillis()).put("stage", stage)
            .put("result", result).put("events", events)
        if (errorClass != null && Regex("[A-Za-z][A-Za-z0-9]{0,79}").matches(errorClass)) entry.put("error_class", errorClass)
        val last = if (traces.length() > 0) traces.getJSONObject(traces.length() - 1) else null
        if (last != null && last.optString("stage") == stage && last.optString("result") == result &&
            last.optInt("events") == events && entry.getLong("at") - last.optLong("at") < 10_000) return@synchronized
        val bounded = JSONArray()
        for (i in maxOf(0, traces.length() - 79) until traces.length()) bounded.put(traces.get(i))
        bounded.put(entry)
        sp.edit().putString("pipeline", bounded.toString()).apply()
    }

    fun eventSaved(events: List<AttentionEvent>, incoming: Int) {
        sp.edit().putLong("events_saved_at", System.currentTimeMillis()).putInt("events_total", events.size)
            .putInt("events_archived", events.count { it.archived }).putString("event_storage", "已保存 $incoming 个识别结果").apply()
        pipeline("EVENT_SAVE", "COMMITTED", incoming)
    }

    fun appList(total: Int, shown: Int, filters: Boolean, demo: Boolean, readFailed: Boolean) {
        sp.edit().putInt("list_total", total).putInt("list_shown", shown).putBoolean("list_filtered", filters)
            .putBoolean("list_demo", demo).putBoolean("event_read_failed", readFailed).putLong("list_at", System.currentTimeMillis()).apply()
        pipeline("APP_LIST", when { readFailed -> "READ_FAILED"; demo -> "DEMO"; filters -> "FILTERED"; else -> "DISPLAYED" }, shown)
    }

    fun eventStorageLabel(): String = sp.getString("event_storage", "尚无事件写入").orEmpty()

    fun explanation(): String {
        val prefs = Prefs(context)
        return when {
            sp.getBoolean("event_read_failed", false) -> "APP事件文件读取失败，请导出诊断"
            sp.getString("event_storage", "").orEmpty().contains("失败") -> "事件保存失败，请导出诊断"
            prefs.demoMode -> "当前为示例页面，切回真实数据查看识别结果"
            prefs.captureMode == CaptureMode.INTENT -> "意图模式不自动写入事件；聊天需显式开始记录，事项可手动保存"
            !prefs.autoAnalyze -> "自动事件识别已关闭"
            sp.getString("state", "").orEmpty().contains("名称未确认") -> "消息可读但群名节点为空；开启本机 OCR 复核标题，或标记当前完整会话名"
            sp.getString("state", "").orEmpty().contains("未匹配") -> "已读到会话名但未匹配观测词条，请核对名称与词条"
            sp.getBoolean("list_filtered", false) -> "当前列表有筛选条件，结果可能被日期、重要性或状态隐藏"
            sp.getInt("events_archived", 0) > 0 -> "部分事件已归档，可到归档列表查看"
            else -> "识别、消息保存、事件写入和页面显示分别记录，可导出查看各阶段"
        }
    }

    /** Build on a worker: captures permission facts and counts, never raw content. */
    fun exportJson(now: Long = System.currentTimeMillis()): String {
        val prefs = Prefs(context)
        val events = EventStore(context)
        val list = events.load()
        val a11y = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }
        val permissions = JSONObject().put("accessibility_authorized", a11y)
            .put("service_live", isConnected(now)).put("application_overlay", Settings.canDrawOverlays(context))
            .put("accessibility_overlay_uses_service_permission", true)
            .put("notifications", NotificationManagerCompat.from(context).areNotificationsEnabled())
            .put("app_private_storage_requires_runtime_permission", false)
        val archive = runCatching { MessageArchive(context).use { a ->
            val counts = a.count()
            JSONObject().put("messages", counts.total).put("undated_messages", counts.undated)
                .put("recordings", a.recordings().size)
        } }.getOrElse { JSONObject().put("read_error_class", it.javaClass.simpleName) }
        return JSONObject().put("schema_version", 1).put("app_version", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE).put("generated_at", now)
            .put("device", snapshot(now).device).put("wechat_version", weChatVersion)
            .put("permissions", permissions).put("settings", JSONObject()
                .put("enabled", prefs.enabled).put("mode", prefs.captureMode.name).put("auto_events", prefs.autoAnalyze)
                .put("local_ocr", prefs.localOcrEnabled).put("cloud_enabled", prefs.cloudEnabled)
                .put("recognition_terms_count", prefs.whitelist.size)
                .put("message_rules_count", prefs.messageKeywordRules.size)
                .put("message_rules_enabled", prefs.messageKeywordRules.count { it.enabled }).put("demo", prefs.demoMode))
            .put("capture", JSONObject().put("state", snapshot(now).state).put("read_result", snapshot(now).readResult)
                .put("nodes", snapshot(now).nodes).put("messages", snapshot(now).messages)
                .put("last_inspected", snapshot(now).inspectedAt).put("storage", snapshot(now).storage)
                .put("overlay_result", snapshot(now).overlayResult).put("last_analysis", sp.getString("analysis", "")))
            .put("event_store", JSONObject().put("read_failed", events.readFailed).put("total", list.size)
                .put("archived", list.count { it.archived }).put("completed", list.count { it.status == EventStatus.COMPLETED })
                .put("last_saved", sp.getLong("events_saved_at", 0)).put("result", eventStorageLabel()))
            .put("app_list", JSONObject().put("total", sp.getInt("list_total", 0)).put("shown", sp.getInt("list_shown", 0))
                .put("filtered", sp.getBoolean("list_filtered", false)).put("demo", sp.getBoolean("list_demo", false))
                .put("last_opened", sp.getLong("list_at", 0)))
            .put("archive", archive).put("diagnosis", explanation())
            .put("pipeline", runCatching { JSONArray(sp.getString("pipeline", "[]")) }.getOrDefault(JSONArray()))
            .toString(2)
    }
    fun overlay(value: String) {
        if (sp.getString("overlay", "") == value) return
        val edit = sp.edit().putString("overlay", value)
        if (value != "已隐藏") edit.putString("overlay_result", value).putLong("overlay_at", System.currentTimeMillis())
        edit.apply()
    }
    fun keepAlive(value: String) = sp.edit().putString("keep_alive", value).apply()
    fun isConnected(now: Long = System.currentTimeMillis()): Boolean =
        CaptureRuntime.actions != null && sp.getBoolean("connected", false) && now - sp.getLong("heartbeat", 0) in 0..10_000

    fun healthLabel(enabled: Boolean, authorized: Boolean): String = when {
        !enabled -> "观测已暂停"
        !authorized -> "未授权采集"
        !isConnected() -> "采集已断开 · 需要恢复"
        else -> "采集已连接 · 仅当前会话"
    }

    fun summary(now: Long = System.currentTimeMillis()): String {
        val snapshot = snapshot(now)
        fun time(value: Long?) = value?.let { SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(it)) } ?: "无"
        return listOf(
            "服务：${if (snapshot.connected) "已连接" else "未连接或心跳过期"}",
            "当前状态：${snapshot.state}",
            "上次微信检查：${time(snapshot.inspectedAt)}",
            "读取结果：${snapshot.readResult}",
            "节点 ${snapshot.nodes} · 气泡 ${snapshot.knownNodes} · 结构匹配 ${snapshot.structuralNodes} · 消息 ${snapshot.messages}",
            "最近判断：${sp.getString("analysis", "尚未分析")}",
            "上次落盘：${time(snapshot.savedAt)} · ${snapshot.storage}",
            "事件写入：${eventStorageLabel()}",
            "页面说明：${explanation()}",
            "悬浮窗：${snapshot.overlay}",
            "上次挂窗：${time(snapshot.overlayAt)} · ${snapshot.overlayResult}",
            "保活通知：${snapshot.keepAlive}",
            "微信版本：${snapshot.weChatVersion}",
            snapshot.device
        ).joinToString("\n")
    }

    fun snapshot(now: Long = System.currentTimeMillis()): CaptureDiagnosticSnapshot = CaptureDiagnosticSnapshot(
        connected = isConnected(now),
        state = sp.getString("state", "等待开启无障碍").orEmpty(),
        inspectedAt = sp.getLong("inspected", 0).takeIf { it > 0 },
        readResult = sp.getString("read", "尚未读取").orEmpty(),
        nodes = sp.getInt("nodes", 0),
        knownNodes = sp.getInt("known", 0),
        structuralNodes = sp.getInt("structural", 0),
        messages = sp.getInt("messages", 0),
        savedAt = sp.getLong("saved_at", 0).takeIf { it > 0 },
        storage = sp.getString("storage", "尚未保存").orEmpty(),
        overlay = sp.getString("overlay", "尚未显示").orEmpty(),
        overlayAt = sp.getLong("overlay_at", 0).takeIf { it > 0 },
        overlayResult = sp.getString("overlay_result", "尚未尝试").orEmpty(),
        keepAlive = sp.getString("keep_alive", "尚未启动").orEmpty(),
        weChatVersion = weChatVersion,
        device = "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} · ${android.os.Build.MODEL}"
    )

    companion object { private val TRACE_LOCK = Any() }
}
