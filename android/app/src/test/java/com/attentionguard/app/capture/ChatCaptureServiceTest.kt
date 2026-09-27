package com.attentionguard.app.capture

import android.graphics.Rect
import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
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
