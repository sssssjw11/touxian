package com.attentionguard.app.capture

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

data class CaptureWindow(val root: AccessibilityNodeInfo?, val type: Int, val layer: Int, val focused: Boolean, val active: Boolean)

object ForegroundChatWindow {
    fun choose(active: AccessibilityNodeInfo?, windows: List<CaptureWindow>, ownPackage: String): AccessibilityNodeInfo? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val app = apps.filter { it.focused || it.active }.maxByOrNull { it.layer }
        val activePackage = active?.packageName?.toString()
        val inputPackages = windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.mapNotNull { it.root?.packageName?.toString() }
        if (activePackage != null && activePackage != "com.tencent.mm" && activePackage != ownPackage && activePackage !in inputPackages) return null
        // A real activity always wins over a background WeChat window.
        if (app != null) return app.root?.takeIf { it.packageName?.toString() == "com.tencent.mm" }
        // Do not fall back through another foreground app when focus is ambiguous.
        if (apps.any { it.root?.packageName?.toString() != "com.tencent.mm" }) return null
        return active?.takeIf { it.packageName?.toString() == "com.tencent.mm" }
    }
}
