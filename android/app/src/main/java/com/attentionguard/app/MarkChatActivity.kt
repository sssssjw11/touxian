package com.attentionguard.app

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.capture.CaptureRuntime
import com.attentionguard.app.capture.WeChatAdapter
import com.attentionguard.app.ui.GuardUi

class MarkChatActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var field: TextInputLayout
    private lateinit var title: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = GuardUi(this)
        val root = FrameLayout(this).apply { setBackgroundColor(ui.background) }
        val shell = ui.boundedColumn()
        root.addView(shell, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        shell.addView(ui.row().apply {
            setBackgroundColor(ui.surface)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(16), ui.dp(8))
            addView(ui.iconButton(R.drawable.ag_arrow_left, "返回微信") { finish() })
            addView(ui.text("标记当前会话", R.dimen.ag_type_heading, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        })
        val suggested = intent.getStringExtra(EXTRA_SUGGESTED_TITLE).orEmpty()
        val source = intent.getStringExtra(EXTRA_TITLE_SOURCE) ?: "本机标题识别"
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(28)) }
        shell.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(ui.text("确认会话名称", R.dimen.ag_type_title, bold = true))
        body.addView(ui.row().apply {
            layoutParams = ui.lp(12)
            addView(ui.badge(if (suggested.isBlank()) "手动填写 · 微信标题不可读" else "$source · 请核对"))
        })
        val input = ui.field("当前会话名称", suggested, InputType.TYPE_CLASS_TEXT, R.id.ag_mark_title)
        field = input.first
        title = input.second.apply {
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(120))
            selectAll()
        }
        body.addView(field)
        shell.addView(ui.column().apply {
            setBackgroundColor(ui.surface)
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            addView(ui.button("加入识别词条", R.drawable.ag_bookmark_plus) { save() })
        })
        ui.install(this, root)
    }

    private fun save() {
        field.error = null
        val value = title.text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) { field.error = "请输入当前会话完整名称"; title.requestFocus(); return }
        if (WeChatAdapter.isTruncatedTitle(value)) { field.error = "名称含省略号，请补全当前会话名称"; title.requestFocus(); return }
        runCatching { Prefs(this).addRecognitionTerm(value) }
            .onSuccess { result ->
                val bound = CaptureRuntime.actions?.confirmCurrentTitle(value) == true
                val message = when (result) {
                    Prefs.RecognitionTermResult.ADDED_FIRST -> "已加入词条；观测范围已从全部收窄"
                    Prefs.RecognitionTermResult.ADDED -> if (bound) "已标记并绑定当前会话" else "已加入识别词条"
                    Prefs.RecognitionTermResult.EXISTS -> if (bound) "已绑定当前会话" else "该词条已存在"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                finish()
            }
            .onFailure { field.error = "保存失败，请重试" }
    }

    override fun onDestroy() {
        if (isFinishing) CaptureRuntime.actions?.cancelCurrentTitle()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SUGGESTED_TITLE = "com.attentionguard.app.suggested_title"
        const val EXTRA_TITLE_SOURCE = "com.attentionguard.app.title_source"
    }
}
