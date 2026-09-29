package com.spizganed.quickbuds.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.spizganed.quickbuds.R

/**
 * A mini home screen drawn in ANY palette, not the active one (design/SPEC.md 3.7 and 3.8).
 *
 * [detailed] false: the 150dp preset tile — a card with three rings, a segment pill, a row with
 * two text lines and a dot. [active] adds the 2dp accent stroke.
 * [detailed] true: the Edit preset preview — battery card with rings, percentages and labels, and
 * one toggle row.
 */
class PalettePreviewView(context: Context, private val detailed: Boolean) : View(context) {

    var palette: Palette = ThemeRes.palette(context)
        set(v) { field = v; invalidate() }

    var active = false
        set(v) { field = v; invalidate() }

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val medium = ThemeRes.medium(context)
    private val box = RectF()
    private val rowIcon = context.getDrawable(R.drawable.ic_hires)!!.mutate()
    private val nothing = ThemeRes.nothing(context)
    private val pitch = DotArt.pitchPx(context)

    /** Dot style: every stroke a hairline, one cell wide (a thin stroke skips cells without antialiasing). */
    private fun line(w: Float) = if (nothing) 0f else w

    /** Dot style: the size in whole cells, so the outer border's right and bottom edges are not cut off. */
    private val w get() = if (nothing) (width / pitch).toInt() * pitch else width.toFloat()
    private val h get() = if (nothing) (height / pitch).toInt() * pitch else height.toFloat()

    /** Dot style: a fixed disc of [n] cells on the cell under ([cx], [cy]); a hollow ring when [fill] is null. */
    private fun disc(c: Canvas, cx: Float, cy: Float, n: Int, ring: Int, fill: Int? = ring) =
        DotArt.disc(context, c, (cx / pitch).toInt() - n / 2, (cy / pitch).toInt() - n / 2, n, fill ?: 0, ring)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (detailed) 200f else 150f).toInt())
    }

    /** Filled rounded rect with an optional stroke, inset by half the stroke so it is not clipped. */
    private fun rrect(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int, strokeColor: Int?, strokeW: Float = dp(1f)) {
        if (nothing) {
            // Dot style: the outline is the shape filled in its colour with the fill one cell inside; a stroked
            // outline landed a cell off the fill at a pill's ends and left a lone dot there. A radius of half the
            // height does the same, so it stays a little under.
            box.set(l, t, r, b)
            val rad = minOf(radius, box.height() / 2 * 0.85f)
            val e = if (strokeColor != null) pitch else 0f
            if (strokeColor != null) { fill.color = strokeColor; c.drawRoundRect(box, rad, rad, fill) }
            box.inset(e, e)
            fill.color = color
            c.drawRoundRect(box, rad - e, rad - e, fill)
            return
        }
        val h = if (strokeColor != null) strokeW / 2 else 0f
        box.set(l + h, t + h, r - h, b - h)
        fill.color = color
        c.drawRoundRect(box, radius, radius, fill)
        if (strokeColor != null) {
            stroke.color = strokeColor
            stroke.strokeWidth = strokeW
            c.drawRoundRect(box, radius, radius, stroke)
        }
    }

    private fun ring(c: Canvas, cx: Float, cy: Float, r: Float, w: Float, fraction: Float) {
        stroke.strokeWidth = line(w)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = palette.outline
        c.drawCircle(cx, cy, r, stroke)
        stroke.color = palette.accent
        box.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(box, -90f, 360f * fraction, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    /** Dot style: the shapes as dots; the preview's text and icon on top, as the app draws them. */
    override fun onDraw(c: Canvas) {
        if (!nothing) { if (detailed) drawDetailed(c) else drawTile(c); return }
        DotArt.draw(context, c, width, height) { if (detailed) drawDetailed(it, labels = false) else drawTile(it) }
        if (detailed) drawDetailed(c, shapes = false)
    }

    private fun drawTile(c: Canvas) {
        val p = palette
        rrect(c, 0f, 0f, w, h, dp(18f), p.background, if (active) p.accent else p.outline, if (active) dp(2f) else dp(1f))

        val pad = dp(10f)
        val l = pad
        val r = w - pad
        // Card with three rings.
        var t = pad
        rrect(c, l, t, r, t + dp(46f), dp(10f), p.card, p.outline)
        val ringR = ((r - l) / 3 / 2 - dp(6f)).coerceAtMost(dp(10f))
        for (i in 0..2) {
            val cx = l + (r - l) * (i * 2 + 1) / 6
            if (nothing) disc(c, cx, t + dp(23f), RING_CELLS, p.accent, null) else ring(c, cx, t + dp(23f), ringR, dp(3f), 1f)
        }
        // Segment pill with the accent segment at the start.
        t += dp(54f)
        rrect(c, l, t, r, t + dp(22f), dp(11f), p.card, p.outline)
        rrect(c, l + dp(3f), t + dp(3f), l + (r - l) * 0.3f, t + dp(19f), dp(8f), p.accent, null)
        // Row: two text lines and a dot.
        t += dp(30f)
        rrect(c, l, t, r, h - pad, dp(10f), p.card, p.outline)
        val mid = (t + h - pad) / 2
        rrect(c, l + dp(8f), mid - dp(6f), l + (r - l) * 0.55f, mid - dp(2f), dp(2f), p.text, null)
        rrect(c, l + dp(8f), mid + dp(2f), l + (r - l) * 0.38f, mid + dp(5f), dp(2f), p.textSecondary, null)
        if (nothing) disc(c, r - dp(12f), mid, 5, p.accent) else {
            fill.color = p.accent
            c.drawCircle(r - dp(12f), mid, dp(5f), fill)
        }
    }

    private fun drawDetailed(c: Canvas, shapes: Boolean = true, labels: Boolean = true) {
        val p = palette
        if (shapes) rrect(c, 0f, 0f, w, h, dp(24f), p.background, p.outline)

        val pad = dp(14f)
        val l = pad
        val r = w - pad
        // Battery card.
        var t = pad
        val cardB = t + dp(100f)
        if (shapes) rrect(c, l, t, r, cardB, dp(18f), p.card, p.outline)
        val names = listOf(R.string.status_left, R.string.status_case, R.string.status_right).map { context.getString(it) }
        val levels = listOf(100, 80, 100)
        for (i in 0..2) {
            val cx = l + (r - l) * (i * 2 + 1) / 6
            if (shapes) ring(c, cx, t + dp(30f), dp(18f), dp(4f), levels[i] / 100f)
            if (!labels) continue
            text.color = p.text; text.textSize = dp(13f); text.typeface = medium
            // Dot style: numbers without %, as on the home screen.
            c.drawText(if (nothing) "${levels[i]}" else "${levels[i]}%", cx, t + dp(70f), text)
            text.color = p.textSecondary; text.textSize = dp(10.5f); text.typeface = ThemeRes.regular(context)
            c.drawText(names[i], cx, t + dp(86f), text)
        }
        // Toggle row.
        t = cardB + dp(10f)
        val mid = (t + h - pad) / 2
        if (shapes) {
            rrect(c, l, t, r, h - pad, dp(18f), p.card, p.outline)
            // Switch, on: derived track + accent thumb.
            rrect(c, r - dp(52f), mid - dp(7f), r - dp(18f), mid + dp(7f), dp(7f), p.track, null)
            if (nothing) disc(c, r - dp(26f), mid, 9, p.accent) else {
                fill.color = p.accent
                c.drawCircle(r - dp(26f), mid, dp(10f), fill)
            }
        }
        if (!labels) return
        val iconS = dp(20f)
        val icon = if (nothing) ThemeRes.tint(context, R.drawable.ic_hires, p.accent) else rowIcon.apply { setTint(p.accent) }
        icon.setBounds((l + dp(14f)).toInt(), (mid - iconS / 2).toInt(), (l + dp(14f) + iconS).toInt(), (mid + iconS / 2).toInt())
        icon.draw(c)
        text.textAlign = Paint.Align.LEFT
        text.color = p.text; text.textSize = dp(13.5f); text.typeface = medium
        c.drawText(context.getString(R.string.row_hires_title), l + dp(46f), mid - dp(3f), text)
        text.color = p.textSecondary; text.textSize = dp(11f); text.typeface = ThemeRes.regular(context)
        c.drawText(context.getString(R.string.row_hires_sub), l + dp(46f), mid + dp(13f), text)
        text.textAlign = Paint.Align.CENTER
    }

    private companion object { const val RING_CELLS = 9 }
}
