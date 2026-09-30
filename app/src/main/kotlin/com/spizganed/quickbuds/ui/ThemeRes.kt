package com.spizganed.quickbuds.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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
import java.util.Locale
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

    fun builtInName(context: Context, id: String): String = context.getString(
        when (id) {
            PaletteStore.DARK -> R.string.theme_classic_dark
            PaletteStore.WHITE -> R.string.theme_white
            else -> R.string.theme_oled_black
        }
    )

    @Volatile private var cached: Palette? = null

    /** The active preset. Cached; [invalidate] after changing presets. Under Match system the
     *  cache also drops when the system's light / dark setting no longer matches it. */
    fun palette(context: Context): Palette {
        cached?.let { if (!PaletteStore.auto(context) || it.isLight == PaletteStore.systemLight(context)) return it }
        return PaletteStore.active(context.applicationContext).also { cached = it }
    }

    fun invalidate() { cached = null }

    /** What each live activity was themed with, so a preset change can rebuild it. */
    private val applied = WeakHashMap<Activity, String>()

    // The language is part of it so a language change below Android 13 rebuilds open screens too.
    // The style too, so a Classic / Nothing change rebuilds open screens.
    private fun signature(context: Context, p: Palette) = p.id + p.tokens.contentToString() + language(context) + nothing(context)

    private const val KEY_NOTHING = "styleNothing"

    /**
     * The app's style, shared with the widgets ([USER] 2026-09-28: one switch for both). True: Dot matrix (Doto
     * text, no cards, dot-matrix rings and mode icons). False (default): Classic.
     */
    fun nothing(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTHING, false)

    fun setNothing(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NOTHING, on).apply()
        com.spizganed.quickbuds.widget.AncWidgetProvider.refreshAll(context)
    }

    /** The dot style's font: Doto (SIL OFL, bundled in res/font, weight 900 with round dots, license in assets). */
    fun dotFont(context: Context): Typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.doto) ?: Typeface.DEFAULT

    /** Text weights for code-built views: Classic sans-serif; the dot style Doto everywhere, synthetic bold for medium and bold. */
    fun regular(context: Context): Typeface =
        if (nothing(context)) dotFont(context) else Typeface.DEFAULT
    fun medium(context: Context): Typeface =
        if (nothing(context)) Typeface.create(dotFont(context), Typeface.BOLD) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
    fun bold(context: Context): Typeface =
        if (nothing(context)) Typeface.create(dotFont(context), Typeface.BOLD) else Typeface.DEFAULT_BOLD

    /** Screen titles and big numbers: [bold] (Doto in the dot style, like the widget). */
    fun headline(context: Context): Typeface = bold(context)

    /**
     * The in-app language screen's choices: BCP 47 tag to native name, "" = system default
     * (LanguageActivity). One entry per values-xx folder.
     */
    val LANGUAGES = listOf(
        "" to null, "en" to "English", "zh-CN" to "简体中文", "zh-TW" to "繁體中文",
        "ja" to "日本語", "ko" to "한국어",
        "id" to "Bahasa Indonesia", "ms" to "Bahasa Melayu", "cs" to "Čeština", "de" to "Deutsch",
        "es" to "Español", "fil" to "Filipino", "fr" to "Français", "it" to "Italiano",
        "hu" to "Magyar", "nl" to "Nederlands", "pl" to "Polski", "pt" to "Português",
        "ro" to "Română", "sv" to "Svenska", "vi" to "Tiếng Việt", "tr" to "Türkçe",
        "el" to "Ελληνικά", "ru" to "Русский", "uk" to "Українська",
        "hi" to "हिन्दी", "bn" to "বাংলা", "th" to "ไทย"
    )
    private const val KEY_LANGUAGE = "appLanguage"

    /** The app language tag, "" for the system default. Android 13+ keeps it itself (LocaleManager). */
    fun language(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, "").orEmpty()

    /** Android 13+ recreates every activity itself; below that, [isStale] catches the change on resume. */
    fun setLanguage(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, tag).apply()
    }

    /**
     * Below Android 13: the chosen locale on this activity's resources. Not
     * applyOverrideConfiguration (it throws here, see the class comment). ponytail: the widget and
     * the notification keep the system language below 13; wrap QuickBudsApp's base context if
     * that matters.
     */
    @Suppress("DEPRECATION")
    private fun applyLegacyLanguage(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33) return
        val tag = language(activity)
        val locale = if (tag.isEmpty()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val res = activity.resources
        val cfg = Configuration(res.configuration).apply { setLocale(locale) }
        res.updateConfiguration(cfg, res.displayMetrics)
    }

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
        applyLegacyLanguage(activity)
        val p = palette(activity)
        if (p.builtIn && PaletteStore.accentOverride(activity, p.id) == null) {
            activity.setTheme(styleFor(p.id))
        } else {
            // Custom preset, or a built-in with the user's accent: its style, or the matching
            // light/dark one, plus the preset's values at inflation time.
            activity.setTheme(styleFor(if (p.builtIn) p.id else if (p.isLight) PaletteStore.WHITE else PaletteStore.OLED))
            activity.layoutInflater.factory2 = PaletteFactory(p)
            activity.window.setBackgroundDrawable(ColorDrawable(p.background))
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = p.background
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = p.background
        }
        // Dot matrix: Doto for every text the theme sets (XML and plain code-built TextViews).
        if (nothing(activity)) activity.theme.applyStyle(R.style.ThemeOverlay_App_Nothing, true)
        applied[activity] = signature(activity, p)
    }

    /** For an activity that handles a locale change in place: the new language is now applied. */
    fun markApplied(activity: Activity) { applied[activity] = signature(activity, palette(activity)) }

    /** True when the preset changed since [activity] was themed (checked on resume). */
    fun isStale(activity: Activity): Boolean {
        val sig = applied[activity] ?: return false
        return sig != signature(activity, palette(activity))
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
     * white-on-dark in the widget, so icons are tinted explicitly. Nothing style: as dots ([DotArt.Icon]).
     */
    fun tint(context: Context, drawableRes: Int, color: Int): android.graphics.drawable.Drawable {
        val nothing = nothing(context)
        if (nothing) when (drawableRes) {
            R.drawable.ic_tap_single -> return DotArt.taps(context, 1).also { it.setTint(color) }
            R.drawable.ic_tap_double -> return DotArt.taps(context, 2).also { it.setTint(color) }
            R.drawable.ic_tap_triple -> return DotArt.taps(context, 3).also { it.setTint(color) }
            R.drawable.ic_hold -> return DotArt.hold(context).also { it.setTint(color) }
            R.drawable.ic_close -> return DotArt.close(context).also { it.setTint(color) }
            R.drawable.ic_check -> return DotArt.check(context).also { it.setTint(color) }
            R.drawable.ic_pencil -> return DotArt.pencil(context).also { it.setTint(color) }
            R.drawable.ic_delete -> return DotArt.bin(context).also { it.setTint(color) }
            R.drawable.ic_settings_cog -> return DotArt.cog(context).also { it.setTint(color) }
        }
        val d = context.getDrawable(drawableRes)!!.mutate()
        d.setTint(color)
        // Nothing style: every tinted icon as dots (row icons, chevrons, header buttons, checks).
        return if (nothing) DotArt.Icon(context, d, DotArt.ICON_PITCH_DP) else d
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

    /** A filled, unoutlined rounded button: [shape], dots in the dot style. */
    fun pill(context: Context, fill: Int, radiusDp: Float): Drawable =
        if (nothing(context)) DotArt.Box(context, fill, null, radiusDp) else shape(context, fill, null, radiusDp)

    /**
     * A group of rows or a home tile: [card], or nothing in the Nothing style ([USER] 2026-09-28: no cards,
     * sections split by their labels, as on the Nothing widget).
     */
    fun group(context: Context): Drawable? = if (nothing(context)) null else card(context)

    /** The standard card: `card` fill, 1dp `outline` stroke. */
    /**
     * The selection mark ([USER] 2026-09-30: an outline, never a check), set as a row's or tile's foreground:
     * a 2dp [color] outline, in the dot style one cell of dots. Classic's 24dp radius is the card's, so the corner rows,
     * clipped to the card, keep a whole outline.
     */
    fun selectedBorder(context: Context, color: Int, radiusDp: Float = if (nothing(context)) 14f else 24f): android.graphics.drawable.Drawable {
        val r = dp(context, radiusDp).toFloat()
        if (!nothing(context)) return GradientDrawable().apply { cornerRadius = r; setStroke(dp(context, 2f), color) }
        return DotArt.Part(context, 0f, 0f, android.content.res.ColorStateList.valueOf(color)) { c, box, paint ->
            val e = DotArt.pitchPx(context) / 2
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 0f
            c.drawRoundRect(box.left + e, box.top + e, box.right - e, box.bottom - e, r, r, paint)
        }
    }

    fun card(context: Context, radiusDp: Float = 24f, fill: Int? = null, solid: Boolean = false, topOnly: Boolean = false): Drawable {
        val p = palette(context)
        if (nothing(context)) return DotArt.Box(context, fill ?: p.card, p.outline, radiusDp, topOnly, solid)
        return shape(context, fill ?: p.card, p.outline, radiusDp).apply {
            if (topOnly) { val r = dp(context, radiusDp).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
    }

    /** Icon button / framed control: `card` fill, `outline` stroke, 14dp radius (SPEC section 2). */
    fun iconButton(context: Context, radiusDp: Float = 14f): Drawable = card(context, radiusDp)

    /** Press ripple in `text` at low alpha, over [content] (or bounded by the view when null). */
    fun ripple(context: Context, content: android.graphics.drawable.Drawable? = null): RippleDrawable {
        val p = palette(context)
        return RippleDrawable(
            ColorStateList.valueOf(Palette.withAlpha(p.text, 0.12f)), content,
            if (content == null) ColorDrawable(p.text) else null
        )
    }

    /** SPEC screen padding (16dp sides, 20dp top/bottom) plus the system bar insets. */
    fun screenPadding(view: View, sideDp: Float = 16f, vertDp: Float = 16f) {
        val side = dp(view.context, sideDp)
        val vert = dp(view.context, vertDp)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(side + bars.left, vert + bars.top, side + bars.right, vert + bars.bottom)
            insets
        }
    }

    /** Small chip button; the active one is an `accent` fill. */
    fun chip(context: Context, active: Boolean): Drawable {
        val p = palette(context)
        if (nothing(context)) return DotArt.Box(context, if (active) p.accent else p.card, if (active) null else p.outline, 10f)
        return if (active) shape(context, p.accent, null, 10f) else shape(context, p.card, p.outline, 10f)
    }

    /** Bottom sheet window: rounded top corners only. */
    fun sheet(context: Context): Drawable = card(context, 20f, solid = true, topOnly = true)


    /** Thumb / track tint lists for a platform Switch (SPEC section 1, derived colours). */
    fun switchTints(context: Context): Pair<ColorStateList, ColorStateList> {
        val p = palette(context)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        // Nothing style on a light palette: a card-coloured dot thumb has no shadow and vanished
        // into the page ([USER] 2026-09-29), so it takes textSecondary there.
        val off = if (nothing(context) && p.isLight) p.textSecondary else p.thumbOff
        return ColorStateList(states, intArrayOf(p.accent, off)) to
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
