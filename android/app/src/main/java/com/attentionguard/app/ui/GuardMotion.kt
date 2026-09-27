package com.attentionguard.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import androidx.core.view.doOnLayout
import com.attentionguard.app.R

/** One-shot relay rhythm. No timers or animation work survives detachment. */
object GuardMotion {
    const val QUICK = 160L
    const val SETTLE = 220L
    private val ease = PathInterpolator(0.2f, 0.75f, 0.25f, 1f)

    fun cancel(view: View) {
        (view.getTag(R.id.ag_motion) as? Animator)?.cancel()
    }

    private fun play(view: View, animator: Animator, settle: () -> Unit) {
        cancel(view)
        if (!ValueAnimator.areAnimatorsEnabled()) { settle(); return }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { animator.cancel() }
        }
        view.setTag(R.id.ag_motion, animator)
        view.addOnAttachStateChangeListener(detach)
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                settle()
                view.removeOnAttachStateChangeListener(detach)
                if (view.getTag(R.id.ag_motion) === animator) view.setTag(R.id.ag_motion, null)
            }
        })
        animator.start()
    }

    fun progress(view: View, duration: Long = QUICK, update: (Float) -> Unit) {
        play(view, ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = ease
            addUpdateListener { update(it.animatedValue as Float) }
        }) { update(1f) }
    }

    fun enter(view: View, delay: Long = 0) {
        play(view, AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 0.35f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 6f * view.resources.displayMetrics.density, 0f))
            duration = QUICK
            startDelay = delay
            interpolator = ease
        }) { view.alpha = 1f; view.translationY = 0f }
    }

    fun revealRows(group: ViewGroup) {
        for (index in 0 until minOf(group.childCount, 6)) enter(group.getChildAt(index), index * 22L)
    }

    fun acknowledge(view: View) {
        play(view, AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, .90f, 1.08f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, .90f, 1.08f, 1f)
            )
            duration = SETTLE
            interpolator = ease
        }) { view.scaleX = 1f; view.scaleY = 1f }
    }

    fun refresh(view: View) {
        play(view, ObjectAnimator.ofFloat(view, View.ROTATION, 0f, 180f).apply {
            duration = SETTLE
            interpolator = ease
        }) { view.rotation = 0f }
    }

    /** Outline-only growth keeps glyphs at their final size throughout the reveal. */
    fun unfold(view: View, fromBubble: Boolean) {
        view.doOnLayout {
            if (!view.isAttachedToWindow) return@doOnLayout
            cancel(view)
            val density = view.resources.displayMetrics.density
            val endWidth = view.width
            val endHeight = view.height
            val startWidth = if (fromBubble) minOf(endWidth, (52 * density).toInt()) else endWidth
            val startHeight = minOf(endHeight, (52 * density).toInt())
            var fraction = 0f
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    val width = (startWidth + (endWidth - startWidth) * fraction).toInt()
                    val height = (startHeight + (endHeight - startHeight) * fraction).toInt()
                    val radius = ((if (fromBubble) 26f else 8f) * (1f - fraction) + 8f * fraction) * density
                    outline.setRoundRect(0, 0, width, height, radius)
                }
            }
            view.clipToOutline = true
            play(view, ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SETTLE
                interpolator = ease
                addUpdateListener { fraction = it.animatedValue as Float; view.invalidateOutline() }
            }) {
                view.outlineProvider = ViewOutlineProvider.BACKGROUND
                view.clipToOutline = true
                view.invalidateOutline()
            }
        }
    }

    fun bindPress(view: View) {
        fun scale(to: Float, time: Long) = AnimatorSet().apply {
            playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, to), ObjectAnimator.ofFloat(view, View.SCALE_Y, to))
            duration = time
            interpolator = ease
        }
        view.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), scale(.96f, 80))
            addState(intArrayOf(), scale(1f, QUICK))
        }
    }
}
