package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
    private val nothing = ThemeRes.nothing(context)

    /** Material style: the M3 slider. A 16dp track (tonal past the handle), a gap, and a 4dp bar handle that thins while dragged. */
    private val material = ThemeRes.material(context)
    private val tonal = ThemeRes.palette(context).tonal

    /** Glass: Apple's slider. A thin translucent track, a white fill and a round white thumb with a soft shadow that grows while held. */
    private val glass = ThemeRes.palette(context).glass && !nothing
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = android.graphics.RectF()
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (ThemeRes.palette(context).glass && !nothing) ThemeRes.palette(context).text else accent; textSize = dp(14f); textAlign = Paint.Align.CENTER
        if (nothing) typeface = ThemeRes.headline(context) else isFakeBoldText = true
    }

    private val left get() = dp(22f)
    private val right get() = width - dp(22f)
    private val trackY get() = if (showValue) dp(if (material) 46f else 40f) else dp(22f)

    private fun x(v: Float) = left + (v - min) * (right - left) / (max - min)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (showValue) (if (material) 68f else 58f) else 44f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        // Nothing style: the same drawing as dots (DotArt); the value stays text.
        if (nothing) DotArt.draw(context, canvas, width, height) { shapes(it) } else shapes(canvas)
        if (showValue) canvas.drawText(if (value > 0 && min < 0) "+$value" else "$value", x(pos), trackY - dp(if (material) 26f else 18f), valuePaint)
    }

    private fun shapes(canvas: Canvas) {
        if (glass) { ios(canvas); return }
        if (material) { m3(canvas); return }
        canvas.drawLine(left, trackY, right, trackY, trackPaint)
        canvas.drawLine(left, trackY, x(pos), trackY, fillPaint)
        if (nothing) { DotArt.knob(context, canvas, x(pos), trackY, accent); return }
        val r = if (dragging) dp(10f) else dp(8f)
        canvas.drawCircle(x(pos), trackY, r, dotFill)
        canvas.drawCircle(x(pos), trackY, r, dotRing)
    }

    private fun ios(canvas: Canvas) {
        val x = x(pos)
        val h = dp(2.5f)
        barPaint.color = if (ThemeRes.palette(context).glassLight) 0x2614152B else 0x40FFFFFF
        bar.set(left - h * 2, trackY - h, right + h * 2, trackY + h)
        canvas.drawRoundRect(bar, h, h, barPaint)
        barPaint.color = accent
        bar.set(left - h * 2, trackY - h, x, trackY + h)
        canvas.drawRoundRect(bar, h, h, barPaint)
        val r = if (dragging) dp(15f) else dp(13f)
        val cy = trackY
        shadowPaint.shader = android.graphics.RadialGradient(x, cy + dp(2f), r + dp(7f),
            intArrayOf(0x59000000, 0x59000000, 0), floatArrayOf(0f, r / (r + dp(7f)), 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(x, cy + dp(2f), r + dp(7f), shadowPaint)
        barPaint.color = Color.WHITE
        canvas.drawCircle(x, cy, r, barPaint)
    }

    private fun m3(canvas: Canvas) {
        val x = x(pos)
        val half = dp(8f)
        val gap = dp(6f)
        // Outer ends fully round, the ends next to the handle 2dp, as in M3.
        val outer = half
        val inner = dp(2f)
        if (x - gap > left - half) part(canvas, left - half, x - gap, fillPaint.color, outer, inner)
        if (x + gap < right + half) part(canvas, x + gap, right + half, tonal, inner, outer)
        barPaint.color = fillPaint.color
        val w = if (dragging) dp(1f) else dp(2f)
        bar.set(x - w, trackY - dp(22f), x + w, trackY + dp(22f))
        canvas.drawRoundRect(bar, w, w, barPaint)
    }

    private val path = android.graphics.Path()

    /** One track segment from [from] to [to], with radius [l] on the left ends and [r] on the right. */
    private fun part(canvas: Canvas, from: Float, to: Float, color: Int, l: Float, r: Float) {
        val max = (to - from) / 2
        val lr = minOf(l, max); val rr = minOf(r, max)
        bar.set(from, trackY - dp(8f), to, trackY + dp(8f))
        path.reset()
        path.addRoundRect(bar, floatArrayOf(lr, lr, rr, rr, rr, rr, lr, lr), android.graphics.Path.Direction.CW)
        barPaint.color = color
        canvas.drawPath(path, barPaint)
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
        if (step != value) { value = step; onChange?.invoke(step); Haptics.step(this) }
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
