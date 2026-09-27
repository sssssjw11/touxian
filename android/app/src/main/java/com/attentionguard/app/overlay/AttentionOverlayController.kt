package com.attentionguard.app.overlay

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.TypedValue
import androidx.appcompat.view.ContextThemeWrapper
import com.attentionguard.app.MainActivity
import com.attentionguard.app.R
import com.attentionguard.app.core.AttentionEvent
import com.attentionguard.app.core.CaptureMode
import com.attentionguard.app.core.IntentInsight
import com.attentionguard.app.core.OverlaySize
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.capture.CaptureDiagnostics
import com.attentionguard.app.capture.HistoryState
import com.attentionguard.app.capture.HistorySession
import com.attentionguard.app.ui.GuardUi
import com.attentionguard.app.ui.GuardMotion
import com.attentionguard.app.ui.GuardSegments
import com.google.android.material.button.MaterialButton
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact by default, no chat input access and no deferred window mounts. */
class AttentionOverlayController(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(context)
    private val themeContext = ContextThemeWrapper(context, R.style.Theme_AttentionGuard)
    private val ui = GuardUi(themeContext)
    /**
     * Keep one attached host window and swap a complete content tree into it.
     *
     * Reparenting every child of an attached LinearLayout while Android 15/16
     * is measuring the accessibility overlay can leave a null child slot in
     * ViewGroup.resolveDrawables(). Xiaomi's Android 16 build then crashes the
     * accessibility process when the bubble is expanded. Swapping one detached
     * content root avoids that platform bug and keeps the window stable.
     */
    private var panel: FrameLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var renderPosted = false
    private val deferredRender = Runnable {
        renderPosted = false
        render()
    }
    private var lastEvent: AttentionEvent? = null
    private var lastInsight: IntentInsight? = null
    private var expanded = false
    private var evidenceExpanded = false
    private var menuOpen = false
    private var group: String? = null
    private var loading = false
    private var usingModel = false
    private var status = ""
    private var actionLabel = "切换意图分析"
    private var history: HistorySession? = null
    private var renderKey: List<Any?>? = null
    private var renderedMode: CaptureMode? = null
    private var renderedSize: OverlaySize? = null
    private var renderedInsight: IntentInsight? = null
    private var hiddenForCapture = false
    private val diagnostics = CaptureDiagnostics(context)
    private val accessibilityWindow = context is AccessibilityService
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false
    private var dragging = false
    var onModeChange: ((CaptureMode) -> Unit)? = null
    var onRefreshIntent: (() -> Unit)? = null
    var onMarkCurrentChat: (() -> Unit)? = null
    var onHistorySettings: (() -> Unit)? = null
    var onHistoryStart: (() -> Unit)? = null
    var onHistoryPause: (() -> Unit)? = null
    var onHistoryCancel: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    /**
     * Do not replace an attached accessibility-window view tree from inside
     * ACTION_UP. Xiaomi's input dispatcher can otherwise wait for the same
     * window to finish relayout and report an input ANR.
     */
    private fun requestRender() {
        if (renderPosted) return
        renderPosted = true
        mainHandler.post(deferredRender)
    }

    private fun dispatchClick(action: () -> Unit) {
        if (accessibilityWindow) mainHandler.post(action) else action()
    }

    private val darkOverlay get() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val overlaySurface get() = if (darkOverlay) Color.rgb(27, 34, 32) else ui.surface
    private val overlayInk get() = if (darkOverlay) Color.WHITE else ui.ink
    private val overlaySub get() = if (darkOverlay) Color.rgb(232, 240, 235) else ui.sub
    private val overlayBrand get() = if (darkOverlay) Color.rgb(131, 232, 197) else ui.brand
    private val overlayLine get() = if (darkOverlay) Color.rgb(118, 135, 126) else ui.line
    private fun transparent(color: Int) = Color.argb(prefs.overlayOpacity * 255 / 100,
        Color.red(color), Color.green(color), Color.blue(color))
    private fun overlayText(value: String, size: Int = R.dimen.ag_type_body, tint: Int = ui.ink,
                            bold: Boolean = false): TextView = ui.text(value, size, when (tint) {
        ui.ink -> overlayInk
        ui.sub -> overlaySub
        ui.brand -> overlayBrand
        else -> tint
    }, bold).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(when (size) {
            R.dimen.ag_type_heading -> R.dimen.ag_overlay_heading
            R.dimen.ag_type_body -> R.dimen.ag_overlay_body
            R.dimen.ag_type_label -> R.dimen.ag_overlay_label
            R.dimen.ag_type_caption -> R.dimen.ag_overlay_caption
            else -> size
        }))
        setLineSpacing(ui.dp(1).toFloat(), 1f)
        if (darkOverlay) setShadowLayer(ui.dp(1).toFloat(), 0f, 0f, Color.BLACK)
    }
    private fun overlayIconButton(res: Int, label: String, action: () -> Unit) =
        ui.iconButton(res, label) { dispatchClick(action) }.apply {
            imageTintList = ColorStateList.valueOf(overlayInk)
        }
    private fun overlayButton(label: String, iconRes: Int? = null, primary: Boolean = true,
                              action: () -> Unit): MaterialButton =
        ui.button(label, iconRes, primary) { dispatchClick(action) }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ag_overlay_label))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val fill = if (primary) { if (darkOverlay) Color.rgb(24, 113, 91) else ui.brand } else overlaySurface
            backgroundTintList = ColorStateList.valueOf(transparent(fill))
            strokeWidth = ui.dp(1)
            strokeColor = ColorStateList.valueOf(transparent(if (primary) overlayBrand else overlayLine))
            val foreground = if (primary && (darkOverlay || prefs.overlayOpacity >= 55)) Color.WHITE else overlayBrand
            setTextColor(foreground)
            iconTint = ColorStateList.valueOf(foreground)
        }

    fun isShowing() = panel != null
    fun setHiddenForCapture(hidden: Boolean) {
        hiddenForCapture = hidden
        panel?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }
    fun hide() {
        mainHandler.removeCallbacks(deferredRender)
        renderPosted = false
        panel?.let { GuardMotion.cancel(it); runCatching { wm.removeView(it) } }
        panel = null
        renderKey = null
        renderedMode = null
        renderedSize = null
        renderedInsight = null
        lastInsight = null
        menuOpen = false
        diagnostics.overlay("已隐藏")
    }
    fun showIdle(group: String?, status: String = "正在监测可见消息", history: HistorySession? = null,
                 actionLabel: String = "切换意图分析") {
        this.group = group; this.status = status; this.history = history; this.actionLabel = actionLabel
        lastEvent = null; lastInsight = null; loading = false; expanded = false; render()
    }
    fun showLoading(useModel: Boolean = false) {
        history = null
        loading = true; usingModel = useModel; render()
    }
    fun showEvent(event: AttentionEvent) {
        history = null
        expanded = prefs.overlaySize == OverlaySize.EXPANDED
        group = event.sourceGroup
        lastEvent = event; lastInsight = null; loading = false; render()
    }
    fun showIntent(insight: IntentInsight, expand: Boolean = true) {
        history = null
        group = insight.group
        lastEvent = null; lastInsight = insight; loading = false
        if (expand) {
            expanded = true
            prefs.overlayCollapsed = false
        } else {
            expanded = prefs.overlaySize == OverlaySize.EXPANDED
        }
        render()
    }
    fun showError(message: String) { hide(); toast(message) }
    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    // ImageButton inherits performClick; the drag listener calls it for taps.
    // Semantic click, tap, drag, and cancel are covered by the overlay tests.
    @SuppressLint("ClickableViewAccessibility")
    private fun render() {
        if (!prefs.enabled || dragging) return
        if (!accessibilityWindow && !Settings.canDrawOverlays(context)) { diagnostics.overlay("缺少悬浮窗权限"); return }
        val event = lastEvent
        val insight = lastInsight
        val mode = prefs.captureMode
        val size = prefs.overlaySize
        val collapsed = size != OverlaySize.EXPANDED
        val bubble = size == OverlaySize.BUBBLE
        val metrics = wm.currentWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val availableHeight = bounds.height() - insets.top - insets.bottom
        val key = listOf(mode, size, prefs.overlayOpacity, darkOverlay, prefs.bubbleX, prefs.bubbleY,
            bounds.width(), bounds.height(), insets, context.resources.configuration.fontScale,
            group, status, actionLabel, loading, usingModel, expanded, evidenceExpanded, event,
            menuOpen,
            insight?.copy(capturedAt = insight.capturedAt / 60_000),
            history?.let { listOf(it.id, it.state, it.screens, it.attempts, it.reason) })
        if (panel != null && renderKey == key) return
        val previousSize = renderedSize
        val previousMode = renderedMode
        val previousInsight = renderedInsight
        val previousScroll = panel?.findViewById<ScrollView>(R.id.ag_overlay_detail)?.scrollY ?: 0
        val view = ui.column().apply {
            visibility = if (hiddenForCapture) View.INVISIBLE else View.VISIBLE
            background = if (bubble) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(overlaySurface)
                setStroke(ui.dp(1), overlayLine)
                alpha = prefs.overlayOpacity * 255 / 100
            } else ui.shape(overlaySurface, overlayLine).apply { alpha = (prefs.overlayOpacity * 255 / 100) }
            clipToOutline = true
            elevation = ui.dp(6).toFloat()
            val padding = ui.dp(if (bubble) 2 else 4)
            setPadding(padding, padding, padding, ui.dp(if (bubble) 2 else if (collapsed) 4 else 12))
        }
        val handle = when {
            bubble -> overlayIconButton(if (mode == CaptureMode.INTENT) R.drawable.ag_scan_text else R.drawable.ag_activity,
                "展开悬浮工具条") { prefs.overlaySize = OverlaySize.COMPACT; requestRender() }
            collapsed -> overlayIconButton(R.drawable.ag_minimize_2, "拖动工具条；点按收成小球") {
                prefs.overlaySize = OverlaySize.BUBBLE; requestRender()
            }
            else -> overlayIconButton(R.drawable.ag_grip_horizontal, "拖动卡片；点按复位") {
                prefs.bubbleX = -1; prefs.bubbleY = -1; requestRender()
            }
        }
        if (bubble) {
            handle.imageTintList = ColorStateList.valueOf(overlayBrand)
            view.addView(handle)
        } else {
        val header = ui.row()
        header.addView(handle)
        val label = when {
            mode == CaptureMode.INTENT -> "意图分析"
            loading -> if (usingModel) "DeepSeek 整理中" else "本地整理中"
            event != null -> "${event.priority.label} · 新事件"
            else -> "事件监测"
        }
        if (collapsed) {
            if (mode == CaptureMode.INTENT) {
                header.addView(overlayText("意图分析 · 持续", R.dimen.ag_type_caption, overlayBrand, true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(overlayIconButton(R.drawable.ag_messages_square, "切回事件监测模式") { onModeChange?.invoke(CaptureMode.EVENT) })
            } else {
                header.addView(overlayIconButton(R.drawable.ag_bookmark_plus, "标记当前微信会话") { onMarkCurrentChat?.invoke() })
                header.addView(overlayIconButton(R.drawable.ag_scan_text, "开启意图分析模式") { onModeChange?.invoke(CaptureMode.INTENT) })
                val session = history
                when (session?.state) {
                    HistoryState.RUNNING -> header.addView(overlayIconButton(R.drawable.ag_pause, "暂停回溯") { onHistoryPause?.invoke() })
                    HistoryState.READY, HistoryState.PAUSED -> header.addView(overlayIconButton(R.drawable.ag_radio, if (session.state == HistoryState.READY) "开始回溯" else "继续回溯") { onHistoryStart?.invoke() })
                    else -> header.addView(overlayIconButton(R.drawable.ag_clock_3, "回溯收集") { onHistorySettings?.invoke() })
                }
            }
            header.addView(overlayIconButton(R.drawable.ag_chevron_right, "展开偷闲") {
                expanded = true
                prefs.overlaySize = OverlaySize.EXPANDED
                requestRender()
            })
        } else {
            header.addView(overlayText(label, R.dimen.ag_type_label, ui.brand, true).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(overlayIconButton(R.drawable.ag_minimize_2, "收起悬浮卡片") {
                expanded = false
                prefs.overlaySize = OverlaySize.COMPACT
                requestRender()
            })
        }
        view.addView(header)
        if (collapsed && mode == CaptureMode.INTENT) {
            val affect = lastInsight?.affect
            val affectLabel = affect?.label
                ?.removePrefix("上文：")
                ?.replace("出现", "")
                ?.replace("表达", "")
                ?.replace("线索", "")
                ?.takeIf { it.isNotBlank() } ?: "等待"
            view.addView(ui.column().apply {
                minimumHeight = ui.dp(48)
                contentDescription = "刷新整屏语境"
                setOnClickListener { dispatchClick { onRefreshIntent?.invoke() } }
                setPadding(ui.dp(12), 0, ui.dp(8), ui.dp(4))
                addView(overlayText(
                    if (lastInsight == null) "正在读取 · 点击刷新"
                    else "意图：${lastInsight?.label ?: "暂无明确请求"}",
                    R.dimen.ag_type_caption, overlaySub
                ).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(overlayText(
                    if (lastInsight == null) "情绪：等待证据"
                    else "把握 ${lastInsight?.confidence}% · 情绪 ${affectLabel}${affect?.confidence ?: 0}%",
                    R.dimen.ag_type_caption, overlaySub
                ).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
        }
        if (collapsed && mode == CaptureMode.EVENT && event != null) {
            view.addView(ui.column().apply {
                minimumHeight = ui.dp(48)
                setPadding(ui.dp(12), 0, ui.dp(8), ui.dp(4))
                addView(overlayText(
                    "${event.category.label} · ${event.priority.label} · ${event.attentionScore}分",
                    R.dimen.ag_type_caption, overlayBrand, true
                ).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(overlayText(event.title, R.dimen.ag_type_caption, overlaySub).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
        }
        if (!collapsed) {
            val modes = GuardSegments(themeContext, listOf(
                GuardSegments.Option(R.id.ag_mode_event, "事件监测"),
                GuardSegments.Option(R.id.ag_mode_intent, "意图分析")
            ), modeId(mode), selectedFill = transparent(if (darkOverlay) Color.rgb(24, 113, 91) else ui.brand),
                selectedInk = if (prefs.overlayOpacity >= 55) Color.WHITE else overlayBrand,
                inactiveInk = overlaySub, trackFill = Color.TRANSPARENT, trackBorder = transparent(overlayLine),
                textSize = R.dimen.ag_overlay_label, fromId = previousMode?.let(::modeId)).apply {
                layoutParams = ui.lp(4)
            }
            modes.addOnButtonCheckedListener { _, id, checked ->
                if (checked) dispatchClick {
                    onModeChange?.invoke(if (id == R.id.ag_mode_intent) CaptureMode.INTENT else CaptureMode.EVENT)
                }
            }
            view.addView(modes)
        }
        if (!collapsed) group?.takeIf { it.isNotBlank() }?.let {
            view.addView(overlayText(it, R.dimen.ag_type_caption, ui.sub).apply {
                setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(6))
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        if (!prefs.overlayCollapsed && history != null) {
            val session = requireNotNull(history)
            view.addView(overlayText("${session.state.label} · ${session.screens} 屏", R.dimen.ag_type_label, ui.brand, true))
            if (session.reason.isNotBlank()) view.addView(overlayText(session.reason, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(4) })
            val controls = ui.row()
            if (session.state in setOf(HistoryState.READY, HistoryState.PAUSED)) {
                controls.addView(overlayButton(if (session.state == HistoryState.READY) "开始回溯" else "继续", R.drawable.ag_radio) { onHistoryStart?.invoke() }, LinearLayout.LayoutParams(0, -2, 1f))
            } else if (session.state == HistoryState.RUNNING) controls.addView(overlayIconButton(R.drawable.ag_pause, "暂停回溯") { onHistoryPause?.invoke() })
            controls.addView(overlayIconButton(R.drawable.ag_x, "结束回溯") { onHistoryCancel?.invoke() })
            controls.addView(overlayIconButton(R.drawable.ag_notebook_tabs, "查看采集记录") { onHistorySettings?.invoke() })
            view.addView(controls)
        } else if (!prefs.overlayCollapsed && expanded && insight != null) {
            val detail = ui.column()
            detail.addView(overlayText(insight.label, R.dimen.ag_type_heading, bold = true).apply { layoutParams = ui.lp(4) })
            detail.addView(overlayText("重要性 · ${insight.importance.label} · ${insight.contextSummary?.substringBefore(" · ") ?: "当前语境"}",
                R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(5) })
            detail.addView(ui.confidence("语境置信度", insight.confidence, overlayBrand, overlaySub,
                transparent(overlayLine), R.dimen.ag_overlay_caption, previousInsight?.confidence).apply { layoutParams = ui.lp(8) })
            insight.affect?.let { affect ->
                detail.addView(ui.row().apply {
                    addView(ui.icon(R.drawable.ag_smile, overlayBrand, 18))
                    addView(overlayText(affect.label, R.dimen.ag_type_label, ui.brand, true).apply {
                        setPadding(ui.dp(6), 0, 0, 0); maxLines = 2
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    layoutParams = ui.lp(10)
                })
                detail.addView(ui.confidence("情绪置信度", affect.confidence, overlayBrand, overlaySub,
                    transparent(overlayLine), R.dimen.ag_overlay_caption, previousInsight?.affect?.confidence).apply { layoutParams = ui.lp(5) })
            }
            detail.addView(overlayText("来源 · ${insight.sourceLabel} · ${SimpleDateFormat("HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(insight.capturedAt))}",
                R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8); maxLines = 2 })
            detail.addView(overlayButton(if (evidenceExpanded) "收起判断依据" else "判断依据", R.drawable.ag_chevron_down, false) {
                evidenceExpanded = !evidenceExpanded; requestRender()
            }.apply { layoutParams = ui.lp(6) })
            if (evidenceExpanded) {
                detail.addView(overlayText("依据 · ${insight.sender}：${insight.evidence}", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) })
                insight.affect?.let { affect ->
                    detail.addView(overlayText(affect.evidence, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(6) })
                    affect.basis?.let { detail.addView(overlayText(it, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(5) }) }
                }
                insight.confidenceTrace?.let { trace ->
                    detail.addView(overlayText("把握依据 · ${trace.explanation}", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(6) })
                }
                insight.contextEvidence?.let { context ->
                    detail.addView(overlayText("相关上文 · $context", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(6) })
                }
                detail.addView(overlayText("建议 · ${insight.nextStep}", R.dimen.ag_type_label).apply { layoutParams = ui.lp(8) })
            }
            insight.eventId?.let { detail.addView(overlayButton("查看相关事件", R.drawable.ag_arrow_up_right) { openApp(it) }.apply { layoutParams = ui.lp(12) }) }
            view.addView(boundedDetails(detail, availableHeight))
        } else if (!prefs.overlayCollapsed && expanded && event != null) {
            val detail = ui.column()
            detail.addView(overlayText(event.title, R.dimen.ag_type_heading, bold = true).apply { layoutParams = ui.lp(4) })
            detail.addView(overlayText("事件类型 · ${event.category.label} · 重要性 ${event.priority.label} · ${event.attentionScore}分",
                R.dimen.ag_type_caption, overlayBrand, true).apply { layoutParams = ui.lp(5) })
            if (event.reviewNotes.any { it.contains("截止时间已过") }) {
                detail.addView(overlayText("时间已过 · 保留事项记录", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(5) })
            }
            detail.addView(overlayText(event.summary, R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) })
            event.actionLabel?.let { detail.addView(overlayText("下一步 · $it", R.dimen.ag_type_label, overlayInk, true).apply { layoutParams = ui.lp(8) }) }
            detail.addView(overlayText("来源 · ${event.captureOrigin.label} · ${event.sourcePerson}", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(8) })
            event.dueLabel?.let { detail.addView(overlayText(it, R.dimen.ag_type_label, ui.priority(event.priority).first, true).apply { layoutParams = ui.lp(12) }) }
            view.addView(boundedDetails(detail, availableHeight))
        } else if (!prefs.overlayCollapsed && !loading) {
            view.addView(overlayText(status, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(4) })
            if (event != null || mode == CaptureMode.EVENT) view.addView(overlayButton(event?.title ?: actionLabel,
                if (event == null) R.drawable.ag_scan_text else R.drawable.ag_chevron_down, false) {
                if (event != null) { expanded = true; requestRender() } else onModeChange?.invoke(CaptureMode.INTENT)
            })
        }
        if (!collapsed) view.addView(ui.row().apply {
            gravity = Gravity.CENTER
            if (mode == CaptureMode.INTENT) {
                val refresh = overlayIconButton(R.drawable.ag_rotate_ccw, "刷新整屏语境") { onRefreshIntent?.invoke() }
                refresh.setOnClickListener {
                    dispatchClick { GuardMotion.refresh(refresh); onRefreshIntent?.invoke() }
                }
                addView(refresh)
            } else addView(overlayIconButton(R.drawable.ag_bookmark_plus, "标记当前微信会话") { onMarkCurrentChat?.invoke() })
            addView(overlayIconButton(R.drawable.ag_notebook_tabs, "打开观测簿") { openApp() })
            val more = overlayIconButton(R.drawable.ag_ellipsis, "更多悬浮窗操作") {
                menuOpen = !menuOpen
                requestRender()
            }
            addView(more)
            layoutParams = ui.lp(4)
        })
        if (!collapsed && menuOpen) view.addView(overlayMenu())
        }
        val expandedWidth = minOf(ui.dp(312), (bounds.width() * 0.5f).roundToInt())
        // Keep event and intent surfaces identical in compact mode. The
        // expanded result remains capped at half the display as requested.
        val compactWidth = minOf(ui.dp(248), bounds.width() - insets.left - insets.right - ui.dp(16))
        val desiredWidth = when {
            bubble -> ui.dp(52)
            collapsed -> compactWidth
            else -> expandedWidth
        }
        val width = minOf(desiredWidth, bounds.width() - insets.left - insets.right - ui.dp(16))
        val host = FrameLayout(themeContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            visibility = view.visibility
            addView(view, FrameLayout.LayoutParams(-1, -2))
        }
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST))
        val maxX = (bounds.width() - insets.right - width).coerceAtLeast(insets.left)
        val composerReserve = if (bubble || collapsed) 0 else ui.dp(112)
        val maxY = (bounds.height() - insets.bottom - insets.top - host.measuredHeight - composerReserve).coerceAtLeast(0)
        val type = if (accessibilityWindow) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(width, -2, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (prefs.bubbleX >= 0) prefs.bubbleX else maxX - ui.dp(8)).coerceIn(insets.left, maxX)
            y = (if (prefs.bubbleY >= 0) prefs.bubbleY else ui.dp(96)).coerceIn(0, maxY)
        }
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val dragListener = View.OnTouchListener { target, motion ->
            when (motion.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = motion.rawX; downY = motion.rawY; startX = lp.x; startY = lp.y; dragged = false; dragging = true; true }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(motion.rawX - downX) > touchSlop || kotlin.math.abs(motion.rawY - downY) > touchSlop) dragged = true
                    if (!dragged) return@OnTouchListener true
                    lp.x = (startX + motion.rawX - downX).roundToInt().coerceIn(insets.left, maxX)
                    lp.y = (startY + motion.rawY - downY).roundToInt().coerceIn(0, maxY)
                    panel?.let { runCatching { wm.updateViewLayout(it, lp) } }; true
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    if (!dragged) target.performClick()
                    else { prefs.bubbleX = lp.x; prefs.bubbleY = lp.y; requestRender() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    lp.x = startX; lp.y = startY; dragged = false; dragging = false
                    panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
                    requestRender()
                    true
                }
                else -> false
            }
        }
        handle.setOnTouchListener(dragListener)
        runCatching {
            val mounted = panel
            if (mounted == null) { wm.addView(host, lp); panel = host }
            else {
                GuardMotion.cancel(mounted)
                val previous = mounted.getChildAt(0)
                host.removeView(view)
                mounted.addView(view, FrameLayout.LayoutParams(-1, -2))
                if (previous != null) mounted.removeView(previous)
                mounted.visibility = host.visibility
                wm.updateViewLayout(mounted, lp)
            }
            renderKey = key
            renderedMode = mode
            renderedSize = size
            renderedInsight = insight
            val active = requireNotNull(panel)
            val detail = active.findViewById<ScrollView>(R.id.ag_overlay_detail)
            if (previousMode == mode && previousSize == size && previousScroll > 0) detail?.post {
                if (detail.isAttachedToWindow) detail.scrollTo(0, previousScroll)
            }
            when {
                previousSize == null -> GuardMotion.enter(active)
                previousSize != size && !bubble -> GuardMotion.unfold(active, previousSize == OverlaySize.BUBBLE)
                previousSize != size -> GuardMotion.acknowledge(active.getChildAt(0))
                previousMode != mode -> detail?.getChildAt(0)?.let { GuardMotion.enter(it) }
            }
            diagnostics.overlay(if (accessibilityWindow) "无障碍悬浮窗已显示" else "应用悬浮窗已显示")
        }
            .onFailure { diagnostics.overlay("挂窗失败：${it.javaClass.simpleName}") }
    }

    private fun modeId(mode: CaptureMode) = if (mode == CaptureMode.INTENT) R.id.ag_mode_intent else R.id.ag_mode_event

    private fun boundedDetails(content: View, availableHeight: Int) = object : ScrollView(themeContext) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val max = minOf(ui.dp(185), (availableHeight * 0.38f).roundToInt()).coerceAtLeast(ui.dp(120))
            val limit = minOf(View.MeasureSpec.getSize(heightMeasureSpec), max)
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
        }
    }.apply {
        id = R.id.ag_overlay_detail
        isFillViewport = false
        setPadding(ui.dp(8), 0, ui.dp(8), 0)
        addView(content)
        layoutParams = ui.lp(4)
    }

    private fun overlayMenu(): View = ui.column().apply {
        background = ui.shape(transparent(overlaySurface), transparent(overlayLine), 8)
        elevation = ui.dp(4).toFloat()
        setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        addView(menuItem(R.drawable.ag_bookmark_plus, "标记当前微信会话") {
            menuOpen = false
            requestRender()
            onMarkCurrentChat?.invoke()
        })
        addView(menuItem(R.drawable.ag_clock_3, "回溯收集") {
            menuOpen = false
            requestRender()
            onHistorySettings?.invoke()
        })
        addView(menuItem(R.drawable.ag_grip_horizontal, "复位悬浮窗") {
            prefs.bubbleX = -1
            prefs.bubbleY = -1
            menuOpen = false
            requestRender()
        })
        addView(menuItem(R.drawable.ag_x, "关闭悬浮卡片") {
            menuOpen = false
            onHistoryPause?.invoke()
            hide()
            onDismiss?.invoke()
        })
    }

    private fun menuItem(iconRes: Int, label: String, action: () -> Unit): View = ui.row().apply {
        minimumHeight = ui.dp(48)
        setPadding(ui.dp(8), 0, ui.dp(8), 0)
        addView(ui.icon(iconRes, overlayBrand, 20))
        addView(overlayText(label, R.dimen.ag_type_label, overlayInk, true).apply {
            setPadding(ui.dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        ui.accessibleAction(this, label, action)
    }
    private fun openApp(eventId: String? = lastEvent?.id) {
        hide()
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_EVENT_ID, eventId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { toast("暂时无法打开，请从桌面进入偷闲") }
    }
}



