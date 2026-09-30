package com.attentionguard.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.attentionguard.app.core.EventCategory
import com.attentionguard.app.core.EventPriority
import com.attentionguard.app.core.MessageKeywordRule
import com.attentionguard.app.core.Prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class MessageKeywordActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = Prefs(context)
    private val controllers = mutableListOf<ActivityController<MessageKeywordActivity>>()

    @Before fun reset() {
        context.getSharedPreferences("attention_guard", 0).edit().clear().commit()
    }

    @After fun close() {
        controllers.forEach { it.pause().stop().destroy() }
    }

    @Test fun addingRuleSavesImmediatelyWithDefaultsAndKeepsTitleScopeSeparate() {
        prefs.whitelist = setOf("课程群")
        val activity = open()
        val dialog = add(activity)
        dialog.findViewById<TextInputEditText>(R.id.ag_keyword_input)!!.setText("课程作业")
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        val rule = prefs.messageKeywordRules.single()
        assertEquals("课程作业", rule.keyword)
        assertEquals(EventCategory.ACADEMIC_ADMIN, rule.category)
        assertEquals(EventPriority.P2, rule.priority)
        assertTrue(rule.enabled)
        assertEquals(setOf("课程群"), prefs.whitelist)
        assertFalse(dialog.isShowing)
        assertTrue(texts(activity).any { it == "课程作业" })
    }

    @Test fun saveHandlerIsReadyBeforeQueuedDialogCallbacksRun() {
        val activity = open()
        activity.findViewById<MaterialButton>(R.id.ag_keyword_add).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<TextInputEditText>(R.id.ag_keyword_input)!!.setText("第一下点击")
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
        assertEquals("第一下点击", prefs.messageKeywordRules.single().keyword)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun editingRuleKeepsIdentityAndPersistsCategoryPriorityAndEnabledState() {
        val original = MessageKeywordRule(keyword = "报名")
        prefs.messageKeywordRules = listOf(original)
        val activity = open()
        action(activity, "编辑正文关键词：报名").performClick()
        val dialog = latest()
        dialog.findViewById<TextInputEditText>(R.id.ag_keyword_input)!!.setText("竞赛报名")
        dialog.findViewById<Spinner>(R.id.ag_keyword_category)!!.setSelection(EventCategory.COMPETITION.ordinal)
        dialog.findViewById<Spinner>(R.id.ag_keyword_priority)!!.setSelection(EventPriority.P1.ordinal)
        dialog.findViewById<SwitchCompat>(R.id.ag_keyword_enabled)!!.isChecked = false
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        val saved = prefs.messageKeywordRules.single()
        assertEquals(original.id, saved.id)
        assertEquals("竞赛报名", saved.keyword)
        assertEquals(EventCategory.COMPETITION, saved.category)
        assertEquals(EventPriority.P1, saved.priority)
        assertFalse(saved.enabled)
    }

    @Test fun disablingFromListPersistsBeforeReturning() {
        prefs.messageKeywordRules = listOf(MessageKeywordRule(keyword = "截止"))
        val activity = open()
        val toggle = action(activity, "启用正文关键词：截止") as SwitchCompat
        toggle.isChecked = false
        assertFalse(prefs.messageKeywordRules.single().enabled)
        action(activity, "返回").performClick()
        assertTrue(activity.isFinishing)
        assertFalse(Prefs(context).messageKeywordRules.single().enabled)
    }

    @Test fun deletionNeedsConfirmationAndLeavesOtherRulesIntact() {
        prefs.messageKeywordRules = listOf(MessageKeywordRule("截止"), MessageKeywordRule("讲座"))
        val activity = open()
        action(activity, "删除正文关键词：截止").performClick()
        assertEquals(2, prefs.messageKeywordRules.size)
        click(latest(), AlertDialog.BUTTON_NEGATIVE)
        assertEquals(2, prefs.messageKeywordRules.size)
        action(activity, "删除正文关键词：截止").performClick()
        click(latest(), AlertDialog.BUTTON_POSITIVE)
        assertEquals(listOf("讲座"), prefs.messageKeywordRules.map { it.keyword })
    }

    @Test fun invalidAndNormalizedDuplicateKeywordsStayInEditorWithoutSaving() {
        prefs.messageKeywordRules = listOf(MessageKeywordRule("ＡＩ"))
        val activity = open()
        val dialog = add(activity)
        val input = dialog.findViewById<TextInputEditText>(R.id.ag_keyword_input)!!
        fun error() = children(dialog.window!!.decorView).filterIsInstance<TextInputLayout>().single().error.toString()
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertEquals("请输入正文关键词", error())
        input.setText("a".repeat(81))
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertEquals("正文关键词最多 80 个字符", error())
        input.setText(" ai ")
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertEquals("该关键词已存在，请编辑原规则", error())
        assertTrue(dialog.isShowing)
        assertEquals(1, prefs.messageKeywordRules.size)
    }

    @Test fun hundredRuleLimitKeepsFormOpenAndStoredRulesUnchanged() {
        prefs.messageKeywordRules = (0 until 100).map { MessageKeywordRule("关键词$it") }
        val activity = open()
        val dialog = add(activity)
        dialog.findViewById<TextInputEditText>(R.id.ag_keyword_input)!!.setText("新增词条")
        click(dialog, AlertDialog.BUTTON_POSITIVE)
        assertEquals(View.VISIBLE, dialog.findViewById<TextView>(R.id.ag_keyword_status)!!.visibility)
        assertTrue(dialog.findViewById<TextView>(R.id.ag_keyword_status)!!.text.contains("100"))
        assertTrue(dialog.isShowing)
        assertEquals(100, prefs.messageKeywordRules.size)
    }

    @Test fun rotationRestoresUnsavedEditorAndKeepsOriginalRuleIdentity() {
        val original = MessageKeywordRule("作业")
        prefs.messageKeywordRules = listOf(original)
        val activity = open()
        action(activity, "编辑正文关键词：作业").performClick()
        val dialog = latest()
        dialog.findViewById<TextInputEditText>(R.id.ag_keyword_input)!!.setText("实验作业")
        dialog.findViewById<Spinner>(R.id.ag_keyword_category)!!.setSelection(EventCategory.COURSE.ordinal)
        dialog.findViewById<Spinner>(R.id.ag_keyword_priority)!!.setSelection(EventPriority.P0.ordinal)
        dialog.findViewById<SwitchCompat>(R.id.ag_keyword_enabled)!!.isChecked = false
        val controller = controllers.last()
        controller.recreate()
        val restored = latest()
        assertEquals("实验作业", restored.findViewById<TextInputEditText>(R.id.ag_keyword_input)!!.text.toString())
        assertEquals(EventCategory.COURSE.ordinal, restored.findViewById<Spinner>(R.id.ag_keyword_category)!!.selectedItemPosition)
        assertEquals(EventPriority.P0.ordinal, restored.findViewById<Spinner>(R.id.ag_keyword_priority)!!.selectedItemPosition)
        assertFalse(restored.findViewById<SwitchCompat>(R.id.ag_keyword_enabled)!!.isChecked)
        assertEquals("作业", prefs.messageKeywordRules.single().keyword)
        click(restored, AlertDialog.BUTTON_POSITIVE)
        assertEquals(original.id, prefs.messageKeywordRules.single().id)
        assertEquals("实验作业", prefs.messageKeywordRules.single().keyword)
    }

    @Test fun settingsEntryOpensIndependentBodyRuleEditor() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<MaterialButton>(R.id.ag_message_keywords).performClick()
            assertEquals(MessageKeywordActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        } finally { controller.pause().stop().destroy() }
    }

    private fun open(): MessageKeywordActivity {
        val controller = Robolectric.buildActivity(MessageKeywordActivity::class.java).setup()
        controllers.add(controller)
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }
    private fun add(activity: MessageKeywordActivity): AlertDialog {
        activity.findViewById<MaterialButton>(R.id.ag_keyword_add).performClick()
        return latest()
    }
    private fun latest(): AlertDialog {
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }
    private fun click(dialog: AlertDialog, which: Int) {
        assertTrue(dialog.getButton(which).performClick())
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun action(activity: MessageKeywordActivity, description: String) =
        children(activity.window.decorView).first { it.contentDescription?.toString() == description }
    private fun texts(activity: MessageKeywordActivity) =
        children(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
}
