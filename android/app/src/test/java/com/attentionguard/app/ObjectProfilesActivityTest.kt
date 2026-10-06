package com.attentionguard.app

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.attentionguard.app.core.*
import com.google.android.material.button.MaterialButton
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class ObjectProfilesActivityTest {
    private val context = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<ObjectProfilesActivity>>()
    private fun open(profileId: String? = null, recordingId: String? = null, returnResult: Boolean = false): ObjectProfilesActivity =
        Robolectric.buildActivity(ObjectProfilesActivity::class.java, Intent(context, ObjectProfilesActivity::class.java)
            .putExtra(ObjectProfilesActivity.EXTRA_PROFILE_ID, profileId).putExtra(ObjectProfilesActivity.EXTRA_RECORDING_ID, recordingId)
            .putExtra(ObjectProfilesActivity.EXTRA_RETURN_RESULT, returnResult))
            .setup().also(controllers::add).get().also(::await)
    private fun await(activity: ObjectProfilesActivity) {
        val worker = ObjectProfilesActivity::class.java.getDeclaredField("worker").apply { isAccessible = true }.get(activity) as ExecutorService
        repeat(3) { shadowOf(Looper.getMainLooper()).idle(); worker.submit {}.get(10, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun children(view: View): Sequence<View> = sequence {
        yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
    private fun button(activity: ObjectProfilesActivity, label: String) =
        children(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text.toString() == label }
    private fun seed() {
        MessageArchive(context).use {
            it.createRecording(ChatRecording("r", "聊天", 1))
            it.append(ChatSnapshot("聊天", listOf(Msg("me", "我陪你"), Msg("other", "谢谢你", "甲"))), "r", requireRecording = true)
        }
    }
    @After fun close() { controllers.forEach { it.pause().stop().destroy() } }

    @Test fun createChooseSceneLinkAnalyzeViewSourceAndDeletePreserveRecording() {
        seed()
        val activity = open(recordingId = "r")
        button(activity, "新建对象档案").performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        children(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("对象甲")
        val selectors = children(dialog.window!!.decorView).filterIsInstance<Spinner>().toList()
        selectors[0].setSelection(AnalysisScene.WORK.ordinal); selectors[1].setSelection(ProfileKind.PERSON.ordinal)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); await(activity)
        MessageArchive(context).use {
            val profile = it.profiles().single()
            assertEquals("对象甲", profile.name); assertEquals(AnalysisScene.WORK, profile.scene)
            assertEquals(profile.id, it.profileForRecording("r")!!.id)
        }
        button(activity, "更新本地画像").performClick(); await(activity)
        MessageArchive(context).use { assertNotNull(it.profileSnapshot(it.profiles().single().id)!!.freshLocalReport) }
        val proof = children(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text.startsWith("查看原文依据") }
        proof.performClick()
        val source = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(children(source.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == "谢谢你" || it.text.toString() == "我陪你" })
        source.dismiss()
        button(activity, "删除档案").performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); await(activity)
        MessageArchive(context).use { assertTrue(it.profiles().isEmpty()); assertEquals(2, it.recordingMessages("r").size) }
    }
    @Test fun staleProfileIsMarkedPendingAndLocalResultsStayUnavailableUntilUpdate() {
        seed()
        MessageArchive(context).use {
            it.createProfile(ConversationProfile("p", "对象"), "r")
            val snapshot = it.profileSnapshot("p")!!
            it.saveProfileAnalysis("p", snapshot.fingerprint, RelationshipAnalysis.analyze(snapshot.messages, "对象", profile = snapshot.profile))
            it.writableDatabase.execSQL("UPDATE messages SET body='修正后的消息' WHERE stream='r'")
        }
        val activity = open("p")
        val text = children(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(text.any { it.contains("画像待更新") })
        assertFalse(text.any { it == "本地画像" })
    }

    @Test fun sameNameProfilesCanBeFoundByRecordOrIdAndTransferReturnsToCallerOnlyAfterConfirmation() {
        seed()
        MessageArchive(context).use {
            it.createProfile(ConversationProfile("p-aaaaaa", "同名对象", AnalysisScene.FRIEND), "r")
            it.createProfile(ConversationProfile("p-bbbbbb", "同名对象", AnalysisScene.WORK))
        }
        val activity = open(recordingId = "r", returnResult = true)
        val search = activity.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.ag_profile_search)
        search.setText("聊天")
        assertEquals(1, children(activity.window.decorView).filterIsInstance<MaterialButton>().count { it.text.toString() == "关联到这个档案" })
        search.setText("bbbbbb")
        button(activity, "关联到这个档案").performClick()
        var dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(children(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("原档案移除") })
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        MessageArchive(context).use { assertEquals("p-aaaaaa", it.profileForRecording("r")!!.id) }
        assertFalse(activity.isFinishing)
        button(activity, "关联到这个档案").performClick()
        dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); await(activity)
        MessageArchive(context).use {
            assertEquals("p-bbbbbb", it.profileForRecording("r")!!.id); assertEquals(2, it.recordingMessages("r").size)
        }
        assertTrue(activity.isFinishing)
        assertEquals(android.app.Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals("p-bbbbbb", shadowOf(activity).resultIntent.getStringExtra(ObjectProfilesActivity.EXTRA_PROFILE_ID))
    }

    @Test fun cancelOnlyAppearsDuringWorkAndUpdateMetadataSurvivesReopen() {
        seed()
        MessageArchive(context).use { it.createProfile(ConversationProfile("p", "对象"), "r") }
        val activity = open("p")
        assertEquals(View.GONE, button(activity, "取消分析").visibility)
        val update = button(activity, "更新本地画像")
        update.performClick()
        assertFalse(update.isEnabled)
        assertEquals(View.VISIBLE, button(activity, "取消分析").visibility)
        await(activity)
        assertEquals(View.GONE, button(activity, "取消分析").visibility)
        MessageArchive(context).use {
            val snapshot = it.profileSnapshot("p")!!
            assertNotNull(snapshot.lastAnalyzedAt); assertEquals(2, snapshot.lastMessageCount)
            assertEquals("画像已更新", snapshot.updateLabel)
        }
    }

    @Test fun staleSummaryCanBeReviewedButHasNoCurrentReportAndUnlinkNeedsConfirmation() {
        seed()
        MessageArchive(context).use {
            it.createProfile(ConversationProfile("p", "对象"), "r")
            val snapshot = it.profileSnapshot("p")!!
            it.saveProfileAnalysis("p", snapshot.fingerprint, RelationshipAnalysis.analyze(snapshot.messages, "对象", profile = snapshot.profile))
            it.append(ChatSnapshot("聊天", listOf(Msg("me", "我陪你"), Msg("other", "谢谢你", "甲"), Msg("other", "需要空间", "甲"))), "r", requireRecording = true)
            assertNull(it.profileSnapshot("p")!!.freshReport)
        }
        val activity = open("p")
        button(activity, "回顾上次摘要").performClick()
        assertTrue(children(activity.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains("本次分析不会引用") })
        button(activity, "解除关联").performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        MessageArchive(context).use { assertNotNull(it.profileForRecording("r")) }
        button(activity, "解除关联").performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick(); await(activity)
        MessageArchive(context).use { assertNull(it.profileForRecording("r")); assertTrue(it.recordingMessages("r").isNotEmpty()) }
    }
}
