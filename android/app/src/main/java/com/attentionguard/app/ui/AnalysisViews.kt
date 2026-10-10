package com.attentionguard.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.WindowManager
import android.view.View
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.HorizontalScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.attentionguard.app.R
import com.attentionguard.app.core.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object AnalysisViews {
    fun confirmInput(context: Context, input: AnalysisInput, title: String, overlay: Boolean = false,
                     onSend: () -> Unit): AlertDialog {
        val themed = context(context)
        val ui = GuardUi(themed)
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20)) }
        val current = input.messages.count { !it.ref.startsWith("archive:") }
        body.addView(ui.text(title, bold = true))
        body.addView(ui.text("场景：${input.scene.label}\n" +
            (if (input.live) "当前消息 $current 条 · 历史依据 ${input.messages.size - current} 条"
                else "本地统计覆盖 ${input.totalSelected} 条 · 发送 ${input.messages.size} 条关键消息") +
            "\n正文与摘要共 ${input.messages.sumOf { it.text.length } + input.profileSummary.length} 字符",
            R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) })
        body.addView(ui.text(if (input.live) "结果仅在本次页面显示，内容不会自动录制或归档。" else
            "本地分析保留，原文按当前选择的范围发送。", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
        if (input.profileSummary.isNotBlank()) body.addView(ui.text("附带档案背景摘要（仅供核对，不作为新事实）：\n${input.profileSummary}",
            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(12) })
        val preview = ui.column().apply { visibility = View.GONE }
        input.messages.forEach { message ->
            preview.addView(ui.text((if (message.side == "me") "我" else message.sender ?: "对方") +
                " · " + if (message.ref.startsWith("archive:")) "归档原文" else "当前输入",
                R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(12) })
            preview.addView(ui.text(message.text + if (message.truncated) "\n（正文已截取，此范围外内容不发送）" else "")
                .apply { setTextIsSelectable(true); layoutParams = ui.lp(4) })
        }
        val toggle = ui.button("查看将发送的原文", R.drawable.ag_eye, false) {}
        toggle.setOnClickListener {
            preview.visibility = if (preview.visibility == View.GONE) View.VISIBLE else View.GONE
            toggle.text = if (preview.visibility == View.VISIBLE) "收起发送原文" else "查看将发送的原文"
        }
        body.addView(toggle, ui.lp(12)); body.addView(preview)
        return show(MaterialAlertDialogBuilder(themed).setTitle("发送这些内容到 DeepSeek？")
            .setView(ui.scroll(body)).setNegativeButton("取消", null).setPositiveButton("发送并分析") { _, _ -> onSend() }.create(), overlay)
    }
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
        form.addView(ui.text("确认「单人聊天」后才生成沟通画像；群聊保留成员区分，类型未确认时只分析互动。",
            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
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
                 originals: Map<String, String> = emptyMap(), overlay: Boolean = false,
                 sources: List<ContextMessage> = emptyList(), recordingNames: Map<String, String> = emptyMap()): AlertDialog {
        val themed = context(context)
        val ui = GuardUi(themed)
        val content = ui.column().apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20)) }
        evidence.forEach { proof ->
            val who = if (proof.side == "me") "我" else proof.sender ?: "对方"
            val row = sources.firstOrNull { it.ref == proof.ref }
            val source = when {
                proof.ref.startsWith("live:") -> "当前屏幕"
                proof.ref.startsWith("manual:") -> "手动输入"
                else -> "归档原文" + row?.recordingId?.let { " · ${recordingNames[it] ?: it.takeLast(6)}" }.orEmpty()
            }
            content.addView(ui.text("$who · $source · ${proof.day ?: "日期未确认"}" +
                if (row != null && row.captureMethod != "nodes") " · OCR 待核对" else "", R.dimen.ag_type_caption, ui.sub)
                .apply { layoutParams = ui.lp(12) })
            val original = originals[proof.ref] ?: row?.text ?: proof.quote
            val highlighted = SpannableString(original)
            val start = original.indexOf(proof.quote)
            if (start >= 0) highlighted.setSpan(BackgroundColorSpan(ui.color(R.color.ag_ripple)), start,
                start + proof.quote.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            content.addView(ui.text(original).apply { text = highlighted; setTextIsSelectable(true); layoutParams = ui.lp(6) })
            if (row != null) {
                val same = sources.filter { it.recordingId == row.recordingId }
                val index = same.indexOfFirst { it.ref == row.ref }
                val neighbours = same.subList((index - 2).coerceAtLeast(0), (index + 3).coerceAtMost(same.size))
                if (neighbours.size > 1) {
                    val surrounding = ui.column().apply { visibility = View.GONE; layoutParams = ui.lp(8) }
                    surrounding.addView(ui.text("前后文（当前记录中的相邻消息，可能存在采集缺口）", R.dimen.ag_type_caption, ui.sub))
                    neighbours.forEach { item ->
                        surrounding.addView(ui.text((if (item.side == "me") "我" else item.sender ?: "对方") +
                            " · ${item.day ?: "日期未确认"}" + if (item.ref == row.ref) " · 引用位置" else "",
                            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(10) })
                        surrounding.addView(ui.text(originals[item.ref] ?: item.text).apply { setTextIsSelectable(true); layoutParams = ui.lp(4) })
                    }
                    val toggle = ui.button("查看前后文", R.drawable.ag_messages_square, false) {}
                    toggle.setOnClickListener {
                        surrounding.visibility = if (surrounding.visibility == View.GONE) View.VISIBLE else View.GONE
                        toggle.text = if (surrounding.visibility == View.VISIBLE) "收起前后文" else "查看前后文"
                    }
                    content.addView(toggle, ui.lp(8)); content.addView(surrounding)
                }
            }
        }
        return show(MaterialAlertDialogBuilder(themed).setTitle(title).setView(ui.scroll(content))
            .setPositiveButton("关闭", null).create(), overlay)
    }

    fun append(container: LinearLayout, insight: ContextInsight, originals: Map<String, String> = emptyMap(),
               sources: List<ContextMessage> = emptyList(), recordingNames: Map<String, String> = emptyMap()) {
        val ui = GuardUi(container.context)
        container.addView(ui.text("${insight.scene.label} · ${insight.source}", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
        container.addView(ui.text(insight.summary).apply { layoutParams = ui.lp(8) })
        val content = ui.column()
        val allId = View.generateViewId()
        val ids = InsightKind.entries.associateWith { View.generateViewId() }
        val replyId = View.generateViewId()
        val options = listOf(GuardSegments.Option(allId, "全部")) +
            InsightKind.entries.filter { kind -> insight.sections.any { it.kind == kind } }.map { GuardSegments.Option(ids.getValue(it), it.label) } +
            if (insight.replies.isNotEmpty()) listOf(GuardSegments.Option(replyId, "回应建议")) else emptyList()
        if (options.size > 2) {
            val tabs = GuardSegments(container.context, options, allId, itemWidth = ui.dp(94))
            container.addView(HorizontalScrollView(container.context).apply {
                isHorizontalScrollBarEnabled = false; addView(tabs); layoutParams = ui.lp(12)
                contentDescription = "分析栏目，可左右滑动"
            })
            tabs.addOnButtonCheckedListener { _, id, checked ->
                if (checked) for (index in 0 until content.childCount) {
                    val child = content.getChildAt(index)
                    child.visibility = if (id == allId || child.tag == id) View.VISIBLE else View.GONE
                }
            }
        }
        container.addView(content, ui.lp())
        insight.sections.forEach { section ->
            val card = ui.panel().apply { layoutParams = ui.lp(12); tag = ids.getValue(section.kind) }
            content.addView(card)
            card.addView(ui.text("${section.kind.label} · ${section.title}", bold = true))
            card.addView(ui.text(section.level.label, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(4) })
            card.addView(ui.text(section.detail).apply { layoutParams = ui.lp(6) })
            section.alternatives.forEach {
                card.addView(ui.text("其他解释：$it", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(6) })
            }
            if (section.evidence.isNotEmpty()) card.addView(ui.button("查看原文依据（${section.evidence.size}）", R.drawable.ag_eye, false) {
                evidence(container.context, section.title, section.evidence, originals, sources = sources, recordingNames = recordingNames)
            }.apply { layoutParams = ui.lp(8) })
        }
        if (insight.replies.isNotEmpty()) content.addView(ui.heading("回应建议").apply { tag = replyId })
        insight.replies.forEach { reply ->
            val card = ui.panel().apply { tag = replyId; layoutParams = ui.lp(12) }
            content.addView(card)
            card.addView(ui.text(reply.timing, R.dimen.ag_type_caption, ui.sub))
            card.addView(ui.text(reply.text).apply { layoutParams = ui.lp(6); setTextIsSelectable(true) })
            card.addView(ui.button("复制回复", R.drawable.ag_copy, false) {
                copy(container.context, reply.text)
                ui.feedback(container, "回复已复制")
            }.apply { layoutParams = ui.lp(6) })
            card.addView(ui.button("回复依据", R.drawable.ag_eye, false) {
                evidence(container.context, "回复适用依据", reply.evidence, originals, sources = sources, recordingNames = recordingNames)
            })
        }
        if (insight.limitations.isNotEmpty()) container.addView(ui.text(insight.limitations.joinToString("\n"), R.dimen.ag_type_caption, ui.sub)
            .apply { layoutParams = ui.lp(14) })
    }

    fun metrics(container: LinearLayout, metrics: List<RelationshipMetric>) {
        if (metrics.isEmpty()) return
        val ui = GuardUi(container.context)
        val details = ui.column().apply { visibility = View.GONE }
        val toggle = ui.button("展开本地统计（${metrics.size} 项）", R.drawable.ag_activity, false) {}
        toggle.setOnClickListener {
            details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
            toggle.text = if (details.visibility == View.VISIBLE) "收起本地统计" else "展开本地统计（${metrics.size} 项）"
        }
        container.addView(toggle, ui.lp(12)); container.addView(details)
        metrics.forEach { metric ->
            details.addView(ui.statusRow(metric.label, metric.value, ui.brand).apply { layoutParams = ui.lp(8) })
            if (metric.detail.isNotBlank()) details.addView(ui.text(metric.detail, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(4) })
        }
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("偷闲 · 回应建议", text))
    }
}
