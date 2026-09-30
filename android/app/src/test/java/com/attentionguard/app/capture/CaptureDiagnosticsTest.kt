package com.attentionguard.app.capture

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.core.CaptureMode
import com.attentionguard.app.core.DemoAttentionData

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CaptureDiagnosticsTest {
    private val diagnostics = CaptureDiagnostics(RuntimeEnvironment.getApplication())
    @Test fun exportedReportDistinguishesIntentNoWriteFromPermissionFailure() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.captureMode = CaptureMode.INTENT
        prefs.whitelist = setOf("private group")
        diagnostics.analyzed("INTENT", 4, 0, "HIGH", 80)
        val json = JSONObject(diagnostics.exportJson())
        assertEquals("INTENT", json.getJSONObject("settings").getString("mode"))
        assertTrue(json.getString("diagnosis").contains("不自动写入事件"))
        assertFalse(json.toString().contains("private group"))
        assertTrue(json.getJSONObject("permissions").getBoolean("accessibility_overlay_uses_service_permission"))
    }

    @Test fun eventWriteAndListStagesSurviveExportWithoutRawEvents() {
        diagnostics.pipeline("LOCAL_IDENTIFIED", "READY_TO_SAVE", 2)
        diagnostics.eventSaved(DemoAttentionData.events, 2)
        diagnostics.appList(4, 0, true, false, false)
        val report = JSONObject(diagnostics.exportJson())
        assertTrue(report.getJSONArray("pipeline").toString().contains("COMMITTED"))
        assertTrue(report.getJSONArray("pipeline").toString().contains("FILTERED"))
        assertFalse(report.toString().contains(DemoAttentionData.events.first().evidence.first()))
        assertEquals(0, report.getJSONObject("app_list").getInt("shown"))
    }

    @Test fun traceIsBoundedAndWriteFailureHasAnActionableExplanation() {
        repeat(110) { diagnostics.pipeline("EVENT_SAVE", "FAILED", it, "IOException") }
        assertEquals(80, JSONObject(diagnostics.exportJson()).getJSONArray("pipeline").length())
        assertTrue(diagnostics.explanation().contains("保存失败"))
    }
    @Test fun leavingWechatKeepsTheLastMountFailureVisible() {
        diagnostics.overlay("挂窗失败：BadTokenException")
        diagnostics.overlay("已隐藏")
        val result = diagnostics.summary()
        assertTrue(result.contains("悬浮窗：已隐藏"))
        assertTrue(result.contains("挂窗失败：BadTokenException"))
    }
    @Test fun aStaleHeartbeatCannotClaimTheServiceIsConnected() {
        CaptureRuntime.actions = object : CaptureActions {
            override fun armHistory(config: HistoryConfig) = false
            override fun pauseHistory() = Unit
            override fun cancelHistory() = Unit
        }
        try {
            diagnostics.heartbeat(true)
            assertTrue(diagnostics.summary().contains("服务：已连接"))
            assertTrue(diagnostics.summary(System.currentTimeMillis() + 11_000).contains("心跳过期"))
            diagnostics.heartbeat(false)
            assertTrue(diagnostics.summary().contains("未连接"))
        } finally { CaptureRuntime.actions = null }
    }
    @Test fun permissionAndPersistedHeartbeatDoNotProveALiveConnection() {
        CaptureRuntime.actions = null
        diagnostics.heartbeat(true)
        assertTrue(diagnostics.healthLabel(true, true).contains("需要恢复"))
        assertEquals("观测已暂停", diagnostics.healthLabel(false, true))
        assertEquals("未授权采集", diagnostics.healthLabel(true, false))
    }
}
