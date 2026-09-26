package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.spizganed.quickbuds.R
import java.util.WeakHashMap

/**
 * App theming: which theme is selected, and the palette.
 *
 * WHY THIS CLASS EXISTS
 * The old MainActivity.applyTheme walked the view tree after inflation and pushed
 * colours onto each TextView/Button. That worked while the screen had a header and
 * a panel, but it cannot express the redesigned UI: a settings row is a card, an
 * ANC circle is a segment, a chevron is secondary text — a tree walk flattens all
 * of them to one colour. So colours moved into resources and the theme is now
 * chosen by a STYLE, with Kotlin only retinting vector icons.
 *
 * HOW A THEME IS APPLIED — the third design, and why the first two failed
 *
 * Attempt 1: applyOverrideConfiguration() with a uiMode override, so the
 *   values-night / values-notnight palette folders would resolve.
 *   FAILED: throws "getResources() or getAssets() has already been called" on
 *   this device, because something in the Activity ATTACH path touches resources
 *   before onCreate. Five reorderings inside onCreate all failed identically.
 *   Do NOT reintroduce it.
 *
 * Attempt 2: three real styles (Theme.App.OLED / .Dark / .Light) selected with
 *   setTheme(). This stopped the crash, but the themes did not visibly change:
 *   setTheme() picks a STYLE, and a style cannot change which @color/app_*
 *   resource resolves. Those still came from values-night (-notnight), which
 *   follow the SYSTEM dark-mode setting, not the in-app choice. So choosing
 *   "Light" in the app still rendered the OLED palette.
 *
 * Attempt 3 (this one): make the distinction an ATTRIBUTE. Theme.App.Base sets
 *   appPalette, and each of the three themes points appPalette at a different
 *   colour set defined in values/themes.xml. Colours are then read through
 *   ThemeRes.color(), which resolves the attribute against the CURRENT theme.
 *   That is a normal resource lookup at inflate/query time, so it follows the
 *   in-app choice and cannot throw.
 *
 * Anything that needs a themed colour should call ThemeRes.color(context, R.attr.x)
 * rather than getColor(R.color.x) directly, when the value differs per theme.
 *
 * CUSTOM PRESETS (design/SPEC.md sections 1, 3.7, 3.8)
 * There are six token attributes (themes.xml). A built-in preset is a compiled style
 * that sets them. A custom preset cannot be a style, so [select] picks the base style
 * with the matching light/dark window and installs [PaletteFactory], which swaps each
 * `?attr/appColor*` reference in inflated XML for the preset's value. Code-built views
 * read colours through [color] and [palette], which return the preset's tokens. No
 * view-tree walk after inflation (SPEC's PaletteApplier) — deliberately.
 */
object ThemeRes {

    // Renamed from "BudsQSPrefs" with the project's move to the QuickBuds name. The
    // file name IS the string. After this release the name is permanent.
    private const val PREFS = "QuickBudsPrefs"

    /** Exposed so other screens reuse this prefs file rather than duplicating the name. */
    const val PREFS_NAME = PREFS

    /** Timestamp of the last crash report already shown, so it is announced once. */
    const val KEY_LAST_SHOWN_CRASH = "lastShownCrash"

    /** The six token attributes, in [Palette.tokens] order. */
    val TOKEN_ATTRS = intArrayOf(
        R.attr.appColorBg, R.attr.appColorCard, R.attr.appColorAccent,
        R.attr.appColorTextPrimary, R.attr.appColorTextSecondary, R.attr.appColorOutline
    )

    fun styleFor(builtInId: String): Int = when (builtInId) {
        PaletteStore.DARK -> R.style.Theme_App_Dark
        PaletteStore.WHITE -> R.style.Theme_App_Light
        else -> R.style.Theme_App_OLED
    }

    fun builtInName(id: String): String = when (id) {
        PaletteStore.DARK -> "Classic Dark"
        PaletteStore.WHITE -> "White"
        else -> "OLED Black"
    }

    @Volatile private var cached: Palette? = null

    /** The active preset. Cached; [invalidate] after changing presets. */
    fun palette(context: Context): Palette =
        cached ?: PaletteStore.active(context.applicationContext).also { cached = it }

    fun invalidate() { cached = null }

    /** What each live activity was themed with, so a preset change can rebuild it. */
    private val applied = WeakHashMap<Activity, String>()

    private fun signature(p: Palette) = p.id + p.tokens.contentToString()

    /**
     * Selects and applies the theme for one activity.
     *
     * Call as the FIRST statement of onCreate, BEFORE super.onCreate().
     *
     * setTheme() is safe before super.onCreate because it only records a style id
     * and resolves it lazily. That is the whole reason this approach replaced
     * applyOverrideConfiguration(). The window calls below only store values until
     * the decor exists.
     */
    fun select(activity: Activity) {
        val p = palette(activity)
        if (p.builtIn) {
            activity.setTheme(styleFor(p.id))
        } else {
            activity.setTheme(styleFor(if (p.isLight) PaletteStore.WHITE else PaletteStore.OLED))
            activity.layoutInflater.factory2 = PaletteFactory(p)
            activity.window.setBackgroundDrawable(ColorDrawable(p.background))
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = p.background
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = p.background
        }
        applied[activity] = signature(p)
    }

    /** True when the preset changed since [activity] was themed (checked on resume). */
    fun isStale(activity: Activity): Boolean {
        val sig = applied[activity] ?: return false
        return sig != signature(palette(activity))
    }

    /**
     * Reads a themed colour.
     *
     * A token attribute returns the active preset's value (built-in or custom). Any other
     * attribute or colour resource is resolved as before, so call sites can pass either.
     */
    fun color(context: Context, resId: Int): Int {
        val i = TOKEN_ATTRS.indexOf(resId)
        if (i >= 0) return palette(context).tokens[i]
        return try {
            val tv = TypedValue()
            if (context.theme.resolveAttribute(resId, tv, true)) {
                if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
            } else {
                context.getColor(resId)
            }
        } catch (_: Exception) {
            palette(context).textSecondary
        }
    }

    /**
     * Tints a vector drawable for the current theme.
     *
     * Every glyph here is drawn with fillColor="#FFFFFF" because it is used
     * white-on-dark in the widget, so icons are tinted explicitly.
     */
    fun tint(context: Context, drawableRes: Int, color: Int): android.graphics.drawable.Drawable {
        val d = context.getDrawable(drawableRes)!!.mutate()
        d.setTint(color)
        return d
    }

    /** A rounded rectangle in token colours: cards, pills, chips, sheet backgrounds. */
    fun shape(
        context: Context, fill: Int, stroke: Int? = null, radiusDp: Float,
        strokeDp: Float = 1f
    ) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radiusDp).toFloat()
        if (stroke != null) setStroke(dp(context, strokeDp).coerceAtLeast(1), stroke)
    }

    /** The standard card: `card` fill, 1dp `outline` stroke. */
    fun card(context: Context, radiusDp: Float = 24f): GradientDrawable {
        val p = palette(context)
        return shape(context, p.card, p.outline, radiusDp)
    }

    /** Icon button / framed control: `card` fill, `outline` stroke, 14dp radius (SPEC section 2). */
    fun iconButton(context: Context, radiusDp: Float = 14f): GradientDrawable = card(context, radiusDp)

    /** Press ripple in `text` at low alpha, over [content] (or bounded by the view when null). */
    fun ripple(context: Context, content: android.graphics.drawable.Drawable? = null): RippleDrawable {
        val p = palette(context)
        return RippleDrawable(
            ColorStateList.valueOf(Palette.withAlpha(p.text, 0.12f)), content,
            if (content == null) ColorDrawable(p.text) else null
        )
    }

    /** SPEC screen padding (16dp sides, 20dp top/bottom) plus the system bar insets. */
    fun screenPadding(view: View, sideDp: Float = 16f, vertDp: Float = 20f) {
        val side = dp(view.context, sideDp)
        val vert = dp(view.context, vertDp)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(side + bars.left, vert + bars.top, side + bars.right, vert + bars.bottom)
            insets
        }
    }

    /** Small chip button; the active one is an `accent` fill. */
    fun chip(context: Context, active: Boolean): GradientDrawable {
        val p = palette(context)
        return if (active) shape(context, p.accent, null, 10f) else shape(context, p.card, p.outline, 10f)
    }

    /** Bottom sheet window: rounded top corners only. */
    fun sheet(context: Context): GradientDrawable = card(context, 0f).apply {
        val r = dp(context, 20f).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }


    /** Thumb / track tint lists for a platform Switch (SPEC section 1, derived colours). */
    fun switchTints(context: Context): Pair<ColorStateList, ColorStateList> {
        val p = palette(context)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        return ColorStateList(states, intArrayOf(p.accent, p.thumbOff)) to
            ColorStateList(states, intArrayOf(p.track, p.track))
    }

    /** dp -> px, for the programmatically built rows. */
    fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    ).toInt()

    /**
     * Applies a custom preset at inflation time: creates each view as the platform
     * inflater would, then replaces any `?attr/appColor*` background or text colour
     * with the preset's value. Views built in code never pass through here; they read
     * [color] directly.
     */
    private class PaletteFactory(private val p: Palette) : LayoutInflater.Factory2 {
        private val prefixes = arrayOf("android.widget.", "android.webkit.", "android.app.", "android.view.")

        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
            val view = create(name, context, attrs) ?: return null
            for (i in 0 until attrs.attributeCount) {
                val value = attrs.getAttributeValue(i) ?: continue
                if (!value.startsWith("?")) continue
                val token = TOKEN_ATTRS.indexOf(value.substring(1).toIntOrNull() ?: continue)
                if (token < 0) continue
                val c = p.tokens[token]
                when (attrs.getAttributeNameResource(i)) {
                    android.R.attr.background -> view.setBackgroundColor(c)
                    android.R.attr.textColor -> (view as? TextView)?.setTextColor(c)
                    android.R.attr.textColorHint -> (view as? TextView)?.setHintTextColor(c)
                }
            }
            return view
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            onCreateView(null, name, context, attrs)

        private fun create(name: String, context: Context, attrs: AttributeSet): View? {
            val inflater = LayoutInflater.from(context)
            if ('.' in name) return runCatching { inflater.createView(name, null, attrs) }.getOrNull()
            for (prefix in prefixes) {
                try {
                    return inflater.createView(name, prefix, attrs)
                } catch (_: ClassNotFoundException) {
                }
            }
            return null
        }
    }
}
