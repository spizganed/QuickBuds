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
 * cheap enough to redraw every frame of a drag. Views draw without antialiasing, so a cell is covered or not and
 * keeps its paint's alpha: a faint track or a fading fill reads as fainter dots, below [MIN_ALPHA] none (icons: `solid`).
 */
object DotArt {

    /** Dot pitch in dp: about the home rings' (90dp / 42 cells). */
    const val PITCH_DP = 2.2f
    /** Icons (row icons, chevrons, header buttons): finer, about 20 dots across a 24dp icon. */
    const val ICON_PITCH_DP = 1.2f
    /** The check ([USER] 2026-09-28): fewer, bigger dots; at the icon pitch its diagonal looked ragged. */
    const val CHECK_PITCH_DP = 2f
    private const val MIN_ALPHA = 50
    /** [draw] with `solid` (icons): a cell at least this covered gets a fully opaque dot; kept alpha read as grey. */
    private const val SOLID_MIN = 90
    /** [draw] with a `color` (switch parts): a cell at least half covered, so a small disc reads round, not square. */
    private const val HALF = 128

    private var small: Bitmap? = null
    private var px = IntArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val noAa = android.graphics.PaintFlagsDrawFilter(Paint.ANTI_ALIAS_FLAG, 0)

    /** The pitch in whole px: a fractional pitch put every dot at a different sub-pixel offset, so no two looked alike. */
    fun pitchPx(context: Context, pitchDp: Float = PITCH_DP): Float = Math.round(
        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, pitchDp, context.resources.displayMetrics)
    ).coerceAtLeast(2).toFloat()

    /**
     * Draws [draw] (in the view's own px) into [target] as dots over a [w] x [h] px area. Main thread only.
     * With [color], [draw] only gives the coverage and every lit dot is [color].
     */
    fun draw(context: Context, target: Canvas, w: Int, h: Int, pitchDp: Float = PITCH_DP, solid: Boolean = false, color: Int? = null, draw: (Canvas) -> Unit) {
        val pitch = pitchPx(context, pitchDp)
        val cols = (w / pitch).toInt().coerceAtLeast(1)
        val rows = (h / pitch).toInt().coerceAtLeast(1)
        val bmp = small?.takeIf { it.width == cols && it.height == rows }
            ?: Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888).also { small = it; px = IntArray(cols * rows) }
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        c.scale(1f / pitch, 1f / pitch)
        // Views (sliders, curves): no antialiasing, so a cell is either covered or empty and every dot keeps the
        // paint's own colour. Antialiased edges gave the knob's ring dots several shades ([USER] 2026-09-30).
        if (!solid && color == null) c.drawFilter = noAa
        draw(c)
        bmp.getPixels(px, 0, cols, 0, 0, cols, rows)
        for (y in 0 until rows) for (x in 0 until cols) {
            val v = px[y * cols + x]
            if (v ushr 24 < if (color != null) HALF else if (solid) SOLID_MIN else MIN_ALPHA) continue
            paint.color = color ?: if (solid) v or 0xFF000000.toInt() else v
            target.drawCircle((x + 0.5f) * pitch, (y + 0.5f) * pitch, pitch * 0.42f, paint)
        }
    }

    /**
     * An icon as dots ([ThemeRes.tint] in the Nothing style): [inner] rendered once per size and tint at
     * [ICON_PITCH_DP] (scaled up past 24dp tall), then reused, so a list of rows costs one render per icon.
     */
    class Icon(private val context: Context, private val inner: Drawable, private val pitchDp: Float = ICON_PITCH_DP) : Drawable() {
        private var cache: Bitmap? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val bmp = cache?.takeIf { it.width == b.width() && it.height == b.height() }
                ?: Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888).also { out ->
                    // Same dot count at any size: past 24dp the dots grow (a 68dp bud at 1.2dp read as a fine grid).
                    val scale = (b.height() / context.resources.displayMetrics.density / 24f).coerceAtLeast(1f)
                    DotArt.draw(context, Canvas(out), b.width(), b.height(), pitchDp * scale, solid = true) { c ->
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
     * (a state list: checked / not). The Switch moves the thumb by changing its bounds, so every part
     * uses the host's grid (cells from its 0,0) and shrinks its box to the whole cells inside its bounds:
     * track and thumb dots line up, and a shape centred in its box is symmetric on the grid.
     */
    class Part(
        private val context: Context, private val wDp: Float, private val hDp: Float,
        private val color: android.content.res.ColorStateList,
        private val shape: (Canvas, android.graphics.RectF, Paint) -> Unit
    ) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val box = android.graphics.RectF()
        private var current = color.defaultColor

        override fun draw(canvas: Canvas) {
            val b = bounds
            val pitch = pitchPx(context)
            val l = Math.ceil(b.left / pitch.toDouble()).toFloat() * pitch
            val t = Math.ceil(b.top / pitch.toDouble()).toFloat() * pitch
            val w = Math.floor(b.right / pitch.toDouble()).toFloat() * pitch - l
            val h = Math.floor(b.bottom / pitch.toDouble()).toFloat() * pitch - t
            if (w <= 0f || h <= 0f) return
            box.set(0f, 0f, w, h)
            canvas.save()
            canvas.translate(l, t)
            DotArt.draw(context, canvas, w.toInt(), h.toInt(), solid = true, color = current) { c -> shape(c, box, p) }
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
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
