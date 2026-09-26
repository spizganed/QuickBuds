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
 * Hue slider for the Edit preset colour picker (design/SPEC.md 3.8): a rainbow track and a thumb
 * filled with the current colour. [onChange] fires while dragging (live preview), [onRelease] on
 * finger lift (the save), like the EQ curve.
 */
class HueSliderView(context: Context) : View(context) {

    /** 0..360. */
    var hue = 0f
        set(v) { field = v.coerceIn(0f, 360f); invalidate() }

    /** The thumb's fill: the colour the current hue produces. */
    var thumbColor = 0
        set(v) { field = v; invalidate() }

    var onChange: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val stops = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 0.95f)) }
        track.shader = LinearGradient(dp(14f), 0f, w - dp(14f), 0f, stops, null, Shader.TileMode.CLAMP)
    }

    private val usable get() = width - dp(32f)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        box.set(0f, cy - dp(13f), width.toFloat(), cy + dp(13f))
        c.drawRoundRect(box, dp(13f), dp(13f), track)
        val x = dp(16f) + usable * hue / 360f
        fill.color = thumbColor
        c.drawCircle(x, cy, dp(15f), fill)
        c.drawCircle(x, cy, dp(15f), ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val h = ((e.x - dp(16f)) / usable * 360f).coerceIn(0f, 360f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); hue = h; onChange?.invoke(h) }
            MotionEvent.ACTION_MOVE -> { hue = h; onChange?.invoke(h) }
            MotionEvent.ACTION_UP -> { hue = h; onRelease?.invoke(h); performClick() }
            MotionEvent.ACTION_CANCEL -> onRelease?.invoke(hue)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
