package com.attentionguard.app

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.attentionguard.app.core.IntentInsight
import com.attentionguard.app.core.JevIntentEngine
import com.attentionguard.app.ui.GuardUi
import com.attentionguard.app.ui.GuardMotion

class CustomIntentActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var root: FrameLayout
    private lateinit var chatBox: TextInputLayout
    private lateinit var chat: TextInputEditText
    private lateinit var result: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = GuardUi(this)
        root = FrameLayout(this).apply { setBackgroundColor(ui.background) }
        val shell = ui.boundedColumn()
        root.addView(shell, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        shell.addView(ui.row().apply {
            setBackgroundColor(ui.background)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(16), ui.dp(8))
            addView(ui.iconButton(R.drawable.ag_arrow_left, "返回") { finish() })
            addView(ui.text("自由分析", R.dimen.ag_type_heading, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.badge("本地"))
        })
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(28)) }
        shell.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(ui.text("读懂一段对话", R.dimen.ag_type_title, bold = true))
        body.addView(ui.text("结合多条上下文，判断意图与情绪线索。\n不会创建事件或通知。", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) })
        val field = ui.field("粘贴或输入聊天内容", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, R.id.ag_custom_chat)
        chatBox = field.first.apply {
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            setEndIconContentDescription("清空聊天内容")
        }
        chat = field.second.apply {
            minLines = 5
            maxLines = 8
            gravity = Gravity.TOP
            filters = arrayOf(InputFilter.LengthFilter(12_000))
        }
        body.addView(chatBox)
        body.addView(ui.button("试一段示例", R.drawable.ag_messages_square, false) {
            chat.setText("我：你今天是不是不开心？\n对方：对呀，有点难过……\n我：愿意跟我说说吗？\n对方：谢谢你陪我，感觉好多了🥰")
            chat.setSelection(chat.length())
            analyze()
        }.apply { layoutParams = ui.lp(12) })
        result = ui.panel().apply { id = R.id.ag_custom_result; layoutParams = ui.lp(18); visibility = View.GONE }
        body.addView(result)
        chat.doAfterTextChanged {
            chatBox.error = null
            result.removeAllViews()
            result.visibility = View.GONE
        }
        shell.addView(ui.column().apply {
            setBackgroundColor(ui.surface)
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            addView(ui.button("开始分析", R.drawable.ag_scan_text) { analyze() }.apply { id = R.id.ag_custom_analyze })
        })
        ui.install(this, root)
    }

    private fun analyze() {
        chatBox.error = null
        val input = chat.text?.toString().orEmpty()
        if (input.isBlank()) {
            chatBox.error = "请输入聊天内容"
            chat.requestFocus()
            return
        }
        val insight = JevIntentEngine.analyzeCustom(input)
        if (insight == null) {
            chatBox.error = "未找到对方消息"
            chat.requestFocus()
            return
        }
        chat.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(chat.windowToken, 0)
        renderResult(insight)
    }

    private fun renderResult(insight: IntentInsight) {
        result.removeAllViews()
        result.visibility = View.VISIBLE
        result.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        result.addView(ui.badge(insight.sourceLabel))
        result.addView(ui.text(insight.label, R.dimen.ag_type_heading, bold = true).apply { layoutParams = ui.lp(16) })
        result.addView(ui.text("重要性 · ${insight.importance.label}",
            R.dimen.ag_type_label, ui.brand, true).apply { layoutParams = ui.lp(8) })
        result.addView(ui.confidence("语境置信度", insight.confidence).apply { layoutParams = ui.lp(12) })
        insight.affect?.let { affect ->
            result.addView(ui.row().apply {
                addView(ui.icon(R.drawable.ag_smile, ui.brand, 20))
                addView(ui.text(affect.label, R.dimen.ag_type_heading, ui.brand, true).apply {
                    setPadding(ui.dp(8), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                layoutParams = ui.lp(16)
            })
            result.addView(ui.confidence("情绪置信度", affect.confidence).apply { layoutParams = ui.lp(8) })
            result.addView(ui.text(affect.evidence, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
        }
        insight.contextSummary?.let { summary ->
            result.addView(ui.text("语境 · $summary", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
        }
        result.addView(ui.heading("字面依据"))
        result.addView(ui.text(insight.evidence).apply {
            layoutParams = ui.lp(8)
            setTextIsSelectable(true)
        })
        insight.contextEvidence?.let { context ->
            result.addView(ui.text("相关上文 · $context", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
        }
        result.addView(ui.heading("下一步"))
        result.addView(ui.text(insight.nextStep).apply { layoutParams = ui.lp(8) })
        result.addView(ui.text("语境置信度是本地线索强度，并非统计概率；请结合上下文核对。",
            R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(16) })
        GuardMotion.revealRows(result)
        result.post { result.requestRectangleOnScreen(android.graphics.Rect(0, 0, result.width, ui.dp(210)), false) }
    }
}
