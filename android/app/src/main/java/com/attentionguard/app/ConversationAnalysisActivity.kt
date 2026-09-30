package com.attentionguard.app

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.attentionguard.app.ai.DeepSeekRelationshipClient
import com.attentionguard.app.capture.CaptureRuntime
import com.attentionguard.app.capture.HistoryRange
import com.attentionguard.app.core.ArchivedMessage
import com.attentionguard.app.core.ChatRecording
import com.attentionguard.app.core.MessageArchive
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.core.RecordingState
import com.attentionguard.app.core.RelationshipAnalysis
import com.attentionguard.app.core.RelationshipReport
import com.attentionguard.app.ui.GuardMotion
import com.attentionguard.app.ui.GuardSegments
import com.attentionguard.app.ui.GuardUi
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly recorded sessions, independent from transient intent and event output. */
class ConversationAnalysisActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var archive: MessageArchive
    private lateinit var titleText: TextView
    private lateinit var body: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var detail: LinearLayout
    private lateinit var status: TextView
    private lateinit var quality: TextView
    private lateinit var ranges: GuardSegments
    private lateinit var customDates: LinearLayout
    private lateinit var startButton: MaterialButton
    private lateinit var endButton: MaterialButton
    private lateinit var includeUnknown: SwitchCompat
    private lateinit var analyzeButton: MaterialButton
    private lateinit var cloudButton: MaterialButton
    private lateinit var result: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var moreButton: MaterialButton
    private lateinit var resumeButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var deleteButton: MaterialButton
    private var selectedId: String? = null
    private var selected: ChatRecording? = null
    private var rangeMode = R.id.ag_conversation_all
    private var start = LocalDate.now().minusDays(6)
    private var end = LocalDate.now()
    private var includeUndated = true
    private var selectedMessages = emptyList<ArchivedMessage>()
    private var shownMessages = 0
    private var localReport: RelationshipReport? = null
    private var cloudReport: RelationshipReport? = null
    private var cloudAvailable = false
    private var busy = false
    private var syncing = false
    @Volatile private var closed = false
    @Volatile private var cloudClient: DeepSeekRelationshipClient? = null
    private val generation = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = GuardUi(this)
        archive = MessageArchive(this)
        selectedId = if (savedInstanceState != null) savedInstanceState.getString("recording_id")
            else intent.getStringExtra(EXTRA_RECORDING_ID)
        rangeMode = savedInstanceState?.getInt("range_mode", rangeMode) ?: rangeMode
        start = parseDay(savedInstanceState?.getString("start")) ?: start
        end = parseDay(savedInstanceState?.getString("end")) ?: end
        includeUndated = savedInstanceState?.getBoolean("include_undated", rangeMode == R.id.ag_conversation_all)
            ?: (rangeMode == R.id.ag_conversation_all)
        val root = FrameLayout(this).apply { setBackgroundColor(ui.background) }
        val shell = ui.boundedColumn()
        root.addView(shell, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        shell.addView(ui.row().apply {
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            addView(ui.iconButton(R.drawable.ag_arrow_left, "返回") { goBack() })
            titleText = ui.text("会话深度分析", R.dimen.ag_type_heading, bold = true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            addView(titleText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton(R.drawable.ag_rotate_ccw, "刷新会话记录") {
                if (!busy) load()
            }.apply { id = R.id.ag_conversation_refresh })
        })
        body = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(28)) }
        shell.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        list = ui.column().apply { id = R.id.ag_conversation_list }
        detail = buildDetail().apply { id = R.id.ag_conversation_detail }
        body.addView(list)
        body.addView(detail)
        ui.install(this, root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
    }

    override fun onResume() {
        super.onResume()
        if (!busy) load()
    }

    private fun buildDetail(): LinearLayout = ui.column().apply {
        status = ui.text("", R.dimen.ag_type_label, ui.sub).apply {
            id = R.id.ag_conversation_status
            layoutParams = ui.lp(8)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(status)
        addView(ui.heading("分析范围"))
        ranges = GuardSegments(this@ConversationAnalysisActivity, listOf(
            GuardSegments.Option(R.id.ag_conversation_all, "全部"),
            GuardSegments.Option(R.id.ag_conversation_three_days, "近 3 天"),
            GuardSegments.Option(R.id.ag_conversation_seven_days, "近 7 天"),
            GuardSegments.Option(R.id.ag_conversation_custom, "自定义")
        ), rangeMode).apply {
            id = R.id.ag_conversation_range
            layoutParams = ui.lp(12)
        }
        ranges.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !syncing && id != rangeMode) {
                rangeMode = id
                includeUndated = id == R.id.ag_conversation_all
                updateRangeControls()
                load()
            }
        }
        addView(ranges)
        customDates = ui.row().apply {
            startButton = ui.button("", R.drawable.ag_calendar_plus, false) { chooseDate(true) }.apply {
                id = R.id.ag_conversation_start
                maxLines = 2
                setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
            }
            endButton = ui.button("", R.drawable.ag_calendar_plus, false) { chooseDate(false) }.apply {
                id = R.id.ag_conversation_end
                maxLines = 2
                setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8))
            }
            addView(startButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(endButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = ui.dp(8) })
            layoutParams = ui.lp(10)
        }
        addView(customDates)
        includeUnknown = ui.toggle("包含日期未确认的消息", includeUndated, R.id.ag_conversation_undated)
        includeUnknown.setOnCheckedChangeListener { _, checked ->
            if (!syncing) {
                includeUndated = checked
                load()
            }
        }
        addView(includeUnknown)
        quality = ui.text("", R.dimen.ag_type_caption, ui.sub).apply {
            id = R.id.ag_conversation_quality
            layoutParams = ui.lp(4)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(quality)
        analyzeButton = ui.button("深度分析", R.drawable.ag_scan_text) { analyze(false) }.apply {
            id = R.id.ag_conversation_analyze
            layoutParams = ui.lp(16)
        }
        addView(analyzeButton)
        cloudButton = ui.button("DeepSeek 深化", R.drawable.ag_activity, false) { confirmCloud() }.apply {
            id = R.id.ag_conversation_cloud
            layoutParams = ui.lp(8)
        }
        addView(cloudButton)
        result = ui.column().apply {
            id = R.id.ag_conversation_result
            layoutParams = ui.lp(16)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(result)
        addView(ui.heading("聊天原文"))
        messages = ui.column().apply { id = R.id.ag_conversation_messages; layoutParams = ui.lp(10) }
        addView(messages)
        moreButton = ui.button("加载更多原文", R.drawable.ag_chevron_down, false) { renderMoreMessages() }.apply {
            id = R.id.ag_conversation_more
            layoutParams = ui.lp(10)
        }
        addView(moreButton)
        addView(ui.divider(20))
        resumeButton = ui.button("继续记录", R.drawable.ag_radio, false) { resumeRecording() }.apply {
            id = R.id.ag_conversation_resume
            layoutParams = ui.lp(16)
        }
        addView(resumeButton)
        stopButton = ui.button("结束记录", R.drawable.ag_circle_check, false) { stopRecording() }.apply {
            id = R.id.ag_conversation_stop
            layoutParams = ui.lp(8)
        }
        addView(stopButton)
        deleteButton = ui.button("删除此会话记录", R.drawable.ag_x, false) { confirmDelete() }.apply {
            id = R.id.ag_conversation_delete
            layoutParams = ui.lp(8)
        }
        addView(deleteButton)
        updateRangeControls()
    }

    private fun updateRangeControls() {
        syncing = true
        includeUnknown.isChecked = includeUndated
        ranges.check(rangeMode)
        syncing = false
        customDates.visibility = if (rangeMode == R.id.ag_conversation_custom) View.VISIBLE else View.GONE
        startButton.text = "起始\n$start"
        endButton.text = "结束\n$end"
    }

    private fun currentRange(): HistoryRange? = when (rangeMode) {
        R.id.ag_conversation_three_days -> HistoryRange(LocalDate.now().minusDays(2), LocalDate.now())
        R.id.ag_conversation_seven_days -> HistoryRange(LocalDate.now().minusDays(6), LocalDate.now())
        R.id.ag_conversation_custom -> HistoryRange(start, end)
        else -> null
    }

    private fun chooseDate(first: Boolean) {
        val day = if (first) start else end
        val dialog = DatePickerDialog(this, { _, year, month, date ->
            val chosen = LocalDate.of(year, month + 1, date)
            if (first) start = chosen else end = chosen
            updateRangeControls()
            load()
        }, day.year, day.monthValue - 1, day.dayOfMonth)
        dialog.datePicker.maxDate = (if (first) minOf(end, LocalDate.now()) else LocalDate.now())
            .plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        if (!first) dialog.datePicker.minDate = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        dialog.show()
    }

    private fun load() {
        val id = selectedId
        val request = generation.incrementAndGet()
        cloudClient?.cancel()
        selected = null
        selectedMessages = emptyList()
        localReport = null
        cloudReport = null
        detail.visibility = if (id == null) View.GONE else View.VISIBLE
        list.visibility = if (id == null) View.VISIBLE else View.GONE
        titleText.text = "会话深度分析"
        if (id == null) {
            list.removeAllViews()
            list.addView(ui.text("正在读取会话记录…", R.dimen.ag_type_label, ui.sub))
        } else {
            status.text = "正在读取会话…"
            quality.text = ""
            messages.removeAllViews()
            result.removeAllViews()
            moreButton.visibility = View.GONE
        }
        setBusy(true)
        val range = currentRange()
        val unknown = includeUndated
        val runtimeRecording = CaptureRuntime.recording
        worker.execute {
            if (!valid(request)) return@execute
            runCatching {
                if (id == null) {
                    val records = archive.recordings().map {
                        (runtimeRecording?.takeIf { current -> current.id == it.id } ?: it) to archive.count(it.id).total
                    }
                    post(request) { renderRecordings(records); setBusy(false) }
                } else {
                    val recording = archive.recording(id)?.let {
                        runtimeRecording?.takeIf { current -> current.id == id } ?: it
                    }
                    if (recording == null) {
                        post(request) {
                            selectedId = null
                            setBusy(false)
                            ui.feedback(body, "这份会话记录已被删除")
                            load()
                        }
                    } else {
                        val content = archive.recordingMessages(id, range, unknown)
                        val count = archive.count(id)
                        val raw = archive.recordingAnalysis(id)
                        val saved = readReports(raw, range, unknown, content)
                        val prefs = Prefs(this)
                        val cloudReady = prefs.cloudEnabled && prefs.hasKey()
                        post(request) {
                            selected = recording
                            selectedMessages = content
                            localReport = saved.first
                            cloudReport = saved.second
                            cloudAvailable = cloudReady
                            renderDetail(recording, count.total, count.undated, range)
                            setBusy(false)
                        }
                    }
                }
            }.onFailure {
                post(request) {
                    setBusy(false)
                    if (id == null) {
                        list.removeAllViews()
                        list.addView(ui.text("会话记录读取失败", tint = ui.color(R.color.ag_danger)))
                        list.addView(ui.button("重新读取", R.drawable.ag_rotate_ccw, false) { load() }.apply { layoutParams = ui.lp(12) })
                    } else status.text = "会话记录读取失败，请刷新重试"
                }
            }
        }
    }

    private fun renderRecordings(records: List<Pair<ChatRecording, Int>>) {
        list.removeAllViews()
        list.addView(ui.text("会话记录", R.dimen.ag_type_title, bold = true))
        if (records.isEmpty()) {
            list.addView(ui.text("暂无会话记录", tint = ui.sub).apply { layoutParams = ui.lp(24) })
            list.addView(ui.button("打开微信", R.drawable.ag_arrow_up_right, false) { openWeChat() }.apply { layoutParams = ui.lp(12) })
            return
        }
        records.forEach { (recording, count) ->
            val entry = ui.column().apply {
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
                layoutParams = ui.lp(12)
                addView(ui.row().apply {
                    addView(ui.text(recording.title, bold = true).apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ui.badge(recording.state.label))
                })
                addView(ui.text("$count 条已保存 · " + acquisitionRange(recording), R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
                if (recording.reason.isNotBlank()) addView(ui.text(recording.reason, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(6) })
            }
            ui.accessibleAction(entry, recording.title + "，" + recording.state.label + "，$count 条已保存") {
                selectedId = recording.id
                rangeMode = R.id.ag_conversation_all
                includeUndated = true
                updateRangeControls()
                load()
            }
            ui.selectable(entry)
            list.addView(entry)
        }
        GuardMotion.revealRows(list)
    }

    private fun renderDetail(recording: ChatRecording, total: Int, undated: Int, range: HistoryRange?) {
        titleText.text = recording.title
        status.text = recording.state.label + " · $total 条已保存\n采集时间 · " + acquisitionRange(recording) +
            recording.reason.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        val scope = range?.toString() ?: "全部已保存消息"
        quality.text = "消息日期 · $scope\n当前选择 " + selectedMessages.size + " 条 · 日期未确认 $undated 条" +
            (if (includeUndated) "（已包含）" else "（已排除）") +
            "\n仅包含微信曾显示并被保存的内容，覆盖不完整。" +
            (if (recording.gaps > 0) "\n页面连续性有 " + recording.gaps + " 处中断，可能有遗漏或重复。" else "")
        shownMessages = 0
        messages.removeAllViews()
        renderMoreMessages()
        renderReports()
        resumeButton.visibility = if (recording.state == RecordingState.PAUSED) View.VISIBLE else View.GONE
        stopButton.visibility = if (recording.id == CaptureRuntime.recording?.id &&
            recording.state != RecordingState.FINISHED) View.VISIBLE else View.GONE
    }

    private fun setBusy(value: Boolean) {
        busy = value
        val available = selected != null && selectedMessages.isNotEmpty()
        analyzeButton.isEnabled = !value && available
        cloudButton.isEnabled = !value && available && cloudAvailable
        cloudButton.contentDescription = if (cloudButton.isEnabled) "DeepSeek 深化，将全量统计与最多 90 条关键语境发送至 DeepSeek"
            else "DeepSeek 深化不可用，需有已保存消息并在设置中启用和配置 DeepSeek"
        listOf<View>(ranges, startButton, endButton, includeUnknown, resumeButton, stopButton, deleteButton,
            findViewById(R.id.ag_conversation_refresh)).forEach { it.isEnabled = !value }
        for (i in 0 until ranges.childCount) ranges.getChildAt(i).isEnabled = !value
        deleteButton.isEnabled = !value && selected != null
        resumeButton.isEnabled = !value && selected?.state == RecordingState.PAUSED
        moreButton.isEnabled = !value
    }

    private fun renderMoreMessages() {
        val next = minOf(shownMessages + PAGE_SIZE, selectedMessages.size)
        if (selectedMessages.isEmpty()) messages.addView(ui.text("这个范围内暂无已保存消息", R.dimen.ag_type_label, ui.sub))
        for (index in shownMessages until next) {
            val entry = selectedMessages[index]
            messages.addView(ui.column().apply {
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                addView(ui.text(messageLabel(entry), R.dimen.ag_type_caption, ui.sub))
                addView(ui.text(entry.message.text).apply {
                    layoutParams = ui.lp(6)
                    setTextIsSelectable(true)
                })
                addView(ui.divider(10))
            })
        }
        shownMessages = next
        moreButton.visibility = if (shownMessages < selectedMessages.size) View.VISIBLE else View.GONE
        moreButton.text = "加载更多原文（$shownMessages / " + selectedMessages.size + "）"
    }

    private fun confirmCloud() {
        if (!cloudButton.isEnabled) return
        MaterialAlertDialogBuilder(this).setTitle("发送选定内容进行深化？")
            .setMessage("将选定范围的全量统计与关键语境（最多 90 条消息）发送至 DeepSeek 进行分析。\n\n会话：" + selected?.title +
                "\n范围：" + (currentRange()?.toString() ?: "全部已保存消息") +
                "\n本地统计范围：" + selectedMessages.size + " 条\n本地分析结果会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("发送并分析") { _, _ -> analyze(true) }
            .show()
    }

    private fun analyze(cloud: Boolean) {
        val recording = selected ?: return
        if (busy || selectedMessages.isEmpty()) return
        val range = currentRange()
        val unknown = includeUndated
        val content = selectedMessages.toList()
        val request = generation.incrementAndGet()
        setBusy(true)
        status.text = if (cloud) "正在深化分析…" else "正在分析已保存语境…"
        val oldCloud = cloudReport
        worker.execute {
            if (!valid(request)) return@execute
            var base: RelationshipReport? = null
            runCatching {
                val prefs = Prefs(this)
                if (cloud && (!prefs.cloudEnabled || !prefs.hasKey())) {
                    post(request) {
                        status.text = "DeepSeek 当前不可用，请检查设置"
                        cloudAvailable = false
                        setBusy(false)
                    }
                    return@execute
                }
                base = RelationshipAnalysis.analyze(content, recording.title)
                if (!valid(request)) return@execute
                archive.saveRecordingAnalysis(recording.id, encodeReports(range, unknown, content, requireNotNull(base), null))
                if (cloud) {
                    // Persist the local report before the network call; network failure never replaces it.
                    post(request) { localReport = base; cloudReport = null; renderReports() }
                    val client = DeepSeekRelationshipClient(prefs.activeKey(), prefs.activeModel())
                    cloudClient = client
                    if (!valid(request)) { client.cancel(); return@execute }
                    val enhanced = try { client.analyze(content, requireNotNull(base)) } finally {
                        if (cloudClient === client) cloudClient = null
                    }
                    if (!valid(request)) return@execute
                    archive.saveRecordingAnalysis(recording.id, encodeReports(range, unknown, content, requireNotNull(base), enhanced))
                    post(request) {
                        localReport = base
                        cloudReport = enhanced
                        status.text = "DeepSeek 深化已保存 · 本地结果已保留"
                        renderReports()
                        setBusy(false)
                    }
                } else {
                    post(request) {
                        localReport = base
                        cloudReport = null
                        status.text = "本地分析已保存 · " + content.size + " 条消息"
                        renderReports()
                        setBusy(false)
                    }
                }
            }.onFailure {
                post(request) {
                    if (base != null) {
                        localReport = base
                        cloudReport = if (cloud) null else oldCloud
                        renderReports()
                    }
                    status.text = if (cloud && base != null) "深化失败，本地分析已保留；可重试"
                        else "分析未能完成，请刷新后重试"
                    setBusy(false)
                }
            }
        }
    }

    private fun renderReports() {
        result.removeAllViews()
        localReport?.let { renderReport(it, "本地分析") }
        cloudReport?.let {
            result.addView(ui.divider(20))
            renderReport(it, "DeepSeek 深化")
        }
        if (localReport == null) result.addView(ui.text("尚无当前范围的分析结果", R.dimen.ag_type_caption, ui.sub))
        GuardMotion.revealRows(result)
    }

    private fun renderReport(report: RelationshipReport, sourceLabel: String) {
        result.addView(ui.badge(sourceLabel))
        result.addView(ui.text(report.source + " · " + report.messageCount + " 条分析样本",
            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
        result.addView(ui.text(report.label, R.dimen.ag_type_heading, bold = true).apply { layoutParams = ui.lp(12) })
        result.addView(ui.text(report.summary).apply { layoutParams = ui.lp(8) })
        result.addView(ui.confidence("判断置信度", report.confidence).apply { layoutParams = ui.lp(12) })
        report.metrics.forEach { metric ->
            result.addView(ui.statusRow(metric.label, metric.value.toString(), ui.brand).apply { layoutParams = ui.lp(8) })
            if (metric.detail.isNotBlank()) result.addView(ui.text(metric.detail, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(5) })
        }
        report.findings.forEach { finding ->
            result.addView(ui.text(finding.label, bold = true).apply { layoutParams = ui.lp(18) })
            result.addView(ui.text(finding.detail, R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(6) })
            if (finding.evidence.isNotEmpty()) {
                result.addView(ui.button("查看原文依据（" + finding.evidence.size + "）", R.drawable.ag_eye, false) {
                    val evidenceBody = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20)) }
                    finding.evidence.forEach { proof ->
                        val entry = selectedMessages.firstOrNull { it.id == proof.messageId }
                        evidenceBody.addView(ui.text(entry?.let(::messageLabel) ?: (proof.day ?: "日期未确认"),
                            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(12) })
                        evidenceBody.addView(ui.text(entry?.message?.text ?: proof.quote).apply {
                            layoutParams = ui.lp(6)
                            setTextIsSelectable(true)
                        })
                    }
                    MaterialAlertDialogBuilder(this).setTitle(finding.label)
                        .setView(ui.scroll(evidenceBody)).setPositiveButton("关闭", null).show()
                }.apply { layoutParams = ui.lp(8) })
            }
        }
        if (report.suggestions.isNotEmpty()) {
            result.addView(ui.heading("下一步"))
            result.addView(ui.text(report.suggestions.joinToString("\n")).apply { layoutParams = ui.lp(8) })
        }
        if (report.limitations.isNotEmpty()) result.addView(ui.text(report.limitations.joinToString("\n"),
            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(12) })
    }

    private fun resumeRecording() {
        val recording = selected ?: return
        if (CaptureRuntime.recordingActions?.resumeRecording(recording.id) == true) {
            status.text = "已准备继续记录，请在目标微信会话的悬浮窗确认"
            ui.feedback(body, "返回微信后确认，才会继续记录")
            openWeChat()
        } else ui.feedback(body, "当前无法继续记录，请检查采集连接或其他未结束的任务")
    }

    private fun stopRecording() {
        val recording = selected ?: return
        if (recording.id != CaptureRuntime.recording?.id) return
        CaptureRuntime.recordingActions?.stopRecording()
        load()
    }

    private fun confirmDelete() {
        val recording = selected ?: return
        MaterialAlertDialogBuilder(this).setTitle("删除此会话记录？")
            .setMessage(recording.title + "\n此会话保存的聊天原文和分析结果将从本机删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (recording.id == CaptureRuntime.recording?.id) CaptureRuntime.recordingActions?.stopRecording()
                val request = generation.incrementAndGet()
                setBusy(true)
                worker.execute {
                    if (!valid(request)) return@execute
                    runCatching { archive.deleteRecording(recording.id) }.fold(
                        onSuccess = { post(request) { selectedId = null; setBusy(false); load() } },
                        onFailure = { post(request) { setBusy(false); ui.feedback(body, "删除失败，请重试") } }
                    )
                }
            }.show()
    }

    private fun openWeChat() {
        runCatching {
            startActivity(requireNotNull(packageManager.getLaunchIntentForPackage("com.tencent.mm")))
        }.onFailure { ui.feedback(body, "未能打开微信，请手动切回目标会话") }
    }

    private fun goBack() {
        if (selectedId == null) finish()
        else {
            cloudClient?.cancel()
            selectedId = null
            load()
        }
    }

    private fun valid(request: Int) = !closed && generation.get() == request
    private fun post(request: Int, action: () -> Unit) {
        main.post { if (valid(request)) action() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("recording_id", selectedId)
        outState.putInt("range_mode", rangeMode)
        outState.putString("start", start.toString())
        outState.putString("end", end.toString())
        outState.putBoolean("include_undated", includeUndated)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        closed = true
        generation.incrementAndGet()
        cloudClient?.cancel()
        main.removeCallbacksAndMessages(null)
        // Close after already queued reads/calls release this helper.
        worker.execute { archive.close() }
        worker.shutdown()
        super.onDestroy()
    }

    private fun acquisitionRange(recording: ChatRecording): String =
        time(recording.createdAt) + " 至 " + (recording.endedAt?.let(::time) ?: "尚未结束")

    private fun time(value: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(value))
    private fun messageLabel(entry: ArchivedMessage): String {
        val msg = entry.message
        val person = if (msg.side == "me") "我" else msg.sender?.takeIf { it.isNotBlank() } ?: "对方"
        return person + " · " + (msg.date ?: "日期未确认") +
            msg.timeLabel?.let { " $it" }.orEmpty() +
            (if (msg.captureMethod != "nodes") " · OCR 待核对" else "")
    }

    private fun encodeReports(range: HistoryRange?, unknown: Boolean, content: List<ArchivedMessage>,
                              local: RelationshipReport, cloud: RelationshipReport?): String = JSONObject().apply {
        put("version", 1)
        put("range", range?.let { JSONObject().put("start", it.start.toString()).put("end", it.end.toString()) }
            ?: JSONObject.NULL)
        put("includeUndated", unknown)
        put("fingerprint", fingerprint(content))
        put("report", JSONObject(local.toJson()))
        put("cloudReport", cloud?.let { JSONObject(it.toJson()) } ?: JSONObject.NULL)
    }.toString()

    private fun readReports(raw: String?, range: HistoryRange?, unknown: Boolean,
                            content: List<ArchivedMessage>): Pair<RelationshipReport?, RelationshipReport?> =
        runCatching {
            val stored = JSONObject(raw ?: return null to null)
            val savedRange = stored.optJSONObject("range")
            val rangeMatches = if (range == null) savedRange == null else
                savedRange?.optString("start") == range.start.toString() && savedRange?.optString("end") == range.end.toString()
            if (!rangeMatches || stored.optBoolean("includeUndated") != unknown ||
                stored.optString("fingerprint") != fingerprint(content)) return null to null
            RelationshipReport.fromJson(stored.getJSONObject("report").toString()) to
                stored.optJSONObject("cloudReport")?.let { RelationshipReport.fromJson(it.toString()) }
        }.getOrElse { null to null }

    private fun fingerprint(content: List<ArchivedMessage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        content.forEach { entry ->
            val item = JSONObject().put("id", entry.id).put("body", entry.message.text)
                .put("side", entry.message.side).put("sender", entry.message.sender ?: JSONObject.NULL)
                .put("date", entry.message.date ?: JSONObject.NULL).put("time", entry.message.timeLabel ?: JSONObject.NULL)
                .put("timestamp", entry.message.timestamp ?: JSONObject.NULL)
                .put("kind", entry.message.type.name).put("method", entry.message.captureMethod)
            digest.update(item.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val EXTRA_RECORDING_ID = "recording_id"
        private const val PAGE_SIZE = 50
        private fun parseDay(raw: String?): LocalDate? = raw?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }
}
