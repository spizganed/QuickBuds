package com.spizganed.quickbuds.ui

import android.content.Context
import android.graphics.Typeface
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

    private val SEMIBOLD: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * One settings row (SPEC section 2): 72dp, 16dp padding, 14dp gap, 24dp accent icon (none
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
        minHeightDp: Float = 72f,
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
            minimumHeight = dp(minHeightDp)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            if (onClick != null) {
                background = ThemeRes.ripple(context)
                setOnClickListener { onClick() }
            }
        }

        if (leading != null) row.addView(leading, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(14f) })
        if (iconRes != 0) row.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24f), dp(24f)).apply { marginEnd = dp(14f) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(ThemeRes.tint(context, iconRes, ThemeRes.color(context, R.attr.appColorAccent)))
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
            typeface = SEMIBOLD
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

    /** Tag for the title + subtitle column. */
    const val TEXT_TAG = "setting_row_text"

    /** Tag for the leading icon ImageView. */
    const val ICON_TAG = "setting_row_icon"

    /**
     * Themed toggle: a plain android.widget.Switch (no Material dependency) with the SPEC's
     * derived tints — `track` for the track, accent thumb when on, `text` at 90% when off.
     */
    fun buildSwitch(context: Context, checked: Boolean): Switch {
        val (thumb, track) = ThemeRes.switchTints(context)
        // performClick runs only for a user tap (the switch itself or its row), never for a
        // programmatic isChecked, so it is the one place a toggle's haptic belongs.
        return object : Switch(context) {
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
        }
    }

    /** Chevron for rows that open another screen: 22dp, accent, no frame. */
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

    /** 1dp `outline` divider between rows inside a card. Not drawn after the last row. */
    fun buildDivider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ThemeRes.dp(context, 1f))
        setBackgroundColor(ThemeRes.color(context, R.attr.appColorOutline))
    }

    /** Card (SPEC section 2): 24dp radius, `outline` stroke, rows clipped to the corners. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ThemeRes.card(context)
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /** Adds [row] to [card], with a divider before every row but the first. */
    fun addRow(card: LinearLayout, row: View) {
        if (card.childCount > 0) card.addView(buildDivider(card.context))
        card.addView(row)
    }

    /** Screen title: 24sp bold, 4dp start inset. */
    fun title(context: Context, textRes: Int): TextView = TextView(context).apply {
        setText(textRes)
        setTextColor(ThemeRes.color(context, R.attr.appColorTextPrimary))
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(ThemeRes.dp(context, 4f), 0, 0, ThemeRes.dp(context, 4f))
    }

    /** Section label: 14sp, `textSecondary`, 4dp start inset, 9dp above its card. */
    fun sectionLabel(context: Context, textRes: Int): TextView = TextView(context).apply {
        setText(textRes)
        setTextColor(ThemeRes.color(context, R.attr.appColorTextSecondary))
        textSize = 14f
        val dp = { v: Float -> ThemeRes.dp(context, v) }
        setPadding(dp(4f), dp(18f), 0, dp(9f))
    }

    /**
     * Screen root (SPEC section 2): vertical column on `background`, 16dp sides, 20dp top and
     * bottom plus the system bars (the app draws edge to edge on target SDK 35+).
     */
    fun screen(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ThemeRes.color(context, R.attr.appColorBg))
        ThemeRes.screenPadding(this)
    }

    /** Icon button (SPEC section 2): 44dp, `card` fill, `outline` stroke, 14dp radius, 22dp accent icon. */
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
