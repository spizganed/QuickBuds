package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Noise-control switcher (design/SPEC.md 3.1): a card-style container (22dp radius, 4dp padding)
 * holding equal-width 64dp segments, each a 22dp icon over an 11.5sp label. The accent fill SLIDES
 * to the active segment. A tap reports the segment index through [onSegmentTapped]; the fill only
 * moves when [selected] is set — i.e. when the buds' state says so. [selected] = -1 is the neutral
 * (disconnected) state: no fill.
 */
class AncSegmentedView(
    context: Context,
    private val labels: List<String>,
    iconRes: List<Int>
) : View(context) {

    var onSegmentTapped: ((Int) -> Unit)? = null

    var selected: Int = -1
        set(v) {
            if (v == field) return
            val from = field
            field = v
            anim?.cancel()
            if (width == 0 || from < 0 || v < 0) { pos = v.toFloat(); invalidate(); return }
            anim = ValueAnimator.ofFloat(pos, v.toFloat()).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { pos = it.animatedValue as Float; postInvalidateOnAnimation() }
                start()
            }
        }

    private var pos = -1f
    private var anim: ValueAnimator? = null

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()

    private val p = ThemeRes.palette(context)
    private val icons: List<Drawable> = iconRes.map { context.getDrawable(it)!!.mutate() }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.card }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = p.outline
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11.5f); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val box = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(72f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        box.set(dp(0.5f), dp(0.5f), width - dp(0.5f), h - dp(0.5f))
        canvas.drawRoundRect(box, dp(22f), dp(22f), trackPaint)
        canvas.drawRoundRect(box, dp(22f), dp(22f), strokePaint)

        val inset = dp(4f)
        val segW = (width - inset * 2) / labels.size
        if (pos >= 0f) {
            val left = inset + segW * pos
            box.set(left, inset, left + segW, h - inset)
            canvas.drawRoundRect(box, dp(18f), dp(18f), pillPaint)
        }

        val iconSize = dp(22f)
        val iconTop = h / 2 - dp(19f)
        val baseline = h / 2 + dp(18f)
        labels.forEachIndexed { i, label ->
            // The segment under the moving fill brightens as the fill arrives.
            val closeness = if (pos < 0f) 0f else (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
            val c = Palette.blend(p.textSecondary, p.onAccent, closeness)
            val cx = inset + segW * i + segW / 2
            icons[i].setTint(c)
            icons[i].setBounds(
                (cx - iconSize / 2).toInt(), iconTop.toInt(),
                (cx + iconSize / 2).toInt(), (iconTop + iconSize).toInt()
            )
            icons[i].draw(canvas)
            textPaint.color = c
            canvas.drawText(label, cx, baseline, textPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = ((e.x / width) * labels.size).toInt().coerceIn(0, labels.size - 1)
            performClick()
            Haptics.commit(this)
            onSegmentTapped?.invoke(i)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
