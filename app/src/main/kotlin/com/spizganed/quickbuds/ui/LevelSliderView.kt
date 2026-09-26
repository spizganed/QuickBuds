package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.spizganed.quickbuds.R
import kotlin.math.roundToInt

/**
 * A stepped horizontal slider in [EqCurveView]'s visual language: accent track fill, a ringed
 * knob and the value above the knob. Like the curve, the knob follows
 * the finger continuously and glides onto the nearest step on release; [onRelease] fires once.
 */
class LevelSliderView(
    context: Context,
    private val min: Int,
    private val max: Int
) : View(context) {

    var onRelease: ((Int) -> Unit)? = null
    /** Fires on every step change during a drag, before [onRelease]. */
    var onChange: ((Int) -> Unit)? = null
    /** False = no number over the knob (HeyMelody's alert-volume bar), and a shorter view. */
    var showValue = true

    /** The step value. Setting it from code while the user is not dragging moves the knob. */
    var value: Int = min
        set(v) {
            field = v.coerceIn(min, max)
            if (!dragging) { pos = field.toFloat(); invalidate() }
        }

    var dragging = false
        private set

    private var pos = min.toFloat()
    private var settle: ValueAnimator? = null

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()
    private val accent = ThemeRes.color(context, R.attr.appColorAccent)
    private val secondary = ThemeRes.color(context, R.attr.appColorTextSecondary)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondary; alpha = 60; strokeWidth = dp(6f); strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; strokeWidth = dp(6f); strokeCap = Paint.Cap.ROUND
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ThemeRes.color(context, R.attr.appColorBg) }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; textSize = dp(14f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private val left get() = dp(22f)
    private val right get() = width - dp(22f)
    private val trackY get() = if (showValue) dp(40f) else dp(22f)

    private fun x(v: Float) = left + (v - min) * (right - left) / (max - min)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (showValue) 58f else 44f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawLine(left, trackY, right, trackY, trackPaint)
        canvas.drawLine(left, trackY, x(pos), trackY, fillPaint)

        val r = if (dragging) dp(10f) else dp(8f)
        canvas.drawCircle(x(pos), trackY, r, dotFill)
        canvas.drawCircle(x(pos), trackY, r, dotRing)
        if (showValue) canvas.drawText(if (value > 0) "+$value" else "$value", x(pos), trackY - dp(18f), valuePaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settle?.cancel()
                dragging = true
                // Inside a ScrollView: a sideways drag must not be taken over as a scroll.
                parent?.requestDisallowInterceptTouchEvent(true)
                follow(e.x)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) follow(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                glide()
                if (e.actionMasked == MotionEvent.ACTION_UP) { onRelease?.invoke(value); Haptics.commit(this) }
            }
        }
        return true
    }

    private fun follow(px: Float) {
        pos = (min + (px - left) * (max - min) / (right - left)).coerceIn(min.toFloat(), max.toFloat())
        val step = pos.roundToInt()
        if (step != value) { value = step; onChange?.invoke(step) }
        postInvalidateOnAnimation()
    }

    private fun glide() {
        settle = ValueAnimator.ofFloat(pos, value.toFloat()).apply {
            duration = 120
            addUpdateListener { pos = it.animatedValue as Float; postInvalidateOnAnimation() }
            start()
        }
    }
}
