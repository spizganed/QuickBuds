package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.widget.QuickBudsWidget
import kotlin.math.min

/**
 * The home screen's battery tile: left bud, case and right bud, each in a
 * 104dp battery ring, with the percentage and a wear label under it. Drawn in one view, in the
 * same language as [EqCurveView] / [LevelSliderView].
 *
 * Wear, from the buds' status codes (same meaning the widget uses):
 *   3 / 7 = in ear  -> glyph `text`,                   label "In ear" in `text`, semibold
 *   4 / 0 = in case -> glyph `textSecondary` at 45%,   label "In case" (no badge)
 *   other known     -> glyph `textSecondary`,          label "Out of ear"
 *
 * Disconnected ([connected] false) keeps exactly the same size: track-only rings, glyphs in the
 * disabled colour, "—" and the bare names. Rings animate to a new level.
 */
class BudsStatusView(context: Context) : View(context) {

    private class Slot(val icon: Drawable, val iconRatio: Float, val boxW: Float, val boxH: Float) {
        var level = -1
        var shown = 0f          // animated ring fraction 0..1
        var status = -1
        var anim: ValueAnimator? = null
        var dots: android.graphics.Bitmap? = null   // Nothing: the dot-matrix ring, redrawn when [dotsKey] changes
        var dotsKey = ""
    }

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()

    private val p = ThemeRes.palette(context)

    /** Nothing style: dot-matrix rings (the widget's, [QuickBudsWidget.dotRing]), Doto numbers without `%`. */
    private val nothing = ThemeRes.nothing(context)

    /** False draws the disconnected state (SPEC 3.2). */
    var connected = true
        set(v) { if (field != v) { field = v; invalidate() } }

    // Glyphs keep their SVG's true ratio (buds 176x272, case 496x400), fitted inside the SPEC's
    // boxes (bud 42x56, case 58x42). The right bud is its own mirrored drawable.
    private val slots = listOf(
        Slot(context.getDrawable(R.drawable.ic_bud_left)!!.mutate(), 176f / 272f, 42f, 56f),
        Slot(context.getDrawable(R.drawable.ic_case)!!.mutate(), 496f / 400f, 58f, 42f),
        Slot(context.getDrawable(R.drawable.ic_bud_right)!!.mutate(), 176f / 272f, 42f, 56f)
    )
    private val names = listOf(
        context.getString(R.string.status_left),
        context.getString(R.string.status_case),
        context.getString(R.string.status_right)
    )

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(6f); color = p.outline
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(6f); strokeCap = Paint.Cap.ROUND; color = p.accent
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(21f); textAlign = Paint.Align.CENTER
        typeface = if (nothing) ThemeRes.headline(context) else ThemeRes.medium(context)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f); textAlign = Paint.Align.CENTER; color = p.textSecondary
    }
    private val arcBox = RectF()
    private val semibold = ThemeRes.medium(context)

    /** 90dp (SPEC 104, made ~13% shorter), shrunk only if three columns cannot fit on a very narrow screen. */
    private val ringSize get() = min(dp(90f), width / 3f - dp(8f))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Nothing style: no label line (the glyph's shade shows wear, as on the Nothing widget).
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (nothing) 127f else 148f).toInt())
    }

    /** Feed the current store values; rings animate only when a level actually changes. */
    fun setState(left: Int, case: Int, right: Int, leftStatus: Int, rightStatus: Int) {
        val levels = intArrayOf(left, case, right)
        slots[0].status = leftStatus
        slots[2].status = rightStatus
        slots.forEachIndexed { i, s ->
            val target = if (levels[i] < 0) 0f else levels[i] / 100f
            if (s.level != levels[i]) {
                s.level = levels[i]
                s.anim?.cancel()
                s.anim = ValueAnimator.ofFloat(s.shown, target).apply {
                    duration = 450
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { s.shown = it.animatedValue as Float; postInvalidateOnAnimation() }
                    start()
                }
            }
        }
        // The wear state is read out too: the Nothing style does not draw it.
        val wear = { st: Int -> if (connected) wearLabel(st)?.let { " $it" } ?: "" else "" }
        contentDescription = "${names[0]} ${pctText(left)}${wear(leftStatus)}, ${names[1]} ${pctText(case)}, ${names[2]} ${pctText(right)}${wear(rightStatus)}"
        invalidate()
    }

    private fun pctText(level: Int) = if (level < 0 || !connected) "—" else if (nothing) "$level" else "$level%"

    private fun wearLabel(status: Int) = when (status) {
        3, 7 -> context.getString(R.string.status_in_ear)
        4, 0 -> context.getString(R.string.status_in_case)
        -1 -> null
        else -> context.getString(R.string.status_out)
    }

    override fun onDraw(canvas: Canvas) {
        val ring = ringSize
        val cy = ring / 2
        val colW = width / 3f
        val scale = ring / dp(104f)
        slots.forEachIndexed { i, s ->
            val cx = colW * i + colW / 2
            if (nothing) {
                // One bitmap per animation step (whole percents), so an animation redraws at most 100 of them.
                val level = if (connected && s.level >= 0) Math.round(s.shown * 100) else -1
                val tint = if (connected) QuickBudsWidget.nothingTint(p, i == 1, s.status) else p.disabled
                val key = "$level/$tint/${ring.toInt()}"
                if (s.dotsKey != key) {
                    s.dots = QuickBudsWidget.dotRing(context, p, level, i, tint, ring.toInt().coerceAtLeast(1))
                    s.dotsKey = key
                }
                canvas.drawBitmap(s.dots!!, cx - ring / 2, 0f, null)
            } else {
                val r = ring / 2 - trackPaint.strokeWidth / 2
                arcBox.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawOval(arcBox, trackPaint)
                if (connected && s.shown > 0f) canvas.drawArc(arcBox, -90f, 360f * s.shown, false, arcPaint)

                // Glyph: fit the SPEC box, keeping the SVG's own ratio.
                val bw = dp(s.boxW) * scale
                val bh = dp(s.boxH) * scale
                val (iw, ih) = if (s.iconRatio < bw / bh) bh * s.iconRatio to bh else bw to bw / s.iconRatio
                s.icon.setTint(if (connected) wearTint(p, i == 1, s.status) else p.disabled)
                s.icon.setBounds((cx - iw / 2).toInt(), (cy - ih / 2).toInt(), (cx + iw / 2).toInt(), (cy + ih / 2).toInt())
                s.icon.draw(canvas)
            }

            pctPaint.color = p.text
            canvas.drawText(pctText(s.level), cx, ring + dp(30f), pctPaint)

            if (nothing) return@forEachIndexed
            val wear = if (i == 1 || !connected) null else wearLabel(s.status)
            val label = wear ?: names[i]
            val inEar = wear != null && (s.status == 3 || s.status == 7)
            labelPaint.color = if (inEar) p.text else p.textSecondary
            labelPaint.typeface = if (inEar) semibold else ThemeRes.regular(context)
            // "Out of ear" can be wider than a narrow column: shrink to fit, never clip.
            labelPaint.textSize = dp(13f)
            val room = colW - dp(6f)
            val w = labelPaint.measureText(label)
            if (w > room) labelPaint.textSize = dp(13f) * room / w
            canvas.drawText(label, cx, ring + dp(51f), labelPaint)
        }
    }

    companion object {
        /** Glyph colour by wear (WIDGETS.md 1); the case is always `text`. Shared with the widgets. */
        fun wearTint(p: Palette, isCase: Boolean, status: Int): Int = when {
            isCase || status == 3 || status == 7 -> p.text
            status == 4 || status == 0 -> Palette.withAlpha(p.textSecondary, 0.45f)
            else -> p.textSecondary
        }
    }
}
