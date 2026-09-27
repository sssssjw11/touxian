package com.attentionguard.app.capture

import android.content.Context
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
class CaptureDiagnostics(context: Context) {
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
}
