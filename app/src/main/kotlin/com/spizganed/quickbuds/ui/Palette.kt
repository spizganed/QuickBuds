package com.spizganed.quickbuds.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The six colour tokens of one preset.
 *
 * Built-in presets are NOT stored as literals here: their values live once, in
 * values/themes.xml, and are read back from the compiled style (see [PaletteStore.builtIn]).
 * Custom presets exist only at runtime and are stored as JSON.
 */
data class Palette(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val background: Int,
    val card: Int,
    val accent: Int,
    val text: Int,
    val textSecondary: Int,
    val outline: Int
) {
    val isLight get() = luminance(background) > 0.5

    /** The six tokens in display order, for swatches and the colour editor. */
    val tokens get() = intArrayOf(background, card, accent, text, textSecondary, outline)

    fun withToken(index: Int, color: Int): Palette = when (index) {
        0 -> copy(background = color)
        1 -> copy(card = color)
        2 -> copy(accent = color)
        3 -> copy(text = color)
        4 -> copy(textSecondary = color)
        else -> copy(outline = color)
    }

    // ---- Derived colours (SPEC section 1): computed, never user-editable ----

    /**
     * Label on an accent fill: the lighter of text / background when it reaches 3:1 (WCAG for
     * large text and icons), else whichever reads better. A plain "better" rule put black labels
     * on pure red (#FF0000: black 5.3, white 4.0), which reads as a mistake.
     */
    val onAccent: Int get() {
        val (light, dark) = if (luminance(text) > luminance(background)) text to background else background to text
        return if (contrast(light, accent) >= 3.0 || contrast(light, accent) >= contrast(dark, accent)) light else dark
    }

    /**
     * Toggle track: outline, lightened a little on dark themes (mockup #2E2E30 -> #3A3A3C). On a
     * light theme the outline is too faint to hold a white thumb, so it is darkened toward text.
     */
    val track get() = if (isLight) blend(outline, text, 0.14f) else blend(outline, text, 0.06f)

    /** Toggle thumb when off: text on dark themes; the card colour on light ones (a near-black
     *  thumb looked like a hole in the row). */
    val thumbOff get() = if (isLight) card else withAlpha(text, 0.9f)

    /** Disabled / empty elements: empty ring, placeholder glyphs. */
    val disabled get() = withAlpha(textSecondary, 0.5f)

    /** Background of an expanded row. */
    val expanded get() = blend(card, text, 0.05f)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("background", background).put("card", card).put("accent", accent)
        .put("text", text).put("textSecondary", textSecondary).put("outline", outline)

    companion object {
        fun fromJson(o: JSONObject) = Palette(
            o.getString("id"), o.getString("name"), false,
            o.getInt("background"), o.getInt("card"), o.getInt("accent"),
            o.getInt("text"), o.getInt("textSecondary"), o.getInt("outline")
        )

        /** WCAG 2 relative luminance. */
        fun luminance(c: Int): Double {
            fun ch(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
        }

        /** WCAG 2 contrast ratio, 1..21. */
        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a); val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        )

        fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
    }
}

/**
 * Stores the custom presets (at most [MAX_CUSTOM]) and the active preset id, in the one
 * prefs file ([ThemeRes.PREFS_NAME]).
 */
object PaletteStore {
    const val OLED = "oled"
    const val DARK = "dark"
    const val WHITE = "white"
    const val MAX_CUSTOM = 3

    private const val KEY_ACTIVE = "paletteActive"
    private const val KEY_CUSTOM = "paletteCustom"
    private const val KEY_ACCENT = "paletteAccent_"
    /** Match system (2026-09-27): White in light mode, [KEY_AUTO_DARK] in dark mode. */
    private const val KEY_AUTO = "paletteAuto"
    private const val KEY_AUTO_DARK = "paletteAutoDark"
    /** The pre-preset theme index (0 OLED, 1 Dark, 2 Light), migrated once. */
    private const val KEY_LEGACY = "theme"

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun builtInIds() = listOf(OLED, DARK, WHITE)

    fun activeId(c: Context): String {
        val p = prefs(c)
        if (p.getBoolean(KEY_AUTO, false)) return if (systemLight(c)) WHITE else autoDark(c)
        p.getString(KEY_ACTIVE, null)?.let { return it }
        val migrated = when (p.getInt(KEY_LEGACY, 0)) { 1 -> DARK; 2 -> WHITE; else -> OLED }
        p.edit().putString(KEY_ACTIVE, migrated).remove(KEY_LEGACY).apply()
        return migrated
    }

    /** The system's own light / dark setting, read from the application so it follows a change. */
    fun systemLight(c: Context) = (c.applicationContext.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

    fun auto(c: Context) = prefs(c).getBoolean(KEY_AUTO, false)

    /** The dark built-in Match system uses: OLED Black or Classic Dark. */
    fun autoDark(c: Context) = prefs(c).getString(KEY_AUTO_DARK, null)?.takeIf { it == DARK } ?: OLED

    /** Turning it on keeps the current dark built-in as the dark half; off keeps what shows now. */
    fun setAuto(c: Context, on: Boolean) {
        val current = activeId(c)
        prefs(c).edit().apply {
            if (on && current == DARK) putString(KEY_AUTO_DARK, DARK)
            if (!on) putString(KEY_ACTIVE, current)
            putBoolean(KEY_AUTO, on)
        }.apply()
        ThemeRes.invalidate()
        com.spizganed.quickbuds.widget.AncWidgetProvider.refreshAll(c)
    }

    /**
     * Picks a preset. Under Match system a dark built-in becomes its dark half; any other pick
     * (White, a custom preset) turns Match system off.
     */
    fun setActive(c: Context, id: String) {
        val edit = prefs(c).edit()
        if (auto(c) && (id == OLED || id == DARK)) edit.putString(KEY_AUTO_DARK, id)
        else edit.putBoolean(KEY_AUTO, false).putString(KEY_ACTIVE, id)
        edit.apply()
        ThemeRes.invalidate()
        com.spizganed.quickbuds.widget.AncWidgetProvider.refreshAll(c)
    }

    /** The active preset. A dangling custom id (deleted preset) falls back to OLED Black. */
    fun active(c: Context): Palette {
        val id = activeId(c)
        if (id in builtInIds()) return builtIn(c, id)
        return custom(c).firstOrNull { it.id == id } ?: builtIn(c, OLED)
    }

    /** Reads a built-in preset from its compiled style, so its values live only in themes.xml. */
    fun builtIn(c: Context, id: String): Palette {
        // Resolved attribute by attribute: obtainStyledAttributes() needs a sorted id array.
        val theme = c.resources.newTheme().apply { applyStyle(ThemeRes.styleFor(id), true) }
        val tv = TypedValue()
        val t = ThemeRes.TOKEN_ATTRS.map { theme.resolveAttribute(it, tv, true); tv.data }
        return Palette(id, ThemeRes.builtInName(c, id), true, t[0], t[1], accentOverride(c, id) ?: t[2], t[3], t[4], t[5])
    }

    /** The user's accent for a built-in preset (2026-09-26), or null for the style's own. */
    fun accentOverride(c: Context, id: String): Int? {
        val p = prefs(c)
        val key = KEY_ACCENT + id
        return if (p.contains(key)) p.getInt(key, 0) else null
    }

    /** Sets a built-in preset's accent; null (or the style's own value) removes the override. */
    fun setAccentOverride(c: Context, id: String, color: Int?) {
        val key = KEY_ACCENT + id
        prefs(c).edit().remove(key).apply()
        // Read after the removal, so this is the style's own accent.
        val styleAccent = builtIn(c, id).accent
        if (color != null && color != styleAccent) prefs(c).edit().putInt(key, color).apply()
        ThemeRes.invalidate()
        com.spizganed.quickbuds.widget.AncWidgetProvider.refreshAll(c)
    }

    fun custom(c: Context): List<Palette> {
        val raw = prefs(c).getString(KEY_CUSTOM, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Palette.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Inserts or replaces [p] by id. Returns false when that would exceed [MAX_CUSTOM]. */
    fun saveCustom(c: Context, p: Palette): Boolean {
        val list = custom(c).toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else if (list.size >= MAX_CUSTOM) return false else list.add(p)
        write(c, list)
        return true
    }

    /** Deletes a custom preset; if it was active, OLED Black takes over. */
    fun deleteCustom(c: Context, id: String) {
        write(c, custom(c).filterNot { it.id == id })
        if (activeId(c) == id) setActive(c, OLED)
    }

    private fun write(c: Context, list: List<Palette>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString(KEY_CUSTOM, arr.toString()).apply()
        ThemeRes.invalidate()
        com.spizganed.quickbuds.widget.AncWidgetProvider.refreshAll(c)
    }
}
