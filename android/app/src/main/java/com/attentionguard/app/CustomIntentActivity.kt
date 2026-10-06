package com.attentionguard.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.attentionguard.app.core.AnalysisScene
import com.attentionguard.app.core.AnalysisInput
import com.attentionguard.app.core.ContextInsight
import com.attentionguard.app.core.LocalContextAnalysis
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.ai.DeepSeekContextClient
import com.attentionguard.app.ui.AnalysisViews
import com.attentionguard.app.ui.GuardUi
import com.attentionguard.app.ui.GuardMotion
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CustomIntentActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var root: FrameLayout
    private lateinit var chatBox: TextInputLayout
    private lateinit var chat: TextInputEditText
    private lateinit var result: LinearLayout
    private lateinit var cloudButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var sceneButton: MaterialButton
    private var scene = AnalysisScene.GENERAL
    private var currentInsight: IntentInsight? = null
    private var currentContext: ContextInsight? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    @Volatile private var closed = false
    @Volatile private var client: DeepSeekContextClient? = null
    internal var connectionFactory: (String, String) -> DeepSeekContextClient = { key, model -> DeepSeekContextClient(key, model) }
    internal var cloudCredentials: () -> Pair<String, String>? = {
        val prefs = Prefs(this)
        if (prefs.cloudEnabled && prefs.hasKey()) prefs.activeKey() to prefs.activeModel() else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scene = AnalysisScene.read(savedInstanceState?.getString("scene"))
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
        body.addView(ui.text("按「我：」「对方：」分行输入，保留前文。\n先看本地解释，点按后可用 DeepSeek 深入理解。", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) })
        sceneButton = ui.button("分析场景 · ${scene.label}", R.drawable.ag_settings_2, false) {
            MaterialAlertDialogBuilder(this).setTitle("本次分析场景")
                .setSingleChoiceItems(AnalysisScene.entries.map { it.label }.toTypedArray(), scene.ordinal) { dialog, index ->
                    dialog.dismiss(); cancelRequest()
                    scene = AnalysisScene.entries[index]; sceneButton.text = "分析场景 · ${scene.label}"
                    if (currentInsight != null) analyze()
                }.setNegativeButton("取消", null).show()
        }.apply { id = R.id.ag_custom_scene; layoutParams = ui.lp(12) }
        body.addView(sceneButton)
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
            cancelRequest()
            currentInsight = null; currentContext = null
            chatBox.error = null
            result.removeAllViews()
            result.visibility = View.GONE
        }
        shell.addView(ui.column().apply {
            setBackgroundColor(ui.surface)
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            addView(ui.button("开始分析", R.drawable.ag_scan_text) { analyze() }.apply { id = R.id.ag_custom_analyze })
            cloudButton = ui.button("DeepSeek 深入理解", R.drawable.ag_activity, false) { confirmCloud() }.apply {
                id = R.id.ag_custom_cloud; layoutParams = ui.lp(6)
            }
            addView(cloudButton)
            cancelButton = ui.button("取消深入理解", R.drawable.ag_x, false) {
                cancelRequest(); currentContext = null
                currentInsight?.let(::renderResult); ui.feedback(root, "深化已取消，本地结果保留")
            }.apply { id = R.id.ag_custom_cancel; visibility = View.GONE }
            addView(cancelButton)
        })
        ui.install(this, root)
    }

    private fun analyze() {
        cancelRequest()
        currentContext = null
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
        currentInsight = insight
        result.removeAllViews()
        result.visibility = View.VISIBLE
        result.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val input = AnalysisInput.manual(chat.text?.toString().orEmpty(), scene)
        val understanding = currentContext?.takeIf { it.fingerprint == input.fingerprint } ?: LocalContextAnalysis.live(input)
        result.addView(ui.text(understanding.sections.lastOrNull { it.kind == com.attentionguard.app.core.InsightKind.INTENT }?.title
            ?: "当前理解", R.dimen.ag_type_heading, bold = true))
        val originals = JevIntentEngine.customMessages(chat.text?.toString().orEmpty()).takeLast(AnalysisInput.LIVE_LIMIT)
            .mapIndexed { index, message -> "manual:$index" to message.text }.toMap()
        AnalysisViews.append(result, understanding, originals, sources = input.messages)
        val details = ui.column().apply { visibility = View.GONE }
        val toggle = ui.button("查看本地判断与情绪线索", R.drawable.ag_chevron_down, false) {}
        toggle.setOnClickListener {
            details.visibility = if (details.visibility == View.GONE) View.VISIBLE else View.GONE
            toggle.text = if (details.visibility == View.VISIBLE) "收起本地判断与情绪线索" else "查看本地判断与情绪线索"
        }
        result.addView(toggle, ui.lp(12)); result.addView(details)
        appendLocalDetails(details, insight)
        GuardMotion.revealRows(result)
        result.post { result.requestRectangleOnScreen(android.graphics.Rect(0, 0, result.width, ui.dp(210)), false) }
    }

    private fun appendLocalDetails(result: LinearLayout, insight: IntentInsight) {
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
    }

    private fun cancelRequest() {
        generation.incrementAndGet(); client?.cancel(); client = null
        if (::cancelButton.isInitialized) cancelButton.visibility = View.GONE
        if (::cloudButton.isInitialized) cloudButton.isEnabled = true
    }

    private fun confirmCloud() {
        if (currentInsight == null) analyze()
        if (currentInsight == null) return
        if (cloudCredentials() == null) {
            ui.feedback(root, "请在「我的」中启用并配置 DeepSeek"); return
        }
        val input = AnalysisInput.manual(chat.text?.toString().orEmpty(), scene)
        val token = generation.get()
        AnalysisViews.confirmInput(this, input, "自由分析 · 手动输入") {
            if (!closed && generation.get() == token) send(input, token)
        }
    }

    private fun send(input: AnalysisInput, token: Int) {
        val config = cloudCredentials() ?: return
        val connection = connectionFactory(config.first, config.second)
        client = connection
        cloudButton.isEnabled = false; cancelButton.visibility = View.VISIBLE
        ui.feedback(root, "正在深入理解，可取消")
        worker.execute {
            runCatching { connection.analyze(input) }.fold(onSuccess = { context -> main.post {
                if (!closed && generation.get() == token && input.fingerprint ==
                    AnalysisInput.manual(chat.text?.toString().orEmpty(), scene).fingerprint) {
                    currentContext = context; cancelRequest(); currentInsight?.let(::renderResult)
                }
            } }, onFailure = { main.post {
                if (!closed && generation.get() == token) {
                    cancelRequest(); ui.feedback(root, "深化未完成，本地结果保留；可点按重试")
                }
            } })
            if (client === connection) client = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("scene", scene.name); super.onSaveInstanceState(outState)
    }
    override fun onStop() { cancelRequest(); super.onStop() }
    override fun onDestroy() {
        closed = true; cancelRequest(); main.removeCallbacksAndMessages(null); worker.shutdownNow()
        super.onDestroy()
    }
}
