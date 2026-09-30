package com.attentionguard.app.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.attentionguard.app.CaptureActivity
import com.attentionguard.app.MarkChatActivity
import com.attentionguard.app.ConversationAnalysisActivity
import com.attentionguard.app.MessageKeywordActivity
import com.attentionguard.app.core.AttentionEngine
import com.attentionguard.app.core.CaptureOrigin
import com.attentionguard.app.core.CaptureMode
import com.attentionguard.app.core.ChatSnapshot
import com.attentionguard.app.core.ChatRecording
import com.attentionguard.app.core.RecordingState
import com.attentionguard.app.core.ConversationIdentity
import com.attentionguard.app.core.EventStore
import com.attentionguard.app.core.JevIntentEngine
import com.attentionguard.app.core.MessageArchive
import com.attentionguard.app.core.MessageKeywordRule
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.ai.DeepSeekAttentionClient
import com.attentionguard.app.overlay.AttentionOverlayController
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.UUID

/** Visible, authorized WeChat only. History scrolling requires an explicit session. */
open class ChatCaptureService : AccessibilityService(), CaptureActions, RecordingActions {
    private val main = Handler(Looper.getMainLooper())
    private val storageWorker = Executors.newSingleThreadExecutor()
    private val analysisWorker = Executors.newSingleThreadExecutor()
    private val adapter = WeChatAdapter()
    private lateinit var prefs: Prefs
    private lateinit var store: EventStore
    private lateinit var archive: MessageArchive
    private lateinit var diagnostics: CaptureDiagnostics
    private lateinit var receipt: HistoryReceipt
    private lateinit var shared: SharedPreferences
    private var overlay: AttentionOverlayController? = null
    private var snapshot: ChatSnapshot? = null
    private var inspection: ChatInspection? = null
    private var signature = ""
    private var intentSignature: String? = null
    private class PendingMark(
        val revision: Int, var windowId: Int, var snapshot: ChatSnapshot, val startedAt: Long,
        var confirming: Boolean = false
    )
    private var pendingMark: PendingMark? = null
    private var pinnedTitle: PinnedChatTitle? = null
    private var pinnedTitleOrigin = CaptureOrigin.WECHAT_MANUAL
    private var titleResolutionGeneration = 0
    private var automaticTitleGeneration = 0
    private var automaticTitleRequest: Int? = null
    private var automaticTitleScene = ""
    private var automaticTitleRetryAt = 0L
    private var currentOrigin = CaptureOrigin.WECHAT_AUTO
    private var dismissedTitle: String? = null
    private var liveSuppressedTitle: String? = null
    private var viewportRevision = 0
    private var ocr: OnDeviceChatOcr? = null
    @Volatile private var generation = 0
    @Volatile private var dataGeneration = 0
    @Volatile private var alive = false
    private var archiving = false
    private var recordingWriting = false
    private val recordingQueue = ArrayDeque<ChatSnapshot>()
    private var recordingSignature: String? = null
    private var recordingInflight: String? = null
    @Volatile private var recordingRevision = 0
    @Volatile private var observationEpoch = 0
    private var task: Future<*>? = null
    private var client: DeepSeekAttentionClient? = null
    private val debounce = Runnable { analyze() }
    private val captureSoon = Runnable { safeCapture() }
    private val poll = object : Runnable {
        override fun run() {
            if (!alive) return
            diagnostics.heartbeat(true)
            safeCapture()
            main.postDelayed(this, 1500)
        }
    }
    private val scrollStep = Runnable { scrollHistory() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("enabled", "auto_analyze", "whitelist", "message_keyword_rules", "cloud_enabled", "deepseek_encrypted_v1", "deepseek_model", "relationship", "local_ocr_enabled", "capture_mode")) {
            main.post {
                if (!alive) return@post
                if (key == "enabled" || key == "message_keyword_rules") observationEpoch++
                if (!prefs.enabled || key == "capture_mode") pauseRecording("观测已暂停或模式已切换")
                if (!prefs.enabled || key == "capture_mode" || pendingMark?.confirming == false) cancelTitleResolution()
                if (prefs.captureMode == CaptureMode.INTENT) stopHistoryForIntent()
                else pauseHistory("观测设置已变化，请重新确认回溯")
                dataGeneration++; invalidate(); ocr?.retry()
                dismissedTitle = null
                if (key == "enabled" || key == "capture_mode") updateKeepAlive()
                if (prefs.enabled) safeCapture() else diagnostics.state("观测已暂停")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (alive) return
        prefs = Prefs(this); store = EventStore(this); archive = MessageArchive(this)
        diagnostics = CaptureDiagnostics(this); receipt = HistoryReceipt(this)
        receipt.interrupted()
        CaptureRuntime.history = null
        CaptureRuntime.actions = this
        CaptureRuntime.recordingActions = this
        CaptureRuntime.recording = null
        storageWorker.execute { runCatching { archive.interruptRecordings() } }
        shared = getSharedPreferences("attention_guard", MODE_PRIVATE)
        shared.registerOnSharedPreferenceChangeListener(preferenceListener)
        alive = true
        overlay = AttentionOverlayController(this).also { panel ->
            panel.onModeChange = { mode ->
                if (mode != prefs.captureMode) {
                    if (mode == CaptureMode.INTENT) prefs.overlayCollapsed = false
                    prefs.captureMode = mode
                }
            }
            panel.onRefreshIntent = { if (prefs.captureMode == CaptureMode.INTENT) { intentSignature = null; safeCapture() } }
            panel.onRecordingToggle = {
                if (CaptureRuntime.recording?.state == RecordingState.ACTIVE) pauseRecording()
                else startRecording()
            }
            panel.onRecordingStop = { stopRecording() }
            panel.onSaveIntentEvent = { saveCurrentIntentEvents() }
            panel.onRecordingLibrary = {
                pauseRecording("已打开会话记录")
                runCatching { startActivity(Intent(this, ConversationAnalysisActivity::class.java)
                    .putExtra(ConversationAnalysisActivity.EXTRA_RECORDING_ID, CaptureRuntime.recording?.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { panel.toast("请从偷闲打开会话分析") }
            }
            panel.onMarkCurrentChat = { markCurrentChat() }
            panel.onMessageKeywordSettings = {
                runCatching { startActivity(Intent(this, MessageKeywordActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { panel.toast("请从规则与外观打开消息关键词") }
            }
            panel.onHistorySettings = {
                pauseHistory()
                runCatching { startActivity(Intent(this, CaptureActivity::class.java)
                    .putExtra(CaptureActivity.EXTRA_TITLE, snapshot?.title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                    .onFailure { overlay?.toast("请从应用的来源页打开采集与回溯") }
            }
            panel.onHistoryStart = { startHistory() }
            panel.onHistoryPause = { pauseHistory() }
            panel.onHistoryCancel = { cancelHistory() }
            panel.onDismiss = { dismissedTitle = snapshot?.title ?: "" }
        }
        ocr = OnDeviceChatOcr(this) { hidden -> overlay?.setHiddenForCapture(hidden) }
        updateKeepAlive()
        diagnostics.state("等待前台微信会话")
        main.post(poll)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !alive) return
        if (event.packageName?.toString() == adapter.pkg) viewportRevision++
        if (event.packageName?.toString() == adapter.pkg && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            cancelAutomaticTitle()
            pinnedTitle?.pauseContinuity()
            pauseRecording("微信页面已切换，请确认目标会话后继续记录")
        }
        if (event.packageName?.toString() == adapter.pkg && event.eventType in intArrayOf(
                AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) ocr?.retry()
        if (event.eventType in intArrayOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            // Throttle tree reads without indefinitely postponing a burst.
            if (!main.hasCallbacks(captureSoon)) main.postDelayed(captureSoon, 180)
        }
    }

    private fun cancelPendingAnalysis() {
        generation++
        main.removeCallbacks(debounce)
        client?.cancel(); client = null
        task?.cancel(true); task = null
    }

    private fun invalidate() {
        cancelAutomaticTitle()
        ocr?.cancel()
        cancelPendingAnalysis()
        snapshot = null; inspection = null; signature = ""; intentSignature = null
        currentOrigin = CaptureOrigin.WECHAT_AUTO
        CaptureRuntime.lastVisibleTitle = null
        overlay?.hide()
    }

    private fun cancelTitleResolution() {
        cancelAutomaticTitle()
        titleResolutionGeneration++
        if (pendingMark != null) ocr?.cancel()
        pendingMark = null
    }

    private fun updateKeepAlive() {
        if (!prefs.enabled) {
            stopService(Intent(this, KeepAliveService::class.java))
            diagnostics.keepAlive("观测已暂停")
        } else runCatching { KeepAliveService.start(this) }
            .onSuccess { diagnostics.keepAlive("启动请求已发出") }
            .onFailure { diagnostics.keepAlive("启动失败：${it.javaClass.simpleName}") }
    }

    private fun foregroundRoot(): AccessibilityNodeInfo? {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val visibleWindows = windows.map { CaptureWindow(it.root, it.type, it.layer, it.isFocused, it.isActive) }
        return ForegroundChatWindow.choose(rootInActiveWindow, visibleWindows, packageName)
    }

    private fun safeCapture() {
        if (!alive) return
        runCatching { capture() }.onFailure {
            pauseRecording("读取失败，已暂停记录")
            pauseHistory("读取暂时失败，已停止翻页")
            invalidate()
            diagnostics.state("读取失败：${it.javaClass.simpleName}")
        }
    }

    private fun capture() {
        if (!prefs.enabled) { invalidate(); diagnostics.state("观测已暂停"); return }
        val root = foregroundRoot() ?: run {
            pauseRecording("已离开微信或锁屏，请返回目标会话确认继续")
            pinnedTitle?.pauseContinuity()
            pauseHistory("已离开微信或锁屏，回到目标会话后可继续")
            if (snapshot != null) dataGeneration++
            invalidate(); dismissedTitle = null; liveSuppressedTitle = null
            diagnostics.state("等待前台微信会话")
            return
        }
        val raw = adapter.inspect(root, resources)
        val result = withPinnedTitle(raw, root.windowId)
        diagnostics.inspected(result.nodeCount, result.knownBubbles, result.structuralBubbles,
            result.snapshot?.messages?.size ?: 0, result.reason)
        raw.snapshot?.takeIf { !it.title.isNullOrBlank() && it.messages.isNotEmpty() &&
            (prefs.captureMode == CaptureMode.INTENT || prefs.isAllowed(it.title)) }?.let { known ->
            pinnedTitle = PinnedChatTitle(requireNotNull(known.title), root.windowId, known)
            pinnedTitleOrigin = CaptureOrigin.WECHAT_AUTO
        }
        if (prefs.captureMode == CaptureMode.INTENT) { captureIntent(result); return }
        val next = result.snapshot
        if (next == null || next.title.isNullOrBlank() || next.messages.isEmpty() || !prefs.isAllowed(next.title)) {
            pauseHistory("会话无法确认或没有可读消息，已停止翻页")
            if (snapshot != null) dataGeneration++
            cancelPendingAnalysis(); snapshot = null; inspection = null; signature = ""
            CaptureRuntime.lastVisibleTitle = null
            val reason = if (next != null && !next.title.isNullOrBlank() && !prefs.isAllowed(next.title)) "会话未匹配观测范围" else result.reason
            diagnostics.state(reason)
            // A blocked tree must still be diagnosable, but never treated as messages.
            if (dismissedTitle == null) overlay?.showIdle(if (next != null && !prefs.isAllowed(next.title)) next.title else null, reason)
            if (next != null && next.title.isNullOrBlank()) requestAutomaticTitle(result, root.windowId)
            else if (prefs.localOcrEnabled && next != null && prefs.isAllowed(next.title)) requestOcr(result, root.windowId)
            return
        }
        acceptSnapshot(next, result, if (result.reason == "标题由手动确认") CaptureOrigin.WECHAT_MANUAL else CaptureOrigin.WECHAT_AUTO)
    }

    private fun withPinnedTitle(result: ChatInspection, windowId: Int): ChatInspection {
        val pin = pinnedTitle ?: return result
        val current = result.snapshot ?: run { pin.pauseContinuity(); return result }
        val titled = pin.resolve(current, windowId)
        if (titled == null && pin.shouldExpire()) { pinnedTitle = null }
        if (titled == null) return result
        return result.copy(snapshot = titled, reason = if (pinnedTitleOrigin == CaptureOrigin.WECHAT_MANUAL) "标题由手动确认" else "本机标题已核对")
    }

    private fun cancelAutomaticTitle() {
        automaticTitleGeneration++
        if (automaticTitleRequest != null) ocr?.cancel()
        automaticTitleRequest = null
    }

    protected open fun readChatTitle(result: ChatInspection, stillCurrent: () -> Boolean,
                                      done: (String?, String) -> Unit) {
        ocr?.readVisibleTitle(stillCurrent, result.titleBounds, done)
    }

    private fun requestAutomaticTitle(result: ChatInspection, windowId: Int) {
        val original = result.snapshot ?: return
        pendingMark?.takeUnless(::isCurrentMark)?.let { cancelTitleResolution() }
        if (!prefs.localOcrEnabled || pendingMark != null || automaticTitleRequest != null ||
            original.messages.isEmpty() || result.knownBubbles + result.structuralBubbles == 0) return
        val scene = "$windowId|${original.messagesSignature()}"
        if (scene != automaticTitleScene) { automaticTitleScene = scene; automaticTitleRetryAt = 0L }
        val now = SystemClock.elapsedRealtime()
        if (now < automaticTitleRetryAt) return
        val request = ++automaticTitleGeneration
        automaticTitleRequest = request
        automaticTitleRetryAt = now + 3000L
        fun currentInspection(): ChatInspection? {
            if (!alive || !prefs.enabled || !prefs.localOcrEnabled || prefs.captureMode != CaptureMode.EVENT ||
                automaticTitleRequest != request || pendingMark != null) return null
            val root = foregroundRoot() ?: return null
            if (root.windowId != windowId) return null
            return adapter.inspect(root, resources).takeIf { fresh ->
                fresh.knownBubbles + fresh.structuralBubbles > 0 &&
                    fresh.snapshot?.messagesSignature() == original.messagesSignature()
            }
        }
        diagnostics.pipeline("TITLE_OCR", "STARTED")
        readChatTitle(result, { currentInspection() != null }) { proposed, _ ->
            if (automaticTitleRequest != request) return@readChatTitle
            val fresh = currentInspection()
            automaticTitleRequest = null
            automaticTitleRetryAt = SystemClock.elapsedRealtime() + if (fresh == null) 500L else 10_000L
            val title = proposed?.trim()?.takeIf { it.isNotEmpty() && !WeChatAdapter.isTruncatedTitle(it) }
            val readableTitle = fresh?.snapshot?.title
            if (fresh == null || title == null || (!readableTitle.isNullOrBlank() &&
                    !ConversationIdentity.sameTitle(title, readableTitle))) {
                diagnostics.pipeline("TITLE_OCR", if (fresh == null) "PAGE_CHANGED" else "UNREADABLE")
                return@readChatTitle
            }
            if (!prefs.isAllowed(title)) {
                diagnostics.pipeline("TITLE_OCR", "OUT_OF_SCOPE")
                diagnostics.state("本机已读到群名，但未匹配观测词条")
                overlay?.showIdle(title, "会话未匹配观测范围")
                return@readChatTitle
            }
            val titled = requireNotNull(fresh.snapshot).copy(title = title)
            pinnedTitle = PinnedChatTitle(title, windowId, titled)
            pinnedTitleOrigin = CaptureOrigin.WECHAT_AUTO
            diagnostics.pipeline("TITLE_OCR", "RESOLVED")
            acceptSnapshot(titled, fresh.copy(snapshot = titled, reason = "本机标题已核对"), CaptureOrigin.WECHAT_AUTO)
        }
    }

    private fun captureIntent(result: ChatInspection) {
        CaptureRuntime.lastVisibleTitle = null
        val current = result.snapshot?.takeIf { result.knownBubbles + result.structuralBubbles > 0 }
        if (current == null) {
            pauseRecording("当前没有可读聊天，已暂停记录")
            overlay?.setRecording(CaptureRuntime.recording)
            intentSignature = null
            diagnostics.state("意图分析 · 等待可读聊天")
            overlay?.showIdle(null, "等待当前微信聊天的可读文字", actionLabel = "刷新整屏语境")
            return
        }
        recordIntentScreen(current)
        overlay?.setRecording(CaptureRuntime.recording)
        val currentSignature = current.signature()
        if (intentSignature == currentSignature && overlay?.isShowing() == true) return
        intentSignature = currentSignature
        val insight = JevIntentEngine.analyze(current)
        diagnostics.analyzed("INTENT", current.messages.size, 0, insight?.importance?.name ?: "NONE", insight?.confidence ?: 0)
        if (insight == null) {
            diagnostics.state("意图分析 · 当前屏幕没有可读的对方文字")
            overlay?.showIdle(null, "当前屏幕没有可分析的对方文字", actionLabel = "刷新整屏语境")
        } else {
            diagnostics.state("意图分析 · 整屏语境已更新")
            overlay?.showIntent(insight, expand = false)
        }
    }

    override fun startRecording(): Boolean {
        if (!alive || !prefs.enabled || prefs.captureMode != CaptureMode.INTENT) return false
        val root = foregroundRoot() ?: return false
        val fresh = withPinnedTitle(adapter.inspect(root, resources), root.windowId).snapshot
        if (fresh == null || fresh.title.isNullOrBlank() || fresh.messages.isEmpty() ||
            WeChatAdapter.isTruncatedTitle(fresh.title)) {
            overlay?.toast("请先标记当前会话名称，再开始记录")
            return false
        }
        val existing = CaptureRuntime.recording
        if (existing != null && existing.state != RecordingState.FINISHED &&
            !ConversationIdentity.sameTitle(existing.title, fresh.title)) {
            overlay?.toast("请先结束上一会话的记录")
            return false
        }
        val next = if (existing != null && existing.state != RecordingState.FINISHED)
            existing.copy(state = RecordingState.ACTIVE, reason = "")
        else ChatRecording(UUID.randomUUID().toString(), fresh.title, System.currentTimeMillis())
        recordingRevision++; recordingSignature = null; recordingQueue.clear()
        CaptureRuntime.recording = next
        val revision = recordingRevision
        storageWorker.execute {
            val result = runCatching {
                if (existing == null || existing.state == RecordingState.FINISHED) archive.createRecording(next)
                else archive.updateRecording(next)
            }
            main.post {
                if (!alive || revision != recordingRevision) return@post
                result.onSuccess { intentSignature = null; safeCapture() }
                    .onFailure { pauseRecording("本地记录创建失败"); diagnostics.storageError(); overlay?.toast("记录保存失败，请查看诊断") }
            }
        }
        overlay?.setRecording(next)
        return true
    }

    override fun pauseRecording() = pauseRecording("由用户暂停")
    private fun pauseRecording(reason: String) {
        val current = CaptureRuntime.recording?.takeIf { it.state == RecordingState.ACTIVE } ?: return
        recordingRevision++; recordingSignature = null; recordingQueue.clear()
        val paused = current.copy(state = RecordingState.PAUSED, reason = reason)
        CaptureRuntime.recording = paused
        storageWorker.execute { runCatching { archive.updateRecording(paused) }.onFailure { diagnostics.storageError() } }
        overlay?.setRecording(paused)
    }

    override fun resumeRecording(id: String): Boolean {
        if (!alive || !prefs.enabled || prefs.captureMode != CaptureMode.INTENT) return false
        if (CaptureRuntime.recording?.state == RecordingState.ACTIVE) return false
        recordingRevision++
        val revision = recordingRevision
        storageWorker.execute {
            val stored = runCatching { archive.recording(id) }.getOrNull()
            main.post {
                if (!alive || revision != recordingRevision || stored == null || stored.state == RecordingState.FINISHED) return@post
                CaptureRuntime.recording = stored.copy(state = RecordingState.PAUSED, reason = "等待返回目标会话后确认继续")
                overlay?.setRecording(CaptureRuntime.recording)
            }
        }
        return true
    }

    override fun stopRecording() {
        val current = CaptureRuntime.recording ?: return
        recordingRevision++; recordingSignature = null; recordingQueue.clear()
        val ended = current.copy(state = RecordingState.FINISHED, endedAt = System.currentTimeMillis(), reason = "由用户结束")
        CaptureRuntime.recording = ended
        storageWorker.execute { runCatching { archive.updateRecording(ended) }.onFailure { diagnostics.storageError() } }
        overlay?.setRecording(ended)
    }

    private fun recordIntentScreen(current: ChatSnapshot) {
        val active = CaptureRuntime.recording?.takeIf { it.state == RecordingState.ACTIVE } ?: return
        if (!IntentRecording.accepts(active, current)) { pauseRecording("会话无法确认或已切换，请返回目标会话确认继续"); return }
        val signature = current.messagesSignature()
        if (signature == recordingSignature) return
        if (recordingWriting) {
            if (signature == recordingInflight) return
            if (recordingQueue.lastOrNull()?.messagesSignature() == signature) return
            if (recordingQueue.size >= 12) { pauseRecording("滚动过快，保存队列已满；请放慢并确认继续"); return }
            recordingQueue.addLast(current)
            return
        }
        recordingWriting = true
        recordingInflight = signature
        val revision = recordingRevision
        storageWorker.execute {
            val result = runCatching {
                archive.append(current, active.id, requireRecording = true, canWrite = {
                    alive && prefs.enabled && prefs.captureMode == CaptureMode.INTENT && revision == recordingRevision &&
                        CaptureRuntime.recording?.state == RecordingState.ACTIVE
                })
            }
            main.post {
                recordingWriting = false
                recordingInflight = null
                if (!alive) return@post
                if (revision != recordingRevision) { drainRecordingQueue(); return@post }
                result.onSuccess { write ->
                    recordingSignature = signature
                    diagnostics.saved(write.added)
                    if (write.gap) {
                        val updated = active.copy(gaps = active.gaps + 1)
                        CaptureRuntime.recording = updated
                        storageWorker.execute { runCatching { archive.updateRecording(updated) } }
                    }
                    overlay?.setRecording(CaptureRuntime.recording)
                }.onFailure { diagnostics.storageError(); pauseRecording("保存失败，已暂停记录") }
                drainRecordingQueue()
            }
        }
    }

    private fun drainRecordingQueue() {
        while (!recordingWriting && recordingQueue.isNotEmpty()) recordIntentScreen(recordingQueue.removeFirst())
    }

    private fun stopHistoryForIntent() {
        main.removeCallbacks(scrollStep)
        CaptureRuntime.history?.let { it.cancel(); receipt.save(it) }
        CaptureRuntime.history = null
        liveSuppressedTitle = null
    }

    private fun requestOcr(result: ChatInspection, windowId: Int) {
        if (pendingMark != null || !OnDeviceChatOcr.eligible(result) || CaptureRuntime.history?.let { it.state != HistoryState.CANCELLED && it.state != HistoryState.FINISHED } == true) return
        cancelAutomaticTitle()
        val revision = viewportRevision
        val title = result.snapshot?.title
        ocr?.capture(result, stillCurrent = {
            alive && prefs.enabled && prefs.localOcrEnabled && revision == viewportRevision && prefs.isAllowed(title) &&
                runCatching {
                    val root = foregroundRoot()
                    root != null && root.windowId == windowId && adapter.inspect(root, resources).let { fresh ->
                        fresh.snapshot?.title == title && fresh.ocrRegions == result.ocrRegions
                    }
                }.getOrDefault(false)
        }) { recognized, reason ->
            diagnostics.inspected(result.nodeCount, result.knownBubbles, 0, recognized?.messages?.size ?: 0, reason)
            if (recognized != null && recognized.messages.isNotEmpty()) acceptSnapshot(recognized, result,
                if (result.reason == "标题由手动确认") CaptureOrigin.WECHAT_MANUAL else CaptureOrigin.WECHAT_AUTO)
            else { diagnostics.state(reason); if (dismissedTitle == null) overlay?.showIdle(null, reason) }
        }
    }

    private fun acceptSnapshot(next: ChatSnapshot, result: ChatInspection, origin: CaptureOrigin) {
        if (snapshot?.title != next.title) {
            dataGeneration++; invalidate(); dismissedTitle = null
            if (liveSuppressedTitle != next.title) liveSuppressedTitle = null
        }
        CaptureRuntime.lastVisibleTitle = next.title
        currentOrigin = origin
        snapshot = next; inspection = result
        val session = CaptureRuntime.history
        if (session?.state == HistoryState.RUNNING && session.config.title != next.title) pauseHistory("会话已切换，返回目标会话后可继续")
        val relevantHistory = session?.takeIf { it.config.title == next.title && it.state != HistoryState.CANCELLED }
        val fromOcr = next.messages.any { it.captureMethod == "ocr" }
        diagnostics.state(if (fromOcr) "本机 OCR 采集，内容待核对" else if (relevantHistory?.state == HistoryState.RUNNING) "历史回溯中" else if (origin == CaptureOrigin.WECHAT_MANUAL) "标题手动确认，正在监测可见消息" else "正在监测可见消息")
        val changed = next.signature() != signature
        if (changed || overlay?.isShowing() != true || relevantHistory != null) {
            if (dismissedTitle != next.title) overlay?.showIdle(next.title,
                if (fromOcr) "OCR ${next.messages.size} 条，内容待核对" else "已识别 ${next.messages.size} 条可见消息${if (origin == CaptureOrigin.WECHAT_MANUAL) " · 标题手动确认" else ""}", relevantHistory,
                actionLabel = "切换意图分析")
        }
        // A configured/paused history session must not leak historical content into live AI events.
        if (relevantHistory != null && relevantHistory.state != HistoryState.RUNNING) return
        if (!changed || archiving) return
        cancelPendingAnalysis()
        signature = next.signature()
        val dataRequest = dataGeneration
        val activeSession = relevantHistory?.takeIf { it.state == HistoryState.RUNNING }
        val stream = activeSession?.id ?: "live:${next.sourcePackage}:${next.title}"
        archiving = true
        storageWorker.execute {
            if (!alive || dataRequest != dataGeneration || !prefs.enabled || prefs.captureMode != CaptureMode.EVENT) {
                main.post { archiving = false }
                return@execute
            }
            val outcome = runCatching {
                archive.append(next, stream, activeSession?.config?.range) {
                    alive && dataRequest == dataGeneration && prefs.enabled && prefs.captureMode == CaptureMode.EVENT
                }
            }
            main.post {
                archiving = false
                if (!alive || dataRequest != dataGeneration) return@post
                outcome.onSuccess { write ->
                    diagnostics.saved(write.added)
                    if (activeSession != null && CaptureRuntime.history === activeSession) {
                        activeSession.observe(next, write.gap); receipt.save(activeSession)
                        showHistory()
                    } else if (prefs.captureMode == CaptureMode.EVENT && prefs.autoAnalyze && !fromOcr && liveSuppressedTitle != next.title) {
                        // Persist local event immediately; cloud refinement remains debounced.
                        analyzeLocal(next, origin)
                        main.postDelayed(debounce, 2200)
                    }
                }.onFailure {
                    signature = ""; diagnostics.storageError()
                    pauseHistory("本机保存失败，已停止翻页")
                    overlay?.showIdle(next.title, "保存失败，请检查本机存储")
                }
            }
        }
    }

    private fun analyzeLocal(current: ChatSnapshot, origin: CaptureOrigin) {
        if (current.messages.any { it.captureMethod != "nodes" } || liveSuppressedTitle == current.title || prefs.captureMode != CaptureMode.EVENT) return
        val events = AttentionEngine.buildEvents(current, prefs.relationship, origin, prefs.messageKeywordRules)
        val base = AttentionEngine.primary(events)
        diagnostics.analyzed("EVENT", current.messages.size, events.size, base?.category?.name ?: "NONE", base?.attentionScore ?: 0)
        diagnostics.pipeline("LOCAL_IDENTIFIED", if (base == null) "NO_EVENT" else "READY_TO_SAVE", events.size)
        if (base == null) return
        val request = dataGeneration
        val epoch = observationEpoch
        storageWorker.execute {
            if (!alive || epoch != observationEpoch || !prefs.enabled) {
                diagnostics.pipeline("LOCAL_SAVE", "CANCELLED", events.size)
                return@execute
            }
            val saved = runCatching { store.upsertAll(events) }
            saved.onSuccess { result -> diagnostics.eventSaved(result, events.size) }
                .onFailure { diagnostics.pipeline("LOCAL_SAVE", "FAILED", events.size, it.javaClass.simpleName) }
            main.post {
                if (!alive || request != dataGeneration || prefs.captureMode != CaptureMode.EVENT ||
                    snapshot?.signature() != current.signature()) return@post
                if (saved.isFailure) diagnostics.storageError()
                else if (dismissedTitle != current.title) overlay?.showEvent(saved.getOrThrow().first { it.id == base.id })
            }
        }
    }

    private fun saveCurrentIntentEvents() {
        if (!alive || !prefs.enabled || prefs.captureMode != CaptureMode.INTENT) return
        val root = foregroundRoot() ?: return
        val fresh = withPinnedTitle(adapter.inspect(root, resources), root.windowId).snapshot ?: return
        if (fresh.title.isNullOrBlank() || fresh.messages.any { it.captureMethod != "nodes" }) {
            overlay?.toast("请先确认会话名称和原文")
            return
        }
        val events = AttentionEngine.buildEvents(fresh, prefs.relationship, CaptureOrigin.WECHAT_MANUAL, prefs.messageKeywordRules)
        if (events.isEmpty()) { overlay?.toast("当前整屏没有明确可保存事项"); return }
        val epoch = observationEpoch
        storageWorker.execute {
            if (!alive || !prefs.enabled || epoch != observationEpoch) return@execute
            val result = runCatching { store.upsertAll(events) }
            result.onSuccess { diagnostics.eventSaved(it, events.size) }
                .onFailure { diagnostics.pipeline("EVENT_SAVE", "FAILED", events.size, it.javaClass.simpleName) }
            main.post {
                if (alive) overlay?.toast(if (result.isSuccess) "已保存 ${events.size} 个事项到观测簿" else "事项保存失败，请导出诊断")
            }
        }
    }

    private fun markCurrentChat() {
        if (!alive || !prefs.enabled) { overlay?.toast("请先开启观测"); return }
        cancelTitleResolution()
        val root = foregroundRoot()
        val inspection = root?.let { withPinnedTitle(adapter.inspect(it, resources), it.windowId) }
        val fresh = inspection?.snapshot
        val title = fresh?.takeIf { it.sourcePackage == adapter.pkg }?.title?.takeIf { it.isNotBlank() }
        if (title == null) {
            if (root == null || fresh == null || fresh.messages.isEmpty() || inspection.knownBubbles + inspection.structuralBubbles == 0) {
                overlay?.toast("请先打开微信聊天再标记")
                return
            }
            val pending = PendingMark(titleResolutionGeneration, root.windowId, fresh, SystemClock.elapsedRealtime())
            pendingMark = pending
            if (!readGroupTitleFromChatInfo(pending)) requestOcrTitle(pending)
            return
        }
        runCatching { prefs.addRecognitionTerm(title) }
            .onSuccess { result ->
                pinnedTitle = PinnedChatTitle(title, root.windowId, fresh)
                pinnedTitleOrigin = CaptureOrigin.WECHAT_MANUAL
                overlay?.toast(when (result) {
                    Prefs.RecognitionTermResult.ADDED_FIRST -> "已标记“$title”；观测范围已收窄到该词条"
                    Prefs.RecognitionTermResult.ADDED -> "已将“$title”加入识别词条"
                    Prefs.RecognitionTermResult.EXISTS -> "“$title”已在识别词条中"
                })
            }
            .onFailure { overlay?.toast("识别词条保存失败，请重试") }
    }

    private fun isCurrentMark(pending: PendingMark): Boolean = alive && prefs.enabled &&
        pendingMark === pending && pending.revision == titleResolutionGeneration &&
        SystemClock.elapsedRealtime() - pending.startedAt <= 5 * 60_000L

    private fun sameVisibleChat(pending: PendingMark): Boolean {
        if (!isCurrentMark(pending)) return false
        val root = foregroundRoot() ?: return false
        val fresh = adapter.inspect(root, resources)
        return fresh.knownBubbles + fresh.structuralBubbles > 0 && fresh.snapshot?.let {
            PinnedChatTitle.matchesMessages(pending.snapshot, it, root.windowId == pending.windowId)
        } == true
    }

    private fun abandonMark(pending: PendingMark) {
        if (pendingMark !== pending) return
        cancelTitleResolution()
        overlay?.toast("会话已变化或未能返回，请回到目标聊天重新标记")
    }

    private fun requestOcrTitle(pending: PendingMark) {
        if (!isCurrentMark(pending)) return
        if (!sameVisibleChat(pending)) { abandonMark(pending); return }
        val bounds = foregroundRoot()?.let { adapter.inspect(it, resources).titleBounds }
        ocr?.readVisibleTitle({ sameVisibleChat(pending) }, bounds) { suggested, reason ->
            if (!isCurrentMark(pending)) return@readVisibleTitle
            if (reason == "会话已变化" || !sameVisibleChat(pending)) {
                abandonMark(pending)
                return@readVisibleTitle
            }
            // An ellipsis is not a complete name, even if OCR returned it as text.
            launchMarkActivity(pending, suggested?.takeUnless(WeChatAdapter::isTruncatedTitle), "本机标题识别")
        }
    }

    /**
     * WeChat paints long group names into an empty ActionBar container. Its
     * chat-info page exposes the authoritative full name in android:id/summary.
     * This is only opened after the user explicitly asks to mark the current chat.
     */
    private fun readGroupTitleFromChatInfo(pending: PendingMark): Boolean {
        val root = foregroundRoot() ?: return false
        val info = findNode(root) { node ->
            node.isVisibleToUser && node.isClickable && !node.isEditable &&
                node.packageName?.toString() == adapter.pkg &&
                (node.viewIdResourceName == CHAT_INFO_BUTTON_ID ||
                    node.contentDescription?.toString() == "聊天信息")
        } ?: return false
        if (!info.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
        main.postDelayed({ pollChatInfoTitle(pending, 0) }, CHAT_INFO_POLL_MS)
        return true
    }

    private fun pollChatInfoTitle(pending: PendingMark, attempt: Int) {
        if (!isCurrentMark(pending)) return
        val root = foregroundRoot()
        val page = root?.let(WeChatChatInfo::read)
        if (page?.groupTitle != null || (page != null && attempt >= CHAT_INFO_TIMEOUT_ATTEMPTS)) {
            // Never press Back on a different app or an unrelated WeChat page.
            if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                main.postDelayed({ waitForOriginalChat(pending, page.groupTitle, 0) }, CHAT_INFO_POLL_MS)
            } else abandonMark(pending)
            return
        }
        if (attempt >= CHAT_INFO_TIMEOUT_ATTEMPTS) {
            if (sameVisibleChat(pending)) requestOcrTitle(pending) else abandonMark(pending)
            return
        }
        if (root != null && page == null && !sameVisibleChat(pending) && adapter.inspect(root, resources).snapshot != null) {
            abandonMark(pending)
            return
        }
        main.postDelayed({ pollChatInfoTitle(pending, attempt + 1) }, CHAT_INFO_POLL_MS)
    }

    private fun waitForOriginalChat(pending: PendingMark, title: String?, attempt: Int) {
        if (!isCurrentMark(pending)) return
        if (sameVisibleChat(pending)) {
            if (title == null) requestOcrTitle(pending)
            else launchMarkActivity(pending, title, "微信聊天信息")
            return
        }
        if (attempt >= CHAT_INFO_TIMEOUT_ATTEMPTS) {
            abandonMark(pending)
            return
        }
        main.postDelayed({
            waitForOriginalChat(pending, title, attempt + 1)
        }, CHAT_INFO_POLL_MS)
    }

    private fun launchMarkActivity(pending: PendingMark, suggested: String?, source: String) {
        if (!isCurrentMark(pending) || pending.confirming) return
        if (!sameVisibleChat(pending)) { abandonMark(pending); return }
        val root = foregroundRoot() ?: return
        val current = adapter.inspect(root, resources).snapshot ?: return
        if (suggested != null && !current.title.isNullOrBlank() &&
            !ConversationIdentity.sameTitle(suggested, current.title)) {
            abandonMark(pending)
            return
        }
        pending.snapshot = current
        pending.windowId = root.windowId
        pending.confirming = true
        runCatching {
            startActivity(Intent(this, MarkChatActivity::class.java)
                .putExtra(MarkChatActivity.EXTRA_SUGGESTED_TITLE, suggested.orEmpty())
                .putExtra(MarkChatActivity.EXTRA_TITLE_SOURCE, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        }.onFailure {
            cancelTitleResolution()
            overlay?.toast("暂时无法打开会话标记页")
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walkNodes(root) { node -> if (found == null && predicate(node)) found = node }
        return found
    }

    private fun walkNodes(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 3000) {
            val node = stack.removeLast()
            visit(node)
            for (index in node.childCount - 1 downTo 0) node.getChild(index)?.let(stack::addLast)
        }
    }

    override fun confirmCurrentTitle(title: String): Boolean {
        val pending = pendingMark ?: return false
        val valid = isCurrentMark(pending) && pending.confirming
        cancelTitleResolution()
        if (!valid || title.isBlank() || title.length > 120 || WeChatAdapter.isTruncatedTitle(title)) return false
        pinnedTitle = PinnedChatTitle(title.trim(), pending.windowId, pending.snapshot)
        pinnedTitleOrigin = CaptureOrigin.WECHAT_MANUAL
        return true
    }

    override fun cancelCurrentTitle() { cancelTitleResolution() }

    private fun analyze() {
        val current = snapshot ?: return
        if (current.messages.any { it.captureMethod != "nodes" } || liveSuppressedTitle == current.title) return
        if (!alive || !prefs.enabled || prefs.captureMode != CaptureMode.EVENT || !prefs.isAllowed(current.title) || CaptureRuntime.history?.let { it.config.title == current.title && it.state != HistoryState.CANCELLED } == true) return
        val groupContext = prefs.relationship
        val base = AttentionEngine.primary(AttentionEngine.buildEvents(current, groupContext, currentOrigin, prefs.messageKeywordRules)) ?: return
        if (base.reviewNotes.any { it.startsWith(MessageKeywordRule.EVIDENCE_PREFIX) }) return
        if (!prefs.cloudEnabled || !prefs.hasKey() || !AttentionEngine.shouldNotify(base)) return
        cancelPendingAnalysis()
        val request = generation
        val api = DeepSeekAttentionClient(prefs.activeKey(), prefs.activeModel())
        client = api
        if (dismissedTitle != current.title) overlay?.showLoading(true)
        task = analysisWorker.submit {
            val result = runCatching { api.enrich(current, base, groupContext) }
            main.post {
                if (!alive || request != generation || !prefs.enabled || prefs.captureMode != CaptureMode.EVENT || snapshot?.signature() != current.signature()) return@post
                task = null; client = null
                result.onSuccess { enriched ->
                    storageWorker.execute {
                        if (!alive || request != generation || prefs.captureMode != CaptureMode.EVENT) return@execute
                        val saved = runCatching { store.upsert(enriched) }
                        saved.onSuccess { diagnostics.eventSaved(it, 1) }
                            .onFailure { diagnostics.pipeline("EVENT_SAVE", "FAILED", 1, it.javaClass.simpleName) }
                        main.post stored@{
                            if (!alive || request != generation || prefs.captureMode != CaptureMode.EVENT) return@stored
                            if (saved.isFailure) diagnostics.storageError()
                            else if (dismissedTitle != current.title) overlay?.showEvent(saved.getOrThrow().first { it.id == enriched.id })
                        }
                    }
                }.onFailure {
                    if (dismissedTitle != current.title) overlay?.showEvent(base)
                    overlay?.toast("DeepSeek 暂不可用，本地消息和事件已保留")
                }
            }
        }
    }

    override fun armHistory(config: HistoryConfig): Boolean {
        if (!alive || !prefs.enabled || prefs.captureMode != CaptureMode.EVENT || !prefs.isAllowed(config.title) || config.range.end > java.time.LocalDate.now()) return false
        CaptureRuntime.history?.let { if (it.state in setOf(HistoryState.READY, HistoryState.RUNNING, HistoryState.PAUSED)) return false }
        cancelPendingAnalysis(); dataGeneration++
        CaptureRuntime.history = HistorySession(config).also { receipt.save(it) }
        dismissedTitle = null; signature = ""
        return true
    }

    private fun startHistory() {
        if (prefs.captureMode != CaptureMode.EVENT) return
        safeCapture()
        val current = snapshot ?: return
        val session = CaptureRuntime.history ?: return
        if (!prefs.enabled || !prefs.isAllowed(current.title)) return
        if (!session.start(current.title.orEmpty(), SystemClock.elapsedRealtime())) {
            receipt.save(session); showHistory(); return
        }
        liveSuppressedTitle = current.title
        dataGeneration++; cancelPendingAnalysis(); signature = ""
        receipt.save(session); safeCapture(); showHistory()
        main.removeCallbacks(scrollStep)
        if (session.config.automatic) main.postDelayed(scrollStep, 4000)
    }

    override fun pauseHistory() = pauseHistory("由用户暂停")
    private fun pauseHistory(reason: String) {
        main.removeCallbacks(scrollStep)
        val session = CaptureRuntime.history ?: return
        if (session.state != HistoryState.RUNNING) return
        dataGeneration++; session.pause(reason); receipt.save(session); showHistory()
    }
    override fun cancelHistory() {
        main.removeCallbacks(scrollStep)
        CaptureRuntime.history?.let { it.cancel(); receipt.save(it) }
        dataGeneration++; signature = ""; dismissedTitle = null
        safeCapture()
    }
    private fun showHistory() {
        val session = CaptureRuntime.history ?: return
        if (session.config.title == snapshot?.title && dismissedTitle != snapshot?.title)
            overlay?.showIdle(snapshot?.title, "仅保存在本机", session)
    }
    private fun scrollHistory() {
        if (prefs.captureMode != CaptureMode.EVENT) return
        safeCapture()
        val session = CaptureRuntime.history ?: return
        if (!alive || !prefs.enabled || session.state != HistoryState.RUNNING || snapshot?.title != session.config.title) return
        if (overlay?.isShowing() != true) { pauseHistory("悬浮控制不可用，已停止自动翻页"); return }
        if (archiving) { main.postDelayed(scrollStep, 1000); return }
        if (!session.canScroll(SystemClock.elapsedRealtime())) { receipt.save(session); showHistory(); return }
        // Reacquired in safeCapture; no input, click, paste or coordinate gestures.
        val target = inspection?.scrollTarget
        val ok = target != null && runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }.getOrDefault(false)
        if (!ok) { pauseHistory("聊天列表不支持向前翻页，请改用手动回溯"); return }
        receipt.save(session); showHistory()
        main.postDelayed(scrollStep, 4000)
    }

    override fun onInterrupt() {
        if (!alive) return
        cancelTitleResolution()
        pauseRecording("无障碍服务被中断")
        pauseHistory("无障碍服务被中断"); dataGeneration++; invalidate()
        diagnostics.state("服务被中断，正在等待恢复")
    }
    override fun onDestroy() {
        cancelTitleResolution()
        if (alive) {
            pauseRecording("服务已断开，请返回目标会话确认继续")
            pauseHistory("服务已断开，需重新开始任务")
            alive = false; dataGeneration++
            shared.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            invalidate(); diagnostics.heartbeat(false)
            CaptureRuntime.actions = null; CaptureRuntime.lastVisibleTitle = null
            CaptureRuntime.recordingActions = null; CaptureRuntime.recording = null
            receipt.interrupted(); CaptureRuntime.history = null
            storageWorker.execute { archive.close() }
            stopService(Intent(this, KeepAliveService::class.java))
        }
        main.removeCallbacksAndMessages(null)
        ocr?.close(); ocr = null
        overlay = null
        storageWorker.shutdown(); analysisWorker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHAT_INFO_BUTTON_ID = "com.tencent.mm:id/fq"
        private const val CHAT_INFO_POLL_MS = 220L
        private const val CHAT_INFO_TIMEOUT_ATTEMPTS = 14
    }
}
