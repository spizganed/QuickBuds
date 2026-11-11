package com.spizganed.quickbuds.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The Glass battery ring and its glyph, drawn once for the app's home tile ([BudsStatusView]) and the widgets, so both look the same.
 * Everything scales with the ring: [unit] is the ring's diameter divided by 90, the design's 90 dp ring.
 */
object GlassArt {

    /** A faint disc, a thin track and the accent arc ([frac] 0..1, from 12 o'clock). [a] fades the whole ring (a disconnect). */
    fun ring(c: Canvas, cx: Float, cy: Float, size: Float, frac: Float, accent: Int, ink: Int, light: Boolean, a: Float = 1f) {
        val unit = size / 90f
        val stroke = 4f * unit
        val r = size / 2 - stroke / 2
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = withA(if (light) 0x1A14152B else 0x14FFFFFF, a)
        c.drawCircle(cx, cy, r - stroke / 2 - unit, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = stroke; p.strokeCap = Paint.Cap.ROUND
        p.color = if (light) 0x2614152B else 0x2EFFFFFF
        c.drawOval(box, p)
        if (frac > 0f) {
            p.color = accent
            p.alpha = (255 * (0.35f + 0.65f * a)).toInt()
            if (frac >= 0.995f) c.drawOval(box, p) else c.drawArc(box, -90f, 360f * frac, false, p)
        }
    }

    private fun withA(color: Int, a: Float) = (color and 0x00FFFFFF) or ((((color ushr 24) and 0xFF) * (0.35f + 0.65f * a)).toInt() shl 24)

    /** One glyph's cached masks (the shape and its blurred shadow), made again when its size changes. */
    class Glyph(private val icon: Drawable) {
        private var mask: Bitmap? = null
        private var shadow: Bitmap? = null
        private val off = IntArray(2)
        private var key = ""

        /**
         * The glyph centred on ([cx], [cy]), [w] by [h] px, in [tint]: a soft shadow below, a lit top that fades to a dimmer
         * bottom, and a thin highlight along the top edge.
         */
        fun draw(c: Canvas, cx: Float, cy: Float, w: Float, h: Float, tint: Int, unit: Float, light: Boolean) {
            val iw = w.toInt().coerceAtLeast(1); val ih = h.toInt().coerceAtLeast(1)
            val k = "$iw/$ih/$unit"
            if (key != k) {
                val bmp = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
                icon.setTint(Color.WHITE)
                icon.setBounds(0, 0, iw, ih)
                icon.draw(Canvas(bmp))
                mask = bmp.extractAlpha()
                shadow = bmp.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(3.5f * unit, BlurMaskFilter.Blur.NORMAL) }, off)
                bmp.recycle()
                key = k
            }
            val x = cx - iw / 2f; val y = cy - ih / 2f
            val a = (tint ushr 24) / 255f
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(((if (light) 70 else 150) * a).toInt(), 0, 0, 0) }
            c.drawBitmap(shadow!!, x + off[0], y + off[1] + 3.2f * unit, sp)
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, y, 0f, y + ih, tint, (tint and 0x00FFFFFF) or (((tint ushr 24) * 0.42f).toInt() shl 24), Shader.TileMode.CLAMP)
            }
            c.drawBitmap(mask!!, x, y, body)
            if (!light) {
                // The highlight: white along the top edge only, through the glyph's own shape.
                val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, y, 0f, y + ih * 0.4f, (0x66FFFFFF.toInt() and 0x00FFFFFF) or (((0x66 * a).toInt()) shl 24), 0, Shader.TileMode.CLAMP)
                }
                c.drawBitmap(mask!!, x, y, hi)
            }
        }
    }
}
