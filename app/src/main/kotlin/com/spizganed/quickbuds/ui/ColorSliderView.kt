package com.spizganed.quickbuds.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * One HSV channel of the colour picker (hue, saturation or brightness). The track shows what that
 * channel does to the current colour; the thumb is filled with the current colour. [onChange]
 * fires while dragging (live preview), [onRelease] on finger lift (the save), like the EQ curve.
 */
class ColorSliderView(context: Context, private val channel: Int) : View(context) {

    /** The colour being edited, as HSV. Only [channel] is changed by this slider. */
    var hsv = floatArrayOf(0f, 0f, 0f)
        set(v) { field = v.copyOf(); invalidate() }

    var onChange: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null

    private val max = if (channel == HUE) 360f else 1f

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = ThemeRes.palette(context).text
    }
    private val box = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(40f).toInt())
    }

    private val usable get() = width - dp(32f)

    private fun stops(): IntArray = when (channel) {
        HUE -> IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 0.95f)) }
        SAT -> intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
        else -> intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 0f)), Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        track.shader = LinearGradient(dp(14f), 0f, width - dp(14f), 0f, stops(), null, Shader.TileMode.CLAMP)
        box.set(0f, cy - dp(13f), width.toFloat(), cy + dp(13f))
        c.drawRoundRect(box, dp(13f), dp(13f), track)
        val x = dp(16f) + usable * hsv[channel] / max
        fill.color = Color.HSVToColor(hsv)
        c.drawCircle(x, cy, dp(15f), fill)
        c.drawCircle(x, cy, dp(15f), ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val v = ((e.x - dp(16f)) / usable * max).coerceIn(0f, max)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); set(v); onChange?.invoke(v) }
            MotionEvent.ACTION_MOVE -> { set(v); onChange?.invoke(v) }
            MotionEvent.ACTION_UP -> { set(v); onRelease?.invoke(v); performClick() }
            MotionEvent.ACTION_CANCEL -> onRelease?.invoke(hsv[channel])
        }
        return true
    }

    private fun set(v: Float) { hsv[channel] = v; invalidate() }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        const val HUE = 0
        const val SAT = 1
        const val VAL = 2
    }
}
