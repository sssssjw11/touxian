package com.attentionguard.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.attentionguard.app.R
import com.attentionguard.app.core.EventPriority
import kotlin.math.roundToInt

/** Canonical native primitives. Resource tokens are shared by app and overlay. */
class GuardUi(val context: Context) {
    val ink = color(R.color.ag_ink)
    val sub = color(R.color.ag_secondary)
    val brand = color(R.color.ag_brand)
    val surface = color(R.color.ag_surface)
    val background = color(R.color.ag_background)
    val line = color(R.color.ag_border)
    val info = color(R.color.ag_info)
    fun color(id: Int) = ContextCompat.getColor(context, id)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    fun space(height: Int) = View(context).apply { layoutParams = lp(height = dp(height)) }
    fun lp(top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(top) }

    fun text(value: String, size: Int = R.dimen.ag_type_body, tint: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
        setTextColor(tint)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        letterSpacing = 0f
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
        breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
    }

    fun shape(fill: Int = surface, border: Int? = line, radius: Int = 8) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }

    fun selectable(view: View, fill: Int = surface, border: Int? = line) {
        view.background = RippleDrawable(ColorStateList.valueOf(color(R.color.ag_ripple)), shape(fill, border), shape())
        view.isFocusable = true
    }

    fun icon(res: Int, tint: Int = sub, size: Int = 22) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun iconButton(res: Int, label: String, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(ink)
        contentDescription = label
        tooltipText = label
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        selectable(this, android.graphics.Color.TRANSPARENT, null)
        GuardMotion.bindPress(this)
        setOnClickListener { action() }
    }

    fun button(label: String, iconRes: Int? = null, primary: Boolean = true, action: () -> Unit) =
        MaterialButton(context).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0f
            minHeight = dp(48)
            minimumHeight = dp(48)
            insetTop = 0
            insetBottom = 0
            stateListAnimator = null
            elevation = 0f
            cornerRadius = dp(8)
            backgroundTintList = ColorStateList.valueOf(if (primary) brand else surface)
            setTextColor(if (primary) surface else brand)
            strokeWidth = if (primary) 0 else dp(1)
            strokeColor = ColorStateList.valueOf(line)
            iconTint = ColorStateList.valueOf(if (primary) surface else brand)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconSize = dp(18)
            iconRes?.let { setIconResource(it) }
            layoutParams = lp()
            GuardMotion.bindPress(this)
            setOnClickListener { action() }
        }

    fun navigationRow(iconRes: Int, title: String, subtitle: String, action: () -> Unit): View = row().apply {
        minimumHeight = dp(68)
        setPadding(dp(4), dp(12), dp(4), dp(12))
        addView(icon(iconRes, brand))
        addView(column().apply {
            setPadding(dp(14), 0, dp(12), 0)
            addView(text(title, bold = true))
            addView(text(subtitle, R.dimen.ag_type_label, sub).apply { layoutParams = lp(5) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(icon(R.drawable.ag_chevron_right, size = 18))
        accessibleAction(this, "$title，$subtitle", action)
    }

    fun panel(fill: Int = surface, border: Int? = line, padding: Int = 16) = column().apply {
        background = shape(fill, border, 12)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    fun quickAction(iconRes: Int, title: String, subtitle: String, accent: Boolean = false,
                    action: () -> Unit): View = column().apply {
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(icon(iconRes, brand, 24))
        addView(text(title, R.dimen.ag_type_body, ink, true).apply { layoutParams = lp(10) })
        addView(text(subtitle, R.dimen.ag_type_caption, sub).apply { layoutParams = lp(5) })
        accessibleAction(this, "$title，$subtitle", action)
        selectable(this, if (accent) color(R.color.ag_brand_soft) else surface, line)
    }

    fun toggle(label: String, checked: Boolean, viewId: Int = View.NO_ID) = SwitchCompat(context).apply {
        id = viewId
        text = label
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.ag_type_body))
        setTextColor(ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(56)
        isChecked = checked
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(brand, sub))
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(color(R.color.ag_brand_soft), line))
        layoutParams = lp()
    }

    /** A non-interactive two-column status line used for live diagnostics. */
    fun statusRow(label: String, value: String, tint: Int = sub, iconRes: Int? = null): View = row().apply {
        minimumHeight = dp(56)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = shape(surface, line, 8)
        iconRes?.let { addView(icon(it, tint, 20), LinearLayout.LayoutParams(dp(28), dp(24))) }
        addView(text(label, R.dimen.ag_type_label, sub, true), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, R.dimen.ag_type_label, tint, true).apply {
            gravity = Gravity.END
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** A compact explanation block for local rules and data-quality caveats. */
    fun callout(title: String, detail: String, tint: Int = brand, fill: Int = color(R.color.ag_brand_soft), iconRes: Int = R.drawable.ag_shield_check): View = column().apply {
        background = shape(fill, tint, 8)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        addView(row().apply {
            addView(icon(iconRes, tint, 18))
            addView(text(title, R.dimen.ag_type_label, tint, true).apply { setPadding(dp(8), 0, 0, 0) })
        })
        addView(text(detail, R.dimen.ag_type_caption, sub).apply {
            layoutParams = lp(7)
            setPadding(dp(26), 0, 0, 0)
        })
    }

    fun accessibleAction(view: ViewGroup, label: String, action: () -> Unit) {
        selectable(view, android.graphics.Color.TRANSPARENT, null)
        view.contentDescription = label
        for (i in 0 until view.childCount) view.getChildAt(i).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
        view.setOnClickListener { action() }
        GuardMotion.bindPress(view)
    }

    fun badge(label: String, foreground: Int = brand, fill: Int = color(R.color.ag_brand_soft)) =
        text(label, R.dimen.ag_type_caption, foreground, true).apply {
            background = shape(fill, null, 4)
            setPadding(dp(7), dp(4), dp(7), dp(4))
        }

    fun priority(priority: EventPriority): Pair<Int, Int> = when (priority) {
        EventPriority.P0 -> color(R.color.ag_danger) to color(R.color.ag_danger_soft)
        EventPriority.P1 -> color(R.color.ag_warning) to color(R.color.ag_warning_soft)
        EventPriority.P2 -> color(R.color.ag_info) to color(R.color.ag_info_soft)
        EventPriority.P3 -> color(R.color.ag_secondary) to color(R.color.ag_background)
    }

    fun confidence(label: String, score: Int, tint: Int = brand, labelTint: Int = sub,
                   track: Int = line, size: Int = R.dimen.ag_type_label, previous: Int? = null) = column().apply {
        val value = score.coerceIn(0, 100)
        val level = if (value < 50) "低" else if (value < 75) "中" else "高"
        addView(row().apply {
            addView(text(label, size, labelTint), LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("$level $value%", size, tint, true).apply { fontFeatureSettings = "tnum" })
        })
        addView(LinearProgressIndicator(context).apply {
            max = 100
            trackThickness = dp(3)
            trackCornerRadius = dp(2)
            trackColor = track
            setIndicatorColor(tint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = lp(5, dp(3))
            progress = value
            if (previous != null && previous != value) GuardMotion.progress(this) { t ->
                progress = (previous + (value - previous) * t).roundToInt().coerceIn(0, 100)
            }
        })
    }

    fun divider(top: Int = 16) = View(context).apply { setBackgroundColor(line); layoutParams = lp(top, dp(1)) }
    fun heading(title: String) = text(title, R.dimen.ag_type_heading, ink, true).apply { layoutParams = lp(24) }
    fun scroll(child: View) = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        clipToPadding = false
        addView(child)
    }

    fun field(label: String, value: String, inputType: Int, id: Int): Pair<TextInputLayout, TextInputEditText> {
        val wrapper = TextInputLayout(context).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeColor = brand
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            layoutParams = lp(16)
        }
        val edit = TextInputEditText(context).apply {
            this.id = id
            this.inputType = inputType
            setText(value)
            textSize = 15f
            setTextColor(ink)
            minHeight = dp(56)
            setPadding(dp(14), dp(16), dp(14), dp(16))
        }
        wrapper.addView(edit, LinearLayout.LayoutParams(-1, -2))
        return wrapper to edit
    }

    fun feedback(anchor: View, message: String) = Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT).show()

    fun brandMark(size: Int = 40) = ImageView(context).apply {
        setImageResource(R.drawable.touxian_app_icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = shape(color(R.color.ag_graphite), null)
        clipToOutline = true
        contentDescription = "偷闲"
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun boundedColumn() = object : LinearLayout(context) {
        init { orientation = VERTICAL }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val max = resources.getDimensionPixelSize(R.dimen.ag_content_max)
            super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(widthMeasureSpec), max), MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }

    fun install(activity: AppCompatActivity, root: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(system.left, system.top, system.right, maxOf(system.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }
        activity.setContentView(root)
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.requestApplyInsets(root)
    }
}

