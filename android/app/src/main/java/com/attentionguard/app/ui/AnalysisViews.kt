package com.attentionguard.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.attentionguard.app.R
import com.attentionguard.app.core.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object AnalysisViews {
    fun context(context: Context) = ContextThemeWrapper(context, R.style.Theme_AttentionGuard)
    fun show(dialog: AlertDialog, overlay: Boolean = false): AlertDialog {
        if (overlay) dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.show()
        return dialog
    }

    fun editProfile(context: Context, profile: ConversationProfile? = null, initialName: String = "",
                    overlay: Boolean = false, onSave: (String, AnalysisScene, ProfileKind) -> Unit): AlertDialog {
        val themed = context(context)
        val ui = GuardUi(themed)
        val form = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)) }
        val name = EditText(themed).apply {
            hint = "对象名称"; setSingleLine(true); setText(profile?.name ?: initialName)
            contentDescription = "对象档案名称"
        }
        fun selector(label: String, options: List<String>, selected: Int): Spinner {
            form.addView(ui.text(label, R.dimen.ag_type_label).apply { layoutParams = ui.lp(12) })
            return Spinner(themed).apply {
                contentDescription = label
                minimumHeight = ui.dp(48)
                adapter = ArrayAdapter(themed, android.R.layout.simple_spinner_dropdown_item, options)
                setSelection(selected); form.addView(this, ui.lp(4))
            }
        }
        form.addView(name, ui.lp())
        val scenes = selector("分析场景", AnalysisScene.entries.map { it.label }, profile?.scene?.ordinal ?: 0)
        val kinds = selector("对话类型", ProfileKind.entries.map { it.label }, profile?.kind?.ordinal ?: 0)
        form.addView(ui.text("档案按你的明确关联保存；相同名称不会自动合并。", R.dimen.ag_type_caption, ui.sub)
            .apply { layoutParams = ui.lp(12) })
        val dialog = MaterialAlertDialogBuilder(themed).setTitle(if (profile == null) "为当前对象建档" else "编辑对象档案")
            .setView(ui.scroll(form)).setNegativeButton("取消", null)
            .setPositiveButton(if (profile == null && overlay) "建档并开始记录" else "保存", null).create()
        show(dialog, overlay)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = name.text.toString().trim()
            if (value.isBlank() || value.length > 80) name.error = "请输入 1 至 80 字名称"
            else { dialog.dismiss(); onSave(value, AnalysisScene.entries[scenes.selectedItemPosition], ProfileKind.entries[kinds.selectedItemPosition]) }
        }
        return dialog
    }

    fun evidence(context: Context, title: String, evidence: List<ContextEvidence>,
                 originals: Map<String, String> = emptyMap(), overlay: Boolean = false): AlertDialog {
        val themed = context(context)
        val ui = GuardUi(themed)
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20)) }
        evidence.forEach { proof ->
            val who = if (proof.side == "me") "我" else proof.sender ?: "对方"
            val source = if (proof.ref.startsWith("live:")) "当前屏幕" else "归档原文"
            content.addView(ui.text("$who · $source · ${proof.day ?: "日期未确认"}", R.dimen.ag_type_caption, ui.sub)
                .apply { layoutParams = ui.lp(12) })
            content.addView(ui.text(originals[proof.ref] ?: proof.quote).apply { setTextIsSelectable(true); layoutParams = ui.lp(6) })
        }
        return show(MaterialAlertDialogBuilder(themed).setTitle(title).setView(ui.scroll(content))
            .setPositiveButton("关闭", null).create(), overlay)
    }

    fun append(container: LinearLayout, insight: ContextInsight, originals: Map<String, String> = emptyMap()) {
        val ui = GuardUi(container.context)
        container.addView(ui.text("${insight.scene.label} · ${insight.source}", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
        container.addView(ui.text(insight.summary).apply { layoutParams = ui.lp(8) })
        insight.sections.forEach { section ->
            val card = ui.panel().apply { layoutParams = ui.lp(12) }
            container.addView(card)
            card.addView(ui.text("${section.kind.label} · ${section.title}", bold = true))
            card.addView(ui.text(section.level.label, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(4) })
            card.addView(ui.text(section.detail).apply { layoutParams = ui.lp(6) })
            section.alternatives.forEach {
                card.addView(ui.text("其他解释：$it", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(6) })
            }
            if (section.evidence.isNotEmpty()) card.addView(ui.button("查看原文依据（${section.evidence.size}）", R.drawable.ag_eye, false) {
                evidence(container.context, section.title, section.evidence, originals)
            }.apply { layoutParams = ui.lp(8) })
        }
        if (insight.replies.isNotEmpty()) container.addView(ui.heading("回应建议"))
        insight.replies.forEach { reply ->
            container.addView(ui.text(reply.timing, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
            container.addView(ui.text(reply.text).apply { layoutParams = ui.lp(6); setTextIsSelectable(true) })
            container.addView(ui.button("复制回复", R.drawable.ag_copy, false) {
                copy(container.context, reply.text)
                ui.feedback(container, "回复已复制")
            }.apply { layoutParams = ui.lp(6) })
            container.addView(ui.button("回复依据", R.drawable.ag_eye, false) {
                evidence(container.context, "回复适用依据", reply.evidence, originals)
            })
        }
        if (insight.limitations.isNotEmpty()) container.addView(ui.text(insight.limitations.joinToString("\n"), R.dimen.ag_type_caption, ui.sub)
            .apply { layoutParams = ui.lp(14) })
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("偷闲 · 回应建议", text))
    }
}
