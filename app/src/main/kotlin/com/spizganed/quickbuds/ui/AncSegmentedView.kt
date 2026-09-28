package com.spizganed.quickbuds.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.spizganed.quickbuds.widget.QuickBudsWidget

/**
 * Noise-control switcher (design/SPEC.md 3.1): a card-style container (22dp radius, 4dp padding)
 * holding equal-width 64dp segments, each a 22dp icon over an 11.5sp label. The accent fill SLIDES
 * to the active segment. A tap reports the segment index through [onSegmentTapped]; the fill only
 * moves when [selected] is set — i.e. when the buds' state says so. [selected] = -1 is the neutral
 * (disconnected) state: no fill. With no icons (Dev Tools' Human / Raw) it is a 48dp text-only switch.
 */
class AncSegmentedView(
    context: Context,
    labels: List<String>,
    iconRes: List<Int> = emptyList()
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
    private val labels = labels.toMutableList()
    private val icons: MutableList<Drawable> = iconRes.map { context.getDrawable(it)!!.mutate() }.toMutableList()

    /**
     * Nothing style: the widget's dot-matrix mode icons ([QuickBudsWidget.modeIcon]), white, tinted when drawn.
     * Rendered at a whole-pixel pitch (about 1.15dp) and drawn unscaled: shrunk from the widget's 8 px pitch to
     * 28dp, each dot fell on fractional pixels and smeared ([USER] 2026-09-28).
     */
    private val dotPitch = Math.round(dp(1.15f)).coerceAtLeast(2).toFloat()
    private val dots: MutableList<Bitmap?> =
        if (ThemeRes.nothing(context)) iconRes.map { QuickBudsWidget.modeIcon(WIDGET_ICON[it] ?: it, dotPitch) }.toMutableList() else mutableListOf()

    /** Changes segment [i]'s label and icon (the home ANC segment shows the current level, as the widget's button). */
    fun setSegment(i: Int, label: String, iconRes: Int) {
        if (i !in labels.indices) return
        labels[i] = label
        if (i in icons.indices) icons[i] = context.getDrawable(iconRes)!!.mutate()
        if (i in dots.indices) dots[i] = QuickBudsWidget.modeIcon(WIDGET_ICON[iconRes] ?: iconRes, dotPitch)
        invalidate()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.card }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = p.outline
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(if (iconRes.isEmpty()) 14f else 11.5f); textAlign = Paint.Align.CENTER
        typeface = ThemeRes.medium(context)
    }
    private val box = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Nothing: taller, for the bigger unscaled dot icons.
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (icons.isEmpty()) 48f else if (dots.isNotEmpty()) 72f else 62f).toInt())
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

        // The dot icons get more room: 31 dots in 22dp would blur into a grey disc.
        val iconSize = dp(if (dots.isEmpty()) 22f else 28f)
        val iconTop = h / 2 + dp(5f) - iconSize
        val baseline = if (icons.isEmpty()) h / 2 + textPaint.textSize * 0.35f else h / 2 + dp(16f)
        labels.forEachIndexed { i, label ->
            // The segment under the moving fill brightens as the fill arrives.
            val closeness = if (pos < 0f) 0f else (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
            val c = Palette.blend(p.textSecondary, p.onAccent, closeness)
            val cx = inset + segW * i + segW / 2
            val dot = dots.getOrNull(i)
            if (dot != null) {
                dotPaint.colorFilter = PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN)
                // Unscaled, on whole pixels.
                canvas.drawBitmap(dot, Math.round(cx - dot.width / 2f).toFloat(), Math.round(h / 2 + dp(5f) - dot.height).toFloat(), dotPaint)
            } else icons.getOrNull(i)?.run {
                setTint(c)
                setBounds(
                    (cx - iconSize / 2).toInt(), iconTop.toInt(),
                    (cx + iconSize / 2).toInt(), (iconTop + iconSize).toInt()
                )
                draw(canvas)
            }
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

    companion object {
        /** The home screen's noise icons as the widget's mode icons, the ones drawn as dots. */
        private val WIDGET_ICON = mapOf(
            com.spizganed.quickbuds.R.drawable.ic_noise_off to com.spizganed.quickbuds.R.drawable.ic_mode_off,
            com.spizganed.quickbuds.R.drawable.ic_anc to com.spizganed.quickbuds.R.drawable.ic_mode_anc_medium,
            com.spizganed.quickbuds.R.drawable.ic_adaptive to com.spizganed.quickbuds.R.drawable.ic_mode_adaptive,
            com.spizganed.quickbuds.R.drawable.ic_transparency to com.spizganed.quickbuds.R.drawable.ic_mode_transparency
        )
    }
}
