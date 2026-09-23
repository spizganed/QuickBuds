package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.spizganed.quickbuds.R

/**
 * Noise-control switcher (redesign 2026-09-23, replacing the four separate ANC buttons): one pill
 * track with an accent highlight that SLIDES to the active segment. A tap reports the segment index
 * through [onSegmentTapped]; the caller decides what it means (ANC opens the strength chooser).
 * The highlight only moves when [selected] is set — i.e. when the buds' state says so.
 */
class AncSegmentedView(context: Context, private val labels: List<String>) : View(context) {

    var onSegmentTapped: ((Int) -> Unit)? = null

    var selected: Int = 0
        set(v) {
            if (v == field) return
            field = v
            if (width == 0) { pos = v.toFloat(); invalidate(); return }
            anim?.cancel()
            anim = ValueAnimator.ofFloat(pos, v.toFloat()).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { pos = it.animatedValue as Float; postInvalidateOnAnimation() }
                start()
            }
        }

    private var pos = 0f
    private var anim: ValueAnimator? = null

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ThemeRes.color(context, R.attr.appColorCard)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f)
        color = ThemeRes.color(context, R.attr.appColorOutline)
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ThemeRes.color(context, R.attr.appColorAccent)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val textOn = 0xFFFFFFFF.toInt()
    private val textOff = ThemeRes.color(context, R.attr.appColorTextSecondary)
    private val box = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(54f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val radius = h / 2
        box.set(dp(0.5f), dp(0.5f), width - dp(0.5f), h - dp(0.5f))
        canvas.drawRoundRect(box, radius, radius, trackPaint)
        canvas.drawRoundRect(box, radius, radius, strokePaint)

        val inset = dp(5f)
        val segW = (width - inset * 2) / labels.size
        val left = inset + segW * pos
        box.set(left, inset, left + segW, h - inset)
        canvas.drawRoundRect(box, radius - inset, radius - inset, pillPaint)

        val baseline = h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
        labels.forEachIndexed { i, label ->
            // The label under the moving pill brightens as the pill arrives.
            val closeness = (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
            textPaint.color = blend(textOff, textOn, closeness)
            canvas.drawText(label, inset + segW * i + segW / 2, baseline, textPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = ((e.x / width) * labels.size).toInt().coerceIn(0, labels.size - 1)
            performClick()
            onSegmentTapped?.invoke(i)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
