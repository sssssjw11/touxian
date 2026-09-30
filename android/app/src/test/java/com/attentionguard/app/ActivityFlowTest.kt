package com.attentionguard.app

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.attentionguard.app.core.*
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
class ActivityFlowTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun firstLaunchIsEmptyAndDemoNeverWritesRealEvents() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertTrue(texts(activity).contains("记录本还是空的"))
            assertTrue(EventStore(context).load().isEmpty())
            button(activity, "浏览示例").performClick()
            assertTrue(texts(activity).contains("示例模式"))
            assertTrue(texts(activity).contains(DemoAttentionData.events.first().title))
            assertTrue(EventStore(context).load().isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun aLateCommitUpdatesTheAlreadyOpenAppWithoutReopeningIt() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertTrue(texts(activity).contains("记录本还是空的"))
            val event = DemoAttentionData.events.first().copy(id = "late-commit")
            EventStore(context).upsert(event)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(texts(activity).contains(event.title))
            assertFalse(texts(activity).contains("记录本还是空的"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun realEventCanBeCompletedRestoredAndReopened() {
        val event = DemoAttentionData.events[2].copy(id = "test-event")
        EventStore(context).upsert(event)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).first { it.isClickable && it.contentDescription?.startsWith(event.title) == true }.performClick()
            button(activity, "标记完成").performClick()
            assertEquals(EventStatus.COMPLETED, EventStore(context).load().single().status)
            button(activity, "恢复原状态").performClick()
            assertEquals(EventStatus.MONITORING, EventStore(context).load().single().status)
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(texts(activity).contains("继续关注"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun eventRowQuickActionsCompleteArchiveAndRestoreWithoutOpeningDetail() {
        val event = DemoAttentionData.events.first().copy(id = "quick-event",
            captureOrigin = CaptureOrigin.WECHAT_MANUAL, sourceCapturedAt = 1_700_000_000_000L)
        EventStore(context).upsert(event)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).first { it.contentDescription == "标记完成：${event.title}" }.performClick()
            assertEquals(EventStatus.COMPLETED, EventStore(context).load().single().status)
            assertFalse(texts(activity).contains("事件记录"))
            descendants(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.ag_ledger
            activity.findViewById<MaterialButton>(500 + EventFilter.COMPLETED.ordinal).performClick()
            descendants(activity.window.decorView).first { it.contentDescription == "归档：${event.title}" }.performClick()
            assertTrue(EventStore(context).load().single().archived)
            activity.findViewById<MaterialButton>(500 + EventFilter.ARCHIVED.ordinal).performClick()
            assertTrue(texts(activity).contains(event.title))
            descendants(activity.window.decorView).first { it.contentDescription == "移出归档：${event.title}" }.performClick()
            assertFalse(EventStore(context).load().single().archived)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun detailShowsCaptureProvenance() {
        val event = DemoAttentionData.events.first().copy(id = "source-event",
            captureOrigin = CaptureOrigin.WECHAT_MANUAL, sourceCapturedAt = 1_700_000_000_000L)
        EventStore(context).upsert(event)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).first { it.isClickable && it.contentDescription?.startsWith(event.title) == true }.performClick()
            assertTrue(texts(activity).contains("微信 · 手动识别"))
            assertTrue(texts(activity).any { it.startsWith("采集于 ") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ledgerSearchAndFilterSurviveActivityRecreation() {
        EventStore(context).upsert(DemoAttentionData.events.first().copy(id = "test-event"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.ag_ledger
            activity.findViewById<MaterialButton>(500 + EventFilter.ACTION.ordinal).performClick()
            activity.findViewById<TextInputEditText>(R.id.ag_search).setText("no matching event")
            assertTrue(texts(activity).contains("没有匹配的事件"))
            controller.recreate()
            val recreated = controller.get()
            assertEquals("no matching event", recreated.findViewById<TextInputEditText>(R.id.ag_search).text.toString())
            assertTrue(recreated.findViewById<MaterialButton>(500 + EventFilter.ACTION.ordinal).isChecked)
            button(recreated, "清除筛选").performClick()
            assertEquals("", recreated.findViewById<TextInputEditText>(R.id.ag_search).text.toString())
            assertTrue(texts(recreated).contains(DemoAttentionData.events.first().title))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun overlayIntentOpensRealEventAndBackReturnsToLedger() {
        val event = DemoAttentionData.events[2].copy(id = "overlay-event")
        EventStore(context).upsert(event)
        Prefs(context).demoMode = true
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_EVENT_ID, event.id)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertFalse(Prefs(context).demoMode)
            assertTrue(texts(activity).contains("事件记录"))
            assertTrue(texts(activity).contains(event.title))
            activity.onBackPressedDispatcher.onBackPressed()
            assertNotNull(activity.findViewById<TextInputEditText>(R.id.ag_search))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun overlayIntentReusesOpenActivityAndMissingEventFallsBackToLedger() {
        val event = DemoAttentionData.events[2].copy(id = "overlay-event")
        EventStore(context).upsert(event)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            controller.newIntent(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_EVENT_ID, event.id))
            assertTrue(texts(controller.get()).contains("事件记录"))
            controller.newIntent(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_EVENT_ID, "missing-event"))
            assertNotNull(controller.get().findViewById<TextInputEditText>(R.id.ag_search))
            assertFalse(texts(controller.get()).contains("事件记录"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun settingsRemainLocalByDefaultAndValidateBeforeSaving() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val prefs = Prefs(context)
            assertFalse(activity.findViewById<SwitchCompat>(R.id.ag_cloud).isChecked)
            assertEquals(Prefs.DEFAULT_DEEPSEEK_MODEL, activity.findViewById<TextInputEditText>(R.id.ag_model).text.toString())
            activity.findViewById<SwitchCompat>(R.id.ag_cloud).isChecked = true
            button(activity, "保存设置").performClick()
            val key = activity.findViewById<TextInputEditText>(R.id.ag_key)
            val keyBox = descendants(activity.window.decorView).filterIsInstance<TextInputLayout>().first { it.editText == key }
            assertEquals("请填写 DeepSeek API Key", keyBox.error.toString())
            assertFalse(prefs.cloudEnabled)
            assertFalse(key.isSaveEnabled)
            activity.findViewById<SwitchCompat>(R.id.ag_cloud).isChecked = false
            activity.findViewById<TextInputEditText>(R.id.ag_context).setText("New test context")
            button(activity, "保存设置").performClick()
            assertEquals("New test context", prefs.relationship)
            assertFalse(prefs.cloudEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unsavedSettingsRequireExplicitDiscard() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<TextInputEditText>(R.id.ag_context).setText("Unsaved context")
            activity.onBackPressedDispatcher.onBackPressed()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertFalse(activity.isFinishing)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activity.isFinishing)
            assertEquals(Prefs.DEFAULT_REL, Prefs(context).relationship)
            activity.onBackPressedDispatcher.onBackPressed()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun opacitySliderSavesBothEndpoints() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val slider = activity.findViewById<SeekBar>(R.id.ag_opacity)
            assertEquals(100, slider.max)
            slider.progress = 0
            button(activity, "保存设置").performClick()
            assertEquals(0, Prefs(context).overlayOpacity)
            slider.progress = 100
            button(activity, "保存设置").performClick()
            assertEquals(100, Prefs(context).overlayOpacity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun customIntentEntryAnalyzesLocallyWithoutCreatingAnEvent() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = main.get()
            descendants(activity.window.decorView).first { it.contentDescription == "打开自定义意图分析" }.performClick()
            assertEquals(CustomIntentActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        } finally { main.pause().stop().destroy() }
        val custom = Robolectric.buildActivity(CustomIntentActivity::class.java).setup()
        try {
            val activity = custom.get()
            button(activity, "开始分析").performClick()
            val input = activity.findViewById<TextInputEditText>(R.id.ag_custom_chat)
            val field = descendants(activity.window.decorView).filterIsInstance<TextInputLayout>().first { it.editText == input }
            assertEquals("请输入聊天内容", field.error.toString())
            input.setText("我：这周有任务吗？\n对方：请在周五前提交报告")
            button(activity, "开始分析").performClick()
            assertTrue(texts(activity).contains("手动输入 · 本地规则"))
            assertTrue(texts(activity).contains("可能在提出行动请求"))
            assertTrue(texts(activity).any { it.contains("重要性 · 高") })
            assertTrue(texts(activity).any { it.contains("语境置信度") })
            assertTrue(EventStore(context).load().isEmpty())
            input.append("\n对方：不用了")
            assertFalse(texts(activity).contains("可能在提出行动请求"))
        } finally { custom.pause().stop().destroy() }
    }

    @Test fun manualTitleConfirmationWritesRecognitionTerm() {
        val intent = Intent(context, MarkChatActivity::class.java)
            .putExtra(MarkChatActivity.EXTRA_SUGGESTED_TITLE, "Test group")
        val controller = Robolectric.buildActivity(MarkChatActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertEquals("Test group", activity.findViewById<TextInputEditText>(R.id.ag_mark_title).text.toString())
            button(activity, "加入识别词条").performClick()
            assertEquals(setOf("Test group"), Prefs(context).whitelist)
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun manualTitleShowsItsSourceAndRejectsAnUnfinishedName() {
        val intent = Intent(context, MarkChatActivity::class.java)
            .putExtra(MarkChatActivity.EXTRA_SUGGESTED_TITLE, "课程…群")
            .putExtra(MarkChatActivity.EXTRA_TITLE_SOURCE, "微信聊天信息")
        val controller = Robolectric.buildActivity(MarkChatActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertTrue(texts(activity).contains("微信聊天信息 · 请核对"))
            button(activity, "加入识别词条").performClick()
            assertFalse(activity.isFinishing)
            assertTrue(Prefs(context).whitelist.isEmpty())
            activity.findViewById<TextInputEditText>(R.id.ag_mark_title).setText("课程通知群")
            button(activity, "加入识别词条").performClick()
            assertEquals(setOf("课程通知群"), Prefs(context).whitelist)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun batchSelectionFitsNarrowScreenAndSelectsOnlyFilteredResults() {
        RuntimeEnvironment.setQualifiers("w320dp-h800dp-mdpi")
        RuntimeEnvironment.setFontScale(2f)
        EventStore(context).upsertAll((1..24).map {
            DemoAttentionData.events.first().copy(id = "batch-$it", title = if (it <= 22) "课程事件 $it" else "其他事件 $it")
        })
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.ag_ledger
            activity.findViewById<TextInputEditText>(R.id.ag_search).setText("课程事件")
            button(activity, "批量归档").performClick()
            val root = activity.window.decorView
            fun measure() {
                shadowOf(Looper.getMainLooper()).idle()
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 800)
            }
            measure()
            val all = descendants(root).filterIsInstance<android.widget.CheckBox>().first { it.text == "全选" }
            val exit = descendants(root).first { it.contentDescription == "退出批量选择" }
            val archive = descendants(root).first { it.contentDescription == "归档已选事件" }
            for (view in listOf(all, exit, archive)) {
                val parent = view.parent as View
                assertTrue("Control has no width", view.width > 0)
                assertTrue("Control clipped horizontally", view.left >= 0 && view.right <= parent.width)
                assertTrue("Control too short", view.height >= 48)
            }
            assertFalse(archive.isEnabled)
            all.performClick()
            assertTrue(texts(activity).contains("22 个已选"))
            descendants(root).first { it.contentDescription == "归档已选事件" }.performClick()
            assertEquals(22, EventStore(context).load().count { it.archived })
            assertEquals(2, EventStore(context).load().count { !it.archived })
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test fun narrowLedgerKeepsFilterLabelsWithinMeasuredBoundsAtDoubleFontScale() {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-mdpi")
        RuntimeEnvironment.setFontScale(2f)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.ag_ledger
            shadowOf(Looper.getMainLooper()).idle()
            val root = activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 320, 640)
            EventFilter.values().forEach { filter ->
                val button = activity.findViewById<MaterialButton>(500 + filter.ordinal)
                assertTrue("Filter target too short", button.height >= 48)
                val layout = requireNotNull(button.layout)
                assertTrue("Filter label clipped vertically", layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                assertTrue("Filter outside screen", button.width > 0 && button.width <= 80)
                for (line in 0 until layout.lineCount) assertEquals("Filter label ellipsized", 0, layout.getEllipsisCount(line))
            }
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun texts(activity: Activity) = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun button(activity: Activity, label: String) = descendants(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text.toString() == label }
}
