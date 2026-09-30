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

    @Test fun explicitHeaderGeometryWinsAndDefaultDoesNotIncludeTheNextRow() {
        val actual = Rect(160, 85, 890, 185)
        assertEquals(actual, OnDeviceChatOcr.titleCropBounds(1080, 2400, 30, 2f, actual))
        assertEquals(Rect(183, 30, 928, 142), OnDeviceChatOcr.titleCropBounds(1080, 2400, 30, 2f, null))
        assertEquals(Rect(160, 85, 890, 185), actual)
        assertNull(OnDeviceChatOcr.titleCropBounds(1080, 2400, 30, 2f, Rect(1200, 85, 1400, 185)))
    }

    @Test fun titleRequestStillRestoresOverlayAndDropsCancelledCallbacks() {
        var delivered = false
        ocr.readVisibleTitle({ true }, Rect(10, 20, 120, 60)) { _, _ -> delivered = true }
        assertTrue(hidden)
        advance(300)
        assertEquals(1, service.calls)
        ocr.cancel()
        service.callback!!.onFailure(2)
        assertFalse(hidden)
        assertFalse(delivered)
    }

    @Test fun legacyTrailingLambdaTitleRequestRemainsSupported() {
        var reason: String? = null
        ocr.readVisibleTitle({ true }) { _, result -> reason = result }
        advance(300)
        service.callback!!.onFailure(2)
        assertEquals("标题截屏不可用（2）", reason)
        assertFalse(hidden)
    }

    private fun line(text: String, left: Int = 0, top: Int = 0, width: Int = 180, height: Int = 24) =
        OcrTitleLine(text, left, top, left + width, top + height)

    @Test fun sameBaselineLatinPrefixAndChineseNameAreReassembled() {
        assertEquals("D4星河交流群", WeChatTitleText.select(listOf(
            line("星河交流群", left = 32, top = 5, height = 26),
            line("D4", top = 8, width = 26, height = 22),
            line("昨天 10:30", top = 48)
        )))
    }

    @Test fun separatorsCountersAndNavigationNeverBecomeATitle() {
        val values = listOf("返回", "微信", "聊天信息", "微信（3）", "聊天信息(49)", "09:30", "昨天 10:30", "2026年9月30日 13:00", "(49)", "12345")
        assertNull(WeChatTitleText.select(values.mapIndexed { index, value -> line(value, top = index * 32) }))
        assertEquals("23网工2(49)", WeChatTitleText.select(listOf(
            line("23", left = 40, width = 24),
            line("网工2", left = 70, width = 80), line("(49)", left = 160, width = 45)
        )))
    }

    @Test fun navigationWordsInsideARealNameAreNotRemoved() {
        assertEquals("微信学习交流群", WeChatTitleText.select(listOf(
            line("微信", width = 48), line("学习交流群", left = 54, width = 150)
        )))
        assertEquals("聊天信息研究群", WeChatTitleText.select(listOf(
            line("聊天信息", width = 96), line("研究群", left = 102, width = 90)
        )))
        assertNull(WeChatTitleText.select(listOf(line("聊天信息", width = 96), line("(49)", left = 102, width = 45))))
    }

    @Test fun latinWordSpacesUseGeometryWhileCloseFragmentsStayJoined() {
        assertEquals("A B", WeChatTitleText.select(listOf(
            line("A", width = 14), line("B", left = 21, width = 14)
        )))
        assertEquals("AB", WeChatTitleText.select(listOf(
            line("A", width = 14), line("B", left = 15, width = 14)
        )))
        assertEquals("AIwelink API", WeChatTitleText.select(listOf(
            line("AIwelink", width = 100), line("API", left = 111, width = 42)
        )))
        assertEquals("AIwelink", WeChatTitleText.select(listOf(
            line("AI", width = 24), line("welink", left = 25, width = 76)
        )))
    }

    @Test fun dateFragmentsAreRejectedOnlyAfterTheWholeRowIsReassembled() {
        assertNull(WeChatTitleText.select(listOf(
            line("9月", width = 28), line("30日", left = 32, width = 42), line("13:00", left = 80, width = 62)
        )))
        assertEquals("9月学习群", WeChatTitleText.select(listOf(
            line("9月", width = 28), line("学习群", left = 32, width = 90)
        )))
    }

    @Test fun anyTruncatedPartInvalidatesTheWholeHeaderRow() {
        listOf("星河…讨论群", "星河. ..讨论群").forEach { value ->
            assertNull(WeChatTitleText.select(listOf(
                line("D4", width = 26), line(value, left = 32), line("昨天 10:30", top = 40)
            )))
        }
    }

    @Test fun upperValidTitleWinsOverLongerLowerOcrText() {
        assertEquals("D4交流群", WeChatTitleText.select(listOf(
            line("今天 13:00"), line("D4交流群", top = 35), line("请明天提交数据库课程报告", top = 72)
        )))
    }
}
