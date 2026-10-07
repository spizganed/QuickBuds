package com.spizganed.quickbuds.widget

import android.content.Context
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Widget settings, shared by every placed widget. Each setter
 * repaints all widgets. Also holds the per-widget "mode list is open" stamp (WIDGETS.md 4).
 */
object WidgetSettings {

    /**
     * One noise mode. [key] is the target [WidgetActionReceiver] takes, [store] the name
     * [WidgetStateStore] keeps. [name] is the full name ("ANC Low"), [short] the grid label ("Low").
     */
    class Mode(val key: String, val store: String, val name: Int, val short: Int, val icon: Int)

    val MODES = listOf(
        Mode("low", "ANC-Light", R.string.widget_mode_anc_low, R.string.anc_mode_low, R.drawable.ic_mode_anc_low),
        Mode("med", "ANC-Medium", R.string.widget_mode_anc_medium, R.string.anc_mode_medium, R.drawable.ic_mode_anc_medium),
        Mode("high", "ANC-Deep", R.string.widget_mode_anc_high, R.string.anc_mode_high, R.drawable.ic_mode_anc_high),
        Mode("smart", AncModes.SMART, R.string.widget_mode_anc_smart, R.string.anc_mode_smart, R.drawable.ic_mode_anc_smart),
        Mode("trans", "Transparency", R.string.anc_seg_trans, R.string.anc_seg_trans, R.drawable.ic_mode_transparency),
        Mode("adapt", "Adaptive", R.string.anc_seg_adapt, R.string.anc_seg_adapt, R.drawable.ic_mode_adaptive),
        Mode("off", "Off", R.string.anc_seg_off, R.string.anc_seg_off, R.drawable.ic_mode_off)
    )
    /** How long a tap waits for a second one (400 ms felt slow). */
    const val DOUBLE_TAP_MS = 200L

    private const val KEY_LIST_AT = "widgetListAt_"
    private const val KEY_PAGE = "widgetPage_"
    private const val KEY_CHILD = "widgetChild_"
    private const val KEY_BUTTONS = "widgetButtons_"

    /**
     * The controls page's buttons, in setup screen order: "anc" opens the level list, "trans" / "adapt" / "off"
     * select a mode, "ll" toggles low latency, a [FEATURE_BUTTONS] key toggles that `0x0403` switch.
     */
    val BUTTONS = listOf("anc", "trans", "adapt", "off", "ll", "wind")
    /** Feature buttons: key -> `0x0403` id. */
    val FEATURE_BUTTONS = mapOf("wind" to OpoProtocol.FEATURE_WIND_NOISE)
    /** Buttons a widget shows before its setup screen saves a choice. */
    val DEFAULT_BUTTONS = listOf("anc", "trans", "adapt", "ll")
    /** Slots on the controls page. */
    const val MAX_BUTTONS = 4

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    /** The mode a stored ANC name shows as; an ANC name without a known level counts as Medium. */
    fun modeOf(ancMode: String): Mode =
        MODES.firstOrNull { it.store == ancMode } ?: MODES.first { it.key == if (ancMode.isEmpty()) "off" else "med" }

    /**
     * The controls page's ANC level picker: the levels these buds have, no Off (the lit
     * level turns ANC off, as a lit T or A button does).
     */
    fun ancPicker(c: Context): List<Mode> {
        val anc = AncModes.of(c)
        return MODES.filter { it.key in PICKER && anc.supports(it.store) }
    }
    private val PICKER = setOf("low", "med", "high", "smart")

    /**
     * True: the Nothing style (no boxes, tighter inset, Nothing OS's Ndot digits; layouts `widget_*_n`).
     * The app's style ([ThemeRes.nothing], set in Theme & colors), shared with the app.
     */
    fun nothingStyle(c: Context) = ThemeRes.nothing(c)

    /** The page widget [id] shows, BATTERY or CONTROLS; [default] is its provider's own. */
    fun page(c: Context, id: Int, default: QuickBudsWidget.Kind): QuickBudsWidget.Kind =
        prefs(c).getString(KEY_PAGE + id, null)?.let { runCatching { QuickBudsWidget.Kind.valueOf(it) }.getOrNull() } ?: default

    fun setPage(c: Context, id: Int, page: QuickBudsWidget.Kind?) {
        prefs(c).edit().apply { if (page == null) remove(KEY_PAGE + id) else putString(KEY_PAGE + id, page.name) }.apply()
    }

    /** The child widget [id] last showed (QuickBudsWidget.update: 0 battery, 1 controls, 2 list), -1 when unknown. */
    fun shownChild(c: Context, id: Int) = prefs(c).getInt(KEY_CHILD + id, -1)

    fun setShownChild(c: Context, id: Int, child: Int?) {
        prefs(c).edit().apply { if (child == null) remove(KEY_CHILD + id) else putInt(KEY_CHILD + id, child) }.apply()
    }

    /** Widget [id]'s buttons ([BUTTONS] keys), at most [MAX_BUTTONS]. */
    fun buttons(c: Context, id: Int): List<String> =
        prefs(c).getString(KEY_BUTTONS + id, null)?.split(',')?.filter { it in BUTTONS }?.take(MAX_BUTTONS) ?: DEFAULT_BUTTONS

    fun setButtons(c: Context, id: Int, keys: List<String>) {
        prefs(c).edit().putString(KEY_BUTTONS + id, keys.joinToString(",")).apply()
    }

    /** The connected buds have button [key]. */
    fun available(c: Context, key: String): Boolean {
        val anc = AncModes.of(c)
        return when (key) {
            "anc" -> ancPicker(c).isNotEmpty()
            "trans", "adapt" -> anc.supports(MODES.first { it.key == key }.store)
            "off" -> !anc.isEmpty
            "ll" -> true
            "wind" -> Capabilities.offered(c, OpoProtocol.FEATURE_WIND_NOISE, "windNoise")
            else -> false
        }
    }

    /** Feature [id] is on in the buds' last `0x810D` reply (the same prefs the connection manager keeps). */
    fun featureOn(c: Context, id: Int) = prefs(c).getString(Capabilities.KEY_FEATURES, null).orEmpty()
        .split(',').any { it == "$id=1" }

    /** Drops everything stored for widget [id] (removed from the home screen). */
    fun forget(c: Context, id: Int) {
        prefs(c).edit().apply {
            for (k in listOf(KEY_LIST_AT, KEY_PAGE, KEY_CHILD, KEY_BUTTONS)) remove(k + id)
        }.apply()
    }

    /** When widget [id]'s list was opened, 0 when closed. */
    fun listOpenedAt(c: Context, id: Int) = prefs(c).getLong(KEY_LIST_AT + id, 0L)

    fun listOpen(c: Context, id: Int) = listOpenedAt(c, id) != 0L

    fun setListOpenedAt(c: Context, id: Int, at: Long) {
        prefs(c).edit().apply { if (at == 0L) remove(KEY_LIST_AT + id) else putLong(KEY_LIST_AT + id, at) }.apply()
    }
}
