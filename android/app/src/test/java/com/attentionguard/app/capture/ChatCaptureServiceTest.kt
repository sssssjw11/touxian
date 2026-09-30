package com.attentionguard.app.capture

import android.graphics.Rect
import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.material.button.MaterialButton
import com.attentionguard.app.core.MessageArchive
import com.attentionguard.app.core.CaptureMode
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.core.EventStore
import com.attentionguard.app.MarkChatActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class TestCaptureService : ChatCaptureService() {
    var activeRoot: AccessibilityNodeInfo? = null
    val screenshots = mutableListOf<TakeScreenshotCallback>()
    var fakeTitleReader = false
    val titleReads = mutableListOf<Pair<() -> Boolean, (String?, String) -> Unit>>()
    override fun readChatTitle(result: ChatInspection, stillCurrent: () -> Boolean, done: (String?, String) -> Unit) {
        if (fakeTitleReader) titleReads.add(stillCurrent to done) else super.readChatTitle(result, stillCurrent, done)
    }
    override fun getRootInActiveWindow() = activeRoot
    override fun getWindows() = emptyList<AccessibilityWindowInfo>()
    override fun takeScreenshot(displayId: Int, executor: Executor, callback: TakeScreenshotCallback) {
        screenshots.add(callback)
    }
    fun connect() { super.onServiceConnected() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class ChatCaptureServiceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val controller = Robolectric.buildService(TestCaptureService::class.java).create()
    private val service = controller.get()
    private val windows: ShadowWindowManagerImpl get() = Shadow.extract(service.getSystemService(WindowManager::class.java))
    @Suppress("DEPRECATION")
    private fun node(text: String? = null, rect: Rect = Rect(0, 0, 360, 800)) = AccessibilityNodeInfo.obtain().apply {
        this.text = text; packageName = "com.tencent.mm"; isVisibleToUser = true; setBoundsInScreen(rect)
    }
    private fun root(title: String = "Test group", message: String = "hello", onScroll: (() -> Boolean)? = null): AccessibilityNodeInfo = node().apply {
        shadowOf(this).addChild(node(title, Rect(80, 35, 280, 68)))
        val list = node(rect = Rect(0, 110, 360, 700)).apply { isScrollable = true; className = "android.widget.ListView" }
        if (onScroll != null) shadowOf(list).setOnPerformActionListener { action, _ ->
            assertEquals(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, action)
            onScroll()
        }
        shadowOf(list).addChild(node(message, Rect(40, 220, 210, 270)).apply { viewIdResourceName = "com.tencent.mm:id/bkl" })
        shadowOf(this).addChild(list)
    }
    private fun drain() {
        val field = ChatCaptureService::class.java.getDeclaredField("storageWorker").apply { isAccessible = true }
        repeat(3) {
            val latch = CountDownLatch(1)
            (field.get(service) as ExecutorService).execute { latch.countDown() }
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun tick(seconds: Long = 2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds)); drain() }
    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); drain() }
    private fun mark() = windows.views.asSequence().flatMap { children(it) }
        .first { it.contentDescription == "标记当前微信会话" }.performClick()
    private fun withInfoButton(chat: AccessibilityNodeInfo, onClick: () -> Unit): AccessibilityNodeInfo = chat.apply {
        val button = node(rect = Rect(310, 35, 360, 85)).apply {
            viewIdResourceName = "com.tencent.mm:id/fq"; isClickable = true; contentDescription = "聊天信息"
        }
        shadowOf(button).setOnPerformActionListener { action, _ ->
            assertEquals(AccessibilityNodeInfo.ACTION_CLICK, action)
            onClick(); true
        }
        shadowOf(this).addChild(button)
    }
    private fun infoPage(title: String = "校园创想课程讨论群"): AccessibilityNodeInfo = node().apply {
        shadowOf(this).addChild(node("聊天信息", Rect(80, 35, 280, 68)))
        for ((label, value) in listOf("群公告" to "不要误取这条公告", "群聊名称" to title)) {
            val row = node()
            shadowOf(row).addChild(node(label).apply { viewIdResourceName = "android:id/title" })
            shadowOf(row).addChild(node(value).apply { viewIdResourceName = "android:id/summary" })
            shadowOf(this).addChild(row)
        }
    }
    @After fun cleanup() { controller.destroy(); CaptureRuntime.actions = null; CaptureRuntime.history = null }

    @Test fun entryShowsAccessibilityOverlayImmediatelyAndRecordsOrdinaryChat() {
        ShadowSettings.setCanDrawOverlays(false)
        service.activeRoot = root(); service.connect(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(windows.views.isNotEmpty())
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, (windows.views.first().layoutParams as WindowManager.LayoutParams).type)
        drain()
        MessageArchive(context).use { assertEquals("hello", it.recent().single().message.text) }
        assertTrue(CaptureDiagnostics(context).summary().contains("已读取"))
    }
    @Test fun pollingRecoversFromInitiallyMissingTreeWithoutANewEvent() {
        service.connect(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(windows.views.isEmpty())
        service.activeRoot = root(); tick()
        MessageArchive(context).use { assertEquals(1, it.count().total) }
        assertTrue(windows.views.isNotEmpty())
    }
    @Test fun anAllowedUnreadableTitleIsRecoveredLocallyWithoutReaddingTerms() {
        Prefs(context).whitelist = setOf("Building group")
        Prefs(context).localOcrEnabled = true
        service.fakeTitleReader = true
        service.activeRoot = root(title = "", message = "请大家明天提交作业")
        service.connect(); tick()
        assertEquals(1, service.titleReads.size)
        assertTrue(EventStore(context).load().isEmpty())
        assertTrue(service.titleReads.single().first())
        service.titleReads.single().second("Building group(457)", "本机标题识别完成")
        drain()
        assertEquals(1, EventStore(context).load().size)
        MessageArchive(context).use { assertEquals("Building group(457)", it.recent().single().group) }
    }
    @Test fun fullScreenScrollWithoutOverlapRechecksTheHeaderAndKeepsCapturing() {
        Prefs(context).whitelist = setOf("Building group")
        Prefs(context).localOcrEnabled = true
        service.fakeTitleReader = true
        service.activeRoot = root(title = "", message = "请大家明天提交作业")
        service.connect(); tick()
        service.titleReads[0].second("Building group(457)", "ok"); drain()
        service.activeRoot = root(title = "", message = "后天上午十点到会议室参加会议")
        tick()
        assertEquals(2, service.titleReads.size)
        service.titleReads[1].second("Building group(457)", "ok"); drain()
        MessageArchive(context).use { assertEquals(2, it.count().total) }
        assertTrue(CaptureDiagnostics(context).summary().contains("正在监测可见消息"))
    }
    @Test fun anOcrTitleFromThePreviousScreenCannotBindTheNextConversation() {
        Prefs(context).whitelist = setOf("Building group")
        Prefs(context).localOcrEnabled = true
        service.fakeTitleReader = true
        service.activeRoot = root(title = "", message = "请大家明天提交作业")
        service.connect(); tick()
        service.activeRoot = root(title = "", message = "另一个会话的内容完全不同")
        assertFalse(service.titleReads[0].first())
        service.titleReads[0].second("Building group", "ok"); drain()
        assertTrue(EventStore(context).load().isEmpty())
        tick()
        service.titleReads.last().second("Outside group", "ok"); drain()
        MessageArchive(context).use { assertEquals(0, it.count().total) }
        assertTrue(CaptureDiagnostics(context).summary().contains("未匹配"))
    }
    @Test fun markingAReadableTitleAlsoPinsItForTheNextEmptyTitleFrame() {
        service.fakeTitleReader = true
        val message = "请大家明天提交课程作业并在群里确认"
        service.activeRoot = root(title = "Building group", message = message)
        service.connect(); tick()
        ChatCaptureService::class.java.getDeclaredMethod("markCurrentChat").apply { isAccessible = true }.invoke(service)
        service.activeRoot = root(title = "", message = message)
        tick()
        assertTrue(service.titleReads.isEmpty())
        assertTrue(CaptureDiagnostics(context).summary().contains("标题由手动确认"))
        assertTrue(Prefs(context).isAllowed("Building group"))
    }
    @Test fun automaticTitleFailureIsThrottledAndDoesNotGuessFromTheWhitelist() {
        Prefs(context).whitelist = setOf("Only allowed group")
        Prefs(context).localOcrEnabled = true
        service.fakeTitleReader = true
        service.activeRoot = root(title = "", message = "请大家明天提交作业")
        service.connect(); tick()
        service.titleReads[0].second(null, "标题未能自动识别")
        tick(); tick()
        assertEquals(1, service.titleReads.size)
        assertTrue(EventStore(context).load().isEmpty())
        tick(10)
        assertEquals(2, service.titleReads.size)
    }
    @Test fun navigatingWithIdenticalBubblesCancelsTheOldTitleRequest() {
        Prefs(context).whitelist = setOf("Building group")
        Prefs(context).localOcrEnabled = true
        service.fakeTitleReader = true
        service.activeRoot = root(title = "", message = "请大家明天提交作业")
        service.connect(); tick()
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = "com.tencent.mm" }
        service.onAccessibilityEvent(event)
        assertFalse(service.titleReads[0].first())
        service.titleReads[0].second("Building group", "ok"); drain()
        assertTrue(EventStore(context).load().isEmpty())
    }
    @Test fun intentRecordingSurvivesATitleRepaintWithTheSameMessages() {
        Prefs(context).captureMode = CaptureMode.INTENT
        service.activeRoot = root(title = "Building group", message = "请大家明天提交课程作业并在群里确认")
        service.connect(); tick()
        assertTrue(service.startRecording()); tick()
        service.activeRoot = root(title = "", message = "请大家明天提交课程作业并在群里确认")
        tick()
        assertEquals(com.attentionguard.app.core.RecordingState.ACTIVE, CaptureRuntime.recording?.state)
        MessageArchive(context).use { assertEquals(1, it.count().total) }
    }
    @Test fun cancellingTheMarkPageDoesNotBlockFutureAutomaticTitleReads() {
        service.activeRoot = root(title = "", message = "请在周三下午之前提交课程作业")
        service.connect(); tick(); mark(); advance(300)
        service.screenshots.single().onFailure(2); advance(1)
        val confirmation = shadowOf(service).nextStartedActivity!!
        val activity = Robolectric.buildActivity(MarkChatActivity::class.java, confirmation).setup()
        activity.get().finish(); activity.pause().stop().destroy()
        service.fakeTitleReader = true
        Prefs(context).localOcrEnabled = true
        tick()
        assertEquals(1, service.titleReads.size)
    }
    @Test fun addingAMessageRuleReanalyzesTheCurrentScreenWithoutChangingConversationScope() {
        Prefs(context).whitelist = setOf("Building group")
        service.activeRoot = root(title = "Building group", message = "楼下还有矿泉水")
        service.connect(); tick()
        assertTrue(EventStore(context).load().isEmpty())
        Prefs(context).messageKeywordRules = listOf(com.attentionguard.app.core.MessageKeywordRule(
            "矿泉水", com.attentionguard.app.core.EventCategory.ACTIVITY, com.attentionguard.app.core.EventPriority.P1))
        tick()
        val event = EventStore(context).load().single()
        assertEquals(com.attentionguard.app.core.EventCategory.ACTIVITY, event.category)
        assertEquals(com.attentionguard.app.core.EventPriority.P1, event.priority)
        assertTrue(event.reviewNotes.any { it.startsWith(com.attentionguard.app.core.MessageKeywordRule.EVIDENCE_PREFIX) })
        assertEquals(setOf("Building group"), Prefs(context).whitelist)
    }
    @Test fun messageRulesDoNotCreateAutomaticEventsInIntentMode() {
        Prefs(context).captureMode = CaptureMode.INTENT
        Prefs(context).messageKeywordRules = listOf(com.attentionguard.app.core.MessageKeywordRule("矿泉水"))
        service.activeRoot = root(title = "Building group", message = "楼下还有矿泉水")
        service.connect(); tick()
        assertTrue(EventStore(context).load().isEmpty())
        MessageArchive(context).use { assertEquals(0, it.count().total) }
    }
    @Test fun sameScreenDoesNotDuplicateMessagesAndDisabledAutoStillRecords() {
        Prefs(context).autoAnalyze = false
        service.activeRoot = root(); service.connect(); tick(); tick()
        MessageArchive(context).use { assertEquals(1, it.count().total) }
    }
    @Test fun informationalEventIsShownEvenWhenItDoesNotNeedAnUrgentReminder() {
        service.activeRoot = root(message = "学分数据尚未导入，大家莫急")
        service.connect(); tick()
        assertEquals(1, EventStore(context).load().size)
        assertTrue(windows.views.asSequence().flatMap { children(it) }.filterIsInstance<android.widget.TextView>()
            .any { it.text.toString().contains("事件类型") })
        tick()
        assertTrue(windows.views.asSequence().flatMap { children(it) }.filterIsInstance<android.widget.TextView>()
            .any { it.text.toString().contains("事件类型") })
    }
    @Test fun intentModeUsesCurrentWeChatEvenWhenAutoAnalysisIsOff() {
        Prefs(context).autoAnalyze = false
        service.activeRoot = root(message = "请大家明天提交作业")
        service.connect(); tick()
        button("切换意图分析").performClick(); tick()
        assertEquals(CaptureMode.INTENT, Prefs(context).captureMode)
        assertTrue(EventStore(context).load().isEmpty())
        assertTrue(windows.views.asSequence().flatMap { children(it) }.filterIsInstance<android.widget.TextView>()
            .any { it.text.toString().contains("可能在提出行动请求") })
    }
    @Test fun untitledBlockedChatCanBeAnalyzedWithoutSavingAndStaysVisibleAfterPoll() {
        Prefs(context).whitelist = setOf("Allowed")
        service.activeRoot = root(title = "", message = "请大家明天提交作业")
        service.connect(); tick()
        button("切换意图分析").performClick(); drain(); tick()
        val labels = windows.views.asSequence().flatMap { children(it) }.filterIsInstance<android.widget.TextView>()
            .map { it.text.toString() }.toList()
        assertTrue(labels.any { it.contains("可能在提出行动请求") })
        assertTrue(labels.any { it.contains("重要性 · 高") })
        assertTrue(labels.any { it == "语境置信度" })
        assertTrue(labels.any { Regex("[高中低] \\d+%").matches(it) })
        assertTrue(EventStore(context).load().isEmpty())
        MessageArchive(context).use { assertEquals(0, it.count().total) }
    }
    @Test fun analyzingJustAfterSwitchingChatsKeepsTheNewResult() {
        Prefs(context).autoAnalyze = false
        service.activeRoot = root(title = "First", message = "hello")
        service.connect(); tick()
        service.activeRoot = root(title = "Second", message = "请大家明天提交作业")
        button("切换意图分析").performClick(); drain(); tick()
        val labels = windows.views.asSequence().flatMap { children(it) }.filterIsInstance<android.widget.TextView>()
            .map { it.text.toString() }.toList()
        assertTrue(labels.any { it.contains("可能在提出行动请求") })
        assertTrue(labels.any { it.contains("重要性 · 高") })
        assertTrue(EventStore(context).load().isEmpty())
    }
    @Test fun intentModeKeepsUpdatingWithoutArchivingAndEventModeResumes() {
        Prefs(context).autoAnalyze = false
        service.activeRoot = root(message = "初始文字")
        service.connect(); tick()
        MessageArchive(context).use { assertEquals(1, it.count().total) }
        button("切换意图分析").performClick(); tick()
        assertEquals(CaptureMode.INTENT, Prefs(context).captureMode)
        service.activeRoot = root(message = "请大家明天提交作业")
        tick()
        MessageArchive(context).use { assertEquals(1, it.count().total) }
        assertTrue(EventStore(context).load().isEmpty())
        assertFalse(service.armHistory(HistoryConfig("Test group", HistoryRange(LocalDate.now(), LocalDate.now()), false)))
        assertTrue(windows.views.asSequence().flatMap { children(it) }.filterIsInstance<android.widget.TextView>()
            .any { it.text.toString().contains("可能在提出行动请求") })
        button("事件监测").performClick(); tick()
        assertEquals(CaptureMode.EVENT, Prefs(context).captureMode)
        MessageArchive(context).use { assertEquals(2, it.count().total) }
    }
    @Test fun persistedIntentModeStartsWithoutAnEventCapture() {
        Prefs(context).captureMode = CaptureMode.INTENT
        service.activeRoot = root(title = "", message = "请大家明天提交作业")
        service.connect(); tick()
        assertTrue(windows.views.asSequence().flatMap { children(it) }.filterIsInstance<android.widget.TextView>()
            .any { it.text.toString().contains("可能在提出行动请求") })
        MessageArchive(context).use { assertEquals(0, it.count().total) }
        assertTrue(EventStore(context).load().isEmpty())
    }
    @Test fun foreignForegroundAndWhitelistNeverPersistContent() {
        Prefs(context).whitelist = setOf("Allowed")
        service.activeRoot = root(message = "private text"); service.connect(); tick()
        assertTrue(CaptureDiagnostics(context).summary().contains("未匹配"))
        assertFalse(CaptureDiagnostics(context).summary().contains("private text"))
        service.activeRoot = root().apply { packageName = "other.app" }; tick()
        MessageArchive(context).use { assertEquals(0, it.count().total) }
        assertTrue(windows.views.isEmpty())
    }
    @Test fun blockedCurrentChatCanBeMarkedAfterFreshForegroundVerification() {
        Prefs(context).whitelist = setOf("Allowed")
        service.activeRoot = root(title = "Test group", message = "visible text")
        service.connect(); tick()
        MessageArchive(context).use { assertEquals(0, it.count().total) }
        windows.views.asSequence().flatMap { children(it) }.first { it.contentDescription == "标记当前微信会话" }.performClick(); tick()
        assertEquals(setOf("Allowed", "Test group"), Prefs(context).whitelist)
        MessageArchive(context).use { assertEquals("visible text", it.recent().single().message.text) }
    }

    @Test fun manualMarkWaitsForInfoNavigationAndBindsTheFullNameAfterReturning() {
        val original = withInfoButton(root(title = "", message = "请在周三下午之前提交课程作业")) { }
        service.activeRoot = original
        service.connect(); tick(); mark()
        advance(500)
        assertTrue(service.screenshots.isEmpty())
        assertNull(shadowOf(service).nextStartedActivity)

        service.activeRoot = infoPage()
        advance(300)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_BACK), shadowOf(service).globalActionsPerformed)
        service.activeRoot = root(title = "", message = "请在周三下午之前提交课程作业")
        advance(300)
        val confirmation = shadowOf(service).nextStartedActivity!!
        assertEquals(MarkChatActivity::class.java.name, confirmation.component?.className)
        assertEquals("校园创想课程讨论群", confirmation.getStringExtra(MarkChatActivity.EXTRA_SUGGESTED_TITLE))
        assertEquals("微信聊天信息", confirmation.getStringExtra(MarkChatActivity.EXTRA_TITLE_SOURCE))
        assertTrue(service.screenshots.isEmpty())
        Prefs(context).addRecognitionTerm("校园创想课程讨论群")
        assertTrue(service.confirmCurrentTitle("校园创想课程讨论群"))
        tick()
        MessageArchive(context).use { assertEquals("校园创想课程讨论群", it.recent().single().group) }
        assertTrue(CaptureDiagnostics(context).summary().contains("标题手动确认"))

        service.activeRoot = root(title = "另一个聊天", message = "请在周三下午之前提交课程作业")
        tick()
        assertNull(CaptureRuntime.lastVisibleTitle)
        MessageArchive(context).use { assertEquals(1, it.count().total) }
    }

    @Test fun returningToAnotherChatNeverOpensTheOldGroupsConfirmation() {
        service.activeRoot = withInfoButton(root(title = "", message = "请在周三下午之前提交课程作业")) {
            service.activeRoot = infoPage()
        }
        service.connect(); tick(); mark(); advance(300)
        assertEquals(1, shadowOf(service).globalActionsPerformed.size)
        service.activeRoot = root(title = "其他群", message = "完全不同的聊天内容")
        tick(4)
        assertNull(shadowOf(service).nextStartedActivity)
        assertTrue(service.screenshots.isEmpty())
        assertFalse(service.confirmCurrentTitle("校园创想课程讨论群"))
    }

    @Test fun unrelatedPageTimeoutDoesNotPressBackOrLaunchConfirmation() {
        service.activeRoot = withInfoButton(root(title = "", message = "请在周三下午之前提交课程作业")) { }
        service.connect(); tick(); mark()
        service.activeRoot = node("微信设置")
        tick(4)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
        assertTrue(service.screenshots.isEmpty())
        assertNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun failedInfoNavigationFallsBackToOcrOnlyAfterWaiting() {
        service.activeRoot = withInfoButton(root(title = "", message = "请在周三下午之前提交课程作业")) { }
        service.connect(); tick(); mark(); advance(500)
        assertTrue(service.screenshots.isEmpty())
        tick(4)
        assertEquals(1, service.screenshots.size)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
        service.screenshots.single().onFailure(2); advance(1)
        assertEquals("", shadowOf(service).nextStartedActivity!!.getStringExtra(MarkChatActivity.EXTRA_SUGGESTED_TITLE))
    }

    @Test fun oldOcrCallbackCannotCompleteANewerMarkRequest() {
        service.activeRoot = root(title = "", message = "请在周三下午之前提交课程作业")
        service.connect(); tick(); mark(); advance(300)
        val old = service.screenshots.single()
        service.activeRoot = root(title = "", message = "请在周四上午八点之前到达教室")
        tick(); mark(); advance(300)
        assertEquals(2, service.screenshots.size)
        old.onFailure(2); advance(1)
        assertNull(shadowOf(service).nextStartedActivity)
        service.screenshots.last().onFailure(2); advance(1)
        assertNotNull(shadowOf(service).nextStartedActivity)
    }

    @Test fun disablingCaptureCancelsAnInFlightInfoRead() {
        service.activeRoot = withInfoButton(root(title = "", message = "请在周三下午之前提交课程作业")) {
            service.activeRoot = infoPage()
        }
        service.connect(); tick(); mark()
        Prefs(context).enabled = false
        tick(4)
        assertTrue(shadowOf(service).globalActionsPerformed.isEmpty())
        assertNull(shadowOf(service).nextStartedActivity)
        assertFalse(service.confirmCurrentTitle("校园创想课程讨论群"))
    }

    @Test fun preparedHistoryCannotScrollUntilTheOverlayStartIsPressedAndLeavingPauses() {
        service.activeRoot = root(); service.connect(); tick()
        val config = HistoryConfig("Test group", HistoryRange(LocalDate.now().minusDays(3), LocalDate.now()), false)
        assertTrue(service.armHistory(config)); tick()
        val session = CaptureRuntime.history!!
        assertEquals(HistoryState.READY, session.state)
        button("开始回溯").performClick(); drain()
        assertEquals(HistoryState.RUNNING, session.state)
        assertEquals(0, session.attempts)
        service.activeRoot = null; tick()
        assertEquals(HistoryState.PAUSED, session.state)
        service.activeRoot = root(); tick()
        assertEquals(HistoryState.PAUSED, session.state)
        assertTrue(windows.views.isNotEmpty())
    }
    @Test fun disablingMasterSwitchHidesWindowAndRejectsPendingHistory() {
        service.activeRoot = root(); service.connect(); tick()
        Prefs(context).enabled = false; tick()
        assertTrue(windows.views.isEmpty())
        assertFalse(service.armHistory(HistoryConfig("Test group", HistoryRange(LocalDate.now(), LocalDate.now()), true)))
    }

    @Test fun intentRecordingRequiresAnExplicitStartAndPausesOnAnotherChat() {
        Prefs(context).captureMode = CaptureMode.INTENT
        service.activeRoot = root(message = "请大家明天提交作业"); service.connect(); tick()
        MessageArchive(context).use { assertEquals(0, it.count().total) }
        assertTrue(service.startRecording()); tick()
        val id = CaptureRuntime.recording!!.id
        MessageArchive(context).use { assertEquals(1, it.count(id).total) }
        assertTrue(EventStore(context).load().isEmpty())
        service.activeRoot = root(title = "Another group", message = "不可混入"); tick()
        assertEquals(com.attentionguard.app.core.RecordingState.PAUSED, CaptureRuntime.recording!!.state)
        MessageArchive(context).use { assertEquals(1, it.count(id).total) }
        service.activeRoot = root(message = "新消息"); tick()
        MessageArchive(context).use { assertEquals(1, it.count(id).total) }
        assertTrue(service.startRecording()); tick()
        MessageArchive(context).use { assertEquals(2, it.count(id).total) }
    }

    @Test fun recordingNeedsATitleButIntentDoesNotAndModeSwitchPausesIt() {
        Prefs(context).captureMode = CaptureMode.INTENT
        service.activeRoot = root(title = "", message = "谢谢你"); service.connect(); tick()
        assertFalse(service.startRecording())
        service.activeRoot = root(message = "第一条"); tick()
        assertTrue(service.startRecording()); tick()
        Prefs(context).captureMode = CaptureMode.EVENT; tick()
        assertEquals(com.attentionguard.app.core.RecordingState.PAUSED, CaptureRuntime.recording!!.state)
        assertFalse(service.startRecording())
    }

    @Test fun aWechatNavigationRequiresExplicitRecordingConfirmation() {
        Prefs(context).captureMode = CaptureMode.INTENT
        service.activeRoot = root(message = "第一条"); service.connect(); tick()
        assertTrue(service.startRecording()); tick()
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.tencent.mm"
        })
        tick()
        assertEquals(com.attentionguard.app.core.RecordingState.PAUSED, CaptureRuntime.recording!!.state)
    }

    @Test fun intentResultOnlyBecomesAnAppEventAfterManualSave() {
        Prefs(context).captureMode = CaptureMode.INTENT
        service.activeRoot = root(message = "请大家明天提交课程作业"); service.connect(); tick()
        assertTrue(EventStore(context).load().isEmpty())
        windows.views.asSequence().flatMap { children(it) }.first { it.contentDescription == "更多悬浮窗操作" }.performClick(); tick()
        windows.views.asSequence().flatMap { children(it) }.first { it.contentDescription == "保存当前事项" }.performClick(); tick()
        assertTrue(EventStore(context).load().isNotEmpty())
        assertEquals(CaptureMode.INTENT, Prefs(context).captureMode)
        assertTrue(CaptureDiagnostics(context).eventStorageLabel().contains("已保存"))
    }

    @Test fun fastVisibleScreensQueueInOrderInsteadOfBeingSilentlyDropped() {
        Prefs(context).captureMode = CaptureMode.INTENT
        service.activeRoot = root(message = "第一页"); service.connect(); tick()
        assertTrue(service.startRecording()); tick()
        val id = CaptureRuntime.recording!!.id
        val storage = ChatCaptureService::class.java.getDeclaredField("storageWorker").apply { isAccessible = true }.get(service) as ExecutorService
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        storage.execute { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val capture = ChatCaptureService::class.java.getDeclaredMethod("safeCapture").apply { isAccessible = true }
        try {
            for (text in listOf("第二页", "第二页", "第三页", "第四页")) {
                service.activeRoot = root(message = text); capture.invoke(service)
            }
        } finally { release.countDown() }
        tick()
        MessageArchive(context).use { archive ->
            assertEquals(listOf("第一页", "第二页", "第三页", "第四页"), archive.recordingMessages(id).map { it.message.text })
        }
    }

    @Test fun automaticHistoryOnlyScrollsBackwardAtTheIntervalAndStopsWhenStalled() {
        var scrolls = 0
        service.activeRoot = root(onScroll = { scrolls++; true }); service.connect(); tick()
        assertTrue(service.armHistory(HistoryConfig("Test group", HistoryRange(LocalDate.now().minusDays(1), LocalDate.now()), true)))
        tick(); button("开始回溯").performClick(); drain()
        tick(3); assertEquals(0, scrolls)
        tick(1); assertEquals(1, scrolls)
        repeat(3) { tick(4) }
        assertEquals(3, scrolls)
        assertEquals(HistoryState.PAUSED, CaptureRuntime.history!!.state)
        assertTrue(CaptureRuntime.history!!.reason.contains("未发现新页面"))
    }

    @Test fun unsupportedScrollPausesInsteadOfTryingCoordinateGestures() {
        service.activeRoot = root(onScroll = { false }); service.connect(); tick()
        assertTrue(service.armHistory(HistoryConfig("Test group", HistoryRange(LocalDate.now(), LocalDate.now()), true)))
        tick(); button("开始回溯").performClick(); drain(); tick(4)
        assertEquals(HistoryState.PAUSED, CaptureRuntime.history!!.state)
        assertTrue(CaptureRuntime.history!!.reason.contains("不支持"))
        assertTrue(shadowOf(service).gesturesDispatched.isEmpty())
    }
    private fun button(text: String): MaterialButton = windows.views.asSequence().flatMap { children(it) }.filterIsInstance<MaterialButton>().first { it.text.toString() == text }
    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
}
