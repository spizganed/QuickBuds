package com.spizganed.quickbuds.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.WidgetActions
import com.spizganed.quickbuds.ui.BudsStatusView
import com.spizganed.quickbuds.ui.MainActivity
import com.spizganed.quickbuds.ui.Palette
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * The home-screen widgets (design/widgets/WIDGETS.md, 2026-09-27). One provider per size, so each
 * has its own picker entry and cell size, and the old class names keep placed widgets alive:
 *
 *  - [BatteryWidgetProvider] 2x2
 *  - [LargeWidgetProvider]   3x3, the 2x2 layout scaled up
 *  - [AncWidgetProvider]     4x2 (was 3x2), three battery panels in a row
 *
 * Fixed sizes, not resizable ([USER] 2026-09-27). The 2x2 controls widget (SmallWidgetProvider) is gone.
 *
 * Drawn in the ACTIVE palette at update time: white shapes tinted with ImageView.setColorFilter
 * (every API level) and ring bitmaps drawn here. Disconnected, every size shows only the main
 * screen's Connect chip ([USER] 2026-09-27). The mode list is a ViewFlipper child, opened and closed by
 * [WidgetActionReceiver] (stamp in [WidgetSettings]); it never opens an Activity.
 *
 * Every size is one widget with two pages (battery, controls; [USER] 2026-09-27), stored per
 * widget id and swapped by a swap button or a double tap ([WidgetSettings.doubleTapSwaps]). The
 * pages slide (`w_slide0` battery on the left, `w_slide1` controls on the right); the mode list slides over them (`w_pages`). The
 * mode button has two copies: a mode change fills the hidden one and flips to it ([modeButton]).
 */
open class QuickBudsWidget(private val kind: Kind) : AppWidgetProvider() {

    /**
     * The provider (size). BATTERY and CONTROLS double as the page names ([WidgetSettings.page]);
     * CONTROLS is a page name only since its 2x2 widget was removed.
     */
    enum class Kind(private val classic: Int, private val nothing: Int, val large: Boolean = false) {
        BATTERY(R.layout.widget_pages, R.layout.widget_pages_n),
        CONTROLS(R.layout.widget_pages, R.layout.widget_pages_n),
        COMBINED(R.layout.widget_pages_m, R.layout.widget_pages_m_n),
        LARGE(R.layout.widget_pages_l, R.layout.widget_pages_l_n, large = true);

        /** The layout for the chosen style ([WidgetSettings.nothingStyle]). */
        fun layout(c: Context) = if (WidgetSettings.nothingStyle(c)) nothing else classic
        val small get() = this == BATTERY || this == CONTROLS
        /** 2x2 and 3x3: the same layout at two scales. */
        val square get() = this != COMBINED
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, mgr, id, kind)
    }

    /** Resized or first sized: the 4x2 rings follow the real size ([ringDp]). */
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) {
        update(context, mgr, id, kind)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) WidgetSettings.forget(context, id)
    }

    /** A button's view ids: the tappable frame, its fill and stroke, its icon and its text. */
    private class Btn(val root: Int, val bg: Int, val stroke: Int, val icon: Int, val label: Int)

    companion object {

        private val providers = listOf(
            BatteryWidgetProvider::class.java to Kind.BATTERY,
            AncWidgetProvider::class.java to Kind.COMBINED,
            LargeWidgetProvider::class.java to Kind.LARGE
        )

        /** The mode button's two copies ([modeButton]); both tap through the frame `w_mode`. */
        private val MODE = listOf(
            Btn(R.id.w_mode, R.id.w_mode_bg0, R.id.w_mode_stroke0, R.id.w_mode_icon0, R.id.w_mode_name0),
            Btn(R.id.w_mode, R.id.w_mode_bg1, R.id.w_mode_stroke1, R.id.w_mode_icon1, R.id.w_mode_name1)
        )
        private val MODE_FILLS = intArrayOf(R.id.w_mode_fill0, R.id.w_mode_fill1)
        private val MODE_CONTENT = intArrayOf(R.id.w_mode_content0, R.id.w_mode_content1)
        private val MODE_HINT = intArrayOf(R.id.w_mode_hint0, R.id.w_mode_hint1)
        private val MODE_CAPTION = intArrayOf(R.id.w_mode_caption0, R.id.w_mode_caption1)
        private val LL = Btn(R.id.w_ll, R.id.w_ll_bg, R.id.w_ll_stroke, R.id.w_ll_icon, R.id.w_ll_label)
        private val CONN = Btn(R.id.w_conn, R.id.w_conn_bg, R.id.w_conn_stroke, R.id.w_conn_dot, R.id.w_conn_text)
        private val CELLS = listOf(
            Btn(R.id.w_cell0, R.id.w_cell0_bg, R.id.w_cell0_stroke, R.id.w_cell0_icon, R.id.w_cell0_label),
            Btn(R.id.w_cell1, R.id.w_cell1_bg, R.id.w_cell1_stroke, R.id.w_cell1_icon, R.id.w_cell1_label),
            Btn(R.id.w_cell2, R.id.w_cell2_bg, R.id.w_cell2_stroke, R.id.w_cell2_icon, R.id.w_cell2_label),
            Btn(R.id.w_cell3, R.id.w_cell3_bg, R.id.w_cell3_stroke, R.id.w_cell3_icon, R.id.w_cell3_label),
            Btn(R.id.w_cell4, R.id.w_cell4_bg, R.id.w_cell4_stroke, R.id.w_cell4_icon, R.id.w_cell4_label),
            Btn(R.id.w_cell5, R.id.w_cell5_bg, R.id.w_cell5_stroke, R.id.w_cell5_icon, R.id.w_cell5_label)
        )

        /** Battery panel ids per slot (0 left, 1 case, 2 right): panel fill, ring, percentage, label. */
        private val PANELS = listOf(
            intArrayOf(R.id.w_panel_left_bg, R.id.w_ring_left, R.id.w_pct_left, R.id.w_label_left),
            intArrayOf(R.id.w_panel_case_bg, R.id.w_ring_case, R.id.w_pct_case, R.id.w_label_case),
            intArrayOf(R.id.w_panel_right_bg, R.id.w_ring_right, R.id.w_pct_right, R.id.w_label_right)
        )

        /** Repaints every placed widget of every size: state, palette or settings changed. */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            for ((cls, kind) in providers) {
                for (id in mgr.getAppWidgetIds(ComponentName(context, cls))) update(context, mgr, id, kind)
            }
        }

        /**
         * ViewFlipper.setDisplayedChild replays its animation even for the child already shown, and
         * the service resends the whole cached views to the host on every update (partial ones too),
         * so sending it each time made every refresh flash. Each child's visibility is set directly
         * instead (no animation, right after a host re-inflation too); setDisplayedChild goes out
         * only in the update that changes that flipper's child: `w_slide0` / `w_slide1` slide the pages,
         * `w_pages` fades the mode list in and out. Shown child: 0 battery, 1 controls, 2 list.
         */
        private fun update(context: Context, mgr: AppWidgetManager, id: Int, kind: Kind) {
            // A throw here would leave the host showing "Can't load widget" with no trace.
            try {
                val (v, child) = build(context, WidgetStateStore.read(context), kind, id)
                if (child >= 0) {
                    val prev = WidgetSettings.shownChild(context, id)
                    // The list opens from the controls page, so under it the slide shows controls.
                    val outer = if (child == 2) 1 else 0
                    val inner = if (child == 2) 1 else child
                    if (prev < 0 || (prev == 2) != (child == 2)) v.setDisplayedChild(R.id.w_pages, outer)
                    // One flipper per page (child 1 = the page, 0 = empty), so battery moves on the left, controls on the right.
                    if (prev < 0 || (if (prev == 2) 1 else prev) != inner) {
                        v.setDisplayedChild(R.id.w_slide0, if (inner == 0) 1 else 0)
                        v.setDisplayedChild(R.id.w_slide1, if (inner == 1) 1 else 0)
                    }
                    if (prev != child) WidgetSettings.setShownChild(context, id, child)
                    v.setViewVisibility(R.id.w_content, if (outer == 0) View.VISIBLE else View.GONE)
                    v.setViewVisibility(R.id.w_page2, if (outer == 1) View.VISIBLE else View.GONE)
                    v.setViewVisibility(R.id.w_page0, if (inner == 0) View.VISIBLE else View.GONE)
                    v.setViewVisibility(R.id.w_page1, if (inner == 1) View.VISIBLE else View.GONE)
                }
                mgr.updateAppWidget(id, v)
            } catch (t: Throwable) {
                Log.e("BudsWidget", "update failed for $kind #$id", t)
            }
        }

        /** The views and the flipper child to show (-1: the disconnected layout, no flippers); [update] decides whether to send the child. */
        private fun build(context: Context, state: WidgetStateStore.State, provider: Kind, id: Int): Pair<RemoteViews, Int> {
            val p = ThemeRes.palette(context)
            if (!state.connected) return disconnected(context, p, provider) to -1

            val page = WidgetSettings.page(context, id, Kind.BATTERY)
            val list = page == Kind.CONTROLS && WidgetSettings.listOpen(context, id)
            val v = RemoteViews(context.packageName, provider.layout(context))
            // The host reapplies an update with the same layout onto the views it has, so switching
            // a flipper's child animates. The flip side: every state set here must be set both ways
            // (visibility, click), or the previous update's sticks.
            val child = if (list) 2 else if (page == Kind.BATTERY) 0 else 1
            v.setImageViewResource(R.id.w_bg, if (provider.large) R.drawable.widget_bg_l else R.drawable.widget_bg)
            v.setInt(R.id.w_bg, "setColorFilter", p.card)
            // Double-tap mode: every tap on a page carries the other page, so a second tap swaps.
            val other = if (page == Kind.BATTERY) Kind.CONTROLS else Kind.BATTERY
            val swap = if (!list && WidgetSettings.doubleTapSwaps(context)) other else null
            v.setOnClickPendingIntent(R.id.w_root, when {
                swap != null -> receiverPI(context, WidgetActions.ACTION_OPEN_APP, id, swap = swap)
                WidgetSettings.openAppOnTap(context) -> openAppPI(context)
                else -> null
            })
            swapButtons(context, v, p, page, id, other, shown = !list && swap == null)

            // Both pages and the list are filled every time, so the one sliding or fading out still
            // shows current values (a pick's highlight while the list closes).
            grid(context, v, p, state, provider, id)
            battery(context, v, p, state, provider, id)
            if (!list) controls(context, v, p, state, provider, id, swap)
            return v to child
        }

        /**
         * The swap button: on the battery page at the end of the case bar (2x2, 3x3) or top-right
         * (4x2), top-right on the controls page.
         */
        private fun swapButtons(context: Context, v: RemoteViews, p: Palette, kind: Kind, id: Int, other: Kind, shown: Boolean) {
            for ((root, icon, page) in listOf(Triple(R.id.w_swap_b, R.id.w_swap_b_icon, Kind.BATTERY), Triple(R.id.w_swap, R.id.w_swap_icon, Kind.CONTROLS))) {
                val on = shown && kind == page
                v.setViewVisibility(root, if (on) View.VISIBLE else View.GONE)
                if (!on) continue
                icon(context, v, icon, R.drawable.ic_swap_page, 16f, 16f / 9)
                v.setInt(icon, "setColorFilter", p.textSecondary)
                v.setContentDescription(root, context.getString(R.string.widget_swap_desc))
                v.setOnClickPendingIntent(root, receiverPI(context, WidgetActions.ACTION_PAGE_SWAP, id, swap = other))
            }
        }

        /**
         * Battery panel colour: `card` lightened ~4% toward `text` (WIDGETS.md 2). The Nothing style has
         * no boxes: `card` itself, so panels melt into the widget background.
         */
        private fun panelColor(c: Context, p: Palette) =
            if (WidgetSettings.nothingStyle(c)) p.card else Palette.blend(p.card, p.text, 0.04f)

        /**
         * Selected: accent fill and stroke. Otherwise the panel colour with an `outline` stroke (Classic)
         * or no box at all (Nothing).
         */
        private fun paint(c: Context, v: RemoteViews, b: Btn, p: Palette, selected: Boolean, large: Boolean) {
            v.setImageViewResource(b.bg, if (large) R.drawable.widget_panel_l else R.drawable.widget_panel)
            v.setInt(b.bg, "setColorFilter", if (selected) p.accent else panelColor(c, p))
            v.setImageViewResource(b.stroke, if (large) R.drawable.widget_panel_stroke_l else R.drawable.widget_panel_stroke)
            v.setInt(b.stroke, "setColorFilter", if (selected) p.accent else if (WidgetSettings.nothingStyle(c)) p.card else p.outline)
        }

        /** Paints [b] with [icon] and [text] in the on-accent or secondary colour. */
        private fun content(context: Context, v: RemoteViews, b: Btn, p: Palette, selected: Boolean, icon: Int, text: CharSequence?, iconDp: Float, pitch: Float = DOT_DP) {
            val fg = if (selected) p.onAccent else p.textSecondary
            icon(context, v, b.icon, icon, iconDp, pitch)
            v.setInt(b.icon, "setColorFilter", fg)
            if (text == null) v.setViewVisibility(b.label, View.GONE)
            else {
                v.setViewVisibility(b.label, View.VISIBLE)
                v.setTextViewText(b.label, text)
                v.setTextColor(b.label, fg)
            }
        }

        private fun disconnected(context: Context, p: Palette, kind: Kind): RemoteViews {
            val v = RemoteViews(context.packageName, if (WidgetSettings.nothingStyle(context)) R.layout.widget_disconnected_n else R.layout.widget_disconnected)
            v.setImageViewResource(R.id.w_bg, if (kind.large) R.drawable.widget_bg_l else R.drawable.widget_bg)
            v.setInt(R.id.w_bg, "setColorFilter", p.card)
            paint(context, v, CONN, p, false, kind.large)
            content(context, v, CONN, p, false, R.drawable.ic_status_dot_empty, context.getString(R.string.conn_action_connect), 10f, 10f / 5)
            v.setContentDescription(CONN.root, context.getString(R.string.conn_off) + ". " + context.getString(R.string.conn_action_connect))
            v.setOnClickPendingIntent(CONN.root, connectPI(context))
            return v
        }

        private fun battery(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, kind: Kind, id: Int) {
            val ringDp = ringDp(context, kind, id)
            val levels = intArrayOf(state.leftBattery, state.caseBattery, state.rightBattery)
            val statuses = intArrayOf(state.leftStatus, -1, state.rightStatus)
            val names = intArrayOf(R.string.status_left, R.string.status_case, R.string.status_right)
            // 2x2 and 3x3 show the case as a bar under the two bud panels; the wide 4x2 has three panels.
            val caseBar = kind.square
            val slots = if (caseBar) listOf(0, 2) else listOf(0, 1, 2)
            for (slot in slots) {
                val (bgId, ringId, pct, label) = PANELS[slot].toList()
                v.setImageViewResource(bgId, if (kind.large) R.drawable.widget_panel_l else R.drawable.widget_panel)
                v.setInt(bgId, "setColorFilter", panelColor(context, p))
                v.setImageViewBitmap(ringId, ring(context, p, levels[slot], slot, statuses[slot], ringDp))
                pctText(v, pct, p, levels[slot])
                val inEar = statuses[slot] == 3 || statuses[slot] == 7
                val text = if (slot == 1) context.getString(names[slot]) else wearLabel(context, statuses[slot]) ?: context.getString(names[slot])
                v.setTextViewText(label, if (inEar && !WidgetSettings.nothingStyle(context)) semibold(text) else text)
                v.setTextColor(label, if (inEar) p.text else p.textSecondary)
            }
            if (caseBar) {
                v.setImageViewResource(R.id.w_bar_bg, if (kind.large) R.drawable.widget_panel_l else R.drawable.widget_panel)
                v.setInt(R.id.w_bar_bg, "setColorFilter", panelColor(context, p))
                icon(context, v, R.id.w_case_icon, R.drawable.ic_case, if (kind.large) 39f else 28f, (if (kind.large) 39f else 28f) / ROWS_CASE)
                v.setInt(R.id.w_case_icon, "setColorFilter", p.text)
                v.setImageViewBitmap(R.id.w_case_bar, bar(context, p, state.caseBattery, kind, id))
                pctText(v, R.id.w_pct_case, p, state.caseBattery)
            }
        }

        /**
         * The ring size. 2x2 / 3x3: drawn large enough and shrunk to the panel by the layout. 4x2:
         * the largest ring the panel holds at the widget's real size (portrait: min width, max
         * height), so ring and texts fill the panel as one centred group instead of a small ring
         * over a gap.
         */
        private fun ringDp(context: Context, kind: Kind, id: Int): Float {
            if (kind.square) return if (kind.large) 110f else 56f
            val o = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (w <= 0 || h <= 0) return 56f
            // Widget padding 12, panel padding 8; texts: percentage + label lines.
            val texts = 18f * 1.3f + 12f * 1.3f + 2f
            val byHeight = h - 12f - 8f - texts - 6f
            val byWidth = (w - 12f - 6f * 2) / 3 - 8f
            return minOf(byHeight, byWidth).coerceIn(28f, 120f)
        }

        /** Percentage, always `text`: nothing in the battery display changes colour by level ([USER] 2026-09-27). */
        private fun pctText(v: RemoteViews, id: Int, p: Palette, level: Int) {
            v.setTextViewText(id, if (level in 0..100) "$level%" else "—")
            v.setTextColor(id, p.text)
        }

        private fun semibold(text: String) = SpannableString(text).apply {
            setSpan(TypefaceSpan("sans-serif-medium"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun wearLabel(context: Context, status: Int): String? = when (status) {
            3, 7 -> context.getString(R.string.status_in_ear)
            4, 0 -> context.getString(R.string.status_in_case)
            -1 -> null
            else -> context.getString(R.string.status_out)
        }

        private fun modeControls(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, kind: Kind, id: Int, swap: Kind?) {
            val mode =WidgetSettings.modeOf(state.ancMode)
            val active = mode.key != "off"
            // Nothing: OFF in capitals, as in its mode list.
            val name = context.getString(mode.name).let { if (!active && WidgetSettings.nothingStyle(context)) it.uppercase() else it }
            val opensList = WidgetSettings.tapOpensList(context)
            val k = modeButton(context, v, id, mode.key)
            paint(context, v, MODE[k], p, active, kind.large)
            content(context, v, MODE[k], p, active, mode.icon, name, (if (kind.small) 40f else if (kind.large) 60f else 44f), (if (kind.small) 40f else if (kind.large) 60f else 44f) / ROWS_MODE)
            icon(context, v, MODE_HINT[k], if (opensList) R.drawable.ic_hint_list else R.drawable.ic_hint_cycle, 16f, 16f / 9)
            v.setInt(MODE_HINT[k], "setColorFilter", if (active) p.onAccent else p.textSecondary)
            // Nothing: no hint arrow, the dot name stands alone.
            v.setViewVisibility(MODE_HINT[k], if (WidgetSettings.nothingStyle(context)) View.GONE else View.VISIBLE)
            if (!kind.square) {
                v.setTextViewText(MODE_CAPTION[k], context.getString(R.string.anc_section))
                v.setTextColor(MODE_CAPTION[k], Palette.withAlpha(if (active) p.onAccent else p.textSecondary, 0.8f))
            }
            v.setContentDescription(R.id.w_mode, context.getString(
                if (opensList) R.string.widget_mode_desc_list else R.string.widget_mode_desc_cycle,
                context.getString(R.string.anc_section), name
            ))
            v.setOnClickPendingIntent(R.id.w_mode, receiverPI(context, WidgetActions.ACTION_MODE_TAP, id, swap = swap))
        }

        private fun controls(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, kind: Kind, id: Int, swap: Kind?) {
            // A model with no noise control has no mode button, and low latency fills the page
            // (Android 12+; older ones keep its own height). Both set both ways, see update().
            // The dp heights are the ones scripts/widget-layouts.py gives w_ll.
            val hasAnc = !AncModes.of(context).isEmpty
            v.setViewVisibility(R.id.w_mode, if (hasAnc) View.VISIBLE else View.GONE)
            if (Build.VERSION.SDK_INT >= 31) {
                if (hasAnc) v.setViewLayoutHeight(LL.root, if (kind.small) 48f else if (kind.large) 66f else 50f, TypedValue.COMPLEX_UNIT_DIP)
                else v.setViewLayoutHeight(LL.root, ViewGroup.LayoutParams.MATCH_PARENT.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            }
            if (hasAnc) modeControls(context, v, p, state, kind, id, swap)

            if (!WidgetSettings.lowLatencyShown(context)) {
                v.setViewVisibility(LL.root, View.GONE)
                return
            }
            v.setViewVisibility(LL.root, View.VISIBLE)
            paint(context, v, LL, p, state.gameMode, kind.large)
            content(context, v, LL, p, state.gameMode, R.drawable.ic_low_latency, context.getString(R.string.widget_low_latency), llIconDp(context, kind), llIconDp(context, kind) / ROWS_BOLT)
            v.setContentDescription(LL.root, context.getString(R.string.row_game_title))
            v.setOnClickPendingIntent(LL.root, receiverPI(context, WidgetActions.ACTION_GAME_TOGGLE, id, swap = swap))
        }

        /**
         * Which of the mode button's two copies shows [key], flipping to the other copy when the
         * mode changed: its fill cross-fades and its icon and name tick up. As with the pages
         * ([update]), each copy's visibility is set every time and setDisplayedChild goes out only
         * in the update that changes it, or every refresh would replay the animation.
         */
        private fun modeButton(context: Context, v: RemoteViews, id: Int, key: String): Int {
            val (k, changed) = WidgetSettings.modeSlot(context, id, key)
            if (changed) {
                v.setDisplayedChild(R.id.w_mode_fills, k)
                v.setDisplayedChild(R.id.w_mode_flip, k)
            }
            for (i in 0..1) {
                val shown = if (i == k) View.VISIBLE else View.GONE
                v.setViewVisibility(MODE_FILLS[i], shown)
                v.setViewVisibility(MODE_CONTENT[i], shown)
            }
            return k
        }

        /**
         * The open mode list: 2x2 when a 2x2 / 3x3 widget has <= 4 modes, 3x2 otherwise, icons
         * only when a 2x2 widget has 5-6 (WIDGETS.md 3.2). Tapping the current mode only closes it.
         */
        private fun grid(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, kind: Kind, id: Int) {
            val modes = WidgetSettings.enabled(context)
            val current = WidgetSettings.modeOf(state.ancMode)
            val twoCols = kind.square && modes.size <= 4
            // Nothing: a one-letter label always fits (L, M, H, S, T, A; Off stays a word), and the dot
            // icons need it, since the ANC levels' fine rings blur at dot resolution.
            val letters = WidgetSettings.nothingStyle(context)
            val labels = letters || !(kind.small && modes.size > 4)
            val slots = if (twoCols) listOf(0, 1, 3, 4) else (0..5).toList()
            listOf(2, 5).forEach { v.setViewVisibility(CELLS[it].root, if (twoCols) View.GONE else View.VISIBLE) }
            // Modes that fit one row: the first row takes the whole height.
            v.setViewVisibility(R.id.w_grid_row1, if (modes.size > slots.size / 2) View.VISIBLE else View.GONE)
            slots.forEachIndexed { i, slot ->
                val b = CELLS[slot]
                val mode = modes.getOrNull(i)
                v.setViewVisibility(b.root, if (mode == null) View.INVISIBLE else View.VISIBLE)
                if (mode == null) return@forEachIndexed
                val selected = mode.key == current.key
                paint(context, v, b, p, selected, kind.large)
                content(context, v, b, p, selected, mode.icon, when {
                    letters -> context.getString(mode.short).let { if (mode.key == "off") it.uppercase() else it.take(1).uppercase() }
                    labels -> context.getString(mode.short)
                    else -> null
                }, if (kind.large) 38f else 26f, (if (kind.large) 38f else 26f) / ROWS_LIST)
                v.setContentDescription(b.root, context.getString(mode.name))
                v.setOnClickPendingIntent(b.root,
                    if (selected) receiverPI(context, WidgetActions.ACTION_LIST_CLOSE, id)
                    else receiverPI(context, WidgetActions.ACTION_ANC_SELECT, id, mode.key))
            }
        }

        /**
         * One battery ring as a bitmap: outline track, accent arc from 12 o'clock, and the glyph at
         * its true ratio, tinted by wear (BudsStatusView.wearTint). Small on purpose: RemoteViews
         * bitmaps count against the host's memory limit.
         */
        private fun ring(context: Context, p: Palette, level: Int, slot: Int, status: Int, sizeDp: Float): Bitmap {
            val px = ThemeRes.dp(context, sizeDp).coerceAtLeast(1)
            if (WidgetSettings.nothingStyle(context)) {
                // Nothing style: ring and glyph drawn as one, then turned into one dot matrix, so the
                // ring's dots sit on the glyph's grid. The same [RING_CELLS] on every size, so each ring is
                // the 2x2's image scaled.
                // A translucent tint is made opaque over the card, since a dot is either there or not.
                val n = RING_CELLS
                val tint = BudsStatusView.wearTint(p, slot == 1, status)
                val opaque = Palette.blend(p.card, tint or 0xFF000000.toInt(), Color.alpha(tint) / 255f)
                return matrix(n, n, px / n.toFloat()) { c, size -> drawRing(context, c, size, p, level, slot, opaque, size * 2.6f / n, dim(p)) }
            }
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            drawRing(context, Canvas(bmp), px.toFloat(), p, level, slot, BudsStatusView.wearTint(p, slot == 1, status))
            return bmp
        }

        /** Outline track, accent arc from 12 o'clock, and the glyph in [tint] at its true ratio, in a [size] square. */
        private fun drawRing(context: Context, c: Canvas, size: Float, p: Palette, level: Int, slot: Int, tint: Int, stroke: Float = size * 0.085f, track: Int = p.outline) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
            val box = RectF(stroke / 2, stroke / 2, size - stroke / 2, size - stroke / 2)
            paint.color = track
            c.drawOval(box, paint)
            if (level in 1..100) {
                paint.color = p.accent
                c.drawArc(box, -90f, 360f * level / 100f, false, paint)
            }
            val isCase = slot == 1
            val glyph = context.getDrawable(
                when (slot) { 0 -> R.drawable.ic_bud_left; 1 -> R.drawable.ic_case; else -> R.drawable.ic_bud_right }
            )!!.mutate()
            val ratio = if (isCase) 496f / 400f else 176f / 272f
            // Largest boxes whose corners still clear the ring's inner edge.
            val boxH = size * (if (isCase) 0.46f else 0.6f)
            val boxW = size * (if (isCase) 0.62f else 0.42f)
            val (w, h) = if (ratio < boxW / boxH) boxH * ratio to boxH else boxW to boxW / ratio
            glyph.setTint(tint)
            glyph.setBounds(((size - w) / 2).toInt(), ((size - h) / 2).toInt(), ((size + w) / 2).toInt(), ((size + h) / 2).toInt())
            glyph.draw(c)
        }

        /**
         * The Low latency icon's size. Nothing: 22 / 24dp, a little bigger so the bolt ([bolt]) keeps
         * its shape in dots.
         */
        private fun llIconDp(context: Context, kind: Kind) =
            if (WidgetSettings.nothingStyle(context)) (if (kind.small) 22f else 24f)
            else if (kind.small) 18f else if (kind.large) 25f else 20f

        /**
         * The mode icons and the bolt as pixel art on the dot grid ([USER] 2026-09-28: dotted circles
         * looked too smooth, converted vectors too uneven). The grid is odd so rings have a centre
         * cell; rings are midpoint circles (one clean step per cell) at the vector's radii rounded to
         * whole cells (24 units: ball 2.2, rings 5.2 / 8.2 / 11, so one-cell gaps), dashed rings keep
         * every other pair of dots, the sparkle is a plus, the Off slash runs ring to ring. White.
         * Null: not one of them.
         */
        private fun gridIcon(res: Int, rowsIn: Int): Bitmap? {
            val n = rowsIn or 1                          // odd
            val k = n / 24f                              // cells per vector unit
            val cells = HashSet<Int>()
            fun set(x: Int, y: Int) { if (x in 0 until n && y in 0 until n) cells.add(y * n + x) }
            fun cx(u: Float) = Math.round(u * k - 0.5f + (n - 24 * k) / 2f)
            val c = n / 2
            /** Midpoint circle of radius [r] cells around ([x0], [y0]), each point with its angle (0 = east, clockwise). */
            fun circle(x0: Int, y0: Int, r: Int): List<Triple<Int, Int, Double>> {
                val pts = HashSet<Pair<Int, Int>>()
                var x = r; var y = 0; var err = 1 - r
                while (x >= y) {
                    for ((dx, dy) in listOf(x to y, y to x, -y to x, -x to y, -x to -y, -y to -x, y to -x, x to -y)) pts.add(dx to dy)
                    y++
                    if (err < 0) err += 2 * y + 1 else { x--; err += 2 * (y - x) + 1 }
                }
                return pts.map { (dx, dy) -> Triple(x0 + dx, y0 + dy, (Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())) + 360) % 360) }
            }
            fun ring(x0: Int, y0: Int, units: Float, keep: (Double) -> Boolean = { true }) =
                circle(x0, y0, Math.round(units * k)).forEach { (x, y, a) -> if (keep(a)) set(x, y) }
            fun dashed(units: Float) = circle(c, c, Math.round(units * k)).sortedBy { it.third }
                .forEachIndexed { i, (x, y, _) -> if (i % 4 < 2) set(x, y) }
            fun ball(x0: Int, y0: Int) {
                val r = (2.2f * k).toInt().coerceAtLeast(1)   // rounded down: 5x5 at 17 cells was too heavy
                for (y in -r..r) for (x in -r..r) if (x * x + y * y <= r * r + r) set(x0 + x, y0 + y)
            }
            fun star(x0: Int, y0: Int) {
                val arm = Math.round(3.4f * k).coerceAtLeast(1)
                for (d in -arm..arm) { set(x0 + d, y0); set(x0, y0 + d) }
            }
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                val steps = Math.max(Math.abs(cx(x2) - cx(x1)), Math.abs(cx(y2) - cx(y1))).coerceAtLeast(1)
                for (i in 0..steps) set(Math.round(cx(x1) + (cx(x2) - cx(x1)) * i / steps.toFloat()), Math.round(cx(y1) + (cx(y2) - cx(y1)) * i / steps.toFloat()))
            }
            when (res) {
                R.drawable.ic_mode_anc_low -> { ball(c, c); ring(c, c, 5.2f) }
                R.drawable.ic_mode_anc_medium -> { ball(c, c); ring(c, c, 5.2f); ring(c, c, 8.2f) }
                R.drawable.ic_mode_anc_high -> { ball(c, c); ring(c, c, 5.2f); ring(c, c, 8.2f); ring(c, c, 11f) }
                // The outer arc leaves the top-right open for the sparkle, as in the vector.
                R.drawable.ic_mode_anc_smart -> {
                    ball(c, c); ring(c, c, 5.2f); ring(c, c, 8.2f) { (it + 10) % 360 <= 290 }; star(cx(19f), cx(5f))
                }
                R.drawable.ic_mode_transparency -> { ball(c, c); dashed(5.6f); dashed(9.8f) }
                R.drawable.ic_mode_adaptive -> { val y0 = cx(13f); ball(c, y0); ring(c, y0, 5.8f); star(cx(19f), cx(5.2f)) }
                R.drawable.ic_mode_off -> {
                    val r = Math.round(8.5f * k)
                    ring(c, c, 8.5f)
                    val d = Math.round(r * 0.7071f)
                    for (i in -d..d) set(c + i, c + i)
                }
                R.drawable.ic_low_latency -> { line(15f, 1.5f, 6.5f, 12.5f); line(6.5f, 12.5f, 17.5f, 11.5f); line(17.5f, 11.5f, 9f, 22.5f) }
                else -> return null
            }
            val pitch = 8f
            val out = Bitmap.createBitmap((n * pitch).toInt(), (n * pitch).toInt(), Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            for (i in cells) cv.drawCircle((i % n + 0.5f) * pitch, (i / n + 0.5f) * pitch, pitch * 0.42f, paint)
            return out
        }

        /**
         * The Nothing style's icon dot pitch, the same on every size: finer on the 2x2 made the mode
         * icons stop reading as dots ([USER] 2026-09-28).
         */
        private const val DOT_DP = 1.87f

        /**
         * Dots per icon, the same on every size: the 2x2 is the baseline and the bigger widgets show
         * the same icons with bigger dots ([USER] 2026-09-28). Battery rings: cells across; the rest:
         * rows of the icon.
         */
        private const val RING_CELLS = 42
        private const val ROWS_LIST = 16
        private const val ROWS_MODE = 24
        private const val ROWS_BOLT = 12
        private const val ROWS_CASE = 21

        /**
         * The case row's dot pitch (case icon, case bar) on a [kind] of widget: [DOT_DP] scaled down like
         * the text on the smaller sizes (4x2 18sp, 2x2 16sp vs 22sp), close to the rings' scaled grid.
         */
        private fun dotDp(kind: Kind) = DOT_DP * if (kind.small) 16f / 22f else if (kind.large) 1f else 18f / 22f

        /** Unlit dots (a ring's or the case bar's empty part): halfway to the secondary text, the outline was too faint. */
        private fun dim(p: Palette) = Palette.blend(p.card, p.textSecondary, 0.5f)

        /**
         * An icon: the drawable itself (Classic), or its dot-matrix version (Nothing), [dp] tall on the
         * widget. Tinted by the caller's colour filter. Dots [pitch] dp apart ([dotDp]).
         */
        private fun icon(context: Context, v: RemoteViews, id: Int, res: Int, dp: Float, pitch: Float = DOT_DP) {
            if (!WidgetSettings.nothingStyle(context)) return v.setImageViewResource(id, res)
            val rows = Math.round(dp / pitch).coerceAtLeast(5)
            gridIcon(res, rows)?.let { return v.setImageViewBitmap(id, it) }
            val d = context.getDrawable(res)!!.mutate()
            // Transparency's dashed rings: only cells whose centre sits well inside a dash, so each
            // dash becomes a clean dot instead of noise from dashes straddling cells.
            val cols = Math.round(rows * d.intrinsicWidth.toFloat() / d.intrinsicHeight).coerceAtLeast(1)
            v.setImageViewBitmap(id, when (res) {
                // Cuts to keep (the lid, the LED): sample cell centres.
                R.drawable.ic_case -> dots(d, cols, rows, 8, min = 128, centre = true)
                R.drawable.ic_mode_transparency -> dots(d, cols, rows, 8, min = 220, centre = true)
                // Plain shapes: whole-cell coverage keeps thin diagonals (the bolt's tips).
                else -> dots(d, cols, rows, 8, min = 90)
            })
        }

        /** [d] as a dot matrix ([matrix]), every dot in [color]. */
        private fun dots(d: android.graphics.drawable.Drawable, cols: Int, rows: Int, pitch: Int, color: Int = Color.WHITE, min: Int = 90, centre: Boolean = false): Bitmap =
            matrix(cols, rows, pitch.toFloat(), min, color, centre) { c, _ ->
                d.setTint(Color.WHITE)
                d.setBounds(0, 0, c.width, c.height)
                d.draw(c)
            }

        /**
         * A drawing as a dot matrix like Nothing's own widgets. [draw] paints a [cols] x [rows] grid at
         * 8 px per cell (its size argument is the width in px); each cell at least [min] / 255 covered
         * gets one round dot, [pitch] px apart. With [centre], only the cell's middle quarter counts:
         * a thin cut through it (the bud head's ring, the case lid) empties the dot instead of being
         * averaged away. Without, the whole cell: a thin stroke (the bolt's tips) keeps its dots. The dot takes [color], or when null the colour of the most
         * opaque pixel there (an antialiased edge pixel would give a dark dot).
         */
        private fun matrix(cols: Int, rows: Int, pitch: Float, min: Int = 128, color: Int? = null, centre: Boolean = true, draw: (Canvas, Float) -> Unit): Bitmap {
            val ss = 8
            val bw = cols * ss
            val big = Bitmap.createBitmap(bw, rows * ss, Bitmap.Config.ARGB_8888)
            draw(Canvas(big), bw.toFloat())
            val px = IntArray(bw * rows * ss).also { big.getPixels(it, 0, bw, 0, 0, bw, rows * ss) }
            val out = Bitmap.createBitmap(Math.round(cols * pitch).coerceAtLeast(1), Math.round(rows * pitch).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (y in 0 until rows) for (x in 0 until cols) {
                var sum = 0
                var solid = 0
                val a = if (centre) ss / 4 else 0
                val b = if (centre) ss * 3 / 4 else ss
                for (j in a until b) for (i in a until b) {
                    val v = px[(y * ss + j) * bw + x * ss + i]
                    sum += v ushr 24
                    if (v ushr 24 > solid ushr 24) solid = v
                }
                if (sum / ((b - a) * (b - a)) < min) continue
                paint.color = color ?: (solid or 0xFF000000.toInt())
                c.drawCircle((x + 0.5f) * pitch, (y + 0.5f) * pitch, pitch * 0.42f, paint)
            }
            return out
        }

        /**
         * The 2x2 / 3x3 battery's case bar: 6dp, `outline` track, `accent` fill, round ends. Nothing
         * style: five dot rows, 1.4x the widget's pitch, lit column by column up to the level, the corner
         * dots left out so the ends are round. Drawn exactly as wide as its slot ([barDp]), so the dots
         * are never scaled.
         */
        private fun bar(context: Context, p: Palette, level: Int, kind: Kind, id: Int): Bitmap {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.outline }
            if (WidgetSettings.nothingStyle(context)) {
                // [USER] 2026-09-28: thicker, then fewer dots at that height: 5 rows of 7 rows' height.
                val rows = 5
                val pitch = dotDp(kind) * 7 / 5 * context.resources.displayMetrics.density
                val cols = (ThemeRes.dp(context, barDp(context, kind, id, level)) / pitch).toInt().coerceAtLeast(rows + 1)
                val bmp = Bitmap.createBitmap(Math.round(cols * pitch), Math.round(rows * pitch), Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val lit = if (level in 1..100) maxOf(1, Math.round(cols * level / 100f)) else 0
                for (x in 0 until cols) for (y in 0 until rows) {
                    // Round ends: drop the cells whose centre lies outside a half-circle of radius rows/2.
                    val edge = minOf(x, cols - 1 - x) + 0.5f
                    val dy = y + 0.5f - rows / 2f
                    val r = rows / 2f
                    if (edge < r && (r - edge) * (r - edge) + dy * dy > r * r) continue
                    paint.color = if (x < lit) p.accent else dim(p)
                    c.drawCircle((x + 0.5f) * pitch, (y + 0.5f) * pitch, pitch * 0.42f, paint)
                }
                return bmp
            }
            val w = ThemeRes.dp(context, 80f)
            val h = ThemeRes.dp(context, 6f).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, paint)
            if (level in 1..100) {
                paint.color = p.accent
                c.drawRoundRect(RectF(0f, 0f, maxOf(h.toFloat(), w * level / 100f), h.toFloat()), h / 2f, h / 2f, paint)
            }
            return bmp
        }

        /**
         * The case bar's slot width in dp: the widget's width less everything else in the row (paddings,
         * the case icon, margins, the percentage as Ndot measures it, the swap button when shown).
         * Falls back to 40 / 80dp when the host gives no size.
         */
        private fun barDp(context: Context, kind: Kind, id: Int, level: Int): Float {
            val w = AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            if (w <= 0) return if (kind.large) 80f else 40f
            val d = context.resources.displayMetrics.density
            val pct = Paint().apply {
                typeface = android.graphics.Typeface.create("NDot57All", android.graphics.Typeface.NORMAL)
                textSize = (if (kind.large) 22f else 16f) * context.resources.displayMetrics.scaledDensity
            }.measureText(if (level in 0..100) "$level%" else "—") / d
            val swap = if (WidgetSettings.doubleTapSwaps(context)) 0f else 20f
            return (w - 6f - 24f - (if (kind.large) 48f else 35f) - 16f - pct - swap - 2f).coerceAtLeast(12f)
        }

        private fun openAppPI(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 400, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /**
         * The main screen's Connect, from the widget. With the background service off the widget
         * does not start the service ([BudsService.backgroundAllowed]), so it opens the app instead.
         */
        private fun connectPI(context: Context): PendingIntent {
            if (!BudsService.backgroundAllowed(context)) return openAppPI(context)
            val intent = Intent(context, BudsService::class.java)
                .setAction(BudsService.ACTION_FORCE_CONNECT)
                .putExtra(BudsService.EXTRA_WITH_AUDIO, true)
            return PendingIntent.getForegroundService(context, 401, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * A tap for [WidgetActionReceiver]. The data URI makes each (widget, action, target) its own
         * PendingIntent. [swap] is the 2x2 page to switch to ([WidgetActions.EXTRA_PAGE]).
         */
        private fun receiverPI(context: Context, action: String, id: Int, target: String? = null, swap: Kind? = null): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("quickbuds-widget://$id/$action/${target.orEmpty()}")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                if (target != null) putExtra(WidgetActions.EXTRA_ANC_TARGET, target)
                if (swap != null) putExtra(WidgetActions.EXTRA_PAGE, swap.name)
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

/** 4x2 (3x2 before 2026-09-27). Keeps the original class name so placed widgets still work. */
class AncWidgetProvider : QuickBudsWidget(Kind.COMBINED) {
    companion object {
        fun refreshAll(context: Context) = QuickBudsWidget.refreshAll(context)
    }
}

/** 2x2 battery. */
class BatteryWidgetProvider : QuickBudsWidget(Kind.BATTERY)

/** 3x3: the 2x2 layout, scaled up. */
class LargeWidgetProvider : QuickBudsWidget(Kind.LARGE)
