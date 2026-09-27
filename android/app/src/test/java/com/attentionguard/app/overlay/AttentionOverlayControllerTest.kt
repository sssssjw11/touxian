package com.attentionguard.app.overlay

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.os.Looper
import com.google.android.material.button.MaterialButton
import com.attentionguard.app.MainActivity
import com.attentionguard.app.core.DemoAttentionData
import com.attentionguard.app.core.CaptureMode
import com.attentionguard.app.core.IntentInsight
import com.attentionguard.app.core.OverlaySize
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.capture.*
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class AttentionOverlayControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = Prefs(context)
    private val overlay = AttentionOverlayController(context)
    private val windows = Shadow.extract<ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java))

    @After fun removeWindows() { overlay.hide() }

    @Test fun noPermissionDoesNotMountOrCrash() {
        ShadowSettings.setCanDrawOverlays(false)
        overlay.showIdle("Test group")
        assertFalse(overlay.isShowing())
        assertTrue(windows.views.isEmpty())
    }

    @Test fun tapAndSemanticClickBothResetPosition() {
        showAtRememberedPosition()
        val handle = handle()
        touch(handle, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(handle, MotionEvent.ACTION_UP, 100f, 100f)
        assertEquals(-1, prefs.bubbleX)
        assertEquals(-1, prefs.bubbleY)
        showAtRememberedPosition()
        handle().performClick()
        assertEquals(-1, prefs.bubbleX)
        assertEquals(-1, prefs.bubbleY)
    }

    @Test fun dragReturningToStartIsNotMistakenForTap() {
        showAtRememberedPosition()
        val handle = handle()
        touch(handle, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(handle, MotionEvent.ACTION_MOVE, 135f, 140f)
        touch(handle, MotionEvent.ACTION_MOVE, 100f, 100f)
        touch(handle, MotionEvent.ACTION_UP, 100f, 100f)
        assertEquals(40, prefs.bubbleX)
        assertEquals(80, prefs.bubbleY)
    }

    @Test fun cancelledDragRestoresWindowWithoutSavingPartialPosition() {
        showAtRememberedPosition()
        val handle = handle()
        touch(handle, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(handle, MotionEvent.ACTION_MOVE, 125f, 145f)
        touch(handle, MotionEvent.ACTION_CANCEL, 125f, 145f)
        val params = windows.views.single().layoutParams as WindowManager.LayoutParams
        assertEquals(40, params.x)
        assertEquals(80, params.y)
        assertEquals(40, prefs.bubbleX)
        assertEquals(80, prefs.bubbleY)
    }

    @Test fun openActionTargetsTheDisplayedEventAndRemovesWindow() {
        ShadowSettings.setCanDrawOverlays(true)
        val event = DemoAttentionData.events.first().copy(id = "live-event")
        overlay.showEvent(event)
        assertTrue(descendants(windows.views.single()).filterIsInstance<android.widget.TextView>()
            .any { it.text.toString().contains("事件类型") })
        descendants(windows.views.single()).first { it.contentDescription == "打开观测簿" }.performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(event.id, intent.getStringExtra(MainActivity.EXTRA_EVENT_ID))
        assertFalse(overlay.isShowing())
        assertTrue(windows.views.isEmpty())
    }

    @Test fun loadingKeepsLocalTypeAndImportanceVisible() {
        ShadowSettings.setCanDrawOverlays(true)
        overlay.showEvent(DemoAttentionData.events.first())
        overlay.showLoading(true)
        assertTrue(descendants(windows.views.single()).filterIsInstance<android.widget.TextView>()
            .any { it.text.toString().contains("事件类型") && it.text.toString().contains("P0") })
    }

    @Test fun collapsedCardKeepsHistoryActionAndCanExpand() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.overlayCollapsed = true
        var opened = false
        var selectedMode: CaptureMode? = null
        var marked = false
        overlay.onHistorySettings = { opened = true }
        overlay.onModeChange = { selectedMode = it }
        overlay.onMarkCurrentChat = { marked = true }
        overlay.showIdle("Test group")
        assertTrue(overlay.isShowing())
        descendants(windows.views.single()).first { it.contentDescription == "标记当前微信会话" }.performClick()
        assertTrue(marked)
        descendants(windows.views.single()).first { it.contentDescription == "开启意图分析模式" }.performClick()
        assertEquals(CaptureMode.INTENT, selectedMode)
        descendants(windows.views.single()).first { it.contentDescription == "回溯收集" }.performClick()
        assertTrue(opened)
        descendants(windows.views.single()).first { it.contentDescription == "展开偷闲" }.performClick()
        assertFalse(prefs.overlayCollapsed)
    }

    @Test fun manualIntentOpensExpandedResultFromCollapsedBubble() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.overlayCollapsed = true
        overlay.showIdle("课程群")
        overlay.showIntent(IntentInsight("可能在提出行动请求", "核对期限", "请提交作业", "同学", "课程群", 1_700_000_000_000L))
        assertFalse(prefs.overlayCollapsed)
        button("判断依据").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(descendants(windows.views.single()).filterIsInstance<android.widget.TextView>()
            .any { it.text.toString().contains("请提交作业") })
        assertTrue(descendants(windows.views.single()).filterIsInstance<android.widget.TextView>()
            .any { it.text.toString().contains("语境置信度") })
    }

    @Test fun ongoingIntentUpdatesDoNotForceACollapsedCardOpen() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.captureMode = CaptureMode.INTENT
        prefs.overlayCollapsed = true
        overlay.showIntent(IntentInsight("可能在提出行动请求", "核对期限", "请提交作业", "同学", "", 1L,
            contextSummary = "7 条可读文字"), expand = false)
        assertTrue(prefs.overlayCollapsed)
        val view = windows.views.single()
        assertEquals(248, (view.layoutParams as WindowManager.LayoutParams).width)
        assertTrue(descendants(view).any { it.contentDescription == "切回事件监测模式" })
        assertTrue(descendants(view).any { it.contentDescription == "刷新整屏语境" })
    }

    @Test fun eventAndIntentCompactPanelsUseTheSameWidth() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.captureMode = CaptureMode.EVENT
        prefs.overlaySize = OverlaySize.COMPACT
        overlay.showIdle("课程群")
        val eventWidth = (windows.views.single().layoutParams as WindowManager.LayoutParams).width
        prefs.captureMode = CaptureMode.INTENT
        overlay.showIntent(IntentInsight("暂无明确请求", "继续观察", "普通聊天", "对方", "", 1L), expand = false)
        val intentWidth = (windows.views.single().layoutParams as WindowManager.LayoutParams).width
        assertEquals(eventWidth, intentWidth)
    }

    @Test fun expandedIntentPanelIsCappedAtHalfTheDisplayWidth() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.captureMode = CaptureMode.INTENT
        prefs.overlaySize = OverlaySize.EXPANDED
        overlay.showIntent(IntentInsight("暂无明确请求", "继续观察", "普通聊天", "对方", "", 1L))
        val view = windows.views.single()
        val width = (view.layoutParams as WindowManager.LayoutParams).width
        assertTrue(width <= context.resources.displayMetrics.widthPixels / 2)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun longIntentResultKeepsModeSwitchReachableOnShortScreens() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.captureMode = CaptureMode.INTENT
        prefs.overlayCollapsed = false
        overlay.showIntent(IntentInsight("可能在跟进先前请求", "结合上文核对所指事项和期限，再决定是否处理。",
            "明天之前可以吗？", "同学", "", 1L, contextEvidence = "对方：请帮我提交材料；我：什么时候？；对方：还有一份附件",
            contextSummary = "7 条可读文字 · 行动、时间、问答线索"), expand = true)
        val view = windows.views.single()
        val width = (view.layoutParams as WindowManager.LayoutParams).width
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertTrue(button("事件监测").bottom <= view.measuredHeight)
        assertTrue(button("意图分析").bottom <= view.measuredHeight)
        assertTrue(descendants(view).first { it.contentDescription == "刷新整屏语境" }.bottom <= view.measuredHeight)
        val detail = descendants(view).filterIsInstance<android.widget.ScrollView>().single()
        assertTrue(detail.measuredHeight <= 240)
    }

    @Test fun compactToolbarCanBecomeADraggableBallAndReturnWithoutLosingTheMode() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.captureMode = CaptureMode.INTENT
        prefs.overlaySize = OverlaySize.COMPACT
        overlay.showIntent(IntentInsight("暂无明确请求", "继续观察", "我在看戏", "对方", "", 1L), expand = false)
        descendants(windows.views.single()).first { it.contentDescription == "拖动工具条；点按收成小球" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OverlaySize.BUBBLE, prefs.overlaySize)
        val ball = windows.views.single()
        assertEquals(52, (ball.layoutParams as WindowManager.LayoutParams).width)
        ball.measure(View.MeasureSpec.makeMeasureSpec(52, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
        assertEquals(52, ball.measuredHeight)
        val trigger = descendants(ball).first { it.contentDescription == "展开悬浮工具条" }
        touch(trigger, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(trigger, MotionEvent.ACTION_MOVE, 130f, 130f)
        touch(trigger, MotionEvent.ACTION_UP, 130f, 130f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OverlaySize.BUBBLE, prefs.overlaySize)
        descendants(windows.views.single()).first { it.contentDescription == "展开悬浮工具条" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(OverlaySize.COMPACT, prefs.overlaySize)
        assertEquals(CaptureMode.INTENT, prefs.captureMode)
    }

    @Test fun compactControlsFitAndPauseIsAlwaysReachable() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.overlayCollapsed = true
        val day = LocalDate.now()
        val session = HistorySession(HistoryConfig("Test group", HistoryRange(day, day), true))
        session.start("Test group", 0L)
        var paused = false
        overlay.onHistoryPause = { paused = true }
        overlay.showIdle("Test group", history = session)
        val view = windows.views.single()
        val width = (view.layoutParams as WindowManager.LayoutParams).width
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertEquals(248, view.measuredWidth)
        assertEquals(56, view.measuredHeight)
        val actions = descendants(view).filter { it.isClickable }.toList()
        assertEquals(5, actions.size)
        assertTrue(actions.all { it.width >= 48 && it.height >= 48 && it.right <= width })
        actions.first { it.contentDescription == "暂停回溯" }.performClick()
        assertTrue(paused)
        val mounted = windows.views.single()
        overlay.showIdle("Test group", "different status", session)
        assertSame(mounted, windows.views.single())
    }

    @Test fun opacityAppliesToPanelAndModeSurfacesButNotText() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.captureMode = CaptureMode.INTENT
        prefs.overlaySize = OverlaySize.EXPANDED
        prefs.overlayOpacity = 16
        overlay.showIntent(IntentInsight("暂无明确请求", "继续观察", "普通聊天", "对方", "", 1L), expand = true)
        val view = windows.views.single()
        val expectedAlpha = 16 * 255 / 100
        assertEquals(expectedAlpha, (view as ViewGroup).getChildAt(0).background.alpha)
        val mode = button("意图分析")
        // Buttons are clear: a single selection thumb owns the surface opacity.
        assertEquals(0, android.graphics.Color.alpha(mode.backgroundTintList!!.defaultColor))
        val segments = mode.parent as com.attentionguard.app.ui.GuardSegments
        assertEquals(expectedAlpha, android.graphics.Color.alpha(segments.selectedFill))
        assertEquals(255, android.graphics.Color.alpha(mode.currentTextColor))
    }

    @Test fun unchangedIntentDoesNotRebuildAndNewResultsKeepBallCollapsed() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.captureMode = CaptureMode.INTENT
        prefs.overlaySize = OverlaySize.EXPANDED
        val insight = IntentInsight("暂无明确请求", "继续观察", "普通聊天", "对方", "", 1L)
        overlay.showIntent(insight, expand = false)
        val firstModeButton = button("意图分析")
        overlay.showIntent(insight.copy(capturedAt = 2L), expand = false)
        assertSame(firstModeButton, button("意图分析"))
        prefs.overlaySize = OverlaySize.BUBBLE
        overlay.showIntent(insight.copy(evidence = "新的聊天文字"), expand = false)
        assertEquals(OverlaySize.BUBBLE, prefs.overlaySize)
        assertEquals(52, (windows.views.single().layoutParams as WindowManager.LayoutParams).width)
    }

    private fun showAtRememberedPosition() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.bubbleX = 40
        prefs.bubbleY = 80
        overlay.showIdle("Test group")
        assertTrue(overlay.isShowing())
    }

    private fun handle() = descendants(windows.views.single()).first { it.contentDescription == "拖动卡片；点按复位" }
    private fun button(label: String) = descendants(windows.views.single()).filterIsInstance<MaterialButton>().first { it.text.toString() == label }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
}
