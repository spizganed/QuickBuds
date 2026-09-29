package com.spizganed.quickbuds.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.GoldenSound

/**
 * The hearing profile as HeyMelody draws it: a radar over the test frequencies
 * ([GoldenSound.AXES], 80 at the top, clockwise), one ear at a time, 10 = no change at the rim.
 * Dots in the Nothing style.
 */
class HearingRadarView(context: Context) : View(context) {

    /** Radii 0..10 per axis, null = not read yet. */
    var values: FloatArray? = null
        set(v) { field = v; invalidate() }

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()
    private val nothing = ThemeRes.nothing(context)
    private val accent = ThemeRes.color(context, R.attr.appColorAccent)
    private val secondary = ThemeRes.color(context, R.attr.appColorTextSecondary)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (nothing) DotArt.pitchPx(context) else dp(1f)
        color = ThemeRes.color(context, R.attr.appColorOutline)
    }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        color = secondary
        textAlign = Paint.Align.CENTER
        typeface = ThemeRes.regular(context)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, minOf(w, ThemeRes.dp(context, 260f)))
    }

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val radius get() = minOf(width, height) / 2f - dp(30f)

    private fun point(i: Int, r: Float): Pair<Float, Float> {
        val a = -2 * Math.PI / 3 + i * 2 * Math.PI / GoldenSound.AXES.size
        return (cx + Math.cos(a) * radius * r / 10).toFloat() to (cy + Math.sin(a) * radius * r / 10).toFloat()
    }

    private fun polygon(values: FloatArray): Path = Path().apply {
        values.forEachIndexed { i, v -> point(i, v).let { (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) } }
        close()
    }

    override fun onDraw(canvas: Canvas) {
        if (nothing) DotArt.draw(context, canvas, width, height) { shapes(it) } else shapes(canvas)
        GoldenSound.AXES.forEachIndexed { i, f ->
            val (x, y) = point(i, 12.2f)
            val label = if (f >= 1000) "${f / 1000.0}".removeSuffix(".0") + "k" else "$f"
            canvas.drawText(label, x, y + labelPaint.textSize / 3, labelPaint)
        }
    }

    private fun shapes(canvas: Canvas) {
        val boost = polygon(FloatArray(GoldenSound.AXES.size) { 10f })
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = secondary and 0x00FFFFFF or 0x1A000000
        canvas.drawPath(boost, shapePaint)
        shapePaint.style = Paint.Style.STROKE
        shapePaint.strokeWidth = dp(2f)
        shapePaint.color = secondary
        canvas.drawPath(boost, shapePaint)
        for (ring in listOf(2.5f, 5f, 7.5f)) canvas.drawPath(polygon(FloatArray(GoldenSound.AXES.size) { ring }), gridPaint)
        GoldenSound.AXES.indices.forEach { i ->
            val (x, y) = point(i, 10f)
            canvas.drawLine(cx, cy, x, y, gridPaint)
        }
        val path = polygon(values ?: return)
        shapePaint.style = Paint.Style.FILL
        shapePaint.color = accent and 0x00FFFFFF or 0x40000000
        canvas.drawPath(path, shapePaint)
        shapePaint.style = Paint.Style.STROKE
        shapePaint.strokeWidth = dp(2f)
        shapePaint.color = accent
        canvas.drawPath(path, shapePaint)
    }
}
