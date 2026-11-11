package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.widget.QuickBudsWidget
import kotlin.math.min

/**
 * The home screen's battery tile: left bud, case and right bud, each in a
 * 90dp battery ring, with the percentage and a wear label under it. Drawn in one view, in the
 * same language as [EqCurveView] / [LevelSliderView].
 *
 * Wear, from the buds' status codes (same meaning the widget uses):
 *   3 / 7 = in ear  -> glyph accent,                              label "In ear" in `text`, semibold
 *   4 / 0 = in case -> glyph card blended 28% toward accent,       label "In case" (no badge)
 *   other known     -> glyph card blended 55% toward accent,       label "Out of ear"
 *
 * The case glyph is always visible and takes the accent, like a bud in the ear.
 *
 * Disconnected ([connected] false) keeps exactly the same size: track-only rings, glyphs in the
 * disabled colour, "—" and the bare names. Rings animate to a new level.
 */
class BudsStatusView(context: Context) : View(context) {

    private class Slot(val icon: Drawable, val iconRatio: Float, val boxW: Float, val boxH: Float, val glassW: Float, val glassH: Float) {
        var level = -1
        var shown = 0f          // animated ring fraction 0..1
        var status = -1
        var anim: ValueAnimator? = null
        var dots: android.graphics.Bitmap? = null   // Nothing: the dot-matrix ring, redrawn when [dotsKey] changes
        var dotsKey = ""
        val glyph = GlassArt.Glyph(icon)   // Glass: the glyph with depth
    }

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()

    private val p = ThemeRes.palette(context)

    /** Nothing style: dot-matrix rings (the widget's, [QuickBudsWidget.dotRing]), Doto numbers without `%`. */
    private val nothing = ThemeRes.nothing(context)

    /** False draws the disconnected state. */
    var connected = true
        set(v) {
            if (field == v) return
            field = v
            connAnim?.cancel()
            val to = if (v) 1f else 0f
            val scale = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            // Before the first draw nobody saw the old state: no transition.
            if (!drawn || scale == 0f) { conn = to; invalidate(); return }
            connAnim = ValueAnimator.ofFloat(conn, to).apply {
                duration = 175
                addUpdateListener { conn = it.animatedValue as Float; postInvalidateOnAnimation() }
                start()
            }
        }

    /** 1 connected, 0 disconnected; the glyph colours blend between the two so a disconnect fades like a connect. */
    private var conn = 1f
    private var connAnim: ValueAnimator? = null
    private var drawn = false

    // Glyphs keep their SVG's true ratio (buds 176x272, case 496x400), fitted inside the
    // boxes (bud 42x56, case 58x42). The right bud is its own mirrored drawable.
    private val slots = listOf(
        Slot(context.getDrawable(R.drawable.ic_bud_left)!!.mutate(), 176f / 272f, 42f, 56f, 31f, 42f),
        Slot(context.getDrawable(R.drawable.ic_case)!!.mutate(), 496f / 400f, 58f, 42f, 52f, 42f),
        Slot(context.getDrawable(R.drawable.ic_bud_right)!!.mutate(), 176f / 272f, 42f, 56f, 31f, 42f)
    )
    private val names = listOf(
        context.getString(R.string.status_left),
        context.getString(R.string.status_case),
        context.getString(R.string.status_right)
    )

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Material: the M3 progress track (secondary container).
        style = Paint.Style.STROKE; strokeWidth = dp(if (p.glass && !nothing) 4f else 6f); color = if (ThemeRes.material(context)) p.tonal else p.outline
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(if (p.glass && !nothing) 4f else 6f); strokeCap = Paint.Cap.ROUND; color = p.accent
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(21f); textAlign = Paint.Align.CENTER
        typeface = if (nothing) ThemeRes.headline(context) else ThemeRes.medium(context)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f); textAlign = Paint.Align.CENTER; color = p.textSecondary
    }
    private val arcBox = RectF()

    /** Glass: a lit glass disc in the ring, a soft light along the arc and a gradient arc, as the design's rings. */
    private val glass = p.glass && !nothing
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(13f); strokeCap = Paint.Cap.ROUND }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val sweepMatrix = android.graphics.Matrix()
    private val semibold = ThemeRes.medium(context)

    /** 90dp, shrunk only if three columns cannot fit on a very narrow screen. */
    private val ringSize get() = min(dp(90f), width / 3f - dp(8f))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Nothing style: no label line (the glyph's shade shows wear, as on the Nothing widget).
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (nothing) 131f else 152f).toInt())
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
                    duration = if (target == 0f) 500 else 350
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { s.shown = it.animatedValue as Float; postInvalidateOnAnimation() }
                    start()
                }
            }
        }
        // The wear state is read out too: the Nothing style does not draw it.
        noWearLabel = !ModelCatalog.wearSensorSeen(context)
        val wear = { st: Int -> if (connected) wearLabel(st)?.let { " $it" } ?: "" else "" }
        contentDescription = "${names[0]} ${pctText(left)}${wear(leftStatus)}, ${names[1]} ${pctText(case)}, ${names[2]} ${pctText(right)}${wear(rightStatus)}"
        invalidate()
    }

    /** The number follows the ring while it sweeps (80, 70, ... 0), then a dash; on connect the reverse. */
    private fun pctFor(s: Slot): String {
        val shown = Math.round(s.shown * 100)
        if (s.level < 0 && shown <= 0) return "—"
        return if (nothing) "$shown" else "$shown%"
    }

    private fun pctText(level: Int) = if (level < 0 || !connected) "—" else if (nothing) "$level" else "$level%"

    private fun withA(color: Int, a: Float) = (color and 0x00FFFFFF) or ((((color ushr 24) and 0xFF) * (0.35f + 0.65f * a)).toInt() shl 24)

    /** Glass glyphs are white: bright in the ear and for the case, softer out of the ear, softest in the case. */
    private fun glassTint(isCase: Boolean, status: Int): Int = Palette.withAlpha(p.ink, when {
        isCase || status == 3 || status == 7 -> 0.95f
        status == 4 || status == 0 -> 0.45f
        else -> 0.7f
    })

    /** Before the model's first "in ear" the "in ear" shown may be a stand-in: no label. */
    private var noWearLabel = false

    private fun wearLabel(status: Int) = if (noWearLabel && status == 3) null else when (status) {
        3, 7 -> context.getString(R.string.status_in_ear)
        4, 0 -> context.getString(R.string.status_in_case)
        -1 -> null
        else -> context.getString(R.string.status_out)
    }

    override fun onDraw(canvas: Canvas) {
        drawn = true
        val ring = ringSize
        // Top padding keeps the outer glow of the arc inside the view.
        val top = dp(4f)
        val cy = top + ring / 2
        val colW = width / 3f
        val scale = ring / dp(104f)
        // Until a bud reports its wear the buds draw dim, so the case does too (it would be the one bright glyph).
        val budsKnown = slots[0].status != -1 || slots[2].status != -1
        slots.forEachIndexed { i, s ->
            val cx = colW * i + colW / 2
            val isCase = i == 1 && budsKnown
            if (nothing) {
                // One bitmap per animation step (whole percents), so an animation redraws at most 100 of them.
                val level = if (s.shown > 0f || s.level >= 0) Math.round(s.shown * 100) else -1
                val tint = androidx.core.graphics.ColorUtils.blendARGB(p.disabled, QuickBudsWidget.nothingTint(p, isCase, s.status), conn)
                val key = "$level/$tint/${ring.toInt()}"
                if (s.dotsKey != key) {
                    s.dots = QuickBudsWidget.dotRing(context, p, level, i, tint, ring.toInt().coerceAtLeast(1))
                    s.dotsKey = key
                }
                canvas.drawBitmap(s.dots!!, cx - ring / 2, top, null)
            } else {
                val r = ring / 2 - trackPaint.strokeWidth / 2
                arcBox.set(cx - r, cy - r, cx + r, cy + r)
                if (glass) {
                    GlassArt.ring(canvas, cx, cy, ring, s.shown, p.accent, p.ink, p.glassLight, conn)
                } else {
                    canvas.drawOval(arcBox, trackPaint)
                    if (s.shown > 0f) canvas.drawArc(arcBox, -90f, 360f * s.shown, false, arcPaint)
                }

                // Glyph: fit the glyph box, keeping the SVG's own ratio. Glass: the design's smaller glyph (bud 31x42 in a 90 ring).
                val gscale = if (glass) ring / dp(90f) else scale
                val bw = dp(if (glass) s.glassW else s.boxW) * gscale
                val bh = dp(if (glass) s.glassH else s.boxH) * gscale
                val (iw, ih) = if (s.iconRatio < bw / bh) bh * s.iconRatio to bh else bw to bw / s.iconRatio
                val tint = androidx.core.graphics.ColorUtils.blendARGB(p.disabled, if (glass) glassTint(isCase, s.status) else wearTint(p, isCase, s.status, ThemeRes.material(context)), conn)
                if (glass) {
                    s.glyph.draw(canvas, cx, cy, iw, ih, tint, ring / 90f, p.glassLight)
                } else {
                    s.icon.setTint(tint)
                    s.icon.setBounds((cx - iw / 2).toInt(), (cy - ih / 2).toInt(), (cx + iw / 2).toInt(), (cy + ih / 2).toInt())
                    s.icon.draw(canvas)
                }
            }

            pctPaint.color = p.text
            canvas.drawText(pctFor(s), cx, top + ring + dp(30f), pctPaint)

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
            canvas.drawText(label, cx, top + ring + dp(51f), labelPaint)
        }
    }

    companion object {
        /**
         * Glyph colour by wear: accent in the ear (and the case), darker out of the ear, darkest in the case. Shared with the widgets.
         */
        fun wearTint(p: Palette, isCase: Boolean, status: Int, material: Boolean = false): Int = when {
            // The accent for a bud in the ear and for the case; the bud out of the ear and in the case take it darker, in two steps.
            isCase || status == 3 || status == 7 -> p.accent
            status == 4 || status == 0 -> androidx.core.graphics.ColorUtils.blendARGB(p.card, p.accent, 0.28f)
            else -> androidx.core.graphics.ColorUtils.blendARGB(p.card, p.accent, 0.55f)
        }
    }
}
