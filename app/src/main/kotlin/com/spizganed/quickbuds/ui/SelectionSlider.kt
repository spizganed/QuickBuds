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
 * With a [key] the last position is kept across the screen's own recreate(), so a pick that recreates the
 * activity (a theme) still slides from where the outline was.
 */
class SelectionSlider(private val host: ViewGroup, private val key: String? = null) {
    private var outline: Drawable? = null
    private var color = 0
    private var radius = SettingRowFactory.SPLIT_RADIUS
    private var drawnColor = 0
    private var drawnRadius = 0f
    private var row: View? = null
    private val at = Rect()
    private var anim: ValueAnimator? = null

    /** A [moveTo] waits for its draw pass; the layout listener must not snap the outline in between (it ran first and skipped every slide). */
    private var pending = false

    init {
        // Rows move without being rebuilt too (the Bass boost slider opening): follow them.
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!pending && anim?.isRunning != true) place(animate = false) }
    }

    /** Shows the outline on [row] (null hides it), sliding when it was already on screen. */
    fun moveTo(row: View?, color: Int, radiusDp: Float = SettingRowFactory.SPLIT_RADIUS) {
        this.row = row
        this.color = color
        this.radius = radiusDp
        if (row == null) { pending = false; hide(); return }
        pending = true
        // Rows are laid out on the next pass; measure them then.
        host.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                host.viewTreeObserver.removeOnPreDrawListener(this)
                pending = false
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
        if (d == null || drawnColor != color || drawnRadius != radius) {
            val was = d != null
            d?.let { host.overlay.remove(it) }
            // Where a fresh outline starts: the old one, or the position kept across a recreate.
            val start = if (was) Rect(at) else key?.let { carried[it] }?.let { Rect(it) } ?: Rect(to)
            d = ThemeRes.selectedBorder(host.context, color, radius).also { it.bounds = start; host.overlay.add(it) }
            outline = d
            drawnColor = color
            drawnRadius = radius
            at.set(start)
        }
        key?.let { carried[it] = Rect(to) }
        if (!animate || at == to) { d.bounds = to; at.set(to); host.invalidate(); return }
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
                // A drawable's bounds change does not redraw it: the overlay needs telling.
                host.invalidate()
            }
            start()
        }
    }

    private companion object {
        /** Last outline rect per [key], for a screen that recreates itself on a pick. */
        val carried = HashMap<String, Rect>()
    }
}
