package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
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
    private val nothing = ThemeRes.nothing(context)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; textSize = dp(14f); textAlign = Paint.Align.CENTER
        if (nothing) typeface = ThemeRes.headline(context) else isFakeBoldText = true
    }

    init {
        // Dot style: the grid a full dot wide (thinner fell under DotArt's minimum); knobs are DotArt.knob.
        if (nothing) gridPaint.strokeWidth = DotArt.pitchPx(context)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondary; textSize = dp(12f); textAlign = Paint.Align.CENTER; typeface = ThemeRes.regular(context)
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondary; textSize = dp(12f); textAlign = Paint.Align.LEFT; typeface = ThemeRes.regular(context)
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
        // Nothing style: grid, fill, curve and points as dots (DotArt); the numbers stay text.
        if (nothing) DotArt.draw(context, canvas, width, height) { shapes(it) } else shapes(canvas)

        for (i in gains.indices) {
            val v = gains[i]
            canvas.drawText(if (v > 0) "+$v" else "$v", x(i), dp(22f), valuePaint)
            val f = freqs.getOrNull(i) ?: 0
            canvas.drawText(if (f >= 1000) "${f / 1000}k" else "$f", x(i), height - dp(10f), axisPaint)
        }
    }

    private fun shapes(canvas: Canvas) {
        // Nothing style: each line on one dot column's centre; between two it lit both under the fill (the "bleed").
        val pitch = DotArt.pitchPx(context)
        for (i in gains.indices) {
            val gx = if (nothing) (Math.floor(x(i) / pitch.toDouble()).toFloat() + 0.5f) * pitch else x(i)
            canvas.drawLine(gx, top, gx, bottom, gridPaint)
        }

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
        if (nothing) dotLine(canvas, pitch) else canvas.drawPath(curve, curvePaint)

        for (i in gains.indices) {
            if (nothing) { DotArt.knob(context, canvas, x(i), y(pos[i]), accent); continue }
            val r = if (i == active) dp(9f) else dp(7f)
            canvas.drawCircle(x(i), y(pos[i]), r, dotFill)
            canvas.drawCircle(x(i), y(pos[i]), r, dotRing)
        }
    }

    /**
     * The curve as dots ([USER] 2026-09-30: it was jagged). A stroke sampled at one point per cell came out one
     * cell thick in one place and two in the next; here each cell the path passes through is lit once, so the line
     * is one even, connected row of dots. Called inside [DotArt.draw], where one cell is one pixel.
     */
    private fun dotLine(canvas: Canvas, pitch: Float) {
        val pm = PathMeasure(curve, false)
        val at = FloatArray(2)
        val cell = Paint().apply { color = accent }
        var lastX = Int.MIN_VALUE
        var lastY = Int.MIN_VALUE
        var d = 0f
        while (d <= pm.length) {
            pm.getPosTan(d, at, null)
            val cx = Math.floor(at[0] / pitch.toDouble()).toInt()
            val cy = Math.floor(at[1] / pitch.toDouble()).toInt()
            if (cx != lastX || cy != lastY) canvas.drawRect(cx * pitch, cy * pitch, (cx + 1) * pitch, (cy + 1) * pitch, cell)
            lastX = cx; lastY = cy
            d += pitch / 4
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
                if (e.actionMasked == MotionEvent.ACTION_UP) { onRelease?.invoke(band, gains[band]); Haptics.commit(this) }
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
        val snapped = g.roundToInt()
        if (snapped != gains[active]) Haptics.step(this)
        gains[active] = snapped
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
