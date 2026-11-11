package com.spizganed.quickbuds.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.content.res.ColorStateList
import android.graphics.Color
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
 *   values-night-v31 palette folder would resolve.
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
 * Attempt 3 (this one): make the distinction an ATTRIBUTE. Theme.App.Base defines
 *   six `appColor*` attributes. Each of the three themes sets all six to its own colours
 *   in values/themes.xml. Colours are then read through
 *   ThemeRes.color(), which resolves the attribute against the CURRENT theme.
 *   That is a normal resource lookup at inflate/query time, so it follows the
 *   in-app choice and cannot throw.
 *
 * Anything that needs a themed colour should call ThemeRes.color(context, R.attr.x)
 * rather than getColor(R.color.x) directly, when the value differs per theme.
 *
 * CUSTOM PRESETS
 * There are six token attributes (themes.xml). A built-in preset is a compiled style
 * that sets them. A custom preset cannot be a style, so [select] picks the base style
 * with the matching light/dark window and installs [PaletteFactory], which swaps each
 * `?attr/appColor*` reference in inflated XML for the preset's value. Code-built views
 * read colours through [color] and [palette], which return the preset's tokens. No
 * view-tree walk after inflation — deliberately.
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
    @Volatile private var cachedWallpaper = 0

    /** The active preset. Cached; [invalidate] after changing presets. Under Match system the
     *  cache also drops when the system's light / dark setting no longer matches it. */
    fun palette(context: Context): Palette = base(context).let { if (glass(context)) glassify(it) else it }

    private fun base(context: Context): Palette {
        val wallpaper = PaletteStore.wallpaperKey(context)
        cached?.let { if (wallpaper == cachedWallpaper && (!PaletteStore.auto(context) || it.isLight == PaletteStore.systemLight(context))) return it }
        cachedWallpaper = wallpaper
        return PaletteStore.active(context.applicationContext).also { cached = it }
    }

    /**
     * Glass: always dark text on a deep gradient, so a light preset is not used; the surfaces are translucent whites
     * ([Palette.container]); the accent stays the preset's (or the wallpaper's).
     */
    private fun glassify(p: Palette): Palette {
        val light = p.isLight
        return p.copy(
            glass = true, glassLight = light, background = Color.TRANSPARENT, card = if (light) 0x66FFFFFF else Palette.GLASS_FILL,
            text = if (light) 0xFF14152B.toInt() else Color.WHITE,
            textSecondary = if (light) 0xB314152B.toInt() else 0xB8FFFFFF.toInt(),
            outline = if (light) 0x2914152B else 0x59FFFFFF
        )
    }

    /** Glass fills are see-through: 70% of their alpha over the gradient, 45% over a pitch black window. */
    fun glassAlpha(context: Context, color: Int): Int =
        (color and 0x00FFFFFF) or ((((color ushr 24) and 0xFF) * (if (glassBg(context) != GlassBg.NONE) 0.7f else 0.45f)).toInt() shl 24)

    /** The glass backgrounds, in the picker's order: name key, colour blobs (blue-ish first). */
    enum class GlassBg(val nameRes: Int, val blobs: IntArray?) {
        MIDNIGHT(R.string.glass_bg_midnight, intArrayOf(0xFF4A6DFF.toInt(), 0xFFC27BFF.toInt(), 0xFFFF9D6B.toInt(), 0xFF27D0C4.toInt())),
        AURORA(R.string.glass_bg_aurora, intArrayOf(0xFF2BD99B.toInt(), 0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), 0xFF22D3EE.toInt())),
        SUNSET(R.string.glass_bg_sunset, intArrayOf(0xFFFF6B6B.toInt(), 0xFFFFA94D.toInt(), 0xFFC2410C.toInt(), 0xFFEC4899.toInt())),
        OCEAN(R.string.glass_bg_ocean, intArrayOf(0xFF0EA5E9.toInt(), 0xFF1D4ED8.toInt(), 0xFF14B8A6.toInt(), 0xFF38BDF8.toInt())),
        ACCENT(R.string.glass_bg_accent, null),
        NONE(R.string.glass_bg_none, null)
    }

    private const val KEY_GLASS_BG = "styleGlassBg"

    /** The chosen glass background. The old on / off switch maps to Midnight / None. */
    fun glassBg(context: Context): GlassBg {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains(KEY_GLASS_BG)) return if (p.getBoolean(KEY_GLASS_GRADIENT, true)) GlassBg.MIDNIGHT else GlassBg.NONE
        return GlassBg.values().getOrElse(p.getInt(KEY_GLASS_BG, 0)) { GlassBg.MIDNIGHT }
    }

    fun setGlassBg(context: Context, bg: GlassBg) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_GLASS_BG, bg.ordinal).apply()
    }

    /** The window behind the glass: a base with soft colour blobs, dark or light, tinted by the accent in the Accent background. */
    fun glassBackground(context: Context, accent: Int): Drawable {
        val pal = palette(context)
        val light = pal.glassLight
        // OLED Black is pure black under the glass, Classic Dark a lifted blue-grey: the two dark themes differ in Glass too.
        val oled = pal.id == PaletteStore.OLED
        val choice = glassBg(context)
        if (choice == GlassBg.NONE) return ColorDrawable(if (light) 0xFFF4F5FA.toInt() else if (oled) Color.BLACK else 0xFF1B1D26.toInt())
        val dm = context.resources.displayMetrics
        val r = dm.heightPixels * 0.5f
        fun blob(color: Int, cx: Float, cy: Float, a: Float) = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT
            gradientRadius = r
            setGradientCenter(cx, cy)
            colors = intArrayOf(Palette.withAlpha(color, a), Palette.withAlpha(color, 0f))
        }
        val colors = choice.blobs ?: intArrayOf(accent, Palette.blend(accent, 0xFF4A6DFF.toInt(), 0.5f), Palette.blend(accent, 0xFFFF9D6B.toInt(), 0.5f), Palette.blend(accent, Color.WHITE, 0.35f))
        // Dark: dim blobs on a deep base. Light: soft pastel blobs on a pale base.
        val alphas = if (light) floatArrayOf(0.34f, 0.28f, 0.24f, 0.26f)
            else if (oled) floatArrayOf(0.2f, 0.14f, 0.11f, 0.12f) else floatArrayOf(0.26f, 0.19f, 0.15f, 0.17f)
        return android.graphics.drawable.LayerDrawable(arrayOf(
            ColorDrawable(if (light) 0xFFF1F3FA.toInt() else if (oled) 0xFF050509.toInt() else 0xFF1B1D2B.toInt()),
            blob(colors[0], 0.12f, 0.18f, alphas[0]),
            blob(colors[1], 0.88f, 0.12f, alphas[1]),
            blob(colors[2], 0.78f, 0.92f, alphas[2]),
            blob(colors[3], 0.08f, 0.95f, alphas[3])
        ))
    }

    fun invalidate() { cached = null }

    /** What each live activity was themed with, so a preset change can rebuild it. */
    private val applied = WeakHashMap<Activity, String>()

    // The language is part of it so a language change below Android 13 rebuilds open screens too.
    // The style too, so a Classic / Nothing change rebuilds open screens.
    private fun signature(context: Context, p: Palette) = p.id + p.tokens.contentToString() + language(context) + nothing(context) + material(context) + glass(context) + glassBg(context)

    private const val KEY_NOTHING = "styleNothing"

    /**
     * The app's style, shared with the widgets (one switch for both). True: Dot matrix (Doto
     * text, no cards, dot-matrix rings and mode icons). False: Classic or [material] (a new install's default).
     */
    fun nothing(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTHING, false)

    const val KEY_MATERIAL = "styleMaterial"

    /**
     * Material style (M3): tonal cards without outlines, pill buttons, the M3 switch. The widgets keep
     * the Classic layouts with tonal colours. Never true together with [nothing].
     */
    fun material(context: Context) = !nothing(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { PaletteStore.defaults(it) }.getBoolean(KEY_MATERIAL, false)

    const val KEY_GLASS = "styleGlass"
    private const val KEY_GLASS_GRADIENT = "styleGlassGradient"

    /** Window blur behind a glass sheet: Android 12 and newer, when the system allows it. */
    fun windowBlur(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && (context as? Activity)?.windowManager?.isCrossWindowBlurEnabled == true

    /** Glass: a dim midnight glow behind the glass (the default). Off: a pitch black window (OLED). */
    fun glassGradient(context: Context) = glassBg(context) != GlassBg.NONE



    /** Glass style: Material's shapes on translucent surfaces over a gradient window. Implies [material]. */
    fun glass(context: Context) = material(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GLASS, false)

    /** The style picker's index: 0 Classic, 1 Dot matrix, 2 Material, 3 Glass. */
    fun style(context: Context) = if (nothing(context)) 1 else if (glass(context)) 3 else if (material(context)) 2 else 0

    fun setStyle(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_NOTHING, index == 1).putBoolean(KEY_MATERIAL, index == 2 || index == 3).putBoolean(KEY_GLASS, index == 3).apply()
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
    private fun applyLanguageBelow33(activity: Activity) {
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
        applyLanguageBelow33(activity)
        val p = palette(activity)
        if (p.builtIn && PaletteStore.accentOverride(activity, p.id) == null && !PaletteStore.wallpaper(activity)) {
            activity.setTheme(styleFor(p.id))
        } else {
            // Custom preset, or a built-in with the user's accent or wallpaper colours: its style, or the matching
            // light/dark one, plus the preset's values at inflation time.
            activity.setTheme(styleFor(if (p.builtIn) p.id else if (p.isLight) PaletteStore.WHITE else PaletteStore.OLED))
            activity.layoutInflater.factory2 = PaletteFactory(p)
            activity.window.setBackgroundDrawable(ColorDrawable(p.background))
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = p.background
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = p.background
        }
        if (p.glass) {
            activity.window.setBackgroundDrawable(glassBackground(activity, p.accent))
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = Color.TRANSPARENT
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
        } catch (e: Exception) {
            com.spizganed.quickbuds.bluetooth.PacketLogger.error("theme colour", e)
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
            R.drawable.ic_model_list -> return DotArt.menu(context).also { it.setTint(color) }
            R.drawable.ic_status_dot_filled -> return DotArt.statusDot(context, true).also { it.setTint(color) }
            R.drawable.ic_status_dot_empty -> return DotArt.statusDot(context, false).also { it.setTint(color) }
            R.drawable.ic_settings_cog -> return DotArt.cog(context).also { it.setTint(color) }
        }
        val d = context.getDrawable(drawableRes)!!.mutate()
        d.setTint(color)
        // Nothing style: every tinted icon as dots (row icons, chevrons, header buttons, checks).
        if (nothing) return DotArt.Icon(context, d, DotArt.ICON_PITCH_DP)
        // Glass: every icon has depth.
        return if (palette(context).glass && drawableRes != R.drawable.ic_launcher_foreground) DepthIcon(context, d, color) else d
    }

    /** A drawable for the Glass style: [icon] with a soft shadow below and a lit-to-dim vertical gradient. */
    fun depth(context: Context, icon: Drawable): Drawable = if (palette(context).glass && !nothing(context)) DepthIcon(context, icon, Color.WHITE) else icon

    /**
     * Glass icon depth: the icon's shape is drawn as a mask once per size, then as a blurred shadow and as a gradient.
     * [setTint] changes the colour at once, so the same drawable serves every state.
     */
    class DepthIcon(context: Context, private val icon: Drawable, private var color: Int) : Drawable() {
        private val d = context.resources.displayMetrics.density
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val blur = android.graphics.Paint().apply { maskFilter = android.graphics.BlurMaskFilter(1.6f * d, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        private var mask: android.graphics.Bitmap? = null
        private var shadow: android.graphics.Bitmap? = null
        private val shadowOff = IntArray(2)
        private var key = ""

        override fun getIntrinsicWidth() = icon.intrinsicWidth
        override fun getIntrinsicHeight() = icon.intrinsicHeight

        override fun setTint(tintColor: Int) { color = tintColor; invalidateSelf() }

        override fun draw(canvas: android.graphics.Canvas) {
            val w = bounds.width(); val h = bounds.height()
            if (w <= 0 || h <= 0) return
            if (key != "$w/$h") {
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                icon.setTint(Color.WHITE)
                icon.setBounds(0, 0, w, h)
                icon.draw(android.graphics.Canvas(bmp))
                mask = bmp.extractAlpha()
                shadow = bmp.extractAlpha(blur, shadowOff)
                bmp.recycle()
                key = "$w/$h"
            }
            val a = (color ushr 24) / 255f
            shadowPaint.color = Color.argb((80 * a).toInt(), 0, 0, 0)
            canvas.drawBitmap(shadow!!, bounds.left + shadowOff[0].toFloat(), bounds.top + shadowOff[1] + 1.2f * d, shadowPaint)
            paint.shader = android.graphics.LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(), color, Palette.withAlpha(color, 0.65f * a), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawBitmap(mask!!, bounds.left.toFloat(), bounds.top.toFloat(), paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
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

    /** Label colour on a [chip] (active) or [pill]: the dot style outlines them in the accent, so the label is accent. */
    fun onFill(context: Context): Int = if (nothing(context)) palette(context).accent else palette(context).onAccent

    /** A filled, unoutlined rounded button (dot style: outlined in the fill colour, since a dotted fill hid the label): [shape], dots in the dot style. */
    fun pill(context: Context, fill: Int, radiusDp: Float): Drawable =
        if (nothing(context)) DotArt.Box(context, palette(context).card, fill, radiusDp)
        else shape(context, fill, null, if (material(context)) FULL else radiusDp)

    /** The design's glass surface: a diagonal white gradient, a light rim and a bright line along the top edge. */
    class GlassDrawable(context: Context, private val radiusDp: Float, private val selected: Boolean = false, private val topOnly: Boolean = false, private val base: Int = 0, private val strong: Boolean = false, private val flat: Boolean = false, private val floating: Boolean = false) : Drawable() {
        private val d = context.resources.displayMetrics.density
        private val ctx = context
        private fun dim(c: Int) = glassAlpha(ctx, c)
        private val pal = palette(context)
        private val light = pal.glassLight
        private val accent = pal.accent
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = d
            color = when {
                // A selected surface is the accent; every other one is white (dark mode) or a white plate with a faint ink edge (light mode).
                selected -> Palette.withAlpha(accent, 0.85f)
                light -> if (strong) 0x40FFFFFF.toInt().let { 0x2214152B } else 0x1A14152B
                strong -> 0x80FFFFFF.toInt()
                flat -> 0x2EFFFFFF
                else -> 0x4DFFFFFF
            }
        }
        private val lit = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = d }
        private val box = android.graphics.RectF()
        private val basePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = base }
        private val path = android.graphics.Path()

        override fun onBoundsChange(b: android.graphics.Rect) {
            val from: Int; val to: Int
            if (selected) {
                from = Palette.withAlpha(accent, if (light) 0.55f else 0.5f); to = Palette.withAlpha(accent, if (light) 0.38f else 0.3f)
            } else if (light) {
                from = if (strong) 0xB3FFFFFF.toInt() else if (flat) 0x99FFFFFF.toInt() else 0xB3FFFFFF.toInt()
                to = if (strong) 0x80FFFFFF.toInt() else if (flat) 0x66FFFFFF else 0x80FFFFFF.toInt()
            } else {
                from = if (strong) 0x4AFFFFFF else if (flat) 0x1CFFFFFF else 0x26FFFFFF
                to = if (strong) 0x16FFFFFF else if (flat) 0x0FFFFFFF else 0x0AFFFFFF
            }
            fill.shader = android.graphics.LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                if (selected) from else dim(from), if (selected) to else dim(to), android.graphics.Shader.TileMode.CLAMP)
            lit.shader = android.graphics.LinearGradient(0f, b.top.toFloat(), 0f, b.top + 20 * d, if (light) 0xCCFFFFFF.toInt() else 0x8CFFFFFF.toInt(), 0, android.graphics.Shader.TileMode.CLAMP)
        }

        private var alphaMul = 255

        override fun draw(canvas: android.graphics.Canvas) {
            // A fade (a TransitionDrawable) sets the alpha: draw everything into a layer of that alpha.
            if (alphaMul < 255) {
                if (alphaMul <= 0) return
                val save = canvas.saveLayerAlpha(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), alphaMul)
                drawShape(canvas)
                canvas.restoreToCount(save)
            } else drawShape(canvas)
        }

        private fun drawShape(canvas: android.graphics.Canvas) {
            val r = minOf(radiusDp * d, bounds.width() / 2f, bounds.height() / 2f)
            box.set(bounds); box.inset(d / 2, d / 2)
            if (floating) {
                // A floating sheet: every corner rounded, a dark base so the content behind does not read through.
                canvas.drawRoundRect(box, r, r, basePaint); canvas.drawRoundRect(box, r, r, fill); canvas.drawRoundRect(box, r, r, rim)
                box.inset(d, d)
                canvas.drawRoundRect(box, r - d, r - d, lit)
                return
            }
            if (topOnly) {
                // A sheet: rounded on top only, over a dark base so the content behind does not show through.
                box.bottom += r
                canvas.save(); canvas.clipRect(bounds)
                path.reset(); path.addRoundRect(box, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), android.graphics.Path.Direction.CW)
                canvas.drawPath(path, basePaint); canvas.drawPath(path, fill); canvas.drawPath(path, rim)
                box.inset(d, d)
                path.reset(); path.addRoundRect(box, floatArrayOf(r - d, r - d, r - d, r - d, 0f, 0f, 0f, 0f), android.graphics.Path.Direction.CW)
                canvas.drawPath(path, lit)
                canvas.restore()
                return
            }
            canvas.drawRoundRect(box, r, r, fill)
            canvas.drawRoundRect(box, r, r, rim)
            if (flat) return   // content: no specular line
            box.inset(d, d)
            canvas.drawRoundRect(box, r - d, r - d, lit)
        }
        override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** A group of rows or a home tile: the [card], in dots in the dot style (borders back on home). */
    fun group(context: Context): Drawable? = if (material(context)) Segments(context) else card(context)

    /**
     * Material style: the rows of a [group] as separate tonal tiles (M3 segmented list), split at the
     * [SettingRowFactory.buildDivider] gaps. The outer corners are 16dp, the inner ones 4dp (M3 expressive list). The drawable
     * reads the rows from the view it is the background of.
     */
    class Segments(context: Context) : Drawable() {
        // Glass: content is flat; every tile is its own fully rounded card.
        private val isGlass = palette(context).glass
        private val fill = if (isGlass) glassAlpha(context, palette(context).container) else palette(context).container
        private val outer = dp(context, LIST_OUTER).toFloat()
        private val inner = dp(context, LIST_INNER).toFloat()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        private val path = android.graphics.Path()
        private val box = android.graphics.RectF()
        // Glass: a light edge round each tile.
        private val edge = palette(context).takeIf { it.glass }?.let { p ->
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(context, 1f).toFloat(); color = if (palette(context).glassLight) 0x2214152B else 0x26FFFFFF
            }
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val group = callback as? android.view.ViewGroup ?: return
            val tiles = mutableListOf<Pair<Int, Int>>()
            var top = -1; var bottom = -1
            for (i in 0 until group.childCount) {
                val c = group.getChildAt(i)
                if (c.visibility == View.GONE) continue
                if (c.tag == SettingRowFactory.GAP_TAG) {
                    if (top >= 0) tiles += top to bottom
                    top = -1
                } else {
                    if (top < 0) top = c.top
                    bottom = c.bottom
                }
            }
            if (top >= 0) tiles += top to bottom
            tiles.forEachIndexed { i, (t0, b0) ->
                // The first and last tiles reach the edges, over the group's own padding.
                val t = if (i == 0) bounds.top else t0
                val b = if (i == tiles.lastIndex) bounds.bottom else b0
                val rt = if (isGlass || i == 0) outer else inner
                val rb = if (isGlass || i == tiles.lastIndex) outer else inner
                box.set(bounds.left.toFloat(), t.toFloat(), bounds.right.toFloat(), b.toFloat())
                path.reset()
                path.addRoundRect(box, floatArrayOf(rt, rt, rt, rt, rb, rb, rb, rb), android.graphics.Path.Direction.CW)
                canvas.drawPath(path, paint)
                edge?.let { canvas.drawPath(path, it) }
            }
        }

        override fun getOutline(outline: android.graphics.Outline) = outline.setRoundRect(bounds, outer)
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** The standard card: `card` fill, 1dp `outline` stroke. */
    /**
     * The selection mark (an outline, never a check), set as a row's or tile's foreground:
     * a 2dp [color] outline, in the dot style one cell of dots. 24dp is the card's radius in both styles, so the corner rows
     * line up with the card's own border.
     */
    fun selectedBorder(context: Context, color: Int, radiusDp: Float = 24f): android.graphics.drawable.Drawable {
        val r = dp(context, radiusDp).toFloat()
        // Material: a selected list item is a tinted tile with 16dp corners (M3 expressive list). In a split
        // list the tile's own 4dp corners are masked in the screen colour, so the item reads as one 16dp shape.
        // A tile that is not a row (a theme swatch) keeps the outline.
        if (material(context)) {
            val list = radiusDp == SettingRowFactory.SPLIT_RADIUS
            return object : Drawable() {
                private val tint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = Palette.withAlpha(color, 0.24f) }
                private val mask = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = palette(context).background }
                private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = dp(context, 2f).toFloat(); this.color = color
                }
                private val rr = dp(context, LIST_OUTER).toFloat()
                override fun draw(canvas: android.graphics.Canvas) {
                    val b = android.graphics.RectF(bounds)
                    if (b.width() <= b.height() * 3) { b.inset(line.strokeWidth / 2, line.strokeWidth / 2); canvas.drawRoundRect(b, r, r, line); return }
                    if (list) {
                        val corners = android.graphics.Path().apply {
                            addRect(b, android.graphics.Path.Direction.CW)
                            addRoundRect(b, rr, rr, android.graphics.Path.Direction.CCW)
                            fillType = android.graphics.Path.FillType.EVEN_ODD
                        }
                        canvas.drawPath(corners, mask)
                    }
                    canvas.drawRoundRect(b, rr, rr, tint)
                }
                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java")
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
        }
        if (!nothing(context)) return GradientDrawable().apply { cornerRadius = r; setStroke(dp(context, 2f), color) }
        return DotArt.Part(context, 0f, 0f, android.content.res.ColorStateList.valueOf(color)) { c, box, paint ->
            val e = DotArt.pitchPx(context) / 2
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = 0f
            c.drawRoundRect(box.left + e, box.top + e, box.right - e, box.bottom - e, r, r, paint)
        }
    }

    fun card(context: Context, radiusDp: Float = 24f, fill: Int? = null, solid: Boolean = false, topOnly: Boolean = false, base: Int? = null): Drawable {
        val p = palette(context)
        if (nothing(context)) return DotArt.Box(context, fill ?: p.card, p.outline, radiusDp, topOnly, solid, base)
        // Glass: the design's glass surface; a sheet is nearly opaque, or its content would show through.
        if (p.glass) {
            if (!topOnly) return GlassDrawable(context, radiusDp, flat = true)
            val sheetRgb = if (palette(context).glassLight) 0xF4F6FF else if (palette(context).id == PaletteStore.DARK) 0x20222E else if (glassGradient(context)) 0x0F0F22 else 0x181818
            return GlassDrawable(context, radiusDp, topOnly = true, base = sheetRgb or (if (windowBlur(context)) 0xC4000000.toInt() else 0xF2000000.toInt()))
        }
        // Material: a tonal surface, no outline.
        if (material(context)) return shape(context, if (fill == null || fill == p.card) p.container else fill, null, radiusDp).apply {
            if (topOnly) { val r = dp(context, radiusDp).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
        return shape(context, fill ?: p.card, p.outline, radiusDp).apply {
            if (topOnly) { val r = dp(context, radiusDp).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        }
    }

    /** Icon button / framed control: `card` fill, `outline` stroke, 14dp radius (SPEC section 2). */
    fun iconButton(context: Context, radiusDp: Float = 14f): Drawable = if (palette(context).glass && !nothing(context)) GlassDrawable(context, FULL, flat = true) else card(context, if (material(context)) FULL else radiusDp)

    /** Material style: a list's outer and inner corners, and a selected list item's corners (M3 expressive list). */
    const val LIST_OUTER = 16f
    const val LIST_INNER = 4f

    /** A radius above half of any control's height: a full pill or circle. */
    const val FULL = 999f

    private var snapshot: android.graphics.Bitmap? = null

    /** recreate() with a cross-fade: the old screen is kept as a picture, drawn over the new one and faded out ([fadeInFromSnapshot]). */
    /** True from [recreateFaded] until the new screen has resumed: it must not run its entrance, or it ghosts over the fading picture. */
    @Volatile var justRecreated = false

    fun recreateFaded(a: Activity) {
        justRecreated = true
        val v = a.window.decorView
        snapshot = if (v.width > 0 && v.height > 0)
            android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { v.draw(android.graphics.Canvas(it)) }
        else null
        a.recreate()
    }

    /** Where the last tap landed (screen pixels): a rebuilt screen is revealed from there. */
    @Volatile var lastTapX = -1f
    @Volatile var lastTapY = -1f

    /**
     * Call at the end of onCreate: the picture [recreateFaded] left is wiped away by a circle that grows from the last tap, so the
     * new theme appears inside it. A dissolve between a dark and a light screen passed through a grey flash; this does not.
     */
    fun fadeInFromSnapshot(a: Activity) {
        val bmp = snapshot ?: return
        snapshot = null
        val decor = a.window.decorView as android.view.ViewGroup
        val cx = if (lastTapX >= 0f) lastTapX else bmp.width / 2f
        val cy = if (lastTapY >= 0f) lastTapY else bmp.height / 2f
        val far = Math.hypot(maxOf(cx, bmp.width - cx).toDouble(), maxOf(cy, bmp.height - cy).toDouble()).toFloat()
        val path = android.graphics.Path()
        val cover = object : View(a) {
            var radius = 0f
            override fun onDraw(c: android.graphics.Canvas) {
                path.reset(); path.addCircle(cx, cy, radius, android.graphics.Path.Direction.CW)
                c.save()
                c.clipOutPath(path)
                c.drawBitmap(bmp, 0f, 0f, null)
                c.restore()
            }
        }.apply { isClickable = true } // swallows taps while it wipes
        decor.addView(cover, android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        android.animation.ValueAnimator.ofFloat(0f, far).apply {
            duration = 380
            interpolator = android.view.animation.DecelerateInterpolator(1.4f)
            addUpdateListener { cover.radius = it.animatedValue as Float; cover.invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(an: android.animation.Animator) { decor.removeView(cover); bmp.recycle() }
            })
            start()
        }
    }

    /** No ripple: the view sinks a little under the finger and springs back, like a real button. Listener returns false, so clicks still work. */
    fun sinkOnPress(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.92f).scaleY(0.92f)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f)).setDuration(260).start()
            }
            false
        }
    }

    /** Press ripple in `text` at low alpha, over [content] (or bounded by the view when null). */
    fun ripple(context: Context, content: android.graphics.drawable.Drawable? = null): RippleDrawable {
        val p = palette(context)
        return RippleDrawable(
            ColorStateList.valueOf(Palette.withAlpha(p.text, 0.12f)), content,
            if (content == null) ColorDrawable(p.text) else null
        )
    }

    /** Screen padding (16dp on every side) plus the system bar insets. */
    fun screenPadding(view: View, sideDp: Float = 16f, vertDp: Float = 16f) {
        val side = dp(view.context, sideDp)
        val vert = dp(view.context, vertDp)
        // Set now, so the screen keeps its 16dp margin when the window applies the bars to the
        // decor and never dispatches them to the content (seen on API 34 with a nested ScrollView).
        view.setPadding(side, vert, side, vert)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(side + bars.left, vert + bars.top, side + bars.right, vert + bars.bottom)
            insets
        }
    }

    /** Small chip button; the active one is an `accent` fill. */
    fun chip(context: Context, active: Boolean): Drawable {
        val p = palette(context)
        if (nothing(context)) return DotArt.Box(context, p.card, if (active) p.accent else p.outline, 10f)
        // Material: an M3 filter chip, 8dp corners.
        if (material(context)) return if (active) shape(context, p.accent, null, 8f) else shape(context, p.container, p.outline, 8f)
        return if (active) shape(context, p.accent, null, 10f) else shape(context, p.card, p.outline, 10f)
    }

    /** Bottom sheet window: rounded top corners only. */
    /** Glass: the sheet floats, inset from the screen edges, every corner rounded (Apple's Liquid Glass sheet). */
    fun floatingSheet(context: Context): Drawable {
        val rgb = if (palette(context).glassLight) 0xF4F6FF else if (palette(context).id == PaletteStore.DARK) 0x20222E else if (glassGradient(context)) 0x0F0F22 else 0x181818
        return GlassDrawable(context, 34f, floating = true, base = rgb or (if (Build.VERSION.SDK_INT >= 31) 0x99000000.toInt() else 0xF6000000.toInt()))
    }

    fun sheet(context: Context): Drawable = card(context, if (material(context)) 28f else 20f, topOnly = true, base = palette(context).background)


    /** Thumb / track tint lists for a platform Switch (derived colours). */
    fun switchTints(context: Context): Pair<ColorStateList, ColorStateList> {
        val p = palette(context)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        // Nothing style on a light palette: a card-coloured dot thumb has no shadow and vanished
        // into the page, so it takes textSecondary there.
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
