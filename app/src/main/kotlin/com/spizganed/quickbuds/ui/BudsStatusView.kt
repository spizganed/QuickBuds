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
import kotlin.math.min

/**
 * The main screen's status block (redesign 2026-09-23, replacing the icon card + bar card):
 * left bud, case and right bud, each inside a battery ring, with the percentage and a wear label
 * under it. Drawn in one view, in the same language as [EqCurveView] / [LevelSliderView].
 *
 * Wear, from the buds' status codes (same meaning the widget uses):
 *   3 / 7 = in ear  -> icon full white, label "In ear"
 *   4 / 0 = in case -> icon dimmed,     label "In case"
 *   other known     -> icon grey,       label "Out"
 * A bud in the case is DIMMED rather than hidden (the old card hid it), so the three rings never
 * leave a hole and the layout never moves.
 *
 * Battery rings animate to a new level. An unknown level (-1) draws the track only and "—".
 */
class BudsStatusView(context: Context) : View(context) {

    private class Slot(val icon: Drawable, val iconRatio: Float) {
        var level = -1
        var shown = 0f          // animated ring fraction 0..1
        var status = -1
        var anim: ValueAnimator? = null
    }

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()

    private val primary = ThemeRes.color(context, R.attr.appColorTextPrimary)
    private val secondary = ThemeRes.color(context, R.attr.appColorTextSecondary)
    private val accent = ThemeRes.color(context, R.attr.appColorAccent)

    // Icon source ratios (width / height) from their SVG viewBoxes: buds 176x272, case 496x400.
    private val slots = listOf(
        Slot(context.getDrawable(R.drawable.ic_bud_left)!!.mutate(), 176f / 272f),
        Slot(context.getDrawable(R.drawable.ic_case)!!.mutate(), 496f / 400f),
        Slot(context.getDrawable(R.drawable.ic_bud_right)!!.mutate(), 176f / 272f)
    )
    private val names = listOf(
        context.getString(R.string.status_left),
        context.getString(R.string.status_case),
        context.getString(R.string.status_right)
    )

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(5f); color = secondary; alpha = 50
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(5f); strokeCap = Paint.Cap.ROUND; color = accent
    }
    private val pctPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(19f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f); textAlign = Paint.Align.CENTER; color = secondary
    }
    private val arcBox = RectF()

    private val ringSize get() = min(dp(96f), width / 3f - dp(14f))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(168f).toInt())
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

    private fun pctText(level: Int) = if (level < 0) "—" else "$level%"

    private fun wearLabel(status: Int) = when (status) {
        3, 7 -> context.getString(R.string.status_in_ear)
        4, 0 -> context.getString(R.string.status_in_case)
        -1 -> null
        else -> context.getString(R.string.status_out)
    }

    override fun onDraw(canvas: Canvas) {
        val ring = ringSize
        val cy = dp(8f) + ring / 2
        val colW = width / 3f
        slots.forEachIndexed { i, s ->
            val cx = colW * i + colW / 2
            val r = ring / 2 - trackPaint.strokeWidth
            arcBox.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawOval(arcBox, trackPaint)
            if (s.shown > 0f) canvas.drawArc(arcBox, -90f, 360f * s.shown, false, arcPaint)

            // Icon: fit a box ~56% of the ring, keeping the SVG's own ratio.
            val box = ring * 0.56f
            val (iw, ih) = if (s.iconRatio < 1f) box * s.iconRatio to box else box to box / s.iconRatio
            val inCase = s.status == 4 || s.status == 0
            val tint = when {
                i == 1 -> primary
                s.status == 3 || s.status == 7 -> primary
                else -> secondary
            }
            s.icon.setTint(tint)
            s.icon.alpha = if (i != 1 && inCase) 90 else 255
            s.icon.setBounds((cx - iw / 2).toInt(), (cy - ih / 2).toInt(), (cx + iw / 2).toInt(), (cy + ih / 2).toInt())
            s.icon.draw(canvas)

            val low = s.level in 0..20
            pctPaint.color = if (low) accent else primary
            canvas.drawText(pctText(s.level), cx, cy + ring / 2 + dp(28f), pctPaint)

            val wear = if (i == 1) null else wearLabel(s.status)
            val label = if (wear == null) names[i] else "${names[i]} · $wear"
            canvas.drawText(label, cx, cy + ring / 2 + dp(48f), labelPaint)
        }
    }
}
