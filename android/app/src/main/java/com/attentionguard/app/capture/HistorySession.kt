package com.attentionguard.app.capture

import android.content.Context
import com.attentionguard.app.core.ChatSnapshot
import java.time.LocalDate
import java.util.UUID

data class HistoryRange(val start: LocalDate, val end: LocalDate) {
    init { require(start <= end) }
    fun includes(day: String?) = day == null || day in start.toString()..end.toString()
    override fun toString() = "$start 至 $end"
}

data class HistoryConfig(val title: String, val range: HistoryRange, val automatic: Boolean) {
    init { require(title.isNotBlank() && title.length <= 120) }
}

enum class HistoryState(val label: String) {
    READY("等待在目标会话确认"), RUNNING("回溯中"), PAUSED("已暂停"), FINISHED("已结束"), CANCELLED("已取消")
}

/** Limits are safety bounds, never an assertion that all history was read. */
class HistorySession(val config: HistoryConfig, val id: String = UUID.randomUUID().toString()) {
    var state = HistoryState.READY; private set
    var reason = ""; private set
    var screens = 0; private set
    var attempts = 0; private set
    var gaps = 0; private set
    private var signature = ""
    private var lastScrollSignature: String? = null
    private var stalled = 0
    private var startedAt: Long? = null

    fun start(title: String, now: Long): Boolean {
        if (title != config.title || state !in setOf(HistoryState.READY, HistoryState.PAUSED)) return false
        if (screens >= MAX_SCREENS || attempts >= MAX_SCREENS || startedAt?.let { now - it >= MAX_DURATION } == true) {
            reason = "已达单次任务上限，请结束任务后按需新建"
            return false
        }
        state = HistoryState.RUNNING; reason = ""
        if (startedAt == null) startedAt = now
        lastScrollSignature = null; stalled = 0
        return true
    }
    fun observe(snapshot: ChatSnapshot, gap: Boolean = false) {
        if (state != HistoryState.RUNNING) return
        if (snapshot.title != config.title) { pause("会话已切换，返回目标会话后可继续"); return }
        if (snapshot.messages.isEmpty()) { pause("当前没有可读消息"); return }
        if (snapshot.signature() != signature) { signature = snapshot.signature(); screens++; if (gap) gaps++ }
        if (snapshot.messages.all { it.date != null && it.date < config.range.start.toString() }) {
            state = HistoryState.FINISHED; reason = "已越过起始日期；仅代表可见页面范围，不保证完整"
        }
        if (screens >= MAX_SCREENS && state == HistoryState.RUNNING) pause("已达单次 200 屏上限")
    }
    fun canScroll(now: Long): Boolean {
        if (state != HistoryState.RUNNING || !config.automatic) return false
        if (now - (startedAt ?: now) >= MAX_DURATION) { pause("已达单次 15 分钟上限"); return false }
        if (attempts >= MAX_SCREENS) { pause("已达单次 200 次翻页上限"); return false }
        if (lastScrollSignature == signature) stalled++ else stalled = 0
        if (stalled >= 3) { pause("连续 3 次未发现新页面，可能已到顶或微信未加载"); return false }
        lastScrollSignature = signature; attempts++
        return true
    }
    fun pause(message: String) {
        if (state == HistoryState.RUNNING) { state = HistoryState.PAUSED; reason = message }
    }
    fun cancel() { state = HistoryState.CANCELLED; reason = "已保存的消息保留在本机" }
    fun summary() = "${state.label} · $screens 屏 · $attempts 次翻页\n${config.range}\n${if (gaps > 0) "$gaps 处页面无法连续拼接，可能有遗漏或重复\n" else ""}$reason"

    companion object { const val MAX_SCREENS = 200; const val MAX_DURATION = 15 * 60 * 1000L }
}

interface CaptureActions {
    fun armHistory(config: HistoryConfig): Boolean
    fun pauseHistory()
    fun cancelHistory()
    fun confirmCurrentTitle(title: String): Boolean = false
}

/** Same-process UI bridge; no exported broadcast or remote control surface. */
object CaptureRuntime {
    var actions: CaptureActions? = null
    var history: HistorySession? = null
    var lastVisibleTitle: String? = null
}

class HistoryReceipt(context: Context) {
    private val sp = context.getSharedPreferences("history_receipt", Context.MODE_PRIVATE)
    fun save(session: HistorySession) = sp.edit().putString("summary", session.summary())
        .putString("stream", session.id).putString("title", session.config.title)
        .putBoolean("active", session.state in setOf(HistoryState.READY, HistoryState.RUNNING, HistoryState.PAUSED)).apply()
    fun interrupted() {
        if (sp.getBoolean("active", false)) sp.edit().putBoolean("active", false)
            .putString("summary", "上次任务已中断，未自动恢复。已落盘的消息仍保留。\n" + sp.getString("summary", "")).apply()
    }
    fun summary() = sp.getString("summary", "尚未创建回溯任务").orEmpty()
    fun stream() = sp.getString("stream", null)
}
