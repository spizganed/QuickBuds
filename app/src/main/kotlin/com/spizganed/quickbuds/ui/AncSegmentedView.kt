package com.spizganed.quickbuds.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.spizganed.quickbuds.widget.QuickBudsWidget

/**
 * The pill position at [fraction] of a move from [from] to [to], clamped to the track. [last] is
 * the last segment index. The spring overshoots, so a fraction above 1 is valid: the pill then
 * stays on the edge and never leaves the track.
 */
internal fun segmentPosition(from: Float, to: Float, fraction: Float, last: Int): Float =
    (from + (to - from) * fraction).coerceIn(0f, last.toFloat())

/**
 * True when the view has a selection. [selected] is the segment index; -1 means no selection.
 * The animated position does not decide this: a move to segment 0 passes below 0.
 */
internal fun hasSelection(selected: Int): Boolean = selected >= 0

/**
 * The position a new travel starts from. [current] is the frame on screen now. When it is on the
 * track, the travel continues from it; a second tap during a move does not jump to [from].
 * [last] is the last segment index.
 */
internal fun travelStart(current: Float, from: Int, last: Int): Float =
    if (current in 0f..last.toFloat()) current else from.toFloat().coerceIn(0f, last.toFloat())

/**
 * The fill mix of every segment at [fraction] of a fade that starts from the [current] mixes.
 * The target segment goes to 1 and every other segment goes to 0. At fraction 0 the result is
 * [current], so a second tap during a fade continues from the mixes on screen.
 */
internal fun fadeMixes(current: FloatArray, to: Int, fraction: Float): FloatArray {
    val f = fraction.coerceIn(0f, 1f)
    return FloatArray(current.size) { i ->
        val target = if (i == to) 1f else 0f
        current[i] + (target - current[i]) * f
    }
}

/** The tap area of one segment. A tap and a screen reader use the same area. */
internal data class SegmentBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The tap area of segment [index] of [count] equal bins in a [width] x [height] view.
 * [indexAt] uses the same bins, so a screen-reader click and a tap select the same segment.
 */
internal fun segmentBounds(index: Int, count: Int, width: Int, height: Int): SegmentBounds =
    SegmentBounds(
        left = width.toFloat() * index / count,
        top = 0f,
        right = width.toFloat() * (index + 1) / count,
        bottom = height.toFloat()
    )

/** One coordinate of a cubic Bezier curve at parameter [t]. */
private fun bezier(v1: Float, v2: Float, t: Float): Float {
    val u = 1f - t
    return 3f * u * u * t * v1 + 3f * u * t * t * v2 + t * t * t
}

/** The y value at x = [progress] on a cubic Bezier with the given control points. */
internal fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, progress: Float): Float {
    val x = progress.coerceIn(0f, 1f)
    if (x <= 0f) return 0f
    if (x >= 1f) return 1f
    var low = 0f
    var high = 1f
    repeat(24) {
        val mid = (low + high) / 2f
        if (bezier(x1, x2, mid) < x) low = mid else high = mid
    }
    return bezier(y1, y2, (low + high) / 2f)
}

/** Material standard easing: cubic-bezier(0.2, 0, 0, 1). */
internal fun materialEase(progress: Float): Float = cubicBezier(0.2f, 0f, 0f, 1f, progress)

/** QuickBuds standard-decelerate easing: cubic-bezier(0, 0, 0, 1). */
internal fun quickBudsEase(progress: Float): Float = cubicBezier(0f, 0f, 0f, 1f, progress)

/** True only for a tap that moves the outline between two cells. */
internal fun selectionTravels(tapped: Boolean, from: Int, to: Int): Boolean =
    tapped && from >= 0 && to >= 0 && from != to

/**
 * Noise-control switcher. Material uses 52dp circles and a tonal fill that fades in place.
 * QuickBuds uses one card, hairline dividers and a 2dp accent outline. A tap moves the QuickBuds
 * outline; a buds report fades it in place. Glass and Pixel art keep their own look.
 *
 * A tap reports the segment index through [onSegmentTapped]. [selected] = -1 is the neutral
 * (disconnected) state: no selection.
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
            notifySegmentChanged(from)
            notifySegmentChanged(v)
            // A replaced animator must not move pos: the new one starts from the current frame.
            stop(anim)
            stop(appearAnim)
            stop(fillAnim)
            stop(quickAnim)
            if (p.glass && !nothing) {
                // Glass: the pill appears and leaves with a pop instead of snapping, also from and to "nothing chosen".
                if (width == 0) { pos = v.toFloat(); appear = if (v < 0) 0f else 1f; invalidate(); return }
                if (v < 0) { animateAppear(0f); return }
                if (from < 0) { pos = v.toFloat(); animateAppear(1f); return }
                appear = 1f
            } else if (material) {
                animateMaterial(v)
                return
            } else if (!nothing) {
                animateQuickBuds(from, v)
                return
            } else appear = if (v < 0) 0f else 1f
            if (width == 0 || from < 0 || v < 0) { pos = v.toFloat(); invalidate(); return }
            val start = pos
            val last = labels.size - 1
            val target = v.toFloat().coerceIn(0f, last.toFloat())
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380
                // The pill overshoots its target a little and settles: quick, with a spring.
                interpolator = android.view.animation.OvershootInterpolator(1.1f)
                addUpdateListener {
                    pos = segmentPosition(start, target, it.animatedValue as Float, last)
                    postInvalidateOnAnimation()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // End on the target, also after a cancel with no replacement.
                        pos = target
                        postInvalidateOnAnimation()
                    }
                })
                start()
            }
        }

    private fun stop(a: ValueAnimator?) {
        a?.apply {
            removeAllUpdateListeners()
            removeAllListeners()
            cancel()
        }
    }

    private var pos = -1f
    private var anim: ValueAnimator? = null

    /** Glass: how much of the pill shows, 0 (nothing chosen) to 1. Overshoots a little on its way in. */
    private var appear = 0f
    private var appearAnim: ValueAnimator? = null

    /** Material: the old and the new fill fade together. */
    private var fillAnim: ValueAnimator? = null

    /** QuickBuds: the outline travels, or fades in place when the buds report the change. */
    private var quickAnim: ValueAnimator? = null
    private var quickTravel = false
    private var outlineFrom = -1
    private var outlineTo = -1
    private var outlineMix = 1f

    /** The cell under the finger, and its state-layer amount. */
    private var pressIndex = -1
    private var pressLevel = 0f
    private var pressAnim: ValueAnimator? = null

    /** True only while a tap callback runs, so a synchronous selection travels and a report fades. */
    private var tapInFlight = false

    private fun animateAppear(to: Float) {
        appearAnim = ValueAnimator.ofFloat(appear, to).apply {
            duration = if (to > 0f) 320 else 180
            interpolator = if (to > 0f) android.view.animation.OvershootInterpolator(1.6f) else android.view.animation.DecelerateInterpolator()
            addUpdateListener { appear = it.animatedValue as Float; postInvalidateOnAnimation() }
            start()
        }
    }

    private fun animationsEnabled() = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) != 0f

    /** Material: the old fill fades out while the new fill fades in. The label colour takes 150 ms. */
    private fun animateMaterial(to: Int) {
        // A second tap continues from the mixes on screen: do not reset them to the old target.
        val fillStart = fillMixes.copyOf()
        val colorStart = colorMixes.copyOf()
        if (width == 0 || !animationsEnabled()) { settleMaterial(to); return }
        fillAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = LinearInterpolator()
            addUpdateListener {
                val elapsed = it.animatedFraction * 200f
                val fill = materialEase(elapsed / 200f)
                val color = materialEase((elapsed / 150f).coerceAtMost(1f))
                fadeMixes(fillStart, to, fill).copyInto(fillMixes)
                fadeMixes(colorStart, to, color).copyInto(colorMixes)
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = settleMaterial(to)
            })
            start()
        }
    }

    private fun settleMaterial(to: Int) {
        java.util.Arrays.fill(fillMixes, 0f)
        java.util.Arrays.fill(colorMixes, 0f)
        if (to in labels.indices) {
            fillMixes[to] = 1f
            colorMixes[to] = 1f
        }
        invalidate()
    }

    /**
     * QuickBuds: a tap moves the outline between two cells. A report from the buds fades the old
     * outline out and the new one in at the same place. A disconnect fades the outline out.
     */
    private fun animateQuickBuds(from: Int, to: Int) {
        val last = labels.size - 1
        val travel = selectionTravels(tapInFlight, from, to) && width > 0
        if (width == 0 || !animationsEnabled()) { settleQuickBuds(to); return }
        // A second tap continues from the outline and the colours on screen.
        val start = if (travel) travelStart(pos, from, last) else 0f
        val colorStart = colorMixes.copyOf()
        quickTravel = travel
        outlineFrom = from
        outlineTo = to
        outlineMix = 0f
        if (travel) pos = start
        val duration = if (to < 0) 150L else 200L
        quickAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener {
                val elapsed = it.animatedFraction * duration
                val motion = quickBudsEase(elapsed / duration)
                val color = quickBudsEase((elapsed / 150f).coerceAtMost(1f))
                if (travel) {
                    pos = segmentPosition(start, to.toFloat(), motion, last)
                    outlineMix = 1f
                } else {
                    pos = to.toFloat()
                    outlineMix = motion
                }
                fadeMixes(colorStart, to, color).copyInto(colorMixes)
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = settleQuickBuds(to)
            })
            start()
        }
    }

    private fun settleQuickBuds(to: Int) {
        java.util.Arrays.fill(colorMixes, 0f)
        quickTravel = false
        outlineFrom = -1
        outlineTo = to
        outlineMix = 1f
        if (to in labels.indices) {
            pos = to.toFloat()
            colorMixes[to] = 1f
        } else pos = -1f
        invalidate()
    }

    private fun pressCell(index: Int) {
        if (index !in labels.indices) return
        if (pressIndex == index && pressLevel > 0f) return
        stop(pressAnim)
        pressIndex = index
        pressLevel = 0f
        animatePress(1f, 100)
    }

    private fun releasePress() {
        if (pressIndex < 0) return
        animatePress(0f, 150)
    }

    private fun animatePress(target: Float, duration: Long) {
        stop(pressAnim)
        if (!animationsEnabled()) {
            pressLevel = target
            if (target == 0f) pressIndex = -1
            invalidate()
            return
        }
        pressAnim = ValueAnimator.ofFloat(pressLevel, target).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener {
                pressLevel = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (target == 0f) {
                        pressLevel = 0f
                        pressIndex = -1
                    }
                    postInvalidateOnAnimation()
                }
            })
            start()
        }
    }

    private fun dp(v: Float) = ThemeRes.dp(context, v).toFloat()

    private val p = ThemeRes.palette(context)
    private val labels = labels.toMutableList()
    private val icons: MutableList<Drawable> = iconRes.map { ThemeRes.depth(context, context.getDrawable(it)!!.mutate()) }.toMutableList()
    private val fillMixes = FloatArray(labels.size)
    private val colorMixes = FloatArray(labels.size)

    init {
        // A plain View with no text is not important by default. The virtual nodes below carry it.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /**
     * The view draws the segments, so the system has no node for them. Each labelled segment gets
     * a virtual node: its visible label, the radio-item role, the selected state and a click.
     * An icon-only segment (the bud sides) keeps the view's one description: it has no label.
     */
    private val nodeProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? = when {
            virtualViewId == HOST_VIEW_ID -> hostNode()
            virtualViewId in labels.indices && labels[virtualViewId].isNotEmpty() -> segmentNode(virtualViewId)
            else -> null
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfo.ACTION_CLICK) return false
            if (virtualViewId !in labels.indices || labels[virtualViewId].isEmpty() || !isEnabled) return false
            performClick()
            tapSegment(virtualViewId)
            return true
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = nodeProvider

    private fun hostNode(): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain(this)
        node.packageName = context.packageName
        node.className = "android.widget.RadioGroup"
        node.contentDescription = this.contentDescription
        val bounds = Rect(0, 0, width, height)
        node.setBoundsInParent(bounds)
        node.setBoundsInScreen(onScreen(bounds))
        node.isVisibleToUser = isShown && width > 0 && height > 0
        for (i in labels.indices) if (labels[i].isNotEmpty()) node.addChild(this, i)
        return node
    }

    private fun segmentNode(index: Int): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain()
        node.setSource(this, index)
        node.setParent(this)
        node.packageName = context.packageName
        node.className = "android.widget.RadioButton"
        node.contentDescription = labels[index]
        node.isEnabled = isEnabled
        node.isClickable = true
        node.isFocusable = true
        node.isSelected = index == selected
        node.addAction(AccessibilityNodeInfo.ACTION_CLICK)
        val b = segmentBounds(index, labels.size, width, height)
        val bounds = Rect(b.left.toInt(), b.top.toInt(), kotlin.math.ceil(b.right).toInt(), b.bottom.toInt())
        node.setBoundsInParent(bounds)
        node.setBoundsInScreen(onScreen(bounds))
        node.isVisibleToUser = isShown && width > 0 && height > 0
        return node
    }

    private fun onScreen(bounds: Rect): Rect {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Rect(
            bounds.left + location[0], bounds.top + location[1],
            bounds.right + location[0], bounds.bottom + location[1]
        )
    }

    /** Tells the screen reader that the label or the selected state of segment [index] changed. */
    private fun notifySegmentChanged(index: Int) {
        // Android throws "Accessibility off" for an event sent while no accessibility service runs.
        val a11y = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (index !in labels.indices || !isShown || a11y?.isEnabled != true) return
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        event.packageName = context.packageName
        event.className = "android.widget.RadioButton"
        event.setSource(this, index)
        parent?.requestSendAccessibilityEvent(this, event)
    }

    /**
     * Nothing style: the widget's dot-matrix mode icons ([QuickBudsWidget.modeIcon]), white, tinted when drawn.
     * Rendered at a whole-pixel pitch (about 1.15dp) and drawn unscaled: shrunk from the widget's 8 px pitch to
     * 28dp, each dot fell on fractional pixels and smeared.
     */
    private val dotPitch = Math.round(dp(1.15f)).coerceAtLeast(2).toFloat()
    private val dots: MutableList<Bitmap?> =
        if (ThemeRes.nothing(context)) iconRes.map { QuickBudsWidget.modeIcon(WIDGET_ICON[it] ?: it, dotPitch) }.toMutableList() else mutableListOf()

    /** Changes segment [i]'s label and icon (the home ANC segment shows the current level, as the widget's button). */
    fun setSegment(i: Int, label: String, iconRes: Int) {
        if (i !in labels.indices) return
        labels[i] = label
        if (i in icons.indices) icons[i] = ThemeRes.depth(context, context.getDrawable(iconRes)!!.mutate())
        if (i in dots.indices) dots[i] = QuickBudsWidget.modeIcon(WIDGET_ICON[iconRes] ?: iconRes, dotPitch)
        notifySegmentChanged(i)
        invalidate()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (ThemeRes.material(context)) p.container else p.card }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = p.outline
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
    private val quickBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = p.accent
    }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = p.outline
    }
    private val clipPath = android.graphics.Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(if (iconRes.isEmpty()) 14f else 11.5f); textAlign = Paint.Align.CENTER
        typeface = ThemeRes.medium(context)
    }
    private val box = RectF()
    private val capBounds = android.graphics.Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Nothing: taller, for the bigger unscaled dot icons.
        if (material) { setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (icons.isEmpty()) 44f else if (iconOnly) 56f else 72f).toInt()); return }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(if (icons.isEmpty()) 48f else if (dots.isNotEmpty()) 72f else 62f).toInt())
    }

    private val nothing = ThemeRes.nothing(context)

    /** Glass: icons only, no labels (the Left / Right buds). */
    private val iconOnly = icons.isNotEmpty() && labels.all { it.isEmpty() }

    /**
     * Material style. With icons: round 52dp buttons with the label under each (the noise control).
     * Text only: equal 12dp tiles with a 4dp gap (a choice group). The selected fill uses `tonal`
     * and the label uses `text`; both fade between options instead of sliding.
     */
    private val material = ThemeRes.material(context)

    private val plate = ThemeRes.GlassDrawable(context, 24f, flat = true)
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Glass: one plate with the card's corner radius. A lit glass pill glides under the selected mode (it follows [pos]);
     * every mode is an icon and a label on the plate.
     */
    private fun drawGlassPlate(canvas: Canvas) {
        val n = labels.size
        val inset = dp(4f)
        val segW = (width - inset * 2) / n
        plate.setBounds(0, 0, width, height)
        plate.draw(canvas)
        textPaint.getTextBounds("H", 0, 1, capBounds)
        // The shared clamp in segmentPosition keeps the pill on the plate while it springs.
        val shown = if (appear <= 0.01f && !hasSelection(selected)) -1f else pos
        val a = appear.coerceIn(0f, 1f)
        if (shown >= 0f && a > 0f) {
            val left = inset + segW * shown
            box.set(left, inset, left + segW, height - inset)
            val rad = if (icons.isEmpty()) height / 2f - inset else dp(20f)
            // Flat and bright, the colour of a switch that is on: it reads as "on" like the switches below do.
            val grow = 0.88f + 0.12f * appear
            box.inset(box.width() * (1f - grow) / 2f, box.height() * (1f - grow) / 2f)
            litPaint.shader = null; litPaint.style = Paint.Style.FILL
            litPaint.color = Palette.withAlpha(p.accent, 0.95f * a)
            canvas.drawRoundRect(box, rad, rad, litPaint)
        }
        if (iconOnly) {
            // Icons only: each glyph keeps its own ratio, centred in its segment.
            for (i in 0 until n) {
                val on = if (shown < 0f) 0f else (1f - kotlin.math.abs(shown - i)).coerceIn(0f, 1f) * a
                val cx = inset + segW * i + segW / 2
                icons.getOrNull(i)?.run {
                    setTint(Palette.blend(p.textSecondary, p.onAccent, on))
                    val h = dp(30f)
                    val w = if (intrinsicHeight > 0) h * intrinsicWidth / intrinsicHeight else h
                    setBounds((cx - w / 2).toInt(), ((height - h) / 2).toInt(), (cx + w / 2).toInt(), ((height + h) / 2).toInt())
                    draw(canvas)
                }
            }
            return
        }
        val textOnly = icons.isEmpty()
        val iconS = if (textOnly) 0f else dp(12f)
        val block = if (textOnly) capBounds.height().toFloat() else iconS * 2 + dp(5f) + capBounds.height()
        val iconTop = (height - block) / 2
        for (i in 0 until n) {
            val on = if (shown < 0f) 0f else (1f - kotlin.math.abs(shown - i)).coerceIn(0f, 1f) * a
            val cx = inset + segW * i + segW / 2
            val tint = Palette.blend(p.textSecondary, p.onAccent, on)
            icons.getOrNull(i)?.run {
                setTint(tint)
                setBounds((cx - iconS).toInt(), iconTop.toInt(), (cx + iconS).toInt(), (iconTop + iconS * 2).toInt())
                draw(canvas)
            }
            textPaint.color = tint
            canvas.drawText(labels[i], cx, if (textOnly) iconTop + capBounds.height() else iconTop + iconS * 2 + dp(5f) + capBounds.height(), textPaint)
        }
    }

    private fun drawMaterial(canvas: Canvas) {
        if (p.glass) { drawGlassPlate(canvas); return }
        val n = labels.size
        val gap = dp(4f)
        val segW = (width - gap * (n - 1)) / n
        val idle = Palette.blend(p.container, p.text, 0.08f)
        textPaint.getTextBounds("H", 0, 1, capBounds)
        for (i in 0 until n) {
            val fill = fillMixes[i].coerceIn(0f, 1f)
            val color = colorMixes[i].coerceIn(0f, 1f)
            val left = i * (segW + gap)
            val cx = left + segW / 2
            trackPaint.color = Palette.blend(if (icons.isEmpty()) p.container else idle, p.tonal, fill)
            if (icons.isEmpty()) {
                box.set(left, 0f, left + segW, height.toFloat())
                canvas.drawRoundRect(box, dp(12f), dp(12f), trackPaint)
                if (i == pressIndex && pressLevel > 0f) {
                    pressPaint.color = Palette.withAlpha(p.text, 0.12f * pressLevel)
                    canvas.drawRoundRect(box, dp(12f), dp(12f), pressPaint)
                }
                textPaint.color = Palette.blend(p.textSecondary, p.text, color)
                canvas.drawText(labels[i], cx, height / 2f + capBounds.height() / 2f, textPaint)
                continue
            }
            val r = dp(26f)
            canvas.drawCircle(cx, r, r, trackPaint)
            if (i == pressIndex && pressLevel > 0f) {
                pressPaint.color = Palette.withAlpha(p.text, 0.12f * pressLevel)
                canvas.drawCircle(cx, r, r, pressPaint)
            }
            val tint = Palette.blend(p.textSecondary, p.text, color)
            icons.getOrNull(i)?.run {
                setTint(tint)
                val s = dp(12f).toInt()
                setBounds((cx - s).toInt(), (r - s).toInt(), (cx + s).toInt(), (r + s).toInt())
                draw(canvas)
            }
            textPaint.color = tint
            canvas.drawText(labels[i], cx, r * 2 + dp(6f) + capBounds.height(), textPaint)
        }
    }

    /**
     * QuickBuds: one card with a cell per option. The chosen cell has a 2dp accent outline, inset
     * 4dp. Hairline dividers separate the cells. There is no inner track and no red pill.
     */
    private fun drawQuickBuds(canvas: Canvas) {
        val n = labels.size
        val h = height.toFloat()
        val radius = dp(24f)
        trackPaint.color = p.card
        box.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(box, radius, radius, trackPaint)
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = p.outline
        val edge = dp(0.5f)
        box.set(edge, edge, width - edge, h - edge)
        canvas.drawRoundRect(box, radius, radius, strokePaint)

        val segW = width.toFloat() / n
        if (n > 1) {
            for (i in 1 until n) {
                val x = i * segW
                canvas.drawLine(x, 0f, x, h, dividerPaint)
            }
        }

        if (pressIndex in 0 until n && pressLevel > 0f) {
            clipPath.reset()
            clipPath.addRoundRect(0f, 0f, width.toFloat(), h, radius, radius, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            pressPaint.color = Palette.withAlpha(if (p.isLight) p.text else Color.WHITE, 0.10f * pressLevel)
            canvas.drawRect(pressIndex * segW, 0f, (pressIndex + 1) * segW, h, pressPaint)
            canvas.restore()
        }

        if (quickTravel) {
            drawQuickOutline(canvas, pos, 1f)
        } else {
            if (outlineFrom in 0 until n && outlineMix < 1f) drawQuickOutline(canvas, outlineFrom.toFloat(), 1f - outlineMix)
            if (outlineTo in 0 until n) drawQuickOutline(canvas, outlineTo.toFloat(), outlineMix)
        }

        textPaint.getTextBounds("H", 0, 1, capBounds)
        val capH = capBounds.height().toFloat()
        val textOnly = icons.isEmpty()
        val iconSize = dp(22f)
        val iconTop = (h - (if (textOnly) capH else iconSize + dp(5f) + capH)) / 2f
        for (i in 0 until n) {
            val cx = (i + 0.5f) * segW
            val tint = Palette.blend(p.textSecondary, p.accent, colorMixes[i].coerceIn(0f, 1f))
            if (!textOnly) icons.getOrNull(i)?.run {
                setTint(tint)
                setBounds(
                    (cx - iconSize / 2).toInt(), iconTop.toInt(),
                    (cx + iconSize / 2).toInt(), (iconTop + iconSize).toInt()
                )
                draw(canvas)
            }
            textPaint.color = tint
            val baseline = if (textOnly) iconTop + capH else iconTop + iconSize + dp(5f) + capH
            canvas.drawText(labels[i], cx, baseline, textPaint)
        }
    }

    private fun drawQuickOutline(canvas: Canvas, position: Float, alpha: Float) {
        if (position < 0f || position > labels.size - 1 || alpha <= 0f) return
        val segW = width.toFloat() / labels.size
        val left = segW * position
        val inset = dp(4f)
        box.set(left + inset, inset, left + segW - inset, height - inset)
        quickBorderPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawRoundRect(box, dp(20f), dp(20f), quickBorderPaint)
    }

    /** Pixel art: the dotted outline with one cell of dots selected. */
    private fun track(canvas: Canvas) {
        val h = height.toFloat()
        // The outline as the shape in its colour with the track a cell inside, and a radius a little under
        // half the height: a stroke, or a full half-height radius, left a lone dot at each end.
        val pitch = DotArt.pitchPx(context)
        val trackH = (h / pitch).toInt() * pitch
        box.set(0f, 0f, width.toFloat(), trackH)
        val rad = minOf(dp(22f), trackH / 2 * 0.85f)
        canvas.drawRoundRect(box, rad, rad, strokePaint.apply { style = Paint.Style.FILL })
        box.inset(pitch, pitch)
        canvas.drawRoundRect(box, rad - pitch, rad - pitch, trackPaint)
        // The selected segment: an accent outline a cell wide, the track inside (not a fill).
        // Centred in the snapped track on a whole-cell gap, and concentric with its corners.
        if (hasSelection(selected)) {
            val inset = (dp(4f) / pitch).toInt().coerceAtLeast(1) * pitch
            val segW = (width - inset * 2) / labels.size
            box.set(inset + segW * pos, inset, inset + segW * (pos + 1), trackH - inset)
            val pr = minOf(rad - inset, box.height() / 2 * 0.85f)
            canvas.drawRoundRect(box, pr, pr, pillPaint)
            box.inset(pitch, pitch)
            canvas.drawRoundRect(box, pr - pitch, pr - pitch, trackPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (material) { drawMaterial(canvas); return }
        if (!nothing) { drawQuickBuds(canvas); return }
        val h = height.toFloat()
        DotArt.draw(context, canvas, width, height) { track(it) }

        val inset = dp(4f)
        val segW = (width - inset * 2) / labels.size

        // The dot icons get more room: 31 dots in 22dp would blur into a grey disc.
        val iconSize = dp(if (dots.isEmpty()) 22f else 28f)
        // Icon, gap and the label's cap height as one block, centred in the height, so the space above the
        // icon and below the label are equal.
        val iconH = dots.firstOrNull { it != null }?.height?.toFloat() ?: iconSize
        textPaint.getTextBounds("H", 0, 1, capBounds)
        val capH = capBounds.height().toFloat()
        val iconTop = Math.round((h - (iconH + dp(5f) + capH)) / 2).toFloat()
        val baseline = if (icons.isEmpty()) h / 2 + capH / 2 else iconTop + iconH + dp(5f) + capH
        labels.forEachIndexed { i, label ->
            // The segment under the moving fill brightens as the fill arrives.
            val closeness = if (!hasSelection(selected)) 0f else (1f - kotlin.math.abs(pos - i)).coerceIn(0f, 1f)
            val c = Palette.blend(p.textSecondary, if (nothing) p.accent else p.onAccent, closeness)
            val cx = inset + segW * i + segW / 2
            val dot = dots.getOrNull(i)
            if (dot != null) {
                dotPaint.colorFilter = PorterDuffColorFilter(c, PorterDuff.Mode.SRC_IN)
                // Unscaled, on whole pixels.
                canvas.drawBitmap(dot, Math.round(cx - dot.width / 2f).toFloat(), iconTop, dotPaint)
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

    private fun indexAt(x: Float): Int = ((x / width) * labels.size).toInt().coerceIn(0, labels.size - 1)

    /** Reports segment [index] to [onSegmentTapped] in the same way as a tap, and with the same write. */
    private fun tapSegment(index: Int) {
        Haptics.commit(this)
        tapInFlight = true
        try {
            onSegmentTapped?.invoke(index)
        } finally {
            tapInFlight = false
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        // Glass and Pixel art keep their own press behaviour.
        val layer = (material && !p.glass) || (!material && !nothing)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (layer) pressCell(indexAt(e.x))
            MotionEvent.ACTION_MOVE -> if (layer) {
                if (e.x < 0f || e.x > width) releasePress()
                else {
                    val i = indexAt(e.x)
                    if (i != pressIndex) pressCell(i)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (layer) releasePress()
                performClick()
                tapSegment(indexAt(e.x))
            }
            MotionEvent.ACTION_CANCEL -> if (layer) releasePress()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        /** The home screen's noise icons as the widget's mode icons, the ones drawn as dots. */
        private val WIDGET_ICON = mapOf(
            com.spizganed.quickbuds.R.drawable.ic_noise_off to com.spizganed.quickbuds.R.drawable.ic_mode_off,
            com.spizganed.quickbuds.R.drawable.ic_adaptive to com.spizganed.quickbuds.R.drawable.ic_mode_adaptive,
            com.spizganed.quickbuds.R.drawable.ic_transparency to com.spizganed.quickbuds.R.drawable.ic_mode_transparency
        )
    }
}
