package com.spizganed.quickbuds.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Parcel
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.spizganed.quickbuds.R
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
 *  - [BatteryWidgetProvider] 2x2, starts on the battery page (3.1)
 *  - [SmallWidgetProvider]   2x2, starts on the controls page (3.2)
 *  - [AncWidgetProvider]     3x2 combined (3.3)
 *  - [LargeWidgetProvider]   3x3 combined (3.4)
 *
 * Drawn in the ACTIVE palette at update time: white shapes tinted with ImageView.setColorFilter
 * (every API level) and ring bitmaps drawn here. Disconnected, every size shows only the main
 * screen's Connect chip ([USER] 2026-09-27). The mode list is a layout swap, opened and closed by
 * [WidgetActionReceiver] (stamp in [WidgetSettings]); it never opens an Activity.
 *
 * The two 2x2 providers are one widget with two pages (battery, controls), stored per widget id
 * and swapped by a corner button or a double tap ([WidgetSettings.doubleTapSwaps]).
 */
open class QuickBudsWidget(private val kind: Kind) : AppWidgetProvider() {

    enum class Kind(val layout: Int, val large: Boolean = false) {
        BATTERY(R.layout.widget_battery),
        CONTROLS(R.layout.widget_controls),
        COMBINED(R.layout.widget_combined),
        LARGE(R.layout.widget_large, large = true)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, mgr, id, kind)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) {
            WidgetSettings.setListOpenedAt(context, id, 0L)
            WidgetSettings.setPage(context, id, null)
        }
    }

    /** A button's view ids: the tappable frame, its fill and stroke, its icon and its text. */
    private class Btn(val root: Int, val bg: Int, val stroke: Int, val icon: Int, val label: Int)

    companion object {

        private val providers = listOf(
            BatteryWidgetProvider::class.java to Kind.BATTERY,
            SmallWidgetProvider::class.java to Kind.CONTROLS,
            AncWidgetProvider::class.java to Kind.COMBINED,
            LargeWidgetProvider::class.java to Kind.LARGE
        )

        private val MODE = Btn(R.id.w_mode, R.id.w_mode_bg, R.id.w_mode_stroke, R.id.w_mode_icon, R.id.w_mode_name)
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

        private fun update(context: Context, mgr: AppWidgetManager, id: Int, kind: Kind) {
            // A throw here would leave the host showing "Can't load widget" with no trace.
            try {
                mgr.updateAppWidget(id, build(context, WidgetStateStore.read(context), kind, id))
            } catch (t: Throwable) {
                Log.e("BudsWidget", "update failed for $kind #$id", t)
            }
        }

        fun build(context: Context, state: WidgetStateStore.State, provider: Kind, id: Int): RemoteViews {
            val p = ThemeRes.palette(context)
            if (!state.connected) return disconnected(context, p, provider)

            val twoPages = provider == Kind.BATTERY || provider == Kind.CONTROLS
            val kind = if (twoPages) WidgetSettings.page(context, id, provider) else provider
            val list = kind != Kind.BATTERY && WidgetSettings.listOpen(context, id)
            val v = RemoteViews(context.packageName, if (list && kind != Kind.LARGE) R.layout.widget_list else kind.layout)
            v.setImageViewResource(R.id.w_bg, if (kind.large) R.drawable.widget_bg_l else R.drawable.widget_bg)
            v.setInt(R.id.w_bg, "setColorFilter", p.card)
            // Double-tap mode: every tap on a 2x2 page carries the other page, so a second tap swaps.
            val other = if (kind == Kind.BATTERY) Kind.CONTROLS else Kind.BATTERY
            val swap = if (twoPages && !list && WidgetSettings.doubleTapSwaps(context)) other else null
            if (swap != null) v.setOnClickPendingIntent(R.id.w_root, receiverPI(context, WidgetActions.ACTION_OPEN_APP, id, swap = swap))
            else if (WidgetSettings.openAppOnTap(context)) v.setOnClickPendingIntent(R.id.w_root, openAppPI(context))
            if (twoPages && !list) {
                if (swap != null) v.setViewVisibility(R.id.w_swap, View.GONE)
                else {
                    v.setInt(R.id.w_swap_icon, "setColorFilter", p.textSecondary)
                    v.setContentDescription(R.id.w_swap, context.getString(R.string.widget_swap_desc))
                    v.setOnClickPendingIntent(R.id.w_swap, receiverPI(context, WidgetActions.ACTION_PAGE_SWAP, id, swap = other))
                }
            }

            if (list) {
                grid(context, v, p, state, kind, id)
                if (kind != Kind.LARGE) return v
                v.setViewVisibility(R.id.w_controls, View.GONE)
                v.setViewVisibility(R.id.w_grid, View.VISIBLE)
            }
            if (kind != Kind.CONTROLS) battery(context, v, p, state, kind)
            if (kind != Kind.BATTERY && !list) controls(context, v, p, state, kind, id, swap)
            return v
        }

        /** Battery panel colour: `card` lightened ~4% toward `text` (WIDGETS.md 2). */
        private fun panelColor(p: Palette) = Palette.blend(p.card, p.text, 0.04f)

        /** Selected: accent fill and stroke. Otherwise the panel colour with an `outline` stroke. */
        private fun paint(v: RemoteViews, b: Btn, p: Palette, selected: Boolean, large: Boolean) {
            v.setImageViewResource(b.bg, if (large) R.drawable.widget_panel_l else R.drawable.widget_panel)
            v.setInt(b.bg, "setColorFilter", if (selected) p.accent else panelColor(p))
            v.setImageViewResource(b.stroke, if (large) R.drawable.widget_panel_stroke_l else R.drawable.widget_panel_stroke)
            v.setInt(b.stroke, "setColorFilter", if (selected) p.accent else p.outline)
        }

        /** Paints [b] with [icon] and [text] in the on-accent or secondary colour. */
        private fun content(v: RemoteViews, b: Btn, p: Palette, selected: Boolean, icon: Int, text: CharSequence?) {
            val fg = if (selected) p.onAccent else p.textSecondary
            v.setImageViewResource(b.icon, icon)
            v.setInt(b.icon, "setColorFilter", fg)
            if (text == null) v.setViewVisibility(b.label, View.GONE)
            else {
                v.setViewVisibility(b.label, View.VISIBLE)
                v.setTextViewText(b.label, text)
                v.setTextColor(b.label, fg)
            }
        }

        private fun disconnected(context: Context, p: Palette, kind: Kind): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_disconnected)
            v.setImageViewResource(R.id.w_bg, if (kind.large) R.drawable.widget_bg_l else R.drawable.widget_bg)
            v.setInt(R.id.w_bg, "setColorFilter", p.card)
            paint(v, CONN, p, false, kind.large)
            content(v, CONN, p, false, R.drawable.ic_status_dot_empty, context.getString(R.string.conn_action_connect))
            v.setContentDescription(CONN.root, context.getString(R.string.conn_off) + ". " + context.getString(R.string.conn_action_connect))
            v.setOnClickPendingIntent(CONN.root, connectPI(context))
            return v
        }

        private fun battery(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, kind: Kind) {
            val ringDp = when (kind) { Kind.BATTERY -> 56f; Kind.LARGE -> 50f; else -> 42f }
            val levels = intArrayOf(state.leftBattery, state.caseBattery, state.rightBattery)
            val statuses = intArrayOf(state.leftStatus, -1, state.rightStatus)
            val names = intArrayOf(R.string.status_left, R.string.status_case, R.string.status_right)
            // The 2x2 battery shows the case as a bar under the two bud panels.
            val slots = if (kind == Kind.BATTERY) listOf(0, 2) else listOf(0, 1, 2)
            for (slot in slots) {
                val (bgId, ringId, pct, label) = PANELS[slot].toList()
                v.setImageViewResource(bgId, if (kind.large) R.drawable.widget_panel_l else R.drawable.widget_panel)
                v.setInt(bgId, "setColorFilter", panelColor(p))
                v.setImageViewBitmap(ringId, ring(context, p, levels[slot], slot, statuses[slot], ringDp))
                pctText(v, pct, p, levels[slot])
                val inEar = statuses[slot] == 3 || statuses[slot] == 7
                val text = if (slot == 1) context.getString(names[slot]) else wearLabel(context, statuses[slot]) ?: context.getString(names[slot])
                v.setTextViewText(label, if (inEar) semibold(text) else text)
                v.setTextColor(label, if (inEar) p.text else p.textSecondary)
            }
            if (kind == Kind.BATTERY) {
                v.setInt(R.id.w_bar_bg, "setColorFilter", panelColor(p))
                v.setInt(R.id.w_case_icon, "setColorFilter", p.text)
                v.setImageViewBitmap(R.id.w_case_bar, bar(context, p, state.caseBattery))
                pctText(v, R.id.w_pct_case, p, state.caseBattery)
            }
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

        private fun controls(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, kind: Kind, id: Int, swap: Kind?) {
            val mode = WidgetSettings.modeOf(state.ancMode)
            val active = mode.key != "off"
            val name = context.getString(mode.name)
            val opensList = WidgetSettings.tapOpensList(context)
            paint(v, MODE, p, active, kind.large)
            content(v, MODE, p, active, mode.icon, name)
            v.setImageViewResource(R.id.w_mode_hint, if (opensList) R.drawable.ic_hint_list else R.drawable.ic_hint_cycle)
            v.setInt(R.id.w_mode_hint, "setColorFilter", if (active) p.onAccent else p.textSecondary)
            if (kind == Kind.LARGE) {
                v.setTextViewText(R.id.w_mode_caption, context.getString(R.string.anc_section))
                v.setTextColor(R.id.w_mode_caption, Palette.withAlpha(if (active) p.onAccent else p.textSecondary, 0.8f))
            }
            v.setContentDescription(MODE.root, context.getString(
                if (opensList) R.string.widget_mode_desc_list else R.string.widget_mode_desc_cycle,
                context.getString(R.string.anc_section), name
            ))
            v.setOnClickPendingIntent(MODE.root, receiverPI(context, WidgetActions.ACTION_MODE_TAP, id, swap = swap))

            if (!WidgetSettings.lowLatencyShown(context)) {
                v.setViewVisibility(LL.root, View.GONE)
                return
            }
            paint(v, LL, p, state.gameMode, kind.large)
            content(v, LL, p, state.gameMode, R.drawable.ic_low_latency, context.getString(R.string.widget_low_latency))
            v.setContentDescription(LL.root, context.getString(R.string.row_game_title))
            v.setOnClickPendingIntent(LL.root, receiverPI(context, WidgetActions.ACTION_GAME_TOGGLE, id, swap = swap))
        }

        /**
         * The open mode list: 2x2 when the 2x2 controls widget has <= 4 modes, 3x2 otherwise, icons
         * only when a 2x2 widget has 5-6 (WIDGETS.md 3.2). Tapping the current mode only closes it.
         */
        private fun grid(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, kind: Kind, id: Int) {
            val modes = WidgetSettings.enabled(context)
            val current = WidgetSettings.modeOf(state.ancMode)
            val twoCols = kind == Kind.CONTROLS && modes.size <= 4
            val labels = !(kind == Kind.CONTROLS && modes.size > 4)
            val slots = if (twoCols) listOf(0, 1, 3, 4) else (0..5).toList()
            if (twoCols) listOf(2, 5).forEach { v.setViewVisibility(CELLS[it].root, View.GONE) }
            slots.forEachIndexed { i, slot ->
                val b = CELLS[slot]
                val mode = modes.getOrNull(i)
                if (mode == null) { v.setViewVisibility(b.root, View.INVISIBLE); return@forEachIndexed }
                val selected = mode.key == current.key
                paint(v, b, p, selected, kind.large)
                content(v, b, p, selected, mode.icon, if (labels) context.getString(mode.short) else null)
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
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val stroke = px * 0.085f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
            val box = RectF(stroke / 2, stroke / 2, px - stroke / 2, px - stroke / 2)
            paint.color = p.outline
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
            val boxH = px * (if (isCase) 0.46f else 0.6f)
            val boxW = px * (if (isCase) 0.62f else 0.42f)
            val (w, h) = if (ratio < boxW / boxH) boxH * ratio to boxH else boxW to boxW / ratio
            glyph.setTint(BudsStatusView.wearTint(p, isCase, status))
            glyph.setBounds(((px - w) / 2).toInt(), ((px - h) / 2).toInt(), ((px + w) / 2).toInt(), ((px + h) / 2).toInt())
            glyph.draw(c)
            return bmp
        }

        /** The 2x2 battery's case bar: 6dp, `outline` track, `accent` fill, round ends. */
        private fun bar(context: Context, p: Palette, level: Int): Bitmap {
            val w = ThemeRes.dp(context, 80f)
            val h = ThemeRes.dp(context, 6f).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.outline }
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, paint)
            if (level in 1..100) {
                paint.color = p.accent
                c.drawRoundRect(RectF(0f, 0f, maxOf(h.toFloat(), w * level / 100f), h.toFloat()), h / 2f, h / 2f, paint)
            }
            return bmp
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

        /**
         * Dev Tools' widget logic check: builds every size for the current state
         * and round-trips each through a Parcel, as the AppWidgetService does.
         */
        fun buildWidgetRemoteViews(context: Context): String {
            val sb = StringBuilder("WIDGET REMOTEVIEWS CHECK\n\n")
            val state = WidgetStateStore.read(context)
            sb.appendLine("state: connected=${state.connected} anc=${state.ancMode} game=${state.gameMode}")
            val mgr = AppWidgetManager.getInstance(context)
            for ((cls, kind) in providers) {
                val placed = mgr.getAppWidgetIds(ComponentName(context, cls)).size
                val result = try {
                    val parcel = Parcel.obtain()
                    try { build(context, state, kind, 0).writeToParcel(parcel, 0); "OK (${parcel.dataSize()} bytes)" } finally { parcel.recycle() }
                } catch (t: Throwable) {
                    "FAILED: $t\n${t.stackTraceToString()}"
                }
                sb.appendLine("$kind: placed=$placed build+parcel $result")
            }
            return sb.toString()
        }
    }
}

/** 3x2 combined. Keeps the original class name so widgets placed before the redesign still work. */
class AncWidgetProvider : QuickBudsWidget(Kind.COMBINED) {
    companion object {
        fun refreshAll(context: Context) = QuickBudsWidget.refreshAll(context)
        fun buildWidgetRemoteViews(context: Context) = QuickBudsWidget.buildWidgetRemoteViews(context)
    }
}

/** 2x2 controls (was the 2x2 compact; class name kept for placed widgets). */
class SmallWidgetProvider : QuickBudsWidget(Kind.CONTROLS)

/** 2x2 battery. */
class BatteryWidgetProvider : QuickBudsWidget(Kind.BATTERY)

/** 3x3 combined. */
class LargeWidgetProvider : QuickBudsWidget(Kind.LARGE)
