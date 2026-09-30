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

    /** The one knob every dot-style slider and the EQ curve use ([USER] 2026-09-30): a fixed round ring of dots. */
    private val KNOB = listOf("..###..", ".#...#.", "#.....#", "#.....#", "#.....#", ".#...#.", "..###..")
    private val knobPaint = Paint()
    private val clear = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }

    /**
     * Draws [KNOB] inside a [draw] block, centred on the cell under ([cx], [cy]) in view px: the same dots wherever it
     * sits (a scaled circle landed differently on the grid at every position, some came out square). The inside is
     * [fill], or cleared so the knob is hollow.
     */
    fun knob(context: Context, c: Canvas, cx: Float, cy: Float, ring: Int, fill: Int? = null) {
        val pitch = pitchPx(context)
        val x0 = Math.floor(cx / pitch.toDouble()).toInt() - 3
        val y0 = Math.floor(cy / pitch.toDouble()).toInt() - 3
        for (y in KNOB.indices) {
            val row = KNOB[y]
            val first = row.indexOf('#'); val last = row.lastIndexOf('#')
            for (x in row.indices) {
                val p = when {
                    row[x] == '#' -> knobPaint.apply { color = ring }
                    x in first..last && y in 1..5 -> if (fill != null) knobPaint.apply { color = fill } else clear
                    else -> continue
                }
                c.drawRect((x0 + x) * pitch, (y0 + y) * pitch, (x0 + x + 1) * pitch, (y0 + y + 1) * pitch, p)
            }
        }
    }

    /**
     * A fixed disc of [n] x [n] cells with its top-left cell at ([x0], [y0]), inside a [draw] block: the edge
     * cells in [ring], the rest in [fill]. Swatches use it; scaled circles came out uneven, like the knob.
     */
    fun disc(context: Context, c: Canvas, x0: Int, y0: Int, n: Int, fill: Int, ring: Int) {
        val pitch = pitchPx(context)
        val m = (n - 1) / 2f
        fun inside(x: Int, y: Int) = x in 0 until n && y in 0 until n && (x - m) * (x - m) + (y - m) * (y - m) <= n * n / 4f
        for (y in 0 until n) for (x in 0 until n) {
            if (!inside(x, y)) continue
            knobPaint.color = if (inside(x + 1, y) && inside(x - 1, y) && inside(x, y + 1) && inside(x, y - 1)) fill else ring
            c.drawRect((x0 + x) * pitch, (y0 + y) * pitch, (x0 + x + 1) * pitch, (y0 + y + 1) * pitch, knobPaint)
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

    /**
     * A box (card, button, chip, sheet, dialog) as dots ([ThemeRes.card] in the dot style, [USER] 2026-09-30):
     * the outline one cell of dots, the fill dots a cell inside, both drawn once per size. [solid]: the fill is one
     * smooth shape under the dot outline; [base]: a smooth shape behind the dotted fill, for a sheet or dialog over other content. [topOnly]: square bottom corners.
     */
    class Box(
        private val context: Context, private val fill: Int, private val stroke: Int?, private val radiusDp: Float,
        private val topOnly: Boolean = false, private val solid: Boolean = false, private val base: Int? = null
    ) : Drawable() {
        private var cache: Bitmap? = null
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun path(w: Float, h: Float, inset: Float): android.graphics.Path {
            val r = (ThemeRes.dp(context, radiusDp) - inset).coerceAtLeast(0f)
            val radii = if (topOnly) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else FloatArray(8) { r }
            return android.graphics.Path().apply {
                addRoundRect(android.graphics.RectF(inset, inset, w - inset, h - inset), radii, android.graphics.Path.Direction.CW)
            }
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val pitch = pitchPx(context)
            val w = Math.floor(b.width() / pitch.toDouble()).toInt() * pitch.toInt()
            val h = Math.floor(b.height() / pitch.toDouble()).toInt() * pitch.toInt()
            if (w <= 0 || h <= 0) return
            // Boxes of one size and colour (a list's rows) share one bitmap: a 138-row list drew 138 of them, 1.5 s.
            val key = "$w $h $fill $stroke $radiusDp $topOnly $solid $base $pitch"
            val bmp = cache?.takeIf { it.width == w && it.height == h } ?: shared.get(key)?.also { cache = it }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { out ->
                val c = Canvas(out)
                // [base]: a smooth shape in this colour behind the dots, so nothing below shows through the gaps.
                if (solid || base != null) {
                    c.drawPath(path(w.toFloat(), h.toFloat(), pitch / 2), p.apply { color = base ?: fill; style = Paint.Style.FILL })
                }
                // The solid fill sits under the dots; the outline is dots in both cases (its inside is cleared).
                val edge = stroke ?: fill
                DotArt.draw(context, c, w, h) { d ->
                    d.drawPath(path(w.toFloat(), h.toFloat(), 0f), p.apply { color = edge; style = Paint.Style.FILL })
                    d.drawPath(path(w.toFloat(), h.toFloat(), pitch), if (solid) clear else p.apply { color = fill })
                }
                cache = out
                shared.put(key, out)
            }
            // Whole pixels: a half-pixel offset resampled the dots into faint lines.
            canvas.drawBitmap(bmp, (b.left + (b.width() - w) / 2).toFloat(), (b.top + (b.height() - h) / 2).toFloat(), null)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

        private companion object {
            val shared = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
                override fun sizeOf(key: String, value: Bitmap) = value.byteCount
            }
        }
    }

    /**
     * An icon drawn from a rule instead of sampled from a vector, so every dot is the same and every shape is
     * symmetric ([USER] 2026-09-30: the tap dots were uneven and not round). [lit] says whether cell (x, y) of the
     * [cols] x [rows] grid is a dot; the grid is centred in the bounds at the icon pitch.
     */
    class Pattern(
        private val context: Context, private val cols: Int, private val rows: Int,
        private val pitchDp: Float = ICON_PITCH_DP,
        private val lit: (Int, Int) -> Boolean
    ) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var color = Color.WHITE

        override fun draw(canvas: Canvas) {
            val b = bounds
            val pitch = pitchPx(context, pitchDp)
            val x0 = b.left + Math.round((b.width() - cols * pitch) / 2 / pitch) * pitch
            val y0 = b.top + Math.round((b.height() - rows * pitch) / 2 / pitch) * pitch
            p.color = color
            for (y in 0 until rows) for (x in 0 until cols) {
                if (lit(x, y)) canvas.drawCircle(x0 + (x + 0.5f) * pitch, y0 + (y + 0.5f) * pitch, pitch * 0.42f, p)
            }
        }

        override fun setTintList(tint: android.content.res.ColorStateList?) { color = tint?.defaultColor ?: Color.WHITE; invalidateSelf() }
        override fun setTint(tintColor: Int) { color = tintColor; invalidateSelf() }
        override fun getIntrinsicWidth() = ThemeRes.dp(context, 24f)
        override fun getIntrinsicHeight() = ThemeRes.dp(context, 24f)
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** [n] tap dots side by side: each a round 5x5 disc, one cell apart. */
    fun taps(context: Context, n: Int) = Pattern(context, n * 6 - 1, 5) { x, y ->
        val dx = x % 6 - 2; val dy = y - 2
        x % 6 < 5 && dx * dx + dy * dy <= 6
    }

    /** Press and hold: a dot inside a ring, 15 cells across. */
    fun hold(context: Context) = Pattern(context, 15, 15) { x, y ->
        val d = Math.hypot((x - 7).toDouble(), (y - 7).toDouble())
        d <= 2.3 || (d >= 5.6 && d <= 7.2)
    }

    /** Distance from ([px], [py]) to the segment (ax, ay)-(bx, by); also gives the position along it in [t]. */
    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double, t: DoubleArray? = null): Double {
        val dx = bx - ax; val dy = by - ay
        val u = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        t?.set(0, u)
        return Math.hypot(px - (ax + u * dx), py - (ay + u * dy))
    }

    /** A 15x15 icon of round strokes: a cell is lit when its centre is within [r] cells of a segment (x1, y1, x2, y2). */
    private fun strokes(context: Context, r: Double, vararg segs: DoubleArray) = Pattern(context, 15, 15) { x, y ->
        segs.any { segDist(x + 0.5, y + 0.5, it[0], it[1], it[2], it[3]) <= r }
    }

    fun close(context: Context) = strokes(context, 1.0, doubleArrayOf(2.5, 2.5, 12.5, 12.5), doubleArrayOf(12.5, 2.5, 2.5, 12.5))
    fun check(context: Context) = strokes(context, 1.15, doubleArrayOf(2.5, 8.0, 6.0, 11.5), doubleArrayOf(6.0, 11.5, 12.5, 4.0))

    /** A pencil: one diagonal that narrows to the tip at the bottom left. */
    fun pencil(context: Context) = Pattern(context, 15, 15) { x, y ->
        val t = DoubleArray(1)
        val d = segDist(x + 0.5, y + 0.5, 2.5, 12.5, 12.0, 3.0, t)
        d <= 0.5 + 1.4 * t[0]
    }

    /** A bin: handle, lid and a plain body. */
    fun bin(context: Context) = Pattern(context, 15, 15) { x, y ->
        when (y) {
            1 -> x in 5..9
            2 -> x == 5 || x == 9
            3, 4 -> x in 1..13
            in 6..13 -> x in 2..12
            else -> false
        }
    }

    /** A cog: a ring with a big hole and six square teeth, one at the top. */
    fun cog(context: Context) = Pattern(context, 17, 17) { x, y ->
        val dx = x - 8.0; val dy = y - 8.0
        val d = Math.hypot(dx, dy)
        val a = Math.atan2(dy, dx) + Math.PI / 2
        val off = Math.abs(a - Math.round(a / (Math.PI / 3)) * (Math.PI / 3))
        (d in 3.6..5.9) || (d in 5.5..8.4 && off <= 0.27)
    }

    /** Three lines, one under the other. */
    fun menu(context: Context) = Pattern(context, 16, 16) { x, y -> x in 2..13 && y % 5 in 2..3 && y in 2..13 }

    /** The connection dot: a round 9x9 disc, or its ring. */
    fun statusDot(context: Context, filled: Boolean) = Pattern(context, 9, 9, 1.9f) { x, y ->
        val d = Math.hypot(x - 4.0, y - 4.0)
        d <= 4.4 && (filled || d >= 3.2)
    }
}
