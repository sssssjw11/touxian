package com.attentionguard.app

import android.content.ClipboardManager
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.attentionguard.app.core.*
import com.attentionguard.app.ui.AnalysisViews
import com.attentionguard.app.ui.GuardUi
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class AnalysisViewsTest {
    private fun children(view: View): Sequence<View> = sequence {
        yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
    private fun button(view: View, label: String) = children(view).filterIsInstance<MaterialButton>().first { it.text.toString() == label }
    private fun shown(view: View) = children(view).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()
    @Test fun reportCategoriesWorkAndCopyContainsOnlyReplyDraft() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val container = GuardUi(activity).column()
            activity.setContentView(container)
            val proof = ContextEvidence("live:0", "你好", "other", "甲", null)
            val insight = ContextInsight(AnalysisScene.GENERAL, "简短结论", listOf(
                ContextSection(InsightKind.FACT, "原话", "事实正文", EvidenceLevel.SUPPORTED, listOf(proof)),
                ContextSection(InsightKind.INTENT, "待确认", "解释正文", EvidenceLevel.LIMITED, listOf(proof))
            ), listOf(ReplySuggestion("你好，还有什么想说的吗？", "需要澄清时", listOf(proof))), emptyList(), "test")
            AnalysisViews.append(container, insight)
            button(container, "回应建议").performClick()
            assertFalse(shown(container).contains("事实正文"))
            assertTrue(shown(container).contains("你好，还有什么想说的吗？"))
            button(container, "复制回复").performClick()
            assertEquals("你好，还有什么想说的吗？", activity.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            button(container, "明确表达").performClick()
            assertTrue(shown(container).contains("事实正文")); assertFalse(shown(container).contains("解释正文"))
            button(container, "全部").performClick()
            assertTrue(shown(container).contains("解释正文"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun evidenceHighlightsQuotationAndContextNeverCrossesRecordingBoundary() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val source = ContextMessage("archive:2", "这是当前引用原文", "other", "甲", null, null, "r1")
            val sources = listOf(
                ContextMessage("archive:1", "上一条原文", "me", null, null, null, "r1"),
                source,
                ContextMessage("archive:3", "其他记录内容", "other", "乙", null, null, "r2")
            )
            val dialog = AnalysisViews.evidence(controller.get(), "依据", listOf(ContextEvidence(source.ref, "引用", "other", "甲", null)),
                sources = sources, recordingNames = mapOf("r1" to "已确认会话"))
            val texts = children(dialog.window!!.decorView).filterIsInstance<TextView>()
            val original = texts.first { it.text.toString() == source.text }.text as Spanned
            val highlight = original.getSpans(0, original.length, BackgroundColorSpan::class.java).single()
            assertEquals("引用", original.subSequence(original.getSpanStart(highlight), original.getSpanEnd(highlight)).toString())
            button(dialog.window!!.decorView, "查看前后文").performClick()
            assertTrue(shown(dialog.window!!.decorView).contains("上一条原文"))
            assertFalse(shown(dialog.window!!.decorView).contains("其他记录内容"))
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun confirmationShowsOnlyTheBoundedTextAndNeverSendsBeforePositiveClick() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val input = AnalysisInput.manual("对方：" + "甲".repeat(2500) + "范围外结尾", AnalysisScene.GENERAL)
            assertTrue(input.messages.single().truncated)
            var sent = false
            val dialog = AnalysisViews.confirmInput(controller.get(), input, "手动输入") { sent = true }
            assertFalse(sent)
            button(dialog.window!!.decorView, "查看将发送的原文").performClick()
            assertFalse(shown(dialog.window!!.decorView).any { it.contains("范围外结尾") })
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(sent)
        } finally { controller.pause().stop().destroy() }
    }
}
