package com.attentionguard.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import com.attentionguard.app.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

/** Native checked buttons over one moving selection surface. */
class GuardSegments(
    context: Context,
    options: List<Option>,
    selectedId: Int,
    internal val selectedFill: Int = GuardUi(context).brand,
    private val selectedInk: Int = GuardUi(context).surface,
    private val inactiveInk: Int = GuardUi(context).sub,
    trackFill: Int = GuardUi(context).background,
    trackBorder: Int = GuardUi(context).line,
    textSize: Int = R.dimen.ag_type_label,
    itemWidth: Int? = null,
    fromId: Int? = null
) : MaterialButtonToggleGroup(context) {
    data class Option(val id: Int, val label: String, val icon: Int? = null)

    private val ui = GuardUi(context)
    private val thumb = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = selectedFill }
    private var pendingFrom = fromId?.takeIf { it != selectedId }

    init {
        isSingleSelection = true
        isSelectionRequired = true
        background = ui.shape(trackFill, trackBorder)
        options.forEach { option ->
            addView(ui.button(option.label, option.icon, primary = false) {}.apply {
                id = option.id
                minWidth = 0
                minimumWidth = 0
                isCheckable = true
                maxLines = 2
                setPadding(ui.dp(5), ui.dp(8), ui.dp(5), ui.dp(8))
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(textSize))
                iconSize = ui.dp(16)
                iconPadding = ui.dp(4)
                strokeWidth = 0
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(itemWidth ?: 0, -2, if (itemWidth == null) 1f else 0f))
        }
        check(selectedId)
        tintButtons()
        addOnButtonCheckedListener { _, _, checked ->
            if (checked) {
                tintButtons()
                if (isLaidOut) moveThumb(RectF(thumb))
            }
        }
    }

    private fun tintButtons() {
        for (index in 0 until childCount) (getChildAt(index) as MaterialButton).apply {
            val ink = if (isChecked) selectedInk else inactiveInk
            setTextColor(ink)
            iconTint = ColorStateList.valueOf(ink)
            stateDescription = if (isChecked) "已选中" else "未选中"
        }
    }

    private fun rect(id: Int): RectF {
        val child = findViewById<View>(id) ?: return RectF()
        val inset = ui.dp(3).toFloat()
        return RectF(child.left + inset, child.top + inset, child.right - inset, child.bottom - inset)
    }

    private fun moveThumb(from: RectF) {
        val to = rect(checkedButtonId)
        if (from.isEmpty) { thumb.set(to); invalidate(); return }
        GuardMotion.progress(this) { t ->
            thumb.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t)
            invalidate()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val from = pendingFrom
        pendingFrom = null
        if (from != null) moveThumb(rect(from))
        else if (getTag(R.id.ag_motion) == null) thumb.set(rect(checkedButtonId))
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.drawRoundRect(thumb, ui.dp(6).toFloat(), ui.dp(6).toFloat(), paint)
        super.dispatchDraw(canvas)
    }
}
