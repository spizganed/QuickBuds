package com.spizganed.quickbuds.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable

/**
 * The Nothing style's dot matrix for live views (sliders, the EQ curve, switches): a drawing is rendered
 * at one pixel per cell, then every cell it covers becomes one round dot in that pixel's colour. The
 * widget's `matrix()` does the same with 8x supersampling for still bitmaps; one sample per cell is
 * cheap enough to redraw every frame of a drag. Coverage below [MIN_ALPHA] leaves the cell empty, the
 * rest keep their alpha, so a thin grid line or a fading fill reads as fainter dots (icons: `solid`).
 */
object DotArt {

    /** Dot pitch in dp: about the home rings' (90dp / 42 cells). */
    const val PITCH_DP = 2.2f
    /** Icons (row icons, chevrons, header buttons): finer, about 20 dots across a 24dp icon. */
    const val ICON_PITCH_DP = 1.2f
    private const val MIN_ALPHA = 50
    /** [draw] with `solid` (icons): a cell at least this covered gets a fully opaque dot; kept alpha read as grey. */
    private const val SOLID_MIN = 90

    private var small: Bitmap? = null
    private var px = IntArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Draws [draw] (in the view's own px) into [target] as dots over a [w] x [h] px area. Main thread only. */
    fun draw(context: Context, target: Canvas, w: Int, h: Int, pitchDp: Float = PITCH_DP, solid: Boolean = false, draw: (Canvas) -> Unit) {
        val pitch = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, pitchDp, context.resources.displayMetrics
        ).coerceAtLeast(2f)
        val cols = (w / pitch).toInt().coerceAtLeast(1)
        val rows = (h / pitch).toInt().coerceAtLeast(1)
        val bmp = small?.takeIf { it.width == cols && it.height == rows }
            ?: Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888).also { small = it; px = IntArray(cols * rows) }
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        c.scale(1f / pitch, 1f / pitch)
        draw(c)
        bmp.getPixels(px, 0, cols, 0, 0, cols, rows)
        for (y in 0 until rows) for (x in 0 until cols) {
            val v = px[y * cols + x]
            if (v ushr 24 < if (solid) SOLID_MIN else MIN_ALPHA) continue
            paint.color = if (solid) v or 0xFF000000.toInt() else v
            target.drawCircle((x + 0.5f) * pitch, (y + 0.5f) * pitch, pitch * 0.42f, paint)
        }
    }

    /**
     * An icon as dots ([ThemeRes.tint] in the Nothing style): [inner] rendered once per size and tint at
     * [ICON_PITCH_DP], then reused, so a list of rows costs one render per icon.
     */
    class Icon(private val context: Context, private val inner: Drawable) : Drawable() {
        private var cache: Bitmap? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val bmp = cache?.takeIf { it.width == b.width() && it.height == b.height() }
                ?: Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888).also { out ->
                    DotArt.draw(context, Canvas(out), b.width(), b.height(), ICON_PITCH_DP, solid = true) { c ->
                        inner.setBounds(0, 0, b.width(), b.height()); inner.draw(c)
                    }
                    cache = out
                }
            canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
        }

        override fun setTintList(tint: android.content.res.ColorStateList?) { inner.setTintList(tint); cache = null; invalidateSelf() }
        override fun getIntrinsicWidth() = inner.intrinsicWidth
        override fun getIntrinsicHeight() = inner.intrinsicHeight
        override fun setAlpha(alpha: Int) { inner.alpha = alpha; cache = null; invalidateSelf() }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { inner.colorFilter = cf; cache = null; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * A switch part as dots: [shape] draws it in its bounds with the paint it is given, in [color]
     * (a state list: checked / not). The Switch moves the thumb by changing its bounds.
     */
    class Part(
        private val context: Context, private val wDp: Float, private val hDp: Float,
        private val color: android.content.res.ColorStateList,
        private val shape: (Canvas, android.graphics.RectF, Paint) -> Unit
    ) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = android.graphics.RectF()
        private var current = color.defaultColor

        override fun draw(canvas: Canvas) {
            val b = bounds
            p.color = current
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            DotArt.draw(context, canvas, b.width(), b.height()) { c -> box.set(0f, 0f, b.width().toFloat(), b.height().toFloat()); shape(c, box, p) }
            canvas.restore()
        }

        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val c = color.getColorForState(state, color.defaultColor)
            if (c == current) return false
            current = c
            return true
        }
        override fun getIntrinsicWidth() = ThemeRes.dp(context, wDp)
        override fun getIntrinsicHeight() = ThemeRes.dp(context, hDp)
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
