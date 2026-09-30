package com.spizganed.quickbuds.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/** Shared motion for rows and panels that appear and go away. */
object Motion {
    /**
     * Grows [v] open, or shuts it, by animating its height with a fade — used for a new preset
     * row, a deleted one, and the Bass boost slider. A shut view ends GONE; [onEnd] runs after.
     */
    fun slide(v: View, open: Boolean, ms: Long = if (open) 280 else 220, onEnd: (() -> Unit)? = null) {
        val lp = v.layoutParams
        // A split row's card has a gap above it: it grows and shrinks with the card, or it snaps at the end.
        val mlp = lp as? ViewGroup.MarginLayoutParams
        val gap = mlp?.topMargin ?: 0
        val natural = lp.height
        val full = if (natural > 0) natural else {
            val w = (v.parent as? View)?.width ?: 0
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            v.measuredHeight
        }
        v.visibility = View.VISIBLE
        ValueAnimator.ofInt(if (open) 0 else full, if (open) full else 0).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                val shown = if (open) it.animatedFraction else 1f - it.animatedFraction
                v.alpha = shown
                mlp?.topMargin = (gap * shown).toInt()
                v.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    lp.height = natural
                    mlp?.topMargin = gap
                    v.alpha = 1f
                    if (!open) v.visibility = View.GONE
                    v.requestLayout()
                    onEnd?.invoke()
                }
            })
            start()
        }
    }
}
