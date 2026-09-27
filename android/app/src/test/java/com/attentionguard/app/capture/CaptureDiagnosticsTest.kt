package com.attentionguard.app.capture

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CaptureDiagnosticsTest {
    private val diagnostics = CaptureDiagnostics(RuntimeEnvironment.getApplication())
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
