package com.attentionguard.app

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged
import com.attentionguard.app.core.EventCategory
import com.attentionguard.app.core.EventPriority
import com.attentionguard.app.core.MessageKeywordRule
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.ui.GuardMotion
import com.attentionguard.app.ui.GuardUi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Independently saved body rules; changing these never rewrites the title scope. */
class MessageKeywordActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout
    private lateinit var countText: TextView
    private lateinit var ruleList: LinearLayout
    private var editor: Editor? = null
    private data class Draft(
        val id: String? = null,
        val keyword: String = "",
        val category: EventCategory = EventCategory.ACADEMIC_ADMIN,
        val priority: EventPriority = EventPriority.P2,
        val enabled: Boolean = true
    )
    private data class Editor(
        val id: String?,
        val box: TextInputLayout,
        val input: TextInputEditText,
        val category: Spinner,
        val priority: Spinner,
        val enabled: SwitchCompat,
        val status: TextView,
        val dialog: AlertDialog
    ) {
        fun draft() = Draft(id, input.text.toString(), EventCategory.values()[category.selectedItemPosition],
            EventPriority.values()[priority.selectedItemPosition], enabled.isChecked)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = GuardUi(this)
        prefs = Prefs(this)
        val root = FrameLayout(this).apply { setBackgroundColor(ui.background) }
        val shell = ui.boundedColumn()
        root.addView(shell, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        shell.addView(ui.row().apply {
            setPadding(ui.dp(8), ui.dp(8), ui.dp(16), ui.dp(8))
            addView(ui.iconButton(R.drawable.ag_arrow_left, "返回") { finish() })
            addView(ui.text("消息正文关键词", R.dimen.ag_type_heading, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        })
        body = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(28)) }
        shell.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        countText = ui.text("", R.dimen.ag_type_label, ui.sub).apply {
            layoutParams = ui.lp(8)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(countText)
        body.addView(ui.button("增加关键词", R.drawable.ag_bookmark_plus) { openEditor(Draft()) }.apply {
            id = R.id.ag_keyword_add
            layoutParams = ui.lp(12)
        })
        ruleList = ui.column().apply { id = R.id.ag_keyword_list; layoutParams = ui.lp(8) }
        body.addView(ruleList)
        ui.install(this, root)
        render()
        if (savedInstanceState?.getBoolean("editor_open") == true) openEditor(Draft(
            id = savedInstanceState.getString("draft_id"),
            keyword = savedInstanceState.getString("draft_keyword").orEmpty(),
            category = EventCategory.values().getOrElse(savedInstanceState.getInt("draft_category")) { EventCategory.ACADEMIC_ADMIN },
            priority = EventPriority.values().getOrElse(savedInstanceState.getInt("draft_priority", EventPriority.P2.ordinal)) { EventPriority.P2 },
            enabled = savedInstanceState.getBoolean("draft_enabled", true)
        ))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val rules = prefs.messageKeywordRules
        countText.text = "事件规则 · " + rules.count { it.enabled } + " 条启用 / " + rules.size + " 条"
        ruleList.removeAllViews()
        if (rules.isEmpty()) {
            ruleList.addView(ui.text("暂无正文关键词", tint = ui.sub).apply { layoutParams = ui.lp(20) })
            return
        }
        rules.forEach { rule ->
            val card = ui.column().apply {
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                background = ui.shape(ui.surface, ui.line, 8)
                layoutParams = ui.lp(10)
            }
            card.addView(ui.row().apply {
                addView(ui.text(rule.keyword, bold = true).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.toggle("", rule.enabled).apply {
                    contentDescription = "启用正文关键词：" + rule.keyword
                    tooltipText = contentDescription
                    var updating = false
                    setOnCheckedChangeListener { _, checked ->
                        if (updating) return@setOnCheckedChangeListener
                        val latest = prefs.messageKeywordRules
                        val index = latest.indexOfFirst { it.id == rule.id }
                        if (index < 0) { render(); return@setOnCheckedChangeListener }
                        val changed = latest.toMutableList().apply { this[index] = this[index].copy(enabled = checked) }
                        runCatching { prefs.messageKeywordRules = changed }.onSuccess {
                            render()
                        }.onFailure {
                            updating = true
                            isChecked = !checked
                            updating = false
                            ui.feedback(body, "修改未保存，请重试")
                        }
                    }
                }, LinearLayout.LayoutParams(-2, ui.dp(56)).apply { leftMargin = ui.dp(8) })
            })
            card.addView(ui.row().apply {
                addView(ui.column().apply {
                    addView(ui.text(rule.category.label, R.dimen.ag_type_caption, ui.sub))
                    addView(ui.text("重要性 · " + rule.priority.label, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(4) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ui.iconButton(R.drawable.ag_sliders_horizontal, "编辑正文关键词：" + rule.keyword) {
                    val fresh = prefs.messageKeywordRules.firstOrNull { it.id == rule.id } ?: return@iconButton
                    openEditor(Draft(fresh.id, fresh.keyword, fresh.category, fresh.priority, fresh.enabled))
                })
                addView(ui.iconButton(R.drawable.ag_x, "删除正文关键词：" + rule.keyword) { confirmDelete(rule) })
            })
            ruleList.addView(card)
        }
        GuardMotion.revealRows(ruleList)
    }

    private fun openEditor(draft: Draft) {
        if (editor?.dialog?.isShowing == true) return
        val form = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(12)) }
        val field = ui.field("正文关键词", draft.keyword, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            R.id.ag_keyword_input)
        field.second.setSingleLine()
        field.second.doAfterTextChanged { field.first.error = null }
        form.addView(field.first)
        val category = menu(form, "事件类型", EventCategory.values().map { it.label }, draft.category.ordinal, R.id.ag_keyword_category)
        val priority = menu(form, "重要性", EventPriority.values().map { it.label }, draft.priority.ordinal, R.id.ag_keyword_priority)
        val enabled = ui.toggle("启用规则", draft.enabled, R.id.ag_keyword_enabled)
        form.addView(enabled)
        val status = ui.text("", R.dimen.ag_type_caption, ui.color(R.color.ag_danger)).apply {
            id = R.id.ag_keyword_status
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        form.addView(status)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (draft.id == null) "增加正文关键词" else "编辑正文关键词")
            .setView(ui.scroll(form))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        val state = Editor(draft.id, field.first, field.second, category, priority, enabled, status, dialog)
        editor = state
        dialog.setOnDismissListener { if (editor === state) editor = null }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(state) }
    }

    private fun menu(form: LinearLayout, label: String, choices: List<String>, selected: Int, viewId: Int): Spinner {
        form.addView(ui.text(label, R.dimen.ag_type_label, ui.sub, true).apply { layoutParams = ui.lp(16) })
        val menu = Spinner(this).apply {
            id = viewId
            contentDescription = label
            minimumHeight = ui.dp(48)
            adapter = object : ArrayAdapter<String>(this@MessageKeywordActivity, android.R.layout.simple_spinner_item, choices) {
                init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    style(super.getView(position, convertView, parent))
                override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                    style(super.getDropDownView(position, convertView, parent))
                private fun style(view: View): View = view.apply {
                    (this as? TextView)?.apply {
                        setTextColor(ui.ink)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ag_type_body))
                        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                        letterSpacing = 0f
                    }
                }
            }
            setSelection(selected)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(ui.dp(10), 0, ui.dp(10), 0)
        }
        form.addView(ui.row().apply {
            background = ui.shape(ui.surface, ui.line, 8)
            setPadding(0, 0, ui.dp(12), 0)
            layoutParams = ui.lp(6)
            addView(menu, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
            addView(ui.icon(R.drawable.ag_chevron_down, ui.sub, 18).apply { setOnClickListener { menu.performClick() } })
        })
        return menu
    }

    private fun save(state: Editor) {
        state.box.error = null
        state.status.visibility = View.GONE
        val draft = state.draft()
        val keyword = draft.keyword.trim()
        if (keyword.isEmpty() || keyword.length > 80) {
            state.box.error = if (keyword.isEmpty()) "请输入正文关键词" else "正文关键词最多 80 个字符"
            state.input.requestFocus()
            return
        }
        val rules = prefs.messageKeywordRules
        if (rules.any { it.id != draft.id && MessageKeywordRule.normalizedKeyword(it.keyword) == MessageKeywordRule.normalizedKeyword(keyword) }) {
            state.box.error = "该关键词已存在，请编辑原规则"
            return
        }
        if (draft.id == null && rules.size >= 100) {
            state.status.text = "最多保存 100 条正文关键词，请先删除不再使用的规则"
            state.status.visibility = View.VISIBLE
            return
        }
        val updated = rules.toMutableList()
        val rule = if (draft.id == null) MessageKeywordRule(keyword = keyword, category = draft.category,
            priority = draft.priority, enabled = draft.enabled)
        else MessageKeywordRule(keyword = keyword, category = draft.category, priority = draft.priority,
            id = draft.id, enabled = draft.enabled)
        if (draft.id == null) updated.add(rule)
        else {
            val index = updated.indexOfFirst { it.id == draft.id }
            if (index < 0) {
                state.status.text = "这条规则已被删除，请重新添加"
                state.status.visibility = View.VISIBLE
                return
            }
            updated[index] = rule
        }
        runCatching { prefs.messageKeywordRules = updated }.onSuccess {
            state.dialog.dismiss()
            render()
            ui.feedback(body, "正文关键词已保存")
        }.onFailure {
            state.status.text = "规则未能保存，请重试"
            state.status.visibility = View.VISIBLE
        }
    }

    private fun confirmDelete(rule: MessageKeywordRule) {
        MaterialAlertDialogBuilder(this).setTitle("删除正文关键词？")
            .setMessage(rule.keyword)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                runCatching { prefs.messageKeywordRules = prefs.messageKeywordRules.filterNot { it.id == rule.id } }
                    .onSuccess { render(); ui.feedback(body, "已删除正文关键词") }
                    .onFailure { ui.feedback(body, "删除未保存，请重试") }
            }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val draft = editor?.takeIf { it.dialog.isShowing }?.draft()
        outState.putBoolean("editor_open", draft != null)
        draft?.let {
            outState.putString("draft_id", it.id)
            outState.putString("draft_keyword", it.keyword)
            outState.putInt("draft_category", it.category.ordinal)
            outState.putInt("draft_priority", it.priority.ordinal)
            outState.putBoolean("draft_enabled", it.enabled)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        editor?.dialog?.dismiss()
        editor = null
        super.onDestroy()
    }
}
