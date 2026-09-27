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

    /** Default order (WIDGETS.md 5). */
    val MODES = listOf(
        Mode("low", "ANC-Light", R.string.widget_mode_anc_low, R.string.anc_mode_low, R.drawable.ic_mode_anc_low),
        Mode("med", "ANC-Medium", R.string.widget_mode_anc_medium, R.string.anc_mode_medium, R.drawable.ic_mode_anc_medium),
        Mode("high", "ANC-Deep", R.string.widget_mode_anc_high, R.string.anc_mode_high, R.drawable.ic_mode_anc_high),
        Mode("smart", AncModes.SMART, R.string.widget_mode_anc_smart, R.string.anc_mode_smart, R.drawable.ic_mode_anc_smart),
        Mode("trans", "Transparency", R.string.anc_seg_trans, R.string.anc_seg_trans, R.drawable.ic_mode_transparency),
        Mode("adapt", "Adaptive", R.string.anc_seg_adapt, R.string.anc_seg_adapt, R.drawable.ic_mode_adaptive),
        Mode("off", "Off", R.string.anc_seg_off, R.string.anc_seg_off, R.drawable.ic_mode_off)
    )
    private val DEFAULT_ON = setOf("low", "med", "high", "trans")
    const val MIN_ON = 2
    /** The mode list has six cells; seven modes exist since Smart (2026-09-27). */
    const val MAX_ON = 6
    const val LIST_TIMEOUT_MS = 5_000L
    /** How long a 2x2 tap waits for a second one in double-tap mode. */
    const val DOUBLE_TAP_MS = 400L
    /** How long the controls page stays after a mode or low latency change. */
    const val BACK_TO_BATTERY_MS = 100L

    private const val KEY_TAP_LIST = "widgetTapList"
    private const val KEY_ORDER = "widgetModeOrder"
    private const val KEY_ON = "widgetModesOn"
    private const val KEY_LOW_LATENCY = "widgetLowLatency"
    private const val KEY_OPEN_APP = "widgetOpenApp"
    private const val KEY_LIST_AT = "widgetListAt_"
    private const val KEY_DOUBLE_TAP = "widgetDoubleTap"
    private const val KEY_PAGE = "widgetPage_"
    private const val KEY_CHILD = "widgetChild_"
    private const val KEY_MODE = "widgetMode_"
    private const val KEY_SLOT = "widgetModeSlot_"

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    private fun set(c: Context, edit: (android.content.SharedPreferences.Editor) -> Unit) {
        prefs(c).edit().also(edit).apply()
        QuickBudsWidget.refreshAll(c)
    }

    /** The mode a stored ANC name shows as; an ANC name without a known level counts as Medium. */
    fun modeOf(ancMode: String): Mode =
        MODES.firstOrNull { it.store == ancMode } ?: MODES.first { it.key == if (ancMode.isEmpty()) "off" else "med" }

    /** True: the mode button opens the list. False (default): it steps to the next mode. */
    fun tapOpensList(c: Context) = prefs(c).getBoolean(KEY_TAP_LIST, false)
    fun setTapOpensList(c: Context, v: Boolean) = set(c) { it.putBoolean(KEY_TAP_LIST, v) }

    /** All modes in the user's order, checked or not. */
    fun order(c: Context): List<Mode> {
        val keys = prefs(c).getString(KEY_ORDER, null)?.split(",").orEmpty()
        return keys.mapNotNull { k -> MODES.firstOrNull { it.key == k } } + MODES.filter { it.key !in keys }
    }
    fun setOrder(c: Context, modes: List<Mode>) = set(c) { it.putString(KEY_ORDER, modes.joinToString(",") { m -> m.key }) }

    fun enabledKeys(c: Context): Set<String> = prefs(c).getStringSet(KEY_ON, null) ?: DEFAULT_ON
    fun setEnabledKeys(c: Context, keys: Set<String>) = set(c) { it.putStringSet(KEY_ON, keys.toSet()) }

    /**
     * The checked modes the connected buds have ([AncModes]), in order: what both the cycle and the
     * list use. None checked there: every mode they have.
     */
    fun enabled(c: Context): List<Mode> {
        val anc = AncModes.of(c)
        val have = order(c).filter { anc.supports(it.store) }
        return enabledKeys(c).let { on -> have.filter { it.key in on } }.ifEmpty { have }.take(MAX_ON)
    }

    /** The mode after [current] among the checked ones, wrapping; the first one if [current] is not checked. */
    fun next(c: Context, current: Mode): Mode {
        val on = enabled(c).ifEmpty { return current }
        val i = on.indexOfFirst { it.key == current.key }
        return on[(i + 1) % on.size]
    }

    fun lowLatencyShown(c: Context) = prefs(c).getBoolean(KEY_LOW_LATENCY, true)
    fun setLowLatencyShown(c: Context, v: Boolean) = set(c) { it.putBoolean(KEY_LOW_LATENCY, v) }

    fun openAppOnTap(c: Context) = prefs(c).getBoolean(KEY_OPEN_APP, false)
    fun setOpenAppOnTap(c: Context, v: Boolean) = set(c) { it.putBoolean(KEY_OPEN_APP, v) }

    /** True: a double tap switches a widget's pages (every size). False (default): the swap button does. */
    fun doubleTapSwaps(c: Context) = prefs(c).getBoolean(KEY_DOUBLE_TAP, false)
    fun setDoubleTapSwaps(c: Context, v: Boolean) = set(c) { it.putBoolean(KEY_DOUBLE_TAP, v) }

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

    /**
     * Which of widget [id]'s two mode-button copies shows mode [key] (0 or 1), and whether it just
     * changed to it (the renderer then flips). The first time: the stored copy, no flip.
     */
    fun modeSlot(c: Context, id: Int, key: String): Pair<Int, Boolean> {
        val p = prefs(c)
        val last = p.getString(KEY_MODE + id, null)
        val slot = p.getInt(KEY_SLOT + id, 0)
        if (last == key) return slot to false
        val next = if (last == null) slot else 1 - slot
        p.edit().putString(KEY_MODE + id, key).putInt(KEY_SLOT + id, next).apply()
        return next to (last != null)
    }

    /** Drops everything stored for widget [id] (removed from the home screen). */
    fun forget(c: Context, id: Int) {
        prefs(c).edit().apply {
            for (k in listOf(KEY_LIST_AT, KEY_PAGE, KEY_CHILD, KEY_MODE, KEY_SLOT)) remove(k + id)
        }.apply()
    }

    /** When widget [id]'s list was opened, 0 when closed. */
    fun listOpenedAt(c: Context, id: Int) = prefs(c).getLong(KEY_LIST_AT + id, 0L)

    /** Open for less than [LIST_TIMEOUT_MS]: an older stamp (the process died before the close ran) reads as closed. */
    fun listOpen(c: Context, id: Int) = System.currentTimeMillis() - listOpenedAt(c, id) < LIST_TIMEOUT_MS

    fun setListOpenedAt(c: Context, id: Int, at: Long) {
        prefs(c).edit().apply { if (at == 0L) remove(KEY_LIST_AT + id) else putLong(KEY_LIST_AT + id, at) }.apply()
    }
}
