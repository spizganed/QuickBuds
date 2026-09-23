package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.EqCodec
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The custom-preset band editor, drawn the way HeyMelody draws it: one point per band on a
 * shared ±6 dB scale, joined by a smooth curve with a fill under it. Drag a point up or down;
 * gains snap to whole dB and [onRelease] fires once when the finger lifts (one write per gesture).
 */
class EqCurveView(context: Context) : View(context) {

    var freqs: List<Int> = emptyList()
    var gains: IntArray = IntArray(0)
    var onRelease: ((band: Int, gain: Int) -> Unit)? = null

    private var active = -1

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()
    private val accent = ThemeRes.color(context, R.attr.appColorAccent)
    private val secondary = ThemeRes.color(context, R.attr.appColorTextSecondary)
    private val bg = ThemeRes.color(context, R.attr.appColorBg)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondary; alpha = 60; strokeWidth = dp(1f)
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; textSize = dp(14f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondary; textSize = dp(12f); textAlign = Paint.Align.CENTER
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondary; textSize = dp(12f); textAlign = Paint.Align.LEFT
    }

    // Plot area.
    private val left get() = dp(62f)
    private val right get() = width - dp(20f)
    private val top get() = dp(44f)
    private val bottom get() = height - dp(36f)

    private fun x(band: Int) =
        if (gains.size < 2) left else left + band * (right - left) / (gains.size - 1)

    private fun y(gain: Float) =
        top + (EqCodec.GAIN_MAX - gain) * (bottom - top) / (EqCodec.GAIN_MAX - EqCodec.GAIN_MIN)

    /**
     * What is DRAWN, per band, in fractional dB. It follows the finger exactly while dragging and
     * glides onto the snapped value on release. Drawing the snapped int directly made the point jump
     * between 13 fixed steps, which read as a low frame rate (2026-09-23).
     */
    private var pos = FloatArray(0)
    private var settle: ValueAnimator? = null

    private val curve = Path()
    private val fill = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(280f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            (accent and 0x00FFFFFF) or 0x70000000, accent and 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (gains.isEmpty()) return
        if (pos.size != gains.size) pos = FloatArray(gains.size) { gains[it].toFloat() }

        for (g in intArrayOf(EqCodec.GAIN_MAX, 0, EqCodec.GAIN_MIN)) {
            val label = if (g > 0) "+$g dB" else "$g dB"
            canvas.drawText(label, dp(4f), y(g.toFloat()) + dp(4f), scalePaint)
        }
        for (i in gains.indices) canvas.drawLine(x(i), top, x(i), bottom, gridPaint)

        // Smooth curve: horizontal-tangent cubic between neighbours, so it never overshoots a point.
        curve.reset()
        curve.moveTo(x(0), y(pos[0]))
        for (i in 1 until pos.size) {
            val mx = (x(i - 1) + x(i)) / 2
            curve.cubicTo(mx, y(pos[i - 1]), mx, y(pos[i]), x(i), y(pos[i]))
        }
        fill.set(curve)
        fill.lineTo(x(pos.size - 1), bottom)
        fill.lineTo(x(0), bottom)
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(curve, curvePaint)

        for (i in gains.indices) {
            val r = if (i == active) dp(9f) else dp(7f)
            canvas.drawCircle(x(i), y(pos[i]), r, dotFill)
            canvas.drawCircle(x(i), y(pos[i]), r, dotRing)
            val v = gains[i]
            canvas.drawText(if (v > 0) "+$v" else "$v", x(i), dp(22f), valuePaint)
            val f = freqs.getOrNull(i) ?: 0
            canvas.drawText(if (f >= 1000) "${f / 1000}k" else "$f", x(i), height - dp(10f), axisPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (gains.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settle?.end()
                active = gains.indices.minByOrNull { abs(x(it) - e.x) } ?: return false
                // The sheet sits in a dialog; keep a vertical drag from being taken as a scroll.
                parent?.requestDisallowInterceptTouchEvent(true)
                follow(e.y)
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) follow(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (active >= 0) {
                val band = active
                active = -1
                glideTo(band)
                if (e.actionMasked == MotionEvent.ACTION_UP) onRelease?.invoke(band, gains[band])
            }
        }
        return true
    }

    /** Point follows the finger continuously; the stored gain is the nearest whole dB. */
    private fun follow(py: Float) {
        val span = (bottom - top) / (EqCodec.GAIN_MAX - EqCodec.GAIN_MIN)
        val g = (EqCodec.GAIN_MAX - (py - top) / span)
            .coerceIn(EqCodec.GAIN_MIN.toFloat(), EqCodec.GAIN_MAX.toFloat())
        if (pos.size != gains.size) pos = FloatArray(gains.size) { gains[it].toFloat() }
        pos[active] = g
        gains[active] = g.roundToInt()
        postInvalidateOnAnimation()
    }

    private fun glideTo(band: Int) {
        val from = pos[band]
        val to = gains[band].toFloat()
        settle = ValueAnimator.ofFloat(from, to).apply {
            duration = 120
            addUpdateListener { pos[band] = it.animatedValue as Float; postInvalidateOnAnimation() }
            start()
        }
    }
}
