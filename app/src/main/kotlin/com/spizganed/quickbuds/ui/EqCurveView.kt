package com.spizganed.quickbuds.ui

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

    private fun y(gain: Int) =
        top + (EqCodec.GAIN_MAX - gain) * (bottom - top) / (EqCodec.GAIN_MAX - EqCodec.GAIN_MIN)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(280f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (gains.isEmpty()) return

        for (g in intArrayOf(EqCodec.GAIN_MAX, 0, EqCodec.GAIN_MIN)) {
            val label = if (g > 0) "+$g dB" else "$g dB"
            canvas.drawText(label, dp(4f), y(g) + dp(4f), scalePaint)
        }
        for (i in gains.indices) canvas.drawLine(x(i), top, x(i), bottom, gridPaint)

        // Smooth curve: horizontal-tangent cubic between neighbours, so it never overshoots a point.
        val curve = Path().apply {
            moveTo(x(0), y(gains[0]))
            for (i in 1 until gains.size) {
                val mx = (x(i - 1) + x(i)) / 2
                cubicTo(mx, y(gains[i - 1]), mx, y(gains[i]), x(i), y(gains[i]))
            }
        }
        val fill = Path(curve).apply {
            lineTo(x(gains.size - 1), bottom)
            lineTo(x(0), bottom)
            close()
        }
        fillPaint.shader = LinearGradient(
            0f, top, 0f, bottom,
            (accent and 0x00FFFFFF) or 0x70000000, accent and 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(curve, curvePaint)

        for (i in gains.indices) {
            val r = if (i == active) dp(9f) else dp(7f)
            canvas.drawCircle(x(i), y(gains[i]), r, dotFill)
            canvas.drawCircle(x(i), y(gains[i]), r, dotRing)
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
                active = gains.indices.minByOrNull { abs(x(it) - e.x) } ?: return false
                // The sheet sits in a dialog; keep a vertical drag from being taken as a scroll.
                parent?.requestDisallowInterceptTouchEvent(true)
                setGain(e.y)
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) setGain(e.y)
            MotionEvent.ACTION_UP -> if (active >= 0) {
                val band = active
                active = -1
                invalidate()
                onRelease?.invoke(band, gains[band])
            }
            MotionEvent.ACTION_CANCEL -> { active = -1; invalidate() }
        }
        return true
    }

    private fun setGain(py: Float) {
        val span = (bottom - top) / (EqCodec.GAIN_MAX - EqCodec.GAIN_MIN)
        val g = (EqCodec.GAIN_MAX - (py - top) / span).roundToInt()
            .coerceIn(EqCodec.GAIN_MIN, EqCodec.GAIN_MAX)
        if (gains[active] != g) gains[active] = g
        invalidate()
    }
}
