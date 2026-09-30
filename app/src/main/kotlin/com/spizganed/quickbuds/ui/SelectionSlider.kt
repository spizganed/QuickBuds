package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator

/**
 * The [ThemeRes.selectedBorder] of a list of rows as one outline in [host]'s overlay, slid from row to row
 * ([USER] 2026-09-30) instead of appearing on the new one. [host] is the rows' common parent (it scrolls with
 * them), so the rows may sit in several cards. The slider outlives row rebuilds: call [moveTo] after each one.
 */
class SelectionSlider(private val host: ViewGroup) {
    private var outline: Drawable? = null
    private var color = 0
    private var drawnColor = 0
    private var row: View? = null
    private val at = Rect()
    private var anim: ValueAnimator? = null

    init {
        // Rows move without being rebuilt too (the Bass boost slider opening): follow them.
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (anim?.isRunning != true) place(animate = false) }
    }

    /** Shows the outline on [row] (null hides it), sliding when it was already on screen. */
    fun moveTo(row: View?, color: Int) {
        this.row = row
        this.color = color
        if (row == null) { hide(); return }
        // Rows are laid out on the next pass; measure them then.
        host.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                host.viewTreeObserver.removeOnPreDrawListener(this)
                place(animate = true)
                return true
            }
        })
    }

    /** Puts the outline on its row again without sliding (after the row finished growing). */
    fun snap() = place(animate = false)

    private fun hide() {
        anim?.cancel()
        outline?.let { host.overlay.remove(it) }
        outline = null
    }

    private fun place(animate: Boolean) {
        val r = row
        if (r == null || r.parent == null || r.width == 0 || r.height == 0) { hide(); return }
        val to = Rect(0, 0, r.width, r.height)
        host.offsetDescendantRectToMyCoords(r, to)
        anim?.cancel()
        var d = outline
        if (d == null || drawnColor != color) {
            d?.let { host.overlay.remove(it) }
            d = ThemeRes.selectedBorder(host.context, color, SettingRowFactory.SPLIT_RADIUS).also { it.bounds = if (outline == null) to else at; host.overlay.add(it) }
            val first = outline == null
            outline = d
            drawnColor = color
            if (first) { at.set(to); return }
        }
        if (!animate || at == to) { d.bounds = to; at.set(to); return }
        val from = Rect(at)
        val shown = d
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                fun mix(a: Int, b: Int) = a + ((b - a) * t).toInt()
                at.set(mix(from.left, to.left), mix(from.top, to.top), mix(from.right, to.right), mix(from.bottom, to.bottom))
                shown.bounds = at
            }
            start()
        }
    }
}
