package com.spizganed.quickbuds.widget

import android.content.Context
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * Widget settings (design/widgets/WIDGETS.md 5), shared by every placed widget. Each setter
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
    /** How long a tap waits for a second one in double-tap mode ([USER] 2026-09-28: 400 ms felt slow). */
    const val DOUBLE_TAP_MS = 200L

    private const val KEY_LOW_LATENCY = "widgetLowLatency"
    private const val KEY_OPEN_APP = "widgetOpenApp"
    private const val KEY_LIST_AT = "widgetListAt_"
    private const val KEY_DOUBLE_TAP = "widgetDoubleTap"
    private const val KEY_NOTHING = "widgetStyleNothing"
    private const val KEY_PAGE = "widgetPage_"
    private const val KEY_CHILD = "widgetChild_"

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    private fun set(c: Context, edit: (android.content.SharedPreferences.Editor) -> Unit) {
        prefs(c).edit().also(edit).apply()
        QuickBudsWidget.refreshAll(c)
    }

    /** The mode a stored ANC name shows as; an ANC name without a known level counts as Medium. */
    fun modeOf(ancMode: String): Mode =
        MODES.firstOrNull { it.store == ancMode } ?: MODES.first { it.key == if (ancMode.isEmpty()) "off" else "med" }

    /**
     * The controls page's ANC level picker ([USER] 2026-09-28): the levels these buds have, no Off (the lit
     * level turns ANC off, as a lit T or A button does).
     */
    fun ancPicker(c: Context): List<Mode> {
        val anc = AncModes.of(c)
        return MODES.filter { it.key in PICKER && anc.supports(it.store) }
    }
    private val PICKER = setOf("low", "med", "high", "smart")

    fun lowLatencyShown(c: Context) = prefs(c).getBoolean(KEY_LOW_LATENCY, true)
    fun setLowLatencyShown(c: Context, v: Boolean) = set(c) { it.putBoolean(KEY_LOW_LATENCY, v) }

    fun openAppOnTap(c: Context) = prefs(c).getBoolean(KEY_OPEN_APP, false)
    fun setOpenAppOnTap(c: Context, v: Boolean) = set(c) { it.putBoolean(KEY_OPEN_APP, v) }

    /** True: a double tap switches a widget's pages (every size). False (default): the swap button does. */
    fun doubleTapSwaps(c: Context) = prefs(c).getBoolean(KEY_DOUBLE_TAP, false)
    fun setDoubleTapSwaps(c: Context, v: Boolean) = set(c) { it.putBoolean(KEY_DOUBLE_TAP, v) }

    /**
     * True: the Nothing style (no boxes, tighter inset, Nothing OS's Ndot digits; layouts `widget_*_n`).
     * False (default): Classic ([USER] 2026-09-28: optional, not the default).
     */
    fun nothingStyle(c: Context) = prefs(c).getBoolean(KEY_NOTHING, false)
    fun setNothingStyle(c: Context, v: Boolean) = set(c) { it.putBoolean(KEY_NOTHING, v) }

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

    /** Drops everything stored for widget [id] (removed from the home screen). */
    fun forget(c: Context, id: Int) {
        prefs(c).edit().apply {
            for (k in listOf(KEY_LIST_AT, KEY_PAGE, KEY_CHILD)) remove(k + id)
        }.apply()
    }

    /** When widget [id]'s list was opened, 0 when closed. */
    fun listOpenedAt(c: Context, id: Int) = prefs(c).getLong(KEY_LIST_AT + id, 0L)

    fun listOpen(c: Context, id: Int) = listOpenedAt(c, id) != 0L

    fun setListOpenedAt(c: Context, id: Int, at: Long) {
        prefs(c).edit().apply { if (at == 0L) remove(KEY_LIST_AT + id) else putLong(KEY_LIST_AT + id, at) }.apply()
    }
}
