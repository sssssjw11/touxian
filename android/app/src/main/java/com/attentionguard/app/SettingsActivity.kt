package com.attentionguard.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.attentionguard.app.core.*
import com.attentionguard.app.ai.DeepSeekAttentionClient
import com.attentionguard.app.ui.GuardUi
import com.attentionguard.app.ui.GuardMotion
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SettingsActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var keyBox: TextInputLayout
    private lateinit var modelBox: TextInputLayout
    private lateinit var key: TextInputEditText
    private lateinit var model: TextInputEditText
    private lateinit var context: TextInputEditText
    private lateinit var whitelist: TextInputEditText
    private lateinit var cloud: SwitchCompat
    private lateinit var auto: SwitchCompat
    private lateinit var opacity: SeekBar
    private lateinit var result: TextView
    private lateinit var test: MaterialButton
    private var original: List<Any> = emptyList()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var pending: Future<*>? = null
    private var client: DeepSeekAttentionClient? = null
    private var testing = false
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = GuardUi(this)
        prefs = Prefs(this)
        root = FrameLayout(this).apply { setBackgroundColor(ui.background) }
        val shell = ui.boundedColumn()
        root.addView(shell, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        shell.addView(ui.row().apply {
            setBackgroundColor(ui.background)
            setPadding(ui.dp(8), ui.dp(8), ui.dp(16), ui.dp(8))
            addView(ui.iconButton(R.drawable.ag_arrow_left, "返回") { leave() })
            addView(ui.text("规则与外观", R.dimen.ag_type_heading, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        })
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(24)) }
        shell.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        val cloudSection = ui.panel().apply { layoutParams = ui.lp(12) }
        cloud = ui.toggle("DeepSeek 联网增强", prefs.cloudEnabled, R.id.ag_cloud)
        cloudSection.addView(cloud)
        cloudSection.addView(ui.text("默认本地运行。启用后，将向 DeepSeek 发送命中规则的当前会话名称、最近 12 条可见消息及群聊语境。", R.dimen.ag_type_label, ui.sub))

        val keyField = ui.field("API Key", prefs.deepSeekKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, R.id.ag_key)
        keyBox = keyField.first
        key = keyField.second.apply { setSingleLine(); isSaveEnabled = false }
        keyBox.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        keyBox.setEndIconContentDescription("显示或隐藏密钥")
        keyBox.helperText = if (prefs.keyUnavailable) "本机密钥无法解密，请重新填写。" else "使用 Android Keystore 加密保存在本机"
        cloudSection.addView(keyBox)
        val modelField = ui.field("模型名称", prefs.deepSeekModel, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, R.id.ag_model)
        modelBox = modelField.first
        model = modelField.second.apply { setSingleLine() }
        modelBox.helperText = "默认 ${Prefs.DEFAULT_DEEPSEEK_MODEL}"
        cloudSection.addView(modelBox)
        result = ui.text("尚未测试", R.dimen.ag_type_label, ui.sub).apply {
            minHeight = ui.dp(40)
            gravity = Gravity.CENTER_VERTICAL
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            layoutParams = ui.lp(12)
        }
        cloudSection.addView(result)
        test = ui.button("测试连接", R.drawable.ag_wifi, false) {
            if (testing) cancelTesting("测试已取消，可重新测试") else confirmTest()
        }
        cloudSection.addView(test)
        body.addView(ui.text("WeChat 观测规则", R.dimen.ag_type_title, bold = true))
        body.addView(ui.text("设定范围，让重要的消息浮上来。", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) })
        val rulesSection = ui.panel().apply { layoutParams = ui.lp(18) }
        val contextField = ui.field("群聊语境", prefs.relationship, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, R.id.ag_context)
        context = contextField.second.apply { minLines = 2; gravity = Gravity.TOP }
        rulesSection.addView(contextField.first)
        val scopeField = ui.field("会话关键词", prefs.whitelist.sorted().joinToString("\n"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, R.id.ag_whitelist)
        whitelist = scopeField.second.apply { minLines = 2; gravity = Gravity.TOP }
        scopeField.first.helperText = "每行一个；留空表示所有当前会话"
        rulesSection.addView(scopeField.first)
        auto = ui.toggle("自动整理可见新消息", prefs.autoAnalyze, R.id.ag_auto)
        rulesSection.addView(auto)
        body.addView(rulesSection)
        body.addView(ui.heading("悬浮卡片"))
        val appearance = ui.panel().apply { layoutParams = ui.lp(12) }
        val opacityLabel = ui.text("背景不透明度 · ${prefs.overlayOpacity}%", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(16) }
        appearance.addView(ui.text("保留文字清晰，只改变卡片背景。", R.dimen.ag_type_label, ui.sub))
        appearance.addView(opacityLabel)
        opacity = SeekBar(this).apply {
            id = R.id.ag_opacity
            max = 100; progress = prefs.overlayOpacity
            contentDescription = "悬浮卡片不透明度"
            minHeight = ui.dp(48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { opacityLabel.text = "背景不透明度 · $value%" }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        appearance.addView(opacity)
        body.addView(appearance)
        body.addView(ui.heading("可选增强"))
        body.addView(cloudSection)
        shell.addView(ui.column().apply {
            setBackgroundColor(ui.surface)
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            addView(ui.button("保存设置", R.drawable.ag_save) { save() })
        })
        original = values()
        ui.install(this, root)
        GuardMotion.revealRows(body)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    private fun values(): List<Any> = listOf(key.text.toString().trim(), model.text.toString().trim(), context.text.toString(), whitelist.text.toString(), cloud.isChecked, auto.isChecked, opacity.progress)

    private fun validate(requireKey: Boolean): Boolean {
        keyBox.error = null; modelBox.error = null
        val secret = key.text.toString().trim()
        if (requireKey && secret.isEmpty()) {
            keyBox.error = "请填写 DeepSeek API Key"; key.requestFocus(); return false
        }
        if (secret.any { it.isWhitespace() }) {
            keyBox.error = "密钥不能包含空格或换行"; key.requestFocus(); return false
        }
        val modelName = model.text.toString().trim()
        if (modelName.isNotEmpty() && !Regex("[a-zA-Z0-9._-]{1,100}").matches(modelName)) {
            modelBox.error = "模型名称只能包含字母、数字、点、短横线或下划线"; model.requestFocus(); return false
        }
        return true
    }

    private fun save() {
        if (!validate(cloud.isChecked)) return
        runCatching {
            if (key.text.toString().trim() != original.firstOrNull() || prefs.keyUnavailable) prefs.deepSeekKey = key.text.toString().trim()
            prefs.deepSeekModel = model.text.toString().trim().ifBlank { Prefs.DEFAULT_DEEPSEEK_MODEL }
            prefs.relationship = context.text.toString().trim().ifBlank { Prefs.DEFAULT_REL }
            prefs.whitelist = whitelist.text.toString().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            prefs.autoAnalyze = auto.isChecked
            prefs.overlayOpacity = opacity.progress
            prefs.cloudEnabled = cloud.isChecked
        }.onSuccess {
            original = values()
            ui.feedback(root, "设置已保存")
            result.text = "设置已保存"
            result.setTextColor(ui.brand)
            keyBox.helperText = "使用 Android Keystore 加密保存在本机"
        }.onFailure {
            result.text = "保存失败，请重试。未加密的密钥不会保存。"
            result.setTextColor(ui.color(R.color.ag_danger))
        }
    }

    private fun confirmTest() {
        if (testing || !validate(true)) return
        MaterialAlertDialogBuilder(this)
            .setTitle("测试 DeepSeek 连接？")
            .setMessage("仅发送内置示例，不发送真实聊天内容。此请求可能产生少量 API 费用。")
            .setNegativeButton("取消", null)
            .setPositiveButton("测试连接") { _, _ -> testConnection() }
            .show()
    }

    private fun testConnection() {
        if (testing) return
        testing = true
        val request = ++generation
        test.isEnabled = true
        test.alpha = 1f
        test.text = "取消测试"
        test.setIconResource(R.drawable.ag_x)
        result.text = "正在连接 DeepSeek…"
        result.setTextColor(ui.sub)
        val api = DeepSeekAttentionClient(key.text.toString().trim(), model.text.toString().trim().ifBlank { Prefs.DEFAULT_DEEPSEEK_MODEL })
        client = api
        pending = worker.submit {
            val snapshot = ChatSnapshot("连接测试", listOf(Msg("other", "请在明天 17:00 前提交实验报告", "老师")))
            val outcome = runCatching {
                val base = requireNotNull(AttentionEngine.buildEvent(snapshot, Prefs.DEFAULT_REL))
                api.enrich(snapshot, base, Prefs.DEFAULT_REL)
            }
            main.post {
                if (isDestroyed || generation != request) return@post
                testing = false; client = null; pending = null
                test.isEnabled = true; test.alpha = 1f; test.text = "测试连接"
                test.setIconResource(R.drawable.ag_wifi)
                result.text = if (outcome.isSuccess) "连接成功 · 结构化结果已验证" else DeepSeekAttentionClient.readableError(outcome.exceptionOrNull())
                result.setTextColor(if (outcome.isSuccess) ui.brand else ui.color(R.color.ag_danger))
            }
        }
    }

    private fun leave() {
        if (values() == original) { finish(); return }
        MaterialAlertDialogBuilder(this).setTitle("设置尚未保存")
            .setMessage("离开后，本次修改不会保留。")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃修改") { _, _ -> finish() }
            .show()
    }

    override fun onStop() {
        super.onStop()
        cancelTesting("测试已取消，可重新测试")
    }

    override fun onDestroy() {
        cancelTesting(null)
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun cancelTesting(message: String?) {
        if (!testing && client == null && pending == null) return
        generation++
        client?.cancel()
        pending?.cancel(true)
        testing = false
        client = null
        pending = null
        if (message != null && ::test.isInitialized && ::result.isInitialized) {
            test.isEnabled = true
            test.alpha = 1f
            test.text = "测试连接"
            test.setIconResource(R.drawable.ag_wifi)
            result.text = message
        }
    }
}
