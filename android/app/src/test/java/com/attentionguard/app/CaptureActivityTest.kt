package com.attentionguard.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.attentionguard.app.capture.*
import com.attentionguard.app.core.Prefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class CaptureActivityTest {
    @After fun reset() { CaptureRuntime.actions = null; CaptureRuntime.history = null }
    @Test fun historyIsFirstAndAutomaticScrollingIsOff() {
        val controller = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        try {
            val activity = controller.get(); shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activity.findViewById<SwitchCompat>(R.id.ag_history_auto).isChecked)
            assertTrue(activity.findViewById<MaterialButton>(R.id.ag_history_prepare).isShown)
            val diagnosis = children(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text == "复制诊断" }
            assertFalse(diagnosis.isShown)
            assertTrue(children(activity.window.decorView).filterIsInstance<TextView>().any { it.text.contains("未连接") })
            activity.findViewById<MaterialButton>(R.id.ag_history_prepare).performClick()
            assertTrue(children(activity.window.decorView).filterIsInstance<TextInputLayout>().any { it.error != null })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun selectedWorkspaceTabSurvivesRecreation() {
        val controller = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        try {
            val tabs = children(controller.get().window.decorView).filterIsInstance<com.google.android.material.tabs.TabLayout>().single()
            tabs.getTabAt(2)!!.select()
            controller.recreate()
            assertEquals(2, children(controller.get().window.decorView).filterIsInstance<com.google.android.material.tabs.TabLayout>().single().selectedTabPosition)
            assertFalse(controller.get().findViewById<MaterialButton>(R.id.ag_history_prepare).isShown)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun prepareRequiresConfirmationAndDoesNotStartRunning() {
        var accepted: HistoryConfig? = null
        CaptureRuntime.actions = object : CaptureActions {
            override fun armHistory(config: HistoryConfig): Boolean { accepted = config; CaptureRuntime.history = HistorySession(config); return true }
            override fun pauseHistory() = Unit
            override fun cancelHistory() = Unit
        }
        val controller = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<TextInputEditText>(R.id.ag_history_title).setText("Test group")
            activity.findViewById<MaterialButton>(R.id.ag_history_prepare).performClick()
            assertNull(accepted)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Test group", accepted!!.title)
            assertFalse(accepted!!.automatic)
            assertEquals(HistoryState.READY, CaptureRuntime.history!!.state)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun ocrRequiresExplicitPrivacyConfirmation() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        try {
            val toggle = children(controller.get().window.decorView).filterIsInstance<SwitchCompat>().first { it.text == "本机 OCR 兜底" }
            toggle.isChecked = true
            assertFalse(Prefs(context).localOcrEnabled); assertFalse(toggle.isChecked)
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Prefs(context).localOcrEnabled); assertTrue(toggle.isChecked)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun draftTargetAndModeSurviveRecreation() {
        val controller = Robolectric.buildActivity(CaptureActivity::class.java).setup()
        try {
            controller.get().findViewById<TextInputEditText>(R.id.ag_history_title).setText("draft title")
            controller.get().findViewById<SwitchCompat>(R.id.ag_history_auto).isChecked = true
            controller.recreate()
            assertEquals("draft title", controller.get().findViewById<TextInputEditText>(R.id.ag_history_title).text.toString())
            assertTrue(controller.get().findViewById<SwitchCompat>(R.id.ag_history_auto).isChecked)
        } finally { controller.pause().stop().destroy() }
    }
    private fun children(view: View): Sequence<View> = sequence {
        yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
}
