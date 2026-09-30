package com.attentionguard.app

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.attentionguard.app.capture.CaptureRuntime
import com.attentionguard.app.capture.RecordingActions
import com.attentionguard.app.core.ChatRecording
import com.attentionguard.app.core.ChatSnapshot
import com.attentionguard.app.core.MessageArchive
import com.attentionguard.app.core.Msg
import com.attentionguard.app.core.RecordingState
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDate
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class ConversationAnalysisActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<ConversationAnalysisActivity>>()

    @Before fun clear() {
        MessageArchive(context).use { it.clear() }
        context.getSharedPreferences("attention_guard", 0).edit().clear().commit()
        CaptureRuntime.recording = null
        CaptureRuntime.recordingActions = null
    }

    @After fun close() {
        controllers.forEach { it.pause().stop().destroy() }
        CaptureRuntime.recording = null
        CaptureRuntime.recordingActions = null
    }

    @Test fun emptyRecordsAndDeletedDeepLinkHaveUsableListState() {
        val activity = open("missing-id")
        await(activity)
        assertTrue(activity.findViewById<View>(R.id.ag_conversation_list).isShown)
        assertFalse(activity.findViewById<View>(R.id.ag_conversation_detail).isShown)
        assertTrue(texts(activity).any { it == "暂无会话记录" })
    }

    @Test fun calendarDateFilteringDoesNotTreatCaptureTimeAsMessageTime() {
        seed()
        val activity = open("fixture-1")
        await(activity)
        assertTrue(activity.findViewById<SwitchCompat>(R.id.ag_conversation_undated).isChecked)
        assertTrue(texts(activity).any { it.contains("当前选择 3 条") })
        activity.findViewById<MaterialButtonToggleGroup>(R.id.ag_conversation_range)
            .check(R.id.ag_conversation_three_days)
        await(activity)
        assertFalse(activity.findViewById<SwitchCompat>(R.id.ag_conversation_undated).isChecked)
        assertTrue(texts(activity).any { it.contains("当前选择 1 条") })
        assertFalse(rawTexts(activity).any { it == "上个月的消息" || it == "日期未知的消息" })
        activity.findViewById<SwitchCompat>(R.id.ag_conversation_undated).isChecked = true
        await(activity)
        assertTrue(texts(activity).any { it.contains("当前选择 2 条") })
        assertTrue(rawTexts(activity).any { it == "日期未知的消息" })
        assertFalse(rawTexts(activity).any { it == "上个月的消息" })
    }

    @Test fun localAnalysisPersistsButDoesNotLeakIntoAnotherDateSelection() {
        seed()
        val activity = open("fixture-1")
        await(activity)
        assertFalse(activity.findViewById<MaterialButton>(R.id.ag_conversation_cloud).isEnabled)
        activity.findViewById<MaterialButton>(R.id.ag_conversation_analyze).performClick()
        await(activity)
        MessageArchive(context).use { archive ->
            val stored = JSONObject(requireNotNull(archive.recordingAnalysis("fixture-1")))
            assertTrue(stored.getBoolean("includeUndated"))
            assertTrue(stored.isNull("range"))
            assertEquals(3, stored.getJSONObject("report").getInt("messageCount"))
            assertFalse(stored.getString("fingerprint").isBlank())
        }
        assertTrue(resultTexts(activity).any { it == "本地分析" })
        activity.findViewById<MaterialButtonToggleGroup>(R.id.ag_conversation_range)
            .check(R.id.ag_conversation_three_days)
        await(activity)
        assertFalse(resultTexts(activity).any { it == "本地分析" })
        assertTrue(resultTexts(activity).any { it == "尚无当前范围的分析结果" })
    }

    @Test fun changedMessageContentInvalidatesSavedReportEvenWhenCountDoesNotChange() {
        seed()
        val activity = open("fixture-1")
        await(activity)
        activity.findViewById<MaterialButton>(R.id.ag_conversation_analyze).performClick()
        await(activity)
        MessageArchive(context).use { archive ->
            archive.writableDatabase.execSQL("UPDATE messages SET body=? WHERE stream=? AND body=?",
                arrayOf("已经修正的原文", "fixture-1", "日期未知的消息"))
        }
        activity.findViewById<View>(R.id.ag_conversation_refresh).performClick()
        await(activity)
        assertTrue(rawTexts(activity).any { it == "已经修正的原文" })
        assertFalse(resultTexts(activity).any { it == "本地分析" })
    }

    @Test fun selectedSessionDateModeAndUnknownDateChoiceSurviveRecreation() {
        seed()
        val activity = open("fixture-1")
        await(activity)
        activity.findViewById<MaterialButtonToggleGroup>(R.id.ag_conversation_range)
            .check(R.id.ag_conversation_custom)
        await(activity)
        activity.findViewById<SwitchCompat>(R.id.ag_conversation_undated).isChecked = true
        await(activity)
        val beforeStart = activity.findViewById<MaterialButton>(R.id.ag_conversation_start).text.toString()
        val beforeEnd = activity.findViewById<MaterialButton>(R.id.ag_conversation_end).text.toString()
        val controller = controllers.last()
        controller.recreate()
        val recreated = controller.get()
        await(recreated)
        assertTrue(recreated.findViewById<View>(R.id.ag_conversation_detail).isShown)
        assertEquals(R.id.ag_conversation_custom,
            recreated.findViewById<MaterialButtonToggleGroup>(R.id.ag_conversation_range).checkedButtonId)
        assertEquals(beforeStart, recreated.findViewById<MaterialButton>(R.id.ag_conversation_start).text.toString())
        assertEquals(beforeEnd, recreated.findViewById<MaterialButton>(R.id.ag_conversation_end).text.toString())
        assertTrue(recreated.findViewById<SwitchCompat>(R.id.ag_conversation_undated).isChecked)
    }

    @Test fun evidenceDialogShowsTheOriginalStoredMessage() {
        seed()
        val activity = open("fixture-1")
        await(activity)
        activity.findViewById<MaterialButton>(R.id.ag_conversation_analyze).performClick()
        await(activity)
        val proofButton = children(activity.findViewById(R.id.ag_conversation_result))
            .filterIsInstance<MaterialButton>().first { it.text.startsWith("查看原文依据") }
        proofButton.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(children(dialog.window!!.decorView).filterIsInstance<TextView>()
            .any { it.text.toString() == "我会陪你，别太累" })
    }

    @Test fun deleteRequiresConfirmationAndRemovesOnlySelectedSession() {
        seed()
        seed("fixture-2", "另一会话")
        val activity = open("fixture-1")
        await(activity)
        activity.findViewById<MaterialButton>(R.id.ag_conversation_delete).performClick()
        MessageArchive(context).use { assertNotNull(it.recording("fixture-1")) }
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        await(activity)
        MessageArchive(context).use {
            assertNull(it.recording("fixture-1"))
            assertEquals(0, it.count("fixture-1").total)
            assertNotNull(it.recording("fixture-2"))
            assertEquals(3, it.count("fixture-2").total)
        }
        assertTrue(activity.findViewById<View>(R.id.ag_conversation_list).isShown)
    }

    @Test fun historicalSessionCannotStopAnotherCurrentRecording() {
        seed(state = RecordingState.PAUSED)
        var stopped = 0
        CaptureRuntime.recording = ChatRecording("different-id", "正在记录的会话", System.currentTimeMillis())
        CaptureRuntime.recordingActions = object : RecordingActions {
            override fun startRecording() = true
            override fun pauseRecording() = Unit
            override fun resumeRecording(id: String) = false
            override fun stopRecording() { stopped++ }
        }
        val activity = open("fixture-1")
        await(activity)
        assertFalse(activity.findViewById<View>(R.id.ag_conversation_stop).isShown)
        activity.findViewById<MaterialButton>(R.id.ag_conversation_stop).performClick()
        assertEquals(0, stopped)
    }

    private fun seed(id: String = "fixture-1", title: String = "测试会话", state: RecordingState = RecordingState.ACTIVE) {
        MessageArchive(context).use { archive ->
            val record = ChatRecording(id, title, System.currentTimeMillis())
            archive.createRecording(record)
            archive.append(ChatSnapshot(title, listOf(
                Msg("me", "上个月的消息", date = LocalDate.now().minusDays(30).toString()),
                Msg("other", "我会陪你，别太累", sender = "测试对象", date = LocalDate.now().toString()),
                Msg("other", "日期未知的消息", sender = "测试对象")
            ), "com.tencent.mm"), id)
            if (state != RecordingState.ACTIVE) archive.updateRecording(record.copy(state = state))
        }
    }

    private fun open(id: String? = null): ConversationAnalysisActivity {
        val intent = Intent(context, ConversationAnalysisActivity::class.java)
        if (id != null) intent.putExtra(ConversationAnalysisActivity.EXTRA_RECORDING_ID, id)
        val controller = Robolectric.buildActivity(ConversationAnalysisActivity::class.java, intent).setup()
        controllers.add(controller)
        return controller.get()
    }

    private fun await(activity: ConversationAnalysisActivity) {
        val field = ConversationAnalysisActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val executor = field.get(activity) as ExecutorService
        // A deleted deep link can schedule one additional list load from its UI callback.
        repeat(3) {
            executor.submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun texts(activity: ConversationAnalysisActivity) =
        children(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun rawTexts(activity: ConversationAnalysisActivity) =
        children(activity.findViewById(R.id.ag_conversation_messages)).filterIsInstance<TextView>()
            .map { it.text.toString() }.toList()
    private fun resultTexts(activity: ConversationAnalysisActivity) =
        children(activity.findViewById(R.id.ag_conversation_result)).filterIsInstance<TextView>()
            .map { it.text.toString() }.toList()
    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
}
