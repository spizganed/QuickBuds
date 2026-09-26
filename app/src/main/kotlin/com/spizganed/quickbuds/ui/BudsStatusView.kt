package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.spizganed.quickbuds.R
import kotlin.math.min

/**
 * The home screen's battery tile (design/SPEC.md 3.1-3.3): left bud, case and right bud, each in a
 * 104dp battery ring, with the percentage and a wear label under it. Drawn in one view, in the
 * same language as [EqCurveView] / [LevelSliderView].
 *
 * Wear, from the buds' status codes (same meaning the widget uses):
 *   3 / 7 = in ear  -> glyph `text`,          label "In ear"
 *   4 / 0 = in case -> glyph `textSecondary`, label "In case", plus the accent case badge
 *   other known     -> glyph `textSecondary`, label "Out of ear"
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
    }

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()

    private val p = ThemeRes.palette(context)

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
    private val badgeIcon = context.getDrawable(R.drawable.ic_case)!!.mutate().apply { setTint(p.onAccent) }
    private val names = listOf(
        context.getString(R.string.status_left),
        context.getString(R.string.status_case),
        context.getString(R.string.status_right)
    )

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); color = p.outline
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); strokeCap = Paint.Cap.ROUND; color = p.accent
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(24f); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f); textAlign = Paint.Align.CENTER; color = p.textSecondary
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcBox = RectF()

    /** 104dp, shrunk only if three columns cannot fit on a very narrow screen. */
    private val ringSize get() = min(dp(104f), width / 3f - dp(8f))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(174f).toInt())
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
        contentDescription = "${names[0]} ${pctText(left)}, ${names[1]} ${pctText(case)}, ${names[2]} ${pctText(right)}"
        invalidate()
    }

    private fun pctText(level: Int) = if (level < 0 || !connected) "—" else "$level%"

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
            val r = ring / 2 - trackPaint.strokeWidth / 2
            arcBox.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawOval(arcBox, trackPaint)
            if (connected && s.shown > 0f) canvas.drawArc(arcBox, -90f, 360f * s.shown, false, arcPaint)

            // Glyph: fit the SPEC box, keeping the SVG's own ratio.
            val bw = dp(s.boxW) * scale
            val bh = dp(s.boxH) * scale
            val (iw, ih) = if (s.iconRatio < bw / bh) bh * s.iconRatio to bh else bw to bw / s.iconRatio
            val inEar = s.status == 3 || s.status == 7
            val inCase = i != 1 && (s.status == 4 || s.status == 0)
            s.icon.setTint(when {
                !connected -> p.disabled
                i == 1 || inEar -> p.text
                else -> p.textSecondary
            })
            s.icon.setBounds((cx - iw / 2).toInt(), (cy - ih / 2).toInt(), (cx + iw / 2).toInt(), (cy + ih / 2).toInt())
            s.icon.draw(canvas)

            // Case badge (SPEC 3.3): 30dp accent circle, 3dp card border, bottom-right inside the ring.
            if (connected && inCase) {
                val bx = cx + dp(20f) * scale
                val by = cy + dp(20f) * scale
                fillPaint.color = p.card
                canvas.drawCircle(bx, by, dp(15f) * scale, fillPaint)
                fillPaint.color = p.accent
                canvas.drawCircle(bx, by, dp(12f) * scale, fillPaint)
                val gw = dp(14f) * scale
                val gh = gw * 400f / 496f
                badgeIcon.setBounds((bx - gw / 2).toInt(), (by - gh / 2).toInt(), (bx + gw / 2).toInt(), (by + gh / 2).toInt())
                badgeIcon.draw(canvas)
            }

            val low = connected && s.level in 0..20
            pctPaint.color = if (low) p.accent else p.text
            canvas.drawText(pctText(s.level), cx, ring + dp(36f), pctPaint)

            val wear = if (i == 1 || !connected) null else wearLabel(s.status)
            val label = if (wear == null) names[i] else "${names[i]} · $wear"
            // "Right · Out of ear" can be wider than a narrow column: shrink to fit, never clip.
            labelPaint.textSize = dp(13f)
            val room = colW - dp(6f)
            val w = labelPaint.measureText(label)
            if (w > room) labelPaint.textSize = dp(13f) * room / w
            canvas.drawText(label, cx, ring + dp(60f), labelPaint)
        }
    }
}
