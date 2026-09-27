package com.attentionguard.app.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.attentionguard.app.core.ChatSnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.Executor

class OcrProbeService : AccessibilityService() {
    var calls = 0
    var callback: TakeScreenshotCallback? = null
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun takeScreenshot(displayId: Int, executor: Executor, callback: TakeScreenshotCallback) {
        calls++; this.callback = callback
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OnDeviceChatOcrTest {
    private val controller = Robolectric.buildService(OcrProbeService::class.java).create()
    private val service = controller.get()
    private var hidden = false
    private val ocr = OnDeviceChatOcr(service) { hidden = it }
    private val inspection = ChatInspection(ChatSnapshot("group", emptyList()), 4, 1, 0, "blocked",
        ocrRegions = listOf(ChatOcrRegion(Rect(30, 220, 250, 270), "other", null, null)))
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    @After fun cleanup() { ocr.close(); controller.destroy() }

    @Test fun aChangedConversationBeforeTheShotDoesNotCapture() {
        var current = true
        var delivered = false
        ocr.capture(inspection, { current }) { _, _ -> delivered = true }
        assertTrue(hidden)
        current = false; advance(300)
        assertEquals(0, service.calls); assertFalse(hidden); assertFalse(delivered)
    }
    @Test fun cancellingRestoresOverlayAndDropsDelayedFailureCallbacks() {
        var delivered = false
        ocr.capture(inspection, { true }) { _, _ -> delivered = true }
        advance(300); assertEquals(1, service.calls)
        ocr.cancel(); assertFalse(hidden)
        service.callback!!.onFailure(2)
        advance(21_000); assertFalse(delivered)
    }
    @Test fun failuresAreBoundedAndManualRetryRearmsTheSameScene() {
        var results = 0
        repeat(5) {
            ocr.capture(inspection, { true }) { _, _ -> results++ }
            advance(300); service.callback?.onFailure(2); advance(8000)
        }
        assertEquals(3, service.calls); assertEquals(3, results)
        ocr.retry()
        ocr.capture(inspection, { true }) { _, _ -> results++ }
        advance(300)
        assertEquals(4, service.calls)
    }
    @Test fun timeoutRestoresOverlayAndCannotDeliverTwice() {
        var results = 0
        ocr.capture(inspection, { true }) { _, reason -> results++; assertTrue(reason.contains("超时")) }
        advance(21_000)
        assertEquals(1, results); assertFalse(hidden)
        service.callback!!.onFailure(2)
        assertEquals(1, results)
    }
    @Test fun noBubbleEvidenceMeansNoScreenshot() {
        ocr.capture(inspection.copy(knownBubbles = 0), { true }) { _, _ -> fail("No result expected") }
        advance(300)
        assertEquals(0, service.calls); assertFalse(hidden)
    }
}
