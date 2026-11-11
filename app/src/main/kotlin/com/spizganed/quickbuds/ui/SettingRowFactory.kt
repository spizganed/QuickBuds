package com.spizganed.quickbuds.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.graphics.PorterDuff
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.TextView
import com.spizganed.quickbuds.R

/**
 * Builds the rows inside the main screen's settings card.
 *
 * WHY ROWS ARE BUILT IN CODE AND NOT IN XML
 * Every row is the same shape (icon, title + subtitle, control) and only the
 * control differs. Six near-identical blocks in XML would mean six places to edit
 * on any change, and — more importantly — the theme has to tint each icon and
 * switch, which XML cannot do for a vector drawable. Building them here keeps
 * "what a row looks like" in exactly one place.
 *
 * The row is a plain LinearLayout rather than a custom View class: it needs no
 * state of its own, so a custom ViewGroup would be ceremony with no payoff.
 */
object SettingRowFactory {

    /**
     * One settings row: 62dp (56dp in Material), 16dp padding, 14dp gap, 24dp accent icon (none
     * when [iconRes] is 0), title + optional subtitle, optional [value] text, then [trailing] in
     * a fixed 52dp slot so every toggle and chevron shares one right edge. The whole row is the
     * hit target, with a ripple.
     *
     * WRAP_CONTENT + minimumHeight, NOT a fixed height: a gesture binding can name several
     * actions, and a fixed height clipped it.
     */
    fun build(
        context: Context,
        iconRes: Int,
        titleRes: Int,
        subtitleRes: Int,
        trailing: View?,
        value: View? = null,
        minHeightDp: Float = 62f,
        leading: View? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val dp = { v: Float -> ThemeRes.dp(context, v) }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Material: the M3 list item heights (56dp two-line rows).
            minimumHeight = dp(if (ThemeRes.material(context)) minOf(minHeightDp, 56f) else minHeightDp)
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            if (onClick != null) {
                background = ThemeRes.ripple(context)
                setOnClickListener { onClick() }
            }
        }

        // Keep the leading view's own size: a plain View (the colour swatch) forced to WRAP_CONTENT
        // takes the whole row width, squeezing the title to one letter per line.
        if (leading != null) row.addView(leading, (leading.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        ).apply { marginEnd = dp(14f) })
        if (iconRes != 0) row.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginEnd = dp(14f) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            // Material: on-surface-variant (secondary text), as Google's lists.
            setImageDrawable(ThemeRes.tint(context, iconRes, ThemeRes.color(context,
                if (ThemeRes.material(context)) R.attr.appColorTextSecondary else R.attr.appColorAccent)))
            contentDescription = ""
            tag = ICON_TAG
        })

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = TEXT_TAG
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(context).apply {
            if (titleRes != 0) setText(titleRes)
            tag = TITLE_TAG
            setTextColor(ThemeRes.color(context, R.attr.appColorTextPrimary))
            textSize = 16f
            typeface = ThemeRes.medium(context)
        })
        if (subtitleRes != 0) {
            textColumn.addView(TextView(context).apply {
                setText(subtitleRes)
                setTextColor(ThemeRes.color(context, R.attr.appColorTextSecondary))
                textSize = 13f
                setPadding(0, dp(2f), 0, 0)
                // Tagged so a live row (hi-res codec) can swap its subtitle text
                // without rebuilding the row and losing its switch state.
                tag = SUBTITLE_TAG
            })
        }
        row.addView(textColumn)
        if (value != null) row.addView(value)
        if (trailing != null) row.addView(FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52f), LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(trailing, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ))
        })
        return row
    }

    /**
     * The row's subtitle, created empty if the row was built without one, so a caller can fill
     * it at runtime (active preset name, installed version).
     */
    fun subtitle(context: Context, row: LinearLayout): TextView {
        row.findViewWithTag<TextView>(SUBTITLE_TAG)?.let { return it }
        val column = row.findViewWithTag<LinearLayout>(TEXT_TAG)
        return TextView(context).apply {
            setTextColor(ThemeRes.color(context, R.attr.appColorTextSecondary))
            textSize = 13f
            setPadding(0, ThemeRes.dp(context, 2f), 0, 0)
            tag = SUBTITLE_TAG
            column.addView(this)
        }
    }

    /** Tag for the subtitle TextView, so callers can find and update it. */
    const val SUBTITLE_TAG = "setting_row_subtitle"

    /** Tag for the title TextView (rows whose title is not a resource, e.g. a preset name). */
    const val TITLE_TAG = "setting_row_title"

    /**
     * Material style: the noise control as its own card ([row] holds the switcher): a tonal card, no label, the
     * round buttons filling it (4dp at the sides, so the longest label fits). Other styles: unchanged.
     */
    fun noiseCard(row: LinearLayout) {
        val context = row.context
        if (!ThemeRes.material(context)) return
        val dp = { v: Float -> ThemeRes.dp(context, v) }
        // Glass: the control is its own plate; no card round it.
        if (ThemeRes.glass(context)) { row.background = null; row.setPadding(0, 0, 0, 0); return }
        row.background = ThemeRes.card(context, ThemeRes.LIST_OUTER)
        row.setPadding(dp(4f), dp(12f), dp(4f), dp(12f))
    }

    /** Tag for the Material style's gap between row tiles ([buildDivider]). */
    const val GAP_TAG = "setting_row_gap"

    /** Tag for the title + subtitle column. */
    const val TEXT_TAG = "setting_row_text"

    /** Tag for the leading icon ImageView. */
    const val ICON_TAG = "setting_row_icon"

    /**
     * Themed toggle: a plain android.widget.Switch (no Material dependency) with the
     * derived tints — `track` for the track, accent thumb when on, the `thumbOff` colour when off
     * (text at 90% on dark, the card colour on light).
     */
    fun buildSwitch(context: Context, checked: Boolean): Switch {
        val (thumb, track) = ThemeRes.switchTints(context)
        // performClick runs only for a user tap (the switch itself or its row), never for a
        // programmatic isChecked, so it is the one place a toggle's haptic belongs.
        return object : RequestSwitch(context) {
            override fun performClick(): Boolean = super.performClick().also { Haptics.commit(this) }
        }.apply {
            isChecked = checked
            text = ""
            showText = false
            thumbTintList = thumb
            trackTintList = track
            trackTintMode = PorterDuff.Mode.SRC_IN
            // No press halo: the stock ripple drew a see-through circle twice the knob's size.
            background = null
            if (ThemeRes.nothing(context)) styleNothing(context, this, track, thumb)
            if (ThemeRes.material(context)) styleMaterial(context, this)
        }
    }

    /** Nothing style: a dotted pill track and a dot-disc thumb (DotArt), coloured by the same tint lists. */
    private fun styleNothing(context: Context, sw: Switch, track: ColorStateList, thumb: ColorStateList) = sw.apply {
        trackDrawable = DotArt.Part(context, 44f, 24f, track) { c, b, p -> c.drawRoundRect(b, b.height() / 2, b.height() / 2, p) }
        thumbDrawable = DotArt.Part(context, 24f, 24f, thumb) { c, b, p ->
            // As tall as the track (no padding around the thumb).
            c.drawCircle(b.centerX(), b.centerY(), b.height() / 2, p)
        }
        thumbTintList = null
        trackTintList = null
        switchMinWidth = 0
    }

    // Material style: the M3 switch. A 52x32dp pill track (outlined when off) and a thumb
    // that grows from 16dp to 24dp when on.
    private fun styleMaterial(context: Context, sw: Switch) = sw.apply {
        val p = ThemeRes.palette(context)
        val dp = { v: Float -> ThemeRes.dp(context, v) }
        val checkedState = intArrayOf(android.R.attr.state_checked)
        trackDrawable = android.graphics.drawable.StateListDrawable().apply {
            setEnterFadeDuration(160); setExitFadeDuration(160)
            // Glass: a tinted track and a white thumb that keeps its size, as the system's own glass switch.
            addState(checkedState, ThemeRes.shape(context, p.accent, null, ThemeRes.FULL).apply { setSize(dp(52f), dp(32f)) })
            addState(intArrayOf(), (if (p.glass) ThemeRes.shape(context, if (p.glassLight) 0x3314152B else 0x4D0A0A28, p.outline, ThemeRes.FULL, 1f)
                else ThemeRes.shape(context, p.container, p.textSecondary, ThemeRes.FULL, 2f)).apply { setSize(dp(52f), dp(32f)) })
        }
        // A plain disc with no padding: Switch shifts a thumb outward by its padding (an inset
        // drawable's knob passed the track's end). The box is half the track wide, as Switch makes
        // itself twice the thumb's width; the disc's centre is 16dp from the track end it sits at.
        // One thumb for both states, drawn from where the Switch's own animation has put its box: the disc glides
        // from 16dp to 36dp, grows and changes colour on the way (so a toggle animates, not snaps).
        val off = if (p.glass) android.graphics.Color.WHITE else p.textSecondary
        val on = if (p.glass) android.graphics.Color.WHITE else p.onAccent
        val offR = if (p.glass) 13f else 8f
        val onR = if (p.glass) 13f else 12f
        thumbDrawable = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            private val shade = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun draw(c: android.graphics.Canvas) {
                val pad = (callback as? View)?.paddingLeft ?: 0
                val t = ((bounds.left - pad) / dp(26f).toFloat()).coerceIn(0f, 1f)
                val cx = pad + dp(16f) + t * dp(20f)
                val r = dp(offR + (onR - offR) * t).toFloat()
                if (p.glass) {
                    shade.shader = android.graphics.RadialGradient(cx, bounds.exactCenterY() + dp(1.5f), r + dp(5f),
                        intArrayOf(0x4D000000, 0x4D000000, 0), floatArrayOf(0f, r / (r + dp(5f)), 1f), android.graphics.Shader.TileMode.CLAMP)
                    c.drawCircle(cx, bounds.exactCenterY() + dp(1.5f), r + dp(5f), shade)
                }
                paint.color = androidx.core.graphics.ColorUtils.blendARGB(off, on, t)
                c.drawCircle(cx, bounds.exactCenterY(), r, paint)
            }
            override fun getIntrinsicWidth() = dp(26f)
            override fun getIntrinsicHeight() = dp(32f)
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        thumbTintList = null
        trackTintList = null
        switchMinWidth = dp(52f)
    }

    /** Chevron for rows that open another screen: 22dp, accent, no frame. */
    /** A switch row bound to a boolean in the app prefs. It saves on change. */
    fun prefSwitch(context: Context, iconRes: Int, titleRes: Int, subRes: Int, key: String, default: Boolean): LinearLayout {
        val prefs = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        val sw = buildSwitch(context, prefs.getBoolean(key, default))
        sw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        return build(context, iconRes, titleRes, subRes, sw) { sw.performClick() }
    }

    /** A filled pill with a verb (Setup's "Add"). Without an action it is plain secondary text, aligned with the switches. */
    fun pillButton(context: Context, textRes: Int, action: (() -> Unit)?): TextView = TextView(context).apply {
        val dp = { v: Float -> ThemeRes.dp(context, v) }
        val p = ThemeRes.palette(context)
        setText(textRes)
        textSize = 14f
        typeface = ThemeRes.bold(context)
        gravity = Gravity.CENTER
        isSingleLine = true
        minWidth = dp(72f)
        setPadding(dp(14f), 0, dp(14f), 0)
        // The state text has no pill: its right edge meets the switches' (the rows' 16dp padding), not 14dp inside it.
        if (action == null) { setTextColor(p.textSecondary); setPadding(0, 0, 0, 0); gravity = Gravity.CENTER_VERTICAL or Gravity.END; minWidth = 0 }
        else {
            setTextColor(ThemeRes.onFill(context))
            background = ThemeRes.ripple(context, ThemeRes.pill(context, p.accent, 18f))
            setOnClickListener { action() }
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36f))
    }

    fun buildChevron(context: Context): ImageView {
        val dp = { v: Float -> ThemeRes.dp(context, v) }
        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(
                ThemeRes.tint(context, R.drawable.ic_chevron_right, ThemeRes.color(context, R.attr.appColorAccent))
            )
            contentDescription = ""
        }
    }

    /**
     * A muted value label before the trailing slot, used by rows that report state (gesture
     * bindings). Capped at ~55% of the screen and two lines so a long binding wraps instead of
     * squeezing the title to nothing.
     */
    fun buildValue(context: Context, text: String): TextView {
        val dp = { v: Float -> ThemeRes.dp(context, v) }
        return TextView(context).apply {
            setText(text)
            setTextColor(ThemeRes.color(context, R.attr.appColorTextSecondary))
            textSize = 13f
            gravity = Gravity.END
            setPadding(dp(8f), 0, 0, 0)
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.55f).toInt()
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    /** 1dp `outline` divider between rows inside a card (Nothing: a row of dots). Not drawn after the last row. */
    fun buildDivider(context: Context): View = View(context).apply {
        if (ThemeRes.material(context)) {
            // Material style: no line. A 2dp gap between the row tiles ([ThemeRes.Segments]); Glass: 8dp, every row its own card.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ThemeRes.dp(context, if (ThemeRes.glass(context)) 8f else 2f))
            tag = GAP_TAG
            return@apply
        }
        val outline = ThemeRes.color(context, R.attr.appColorOutline)
        if (ThemeRes.nothing(context)) {
            // Nothing style: one row of dots (DotArt), a dot tall.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, DotArt.pitchPx(context).toInt())
            background = DotArt.Part(context, 0f, DotArt.PITCH_DP, android.content.res.ColorStateList.valueOf(outline)) { c, b, p -> c.drawRect(b, p) }
            return@apply
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ThemeRes.dp(context, 1f))
        setBackgroundColor(outline)
    }

    /** Card: 24dp radius, `outline` stroke, rows clipped to the corners. The dot style draws the same outline in dots. */
    fun card(context: Context, radiusDp: Float = 24f): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = if (ThemeRes.material(context)) ThemeRes.Segments(context) else ThemeRes.card(context, radiusDp)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /** Corner radius of a split row's card and of its selection outline: less than the 24dp cards, or a 52dp row reads as a pill. */
    const val SPLIT_RADIUS = 16f

    /** A plain column for [addSplit]: rows that are each their own card, with a gap (a selected row's outline fits its own card). */
    fun splitList(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        // Material: one segmented list, as [ThemeRes.Segments]. Corners follow the visible cards.
        if (ThemeRes.material(context)) addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val g = v as LinearLayout
            val shown = (0 until g.childCount).map { g.getChildAt(it) }.filter { it.visibility != View.GONE }
            val outer = ThemeRes.dp(context, ThemeRes.LIST_OUTER).toFloat(); val inner = ThemeRes.dp(context, ThemeRes.LIST_INNER).toFloat()
            shown.forEachIndexed { i, c ->
                val t = if (i == 0) outer else inner; val b = if (i == shown.lastIndex) outer else inner
                (c.background as? android.graphics.drawable.GradientDrawable)?.cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
            }
        }
    }

    /** Adds [row] to [list] as a card of its own, 8dp under the one before (Material: 2dp, a segmented list). */
    fun addSplit(list: LinearLayout, row: View) {
        val material = ThemeRes.material(list.context)
        val c = card(list.context, SPLIT_RADIUS)
        if (material) c.background = ThemeRes.card(list.context, SPLIT_RADIUS)
        (c.layoutParams as LinearLayout.LayoutParams).topMargin = if (list.childCount > 0) ThemeRes.dp(list.context, if (material) 2f else 8f) else 0
        c.addView(row)
        list.addView(c)
    }

    /** Adds [row] to [card], with a divider before every row but the first. */
    fun addRow(card: LinearLayout, row: View) {
        if (card.childCount > 0) card.addView(buildDivider(card.context))
        card.addView(row)
    }

    /** Screen title: 24sp bold (dot style: Doto, [ThemeRes.headline]; Material: 28sp regular, the M3 large top bar), 4dp start inset. */
    fun title(context: Context, textRes: Int): TextView = TextView(context).apply {
        setText(textRes)
        setTextColor(ThemeRes.color(context, R.attr.appColorTextPrimary))
        val material = ThemeRes.material(context)
        textSize = if (material) 28f else 24f
        typeface = if (material) android.graphics.Typeface.DEFAULT else ThemeRes.headline(context)
        setPadding(ThemeRes.dp(context, 4f), 0, 0, ThemeRes.dp(context, 4f))
    }

    /** Section label: 14sp, `textSecondary` (Material: accent, medium), 4dp start inset, 8dp above its card. */
    fun sectionLabel(context: Context, textRes: Int): TextView = TextView(context).apply {
        setText(textRes)
        val material = ThemeRes.material(context)
        setTextColor(ThemeRes.color(context, if (material) R.attr.appColorAccent else R.attr.appColorTextSecondary))
        if (material) typeface = ThemeRes.medium(context)
        textSize = 14f
        val dp = { v: Float -> ThemeRes.dp(context, v) }
        setPadding(dp(4f), dp(14f), 0, dp(8f))
    }

    /**
     * Screen root: vertical column on `background`, 16dp sides, 16dp top and
     * bottom plus the system bars (the app draws edge to edge on target SDK 35+).
     */
    fun screen(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ThemeRes.color(context, R.attr.appColorBg))
        ThemeRes.screenPadding(this)
    }

    /** Icon button: 44dp, `card` fill, `outline` stroke, 14dp radius, 22dp accent icon. */
    fun iconButton(context: Context, iconRes: Int, descRes: Int, onClick: () -> Unit): ImageView {
        val dp = { v: Float -> ThemeRes.dp(context, v) }
        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
            setPadding(dp(11f), dp(11f), dp(11f), dp(11f))
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ThemeRes.ripple(context, ThemeRes.iconButton(context))
            setImageDrawable(ThemeRes.tint(context, iconRes, ThemeRes.color(context, R.attr.appColorAccent)))
            contentDescription = context.getString(descRes)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }
}
