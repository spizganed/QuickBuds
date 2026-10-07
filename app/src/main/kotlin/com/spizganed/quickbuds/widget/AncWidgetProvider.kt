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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.util.Log
import android.view.View
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
 * The home-screen widgets. One provider per size, so each
 * has its own picker entry and cell size, and the old class names keep placed widgets alive:
 *
 *  - [BatteryWidgetProvider]     2x2, battery and controls pages
 *  - [BatteryOnlyWidgetProvider] 2x2, battery page only
 *  - [ControlsWidgetProvider]    2x2, controls page only
 *  - [AncWidgetProvider]         4x2, three battery panels in a row, and controls
 *
 * Every size is fixed. A widget with a controls page has a setup screen ([WidgetSetupActivity]) for its buttons.
 *
 * Drawn in the ACTIVE palette at update time: white shapes tinted with ImageView.setColorFilter
 * (every API level) and ring bitmaps drawn here. Disconnected, every size shows only the main
 * screen's Connect chip. The mode list is a ViewFlipper child, opened and closed by
 * [WidgetActionReceiver] (stamp in [WidgetSettings]); it never opens an Activity.
 *
 * The two-page widgets keep their page per widget id and swap it on a double tap. The
 * pages slide (`w_slide0` battery on the left, `w_slide1` controls on the right); the level picker slides over them (`w_pages`).
 */
open class QuickBudsWidget(private val kind: Kind) : AppWidgetProvider() {

    /**
     * The provider. BATTERY and CONTROLS double as the page names ([WidgetSettings.page]).
     * [fixed]: the one page a single-page widget shows; null for the two-page widgets.
     */
    enum class Kind(private val classic: Int, private val nothing: Int, val fixed: Kind? = null) {
        BATTERY(R.layout.widget_pages, R.layout.widget_pages_n),
        CONTROLS(R.layout.widget_pages, R.layout.widget_pages_n),
        COMBINED(R.layout.widget_pages_m, R.layout.widget_pages_m_n),
        BATTERY_ONLY(R.layout.widget_pages, R.layout.widget_pages_n, BATTERY);

        /** The layout for the chosen style ([WidgetSettings.nothingStyle]). */
        fun layout(c: Context) = if (WidgetSettings.nothingStyle(c)) nothing else classic
        /** The 2x2 (as opposed to the 4x2). */
        val square get() = this != COMBINED
        /** The page shown; CONTROLS is a single-page widget too (its own [fixed] would be a forward reference). */
        val page get() = if (this == CONTROLS) CONTROLS else fixed
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
            BatteryOnlyWidgetProvider::class.java to Kind.BATTERY_ONLY,
            ControlsWidgetProvider::class.java to Kind.CONTROLS,
            AncWidgetProvider::class.java to Kind.COMBINED
        )

        private val CONN = Btn(R.id.w_conn, R.id.w_conn_bg, R.id.w_conn_stroke, R.id.w_conn_dot, R.id.w_conn_text)
        private val CELLS = listOf(
            Btn(R.id.w_cell0, R.id.w_cell0_bg, R.id.w_cell0_stroke, R.id.w_cell0_icon, R.id.w_cell0_label),
            Btn(R.id.w_cell1, R.id.w_cell1_bg, R.id.w_cell1_stroke, R.id.w_cell1_icon, R.id.w_cell1_label),
            Btn(R.id.w_cell2, R.id.w_cell2_bg, R.id.w_cell2_stroke, R.id.w_cell2_icon, R.id.w_cell2_label),
            Btn(R.id.w_cell3, R.id.w_cell3_bg, R.id.w_cell3_stroke, R.id.w_cell3_icon, R.id.w_cell3_label),
            Btn(R.id.w_cell4, R.id.w_cell4_bg, R.id.w_cell4_stroke, R.id.w_cell4_icon, R.id.w_cell4_label),
            Btn(R.id.w_cell5, R.id.w_cell5_bg, R.id.w_cell5_stroke, R.id.w_cell5_icon, R.id.w_cell5_label)
        )

        /** The controls page's quick buttons: ANC, T, A, LL ([controls]). */
        private val QUICK = listOf(
            Btn(R.id.w_q0, R.id.w_q0_bg, R.id.w_q0_stroke, R.id.w_q0_icon, R.id.w_q0_label),
            Btn(R.id.w_q1, R.id.w_q1_bg, R.id.w_q1_stroke, R.id.w_q1_icon, R.id.w_q1_label),
            Btn(R.id.w_q2, R.id.w_q2_bg, R.id.w_q2_stroke, R.id.w_q2_icon, R.id.w_q2_label),
            Btn(R.id.w_q3, R.id.w_q3_bg, R.id.w_q3_stroke, R.id.w_q3_icon, R.id.w_q3_label)
        )

        /** Battery panel ids per slot (0 left, 1 case, 2 right): panel fill, ring, percentage, label, outline. */
        private val PANELS = listOf(
            intArrayOf(R.id.w_panel_left_bg, R.id.w_ring_left, R.id.w_pct_left, R.id.w_label_left, R.id.w_panel_left_stroke),
            intArrayOf(R.id.w_panel_case_bg, R.id.w_ring_case, R.id.w_pct_case, R.id.w_label_case, R.id.w_panel_case_stroke),
            intArrayOf(R.id.w_panel_right_bg, R.id.w_ring_right, R.id.w_pct_right, R.id.w_label_right, R.id.w_panel_right_stroke)
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
        private fun update(context: Context, mgr: AppWidgetManager, id: Int, declared: Kind) {
            val kind = declared
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

            val page = provider.page ?: WidgetSettings.page(context, id, Kind.BATTERY)
            val list = page == Kind.CONTROLS && WidgetSettings.listOpen(context, id)
            val v = RemoteViews(context.packageName, provider.layout(context))
            // The host reapplies an update with the same layout onto the views it has, so switching
            // a flipper's child animates. The flip side: every state set here must be set both ways
            // (visibility, click), or the previous update's sticks.
            val child = if (list) 2 else if (page == Kind.BATTERY) 0 else 1
            v.setImageViewResource(R.id.w_bg, bgRes(context))
            v.setInt(R.id.w_bg, "setColorFilter", boxColor(p))
            // Every tap on a two-page widget carries the other page, so a second tap swaps.
            val other = if (page == Kind.BATTERY) Kind.CONTROLS else Kind.BATTERY
            val swap = if (!list && provider.page == null) other else null
            v.setOnClickPendingIntent(R.id.w_root, swap?.let { receiverPI(context, WidgetActions.ACTION_OPEN_APP, id, swap = it) })

            // Both pages and the list are filled every time, so the one sliding or fading out still
            // shows current values (a pick's highlight while the list closes).
            grid(context, v, p, state, provider, id)
            battery(context, v, p, state, provider, id)
            if (!list) controls(context, v, p, state, provider, id, swap)
            return v to child
        }

        /** The widget box: Nothing's own widgets' radius in the Nothing style, else per size. */
        private fun bgRes(c: Context) =
            if (WidgetSettings.nothingStyle(c)) R.drawable.widget_bg_n else R.drawable.widget_bg

        /** A panel or button inside the box, its corners parallel to the box's in the Nothing style. */
        private fun panelRes(c: Context) =
            if (WidgetSettings.nothingStyle(c)) R.drawable.widget_panel_n else R.drawable.widget_panel

        /** The widget box: the card colour, lifted toward the text when the card is near black (OLED Black), or it vanishes on a dark wallpaper. */
        private fun boxColor(p: Palette) =
            if (Palette.luminance(p.card) < 0.005) Palette.blend(p.card, p.text, 0.11f) else p.card

        /**
         * Battery panel colour: `card` lightened ~4% toward `text` (WIDGETS.md 2). The Nothing style has
         * no boxes: `card` itself, so panels melt into the widget background.
         */
        private fun panelColor(c: Context, p: Palette) =
            if (WidgetSettings.nothingStyle(c)) Palette.blend(boxColor(p), p.text, 0.07f) else Palette.blend(boxColor(p), p.text, 0.04f)

        /** Dot style: the panel's dashed grey outline, as the buttons have it. Classic draws none, so it is hidden (set both ways). */
        private fun panelOutline(c: Context, v: RemoteViews, id: Int, p: Palette) {
            val n = WidgetSettings.nothingStyle(c)
            v.setViewVisibility(id, if (n) View.VISIBLE else View.GONE)
            if (!n) return
            v.setImageViewResource(id, R.drawable.widget_panel_dots_n)
            v.setInt(id, "setColorFilter", Palette.blend(boxColor(p), p.text, 0.2f))
        }

        /**
         * Classic: selected is an accent fill and stroke, otherwise the panel colour with an `outline` stroke.
         * Nothing: every button and battery panel has a slightly lighter fill and a dashed grey outline, the selected one an accent one.
         */
        private fun paint(c: Context, v: RemoteViews, b: Btn, p: Palette, selected: Boolean) {
            val n = WidgetSettings.nothingStyle(c)
            v.setImageViewResource(b.bg, panelRes(c))
            v.setInt(b.bg, "setColorFilter", if (selected && !n) p.accent else panelColor(c, p))
            v.setImageViewResource(b.stroke, if (n) (if (selected) R.drawable.widget_panel_select_n else R.drawable.widget_panel_dots_n)
                else R.drawable.widget_panel_stroke)
            v.setInt(b.stroke, "setColorFilter", if (selected) p.accent else if (n) Palette.blend(boxColor(p), p.text, 0.2f) else p.outline)
        }

        /** Paints [b] with [icon] and [text]: selected on-accent (Classic fill) or accent (Nothing outline), else secondary. */
        private fun content(context: Context, v: RemoteViews, b: Btn, p: Palette, selected: Boolean, icon: Int, text: CharSequence?, iconDp: Float, pitch: Float = DOT_DP) {
            val fg = if (!selected) p.textSecondary else if (WidgetSettings.nothingStyle(context)) p.accent else p.onAccent
            icon(context, v, b.icon, icon, iconDp, pitch)
            v.setInt(b.icon, "setColorFilter", fg)
            if (text == null) v.setViewVisibility(b.label, View.GONE)
            else {
                v.setViewVisibility(b.label, View.VISIBLE)
                setText(context, v, b.label, text, fg)
            }
        }

        private fun disconnected(context: Context, p: Palette, kind: Kind): RemoteViews {
            val v = RemoteViews(context.packageName, if (WidgetSettings.nothingStyle(context)) R.layout.widget_disconnected_n else R.layout.widget_disconnected)
            v.setImageViewResource(R.id.w_bg, bgRes(context))
            v.setInt(R.id.w_bg, "setColorFilter", boxColor(p))
            paint(context, v, CONN, p, false)
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
                val (bgId, ringId, pct, label, strokeId) = PANELS[slot].toList()
                v.setImageViewResource(bgId, panelRes(context))
                v.setInt(bgId, "setColorFilter", panelColor(context, p))
                panelOutline(context, v, strokeId, p)
                v.setImageViewBitmap(ringId, ring(context, p, levels[slot], slot, statuses[slot], ringDp))
                pctText(context, v, pct, p, levels[slot])
                val inEar = statuses[slot] == 3 || statuses[slot] == 7
                val text = if (slot == 1) context.getString(names[slot]) else wearLabel(context, statuses[slot]) ?: context.getString(names[slot])
                // Nothing: no wear text, the glyph's shade says it ([nothingTint]) and the ring's content description reads it.
                val noLabel = WidgetSettings.nothingStyle(context)
                v.setViewVisibility(label, if (noLabel) View.GONE else View.VISIBLE)
                v.setContentDescription(ringId, if (slot == 1) text else context.getString(names[slot]) + ", " + text)
                setText(context, v, label, if (inEar && !noLabel) semibold(text) else text, if (inEar) p.text else p.textSecondary)
            }
            if (caseBar) {
                v.setImageViewResource(R.id.w_bar_bg, panelRes(context))
                v.setInt(R.id.w_bar_bg, "setColorFilter", panelColor(context, p))
                panelOutline(context, v, R.id.w_bar_stroke, p)
                // The case icon takes all the height the rings leave ([caseRowDp]). Nothing: at the rings' dot pitch,
                // so the big icon keeps the app's case details (lid cut, LED).
                val caseDp = caseRowDp(context, kind, id)
                icon(context, v, R.id.w_case_icon, R.drawable.ic_case, caseDp, ringCellDp(context, kind, id))
                v.setInt(R.id.w_case_icon, "setColorFilter", p.text)
                v.setImageViewBitmap(R.id.w_case_bar, bar(context, p, state.caseBattery, kind, id, caseDp))
                // The level is drawn into the bar ([bar]); the bar reads it out.
                v.setViewVisibility(R.id.w_pct_case, View.GONE)
                v.setContentDescription(R.id.w_case_bar, context.getString(R.string.status_case) + ", " + pctLabel(context, state.caseBattery))
            }
        }

        /**
         * The ring size. 2x2: drawn large enough and shrunk to the panel by the layout. 4x2:
         * the largest ring the panel holds at the widget's real size (portrait: min width, max
         * height), so ring and texts fill the panel as one centred group instead of a small ring
         * over a gap.
         */
        private fun ringDp(context: Context, kind: Kind, id: Int): Float {
            // Drawn large enough; the layout shrinks it to the panel's width.
            if (kind.square) return 96f
            val o = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (w <= 0 || h <= 0) return 56f
            if (WidgetSettings.nothingStyle(context)) {
                // Mirrors scripts/widget-layouts.py: widget inset 3 (7 top and bottom), gaps 4, panel paddings 3 outside /
                // 1 inside and 3 top and bottom, percentage margin 5: the rings 6dp from the sides and apart, as on the 2x2.
                val pct = pctPaint(context, kind).fontMetrics.let { it.descent - it.ascent } / context.resources.displayMetrics.density
                return minOf(h - 20f - 5f - pct, (w - 6f - 8f) / 3 - 4f).coerceIn(28f, 160f)
            }
            // Page padding 12 across, 20 down; panel padding 8; texts: percentage + label lines.
            val texts = if (WidgetSettings.nothingStyle(context)) 20f * 1.3f + 2f else 18f * 1.3f + 12f * 1.3f + 2f
            val byHeight = h - 20f - 8f - texts - 6f
            val byWidth = (w - 12f - 6f * 2) / 3 - 8f
            return minOf(byHeight, byWidth).coerceIn(28f, 120f)
        }

        /** Percentage, always `text`: nothing in the battery display changes colour by level. */
        private fun pctText(context: Context, v: RemoteViews, id: Int, p: Palette, level: Int) {
            setText(context, v, id, pctLabel(context, level), p.text)
        }

        /**
         * A widget text. Classic: a TextView. Dot matrix: an ImageView showing the text drawn in Doto, because launchers
         * ignore `@font/` in widget XML. The layout sets the view's height (sp x 1.2, Doto's line height) and the bitmap
         * scales to it, so this draws at one fixed size.
         */
        private fun setText(context: Context, v: RemoteViews, id: Int, text: CharSequence, color: Int) {
            if (!WidgetSettings.nothingStyle(context)) {
                v.setTextViewText(id, text)
                v.setTextColor(id, color)
                return
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = com.spizganed.quickbuds.ui.ThemeRes.dotFont(context)
                textSize = 32f * context.resources.displayMetrics.density
                this.color = color
            }
            val s = text.toString()
            val fm = paint.fontMetrics
            val bmp = Bitmap.createBitmap(maxOf(1, Math.ceil(paint.measureText(s).toDouble()).toInt()),
                Math.ceil((fm.descent - fm.ascent).toDouble()).toInt(), Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawText(s, 0f, -fm.ascent, paint)
            v.setImageViewBitmap(id, bmp)
            v.setContentDescription(id, s)
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

        /**
         * The controls page: up to four buttons, as widget [id]'s setup screen picked them ([WidgetSettings.buttons]).
         * ANC opens the level picker (the mode list with [WidgetSettings.ancPicker]) and shows the current level's
         * icon, Medium's while not in ANC; T, A and Off select their mode, or Off when lit; LL toggles low latency;
         * a feature button toggles its `0x0403` switch. A button the buds do not have stays as an empty cell.
         * Everything set both ways, see [build].
         */
        private fun controls(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, kind: Kind, id: Int, swap: Kind?) {
            val current = WidgetSettings.modeOf(state.ancMode)
            val levels = WidgetSettings.ancPicker(context)
            val inAnc = levels.any { it.key == current.key }
            fun mode(key: String) = WidgetSettings.MODES.first { it.key == key }
            /** First letters of the words, capitals: "Low latency" -> "LL" (translated names too). */
            fun initials(res: Int) = context.getString(res).split(' ').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }.uppercase()
            class Q(val lit: Boolean, val icon: Int, val label: String, val desc: String, val pi: PendingIntent)
            fun quick(target: String) = receiverPI(context, WidgetActions.ACTION_QUICK, id, target, swap)
            fun q(key: String): Q = when (key) {
                "anc" -> Q(inAnc, if (inAnc) current.icon else R.drawable.ic_mode_anc_medium,
                    if (inAnc) "ANC " + context.getString(current.short).take(1).uppercase() else "ANC",
                    context.getString(if (inAnc) current.name else R.string.anc_section), quick("anc"))
                "trans", "adapt" -> Q(current.key == key, mode(key).icon, initials(mode(key).name),
                    context.getString(mode(key).name), quick(key))
                "off" -> Q(current.key == "off", mode("off").icon, context.getString(R.string.anc_seg_off),
                    context.getString(R.string.anc_seg_off), quick("off"))
                "ll" -> Q(state.gameMode, R.drawable.ic_low_latency, initials(R.string.widget_low_latency),
                    context.getString(R.string.row_game_title), receiverPI(context, WidgetActions.ACTION_GAME_TOGGLE, id, swap = swap))
                else -> {
                    val f = WidgetSettings.FEATURE_BUTTONS.getValue(key)
                    Q(WidgetSettings.featureOn(context, f), R.drawable.ic_anc, initials(R.string.row_wind_noise_title),
                        context.getString(R.string.row_wind_noise_title),
                        receiverPI(context, WidgetActions.ACTION_FEATURE_TOGGLE, id, key, swap, f))
                }
            }
            val keys = WidgetSettings.buttons(context, id)
            QUICK.forEachIndexed { i, b ->
                val key = keys.getOrNull(i)?.takeIf { WidgetSettings.available(context, it) }
                v.setViewVisibility(b.root, if (key != null) View.VISIBLE else View.INVISIBLE)
                if (key == null) return@forEachIndexed
                val q = q(key)
                paint(context, v, b, p, q.lit)
                // Nothing: the bolt on 25 rows keeps its 2-dot lines close to the mode icons' weight; mode icons have their own grid.
                content(context, v, b, p, q.lit, q.icon, q.label, 25f, 1f)
                v.setContentDescription(b.root, q.desc)
                v.setOnClickPendingIntent(b.root, q.pi)
            }
        }

        /**
         * The ANC button's level picker ([controls]): 2x2 when a 2x2 / 3x3 widget has <= 4 levels, 3x2 otherwise.
         * No Off: the lit level turns ANC off.
         */
        private fun grid(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, kind: Kind, id: Int) {
            val modes = WidgetSettings.ancPicker(context)
            val current = WidgetSettings.modeOf(state.ancMode)
            val twoCols = kind.square && modes.size <= 4
            // Nothing: a one-letter label (L, M, H, S), since the ANC levels' fine rings blur at dot resolution.
            val letters = WidgetSettings.nothingStyle(context)
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
                paint(context, v, b, p, selected)
                content(context, v, b, p, selected, mode.icon, when {
                    letters -> context.getString(mode.short).take(1).uppercase()
                    else -> context.getString(mode.short)
                }, 26f)
                v.setContentDescription(b.root, context.getString(mode.name))
                v.setOnClickPendingIntent(b.root, receiverPI(context, WidgetActions.ACTION_ANC_SELECT, id, if (selected) "off" else mode.key))
            }
        }

        /**
         * One battery ring as a bitmap: outline track, accent arc from 12 o'clock, and the glyph at
         * its true ratio, tinted by wear (BudsStatusView.wearTint). Small on purpose: RemoteViews
         * bitmaps count against the host's memory limit.
         */
        private fun ring(context: Context, p: Palette, level: Int, slot: Int, status: Int, sizeDp: Float): Bitmap {
            val px = ThemeRes.dp(context, sizeDp).coerceAtLeast(1)
            if (WidgetSettings.nothingStyle(context)) return dotRing(context, p, level, slot, nothingTint(p, slot == 1, status), px)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            drawRing(context, Canvas(bmp), px.toFloat(), p, level, slot, BudsStatusView.wearTint(p, slot == 1, status))
            return bmp
        }

        /**
         * Nothing style: ring and glyph drawn as one, then turned into one dot matrix, so the ring's dots sit on the
         * glyph's grid, [px] square. The same [RING_CELLS] on every size, so each ring is the 2x2's image scaled.
         * [tint] opaque, since a dot is either there or not. Also the app's home rings (BudsStatusView).
         */
        fun dotRing(context: Context, p: Palette, level: Int, slot: Int, tint: Int, px: Int): Bitmap {
            val n = RING_CELLS
            return matrix(n, n, px / n.toFloat(), despeckle = slot != 1) { c, size ->
                val g = drawRing(context, c, size, p, level, slot, tint, size * 2.6f / n, dim(p), if (slot == 1) 1.22f else 1.18f)
                if (slot == 1) clearLed(c, g, size / n)
            }
        }

        /** Outline track, accent arc from 12 o'clock, and the glyph in [tint] at its true ratio, in a [size] square. */
        /** [fill] scales the glyph's box: Nothing's thin ring sits closer around a bigger glyph. */
        private fun drawRing(context: Context, c: Canvas, size: Float, p: Palette, level: Int, slot: Int, tint: Int, stroke: Float = size * 0.085f, track: Int = p.outline, fill: Float = 1f): android.graphics.Rect {
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
            val boxH = size * (if (isCase) 0.46f else 0.6f) * fill
            val boxW = size * (if (isCase) 0.62f else 0.42f) * fill
            val (w, h) = if (ratio < boxW / boxH) boxH * ratio to boxH else boxW to boxW / ratio
            glyph.setTint(tint)
            glyph.setBounds(((size - w) / 2).toInt(), ((size - h) / 2).toInt(), ((size + w) / 2).toInt(), ((size + h) / 2).toInt())
            glyph.draw(c)
            return glyph.bounds
        }

        /**
         * Nothing: clears the dot-matrix cells ([cell] px) of the case glyph drawn in [g] that hold its details, so they
         * always show: the LED (a 9-unit hole at 247.6, 316.5 of 496 x 400) empties one dot, the lid cut (y 137 to
         * 152.6) one row. Both are under a cell tall and fell between cell centres at some sizes.
         */
        private fun clearLed(c: Canvas, g: android.graphics.Rect, cell: Float) {
            val clear = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
            fun cellOf(v: Float) = Math.floor(v / cell.toDouble()).toFloat() * cell
            val x = cellOf(g.left + 247.6f / 496 * g.width())
            val y = cellOf(g.top + 316.5f / 400 * g.height())
            c.drawRect(x, y, x + cell, y + cell, clear)
            val lid = cellOf(g.top + 144.8f / 400 * g.height())
            c.drawRect(g.left.toFloat(), lid, g.right.toFloat(), lid + cell, clear)
        }

        /**
         * The mode icons and the bolt as dot art. White. Null: not one of them.
         *
         * Mode icons are drawn like the battery rings: strokes 1.5 cells, gaps 1.5+
         * (2 cells merged High's rings), on a [MODE_GRID] grid, one dot per covered cell ([matrix]). Thin one-dot rings read as lines,
         * not dots, and grid-snapped thin circles came out square. Each icon fills its box: the outer ring
         * touches the edge. Sizes in cells from the centre.
         *
         * The bolt: pixel art on its own grid (the rows asked for), lines two dots thick.
         */
        private fun gridIcon(res: Int, rowsIn: Int): Bitmap? {
            if (res != R.drawable.ic_low_latency) return modeIcon(res)
            val n = rowsIn or 1                          // odd
            val k = n / 24f                              // cells per vector unit
            val cells = HashSet<Int>()
            fun set(x: Int, y: Int) { if (x in 0 until n && y in 0 until n) cells.add(y * n + x) }
            fun cx(u: Float) = Math.round(u * k - 0.5f + (n - 24 * k) / 2f)
            /** Two dots thick: the second dot beside a steep line, under a flat one. */
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                val dx = cx(x2) - cx(x1); val dy = cx(y2) - cx(y1)
                val steps = Math.max(Math.abs(dx), Math.abs(dy)).coerceAtLeast(1)
                val steep = Math.abs(dy) > Math.abs(dx)
                for (i in 0..steps) {
                    val x = Math.round(cx(x1) + dx * i / steps.toFloat()); val y = Math.round(cx(y1) + dy * i / steps.toFloat())
                    set(x, y); if (steep) set(x + 1, y) else set(x, y + 1)
                }
            }
            line(15f, 1.5f, 6.5f, 12.5f); line(6.5f, 12.5f, 17.5f, 11.5f); line(17.5f, 11.5f, 9f, 22.5f)
            val pitch = 8f
            val out = Bitmap.createBitmap((n * pitch).toInt(), (n * pitch).toInt(), Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            for (i in cells) cv.drawCircle((i % n + 0.5f) * pitch, (i / n + 0.5f) * pitch, pitch * 0.42f, paint)
            return out
        }

        /**
         * A mode icon (see [gridIcon]), or null. Also the app's noise-control segments (AncSegmentedView). Sizes in cells of the [MODE_GRID] grid, from the centre (15.5).
         * Rings are 2 cells wide on radii of k + 0.5, so each covers exactly the dots k and k + 1 out along an
         * axis: edges on cell boundaries left stray dots between rings. Gaps are 2+ cells.
         */
        fun modeIcon(res: Int, pitch: Float = 8f): Bitmap? {
            val n = MODE_GRID
            val m = n / 2f
            val out = m - 1f                             // the outer ring: dots 14 and 15, the last ones in the box
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val draw: (Canvas) -> Unit = when (res) {
                R.drawable.ic_mode_anc_low -> { c -> c.drawCircle(m, m, 5.4f, fill); c.drawCircle(m, m, out, stroke) }
                R.drawable.ic_mode_anc_medium -> { c -> c.drawCircle(m, m, 3.9f, fill); c.drawCircle(m, m, out - 5f, stroke); c.drawCircle(m, m, out, stroke) }
                R.drawable.ic_mode_anc_high -> { c -> c.drawCircle(m, m, 3.4f, fill); for (r in 0..2) c.drawCircle(m, m, out - 4f * r, stroke) }
                // The outer arc leaves the top-right open for the sparkle, as in the vector.
                R.drawable.ic_mode_anc_smart -> { c ->
                    c.drawCircle(m, m, 3.9f, fill); c.drawCircle(m, m, out - 5f, stroke)
                    c.drawArc(RectF(m - out, m - out, m + out, m + out), -20f, 280f, false, stroke)
                    sparkle(c, n - 5.5f, 5.5f, 3.7f, stroke)
                }
                // Identical 3x3-dot blocks spaced evenly (8 inner, 12 outer), half a step off the axes so the pattern is
                // symmetric: grid-sampled dashes each came out a different shape.
                R.drawable.ic_mode_transparency -> { c ->
                    c.drawCircle(m, m, 3.9f, fill)
                    for ((r, count) in listOf(9.2f to 8, 13.9f to 12)) for (i in 0 until count) {
                        val a = 2 * Math.PI * (i + 0.5) / count
                        // Snapped to whole cells, so every block covers the same 3x3 dots.
                        val x = Math.round(m + r * Math.cos(a).toFloat() - 1.5f).toFloat()
                        val y = Math.round(m + r * Math.sin(a).toFloat() - 1.5f).toFloat()
                        c.drawRect(x, y, x + 3f, y + 3f, fill)
                    }
                }
                R.drawable.ic_mode_adaptive -> { c ->
                    val x = m - 2f; val y = m + 2f; val r = out - 2f
                    c.drawCircle(x, y, 3.9f, fill); c.drawCircle(x, y, r, stroke)
                    sparkle(c, n - 5.5f, 5.5f, 3.7f, stroke)
                }
                R.drawable.ic_mode_off -> { c ->
                    c.drawCircle(m, m, out, stroke)
                    val d = out * 0.7071f
                    c.drawLine(m - d, m - d, m + d, m + d, stroke)
                }
                else -> return null
            }
            return matrix(n, n, pitch) { c, size -> c.save(); c.scale(size / n, size / n); draw(c); c.restore() }
        }

        /** A plus-shaped sparkle centred on ([x], [y]), arms [arm] cells long. */
        private fun sparkle(c: Canvas, x: Float, y: Float, arm: Float, stroke: Paint) {
            c.drawLine(x - arm, y, x + arm, y, stroke)
            c.drawLine(x, y - arm, x, y + arm, stroke)
        }

        /**
         * The Nothing style's icon dot pitch, the same on every size: finer on the 2x2 made the mode
         * icons stop reading as dots.
         */
        private const val DOT_DP = 1.87f

        /**
         * Dots per icon, the same on every size: the 2x2 is the baseline and the bigger widgets show
         * the same icons with bigger dots. Battery rings: cells across; the rest:
         * rows of the icon.
         */
        private const val RING_CELLS = 42
        /**
         * The mode icons' grid, the same in the list and on the controls page of every size: per-size grids
         * rounded the rings differently, so the same icon changed shape between screens.
         * 31: 21 made the icons' circles square once they filled their boxes. High's ball and
         * three 2-cell rings with 2-cell gaps fit.
         */
        private const val MODE_GRID = 31

        /** The case bar's dot pitch: [DOT_DP] scaled like the 2x2's percentage text (20 vs 22sp). */
        private fun dotDp(kind: Kind) = DOT_DP * 20f / 22f

        /**
         * The Nothing style's glyph shade, which replaces the wear text: in ear `text`, out of
         * ear `textSecondary` at 65% over the card, in case a third, darker grey, below the ring's unlit dots ([dim]) so it is not
         * mistaken for them. Opaque, since a dot is either there or not. The case glyph is always `text`.
         */
        fun nothingTint(p: Palette, isCase: Boolean, status: Int): Int = when {
            isCase || status == 3 || status == 7 -> p.text
            // Almost invisible: 0.3 sat too close to out of ear, 0.12 a touch too dark.
            status == 4 || status == 0 -> Palette.blend(boxColor(p), p.textSecondary, 0.17f)
            // Plain `textSecondary` read too close to white.
            else -> Palette.blend(boxColor(p), p.textSecondary, 0.65f)
        }

        /** Unlit dots (a ring's or the case bar's empty part): halfway to the secondary text, the outline was too faint. */
        private fun dim(p: Palette) = Palette.blend(boxColor(p), p.textSecondary, 0.5f)

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
                // The LED always empties one dot ([clearLed]).
                R.drawable.ic_case -> matrix(cols, rows, 8f, 128, Color.WHITE, true) { c, w ->
                    d.setTint(Color.WHITE); d.setBounds(0, 0, c.width, c.height); d.draw(c)
                    clearLed(c, d.bounds, w / cols)
                }
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
        private fun matrix(cols: Int, rows: Int, pitch: Float, min: Int = 128, color: Int? = null, centre: Boolean = true, despeckle: Boolean = false, draw: (Canvas, Float) -> Unit): Bitmap {
            val ss = 8
            val bw = cols * ss
            val big = Bitmap.createBitmap(bw, rows * ss, Bitmap.Config.ARGB_8888)
            draw(Canvas(big), bw.toFloat())
            val px = IntArray(bw * rows * ss).also { big.getPixels(it, 0, bw, 0, 0, bw, rows * ss) }
            val out = Bitmap.createBitmap(Math.round(cols * pitch).coerceAtLeast(1), Math.round(rows * pitch).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val lit = BooleanArray(cols * rows)
            val colors = IntArray(cols * rows)
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
                lit[y * cols + x] = true
                colors[y * cols + x] = color ?: (solid or 0xFF000000.toInt())
            }
            // [despeckle]: a lone dot (the bud's tip over the head) is left out.
            fun on(x: Int, y: Int) = x in 0 until cols && y in 0 until rows && lit[y * cols + x]
            for (y in 0 until rows) for (x in 0 until cols) {
                if (!lit[y * cols + x]) continue
                if (despeckle && !on(x - 1, y) && !on(x + 1, y) && !on(x, y - 1) && !on(x, y + 1)) continue
                paint.color = colors[y * cols + x]
                c.drawCircle((x + 0.5f) * pitch, (y + 0.5f) * pitch, pitch * 0.42f, paint)
            }
            return out
        }

        /**
         * The 2x2 / 3x3 battery's case bar, 70% of the case icon's height, the level inside it. Classic: `outline`
         * track, `accent` fill, corners rounded at a third of its height, the number cut out of it. Nothing
         * style: dot rows 1.4x the widget's pitch, 70% of the case icon's height, the percentage cut out of the dots ([GLYPHS]),
         * lit column by column up to the level, the corner dots left out so the corners are round. Drawn exactly as wide as its slot ([barDp]), so the dots
         * are never scaled.
         */
        private fun bar(context: Context, p: Palette, level: Int, kind: Kind, id: Int, caseDp: Float): Bitmap {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.outline }
            if (WidgetSettings.nothingStyle(context)) {
                // 7 rows at that pitch, as many as fill 70% of the case icon's height, the percentage inside.
                val pitch = dotDp(kind) * 7 / 5 * context.resources.displayMetrics.density
                // 70% of the case icon's height (as tall as the icon was too big); odd, so the 7-row
                // digits sit exactly in the middle.
                val rows = ((ThemeRes.dp(context, caseDp * 0.7f) / pitch).toInt() - 1 or 1).coerceAtLeast(9)
                val cols = (ThemeRes.dp(context, barDp(context, kind, id, caseDp)) / pitch).toInt().coerceAtLeast(rows + 1)
                val bmp = Bitmap.createBitmap(Math.round(cols * pitch), Math.round(rows * pitch), Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                // The percentage is part of the matrix: 5x7 digits in inverted dots. Centred; a label
                // wider than the bar is left out.
                val label = pctLabel(context, level).mapNotNull { GLYPHS[it] }
                val tw = label.size * 6 - 1
                val a = (cols - tw) / 2                        // first text column
                val b = (rows - 7) / 2                         // first text row
                fun text(x: Int, y: Int): Boolean {
                    if (tw > cols - 2 || x < a || x >= a + tw || y < b || y >= b + 7 || (x - a) % 6 == 5) return false
                    return label[(x - a) / 6][y - b][(x - a) % 6] == '1'
                }
                val lit = if (level in 1..100) maxOf(1, Math.round(cols * level / 100f)) else 0
                for (x in 0 until cols) for (y in 0 until rows) {
                    // Rounded corners, radius a third of the height (the old pill ends, a curve of
                    // r + 0.4, stepped like an octagon once the bar grew): drop the cells whose centre lies outside.
                    val r = rows / 3f
                    val dx = r - minOf(x, cols - 1 - x) - 0.5f
                    val dy = r - minOf(y, rows - 1 - y) - 0.5f
                    if (dx > 0 && dy > 0 && dx * dx + dy * dy > (r - 0.3f) * (r - 0.3f)) continue
                    // The digits in `text`, like the buds' percentages (they were inverted dots).
                    paint.color = if (text(x, y)) p.text else if (x < lit) p.accent else dim(p)
                    c.drawCircle((x + 0.5f) * pitch, (y + 0.5f) * pitch, pitch * 0.42f, paint)
                }
                return bmp
            }
            // Classic (the Nothing layout's ideas, smooth): as wide as its slot, the number knocked out.
            val w = ThemeRes.dp(context, barDp(context, kind, id, caseDp)).coerceAtLeast(1)
            val h = ThemeRes.dp(context, caseDp * 0.7f).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val box = RectF(0f, 0f, w.toFloat(), h.toFloat())
            c.drawRoundRect(box, h / 3f, h / 3f, paint)
            if (level in 1..100) {
                paint.color = p.accent
                c.save()
                c.clipRect(0f, 0f, w * level / 100f, h.toFloat())
                c.drawRoundRect(box, h / 3f, h / 3f, paint)
                c.restore()
            }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                textSize = h * 0.6f
                textAlign = Paint.Align.CENTER
                color = p.text
            }
            val fm = text.fontMetrics
            c.drawText(pctLabel(context, level), w / 2f, h / 2f - (fm.ascent + fm.descent) / 2, text)
            return bmp
        }

        /**
         * The 2x2 battery page's spacing in dp, per style. Mirrors scripts/widget-layouts.py `GEO`; keep them equal.
         * [side] / [vert] the page padding, [gap] between boxes, ring panel paddings [top], [outer] (widget side),
         * [inner] (the other ring's side) and [bottom], [row] the case row's padding, [pm] the percentage's top margin.
         */
        private class Geo(val side: Float, val vert: Float, val gap: Float, val top: Float, val outer: Float, val inner: Float, val bottom: Float, val row: Float, val pm: Float)
        private val GEO_NOTHING = Geo(3f, 7f, 4f, 3f, 3f, 1f, 4f, 3f, 5f)
        private val GEO_CLASSIC = Geo(6f, 8f, 6f, 4f, 7f, 7f, 3f, 4f, 2f)
        private fun geo(c: Context) = if (WidgetSettings.nothingStyle(c)) GEO_NOTHING else GEO_CLASSIC

        /** The height of a battery text as the layout draws it ([pctPaint]), in dp. */
        private fun textDp(context: Context, paint: Paint) = paint.fontMetrics.let { it.descent - it.ascent } / context.resources.displayMetrics.density

        /** 2x2: a battery ring's size in dp, as wide as its panel. */
        private fun ringWidthDp(context: Context, kind: Kind, w: Int): Float {
            val g = geo(context)
            return (w - 2 * g.side - g.gap) / 2 - g.outer - g.inner
        }

        /**
         * The case bar's slot width in dp: the widget's width less everything else in the row (page and row
         * paddings, the case icon and its margin). The level sits inside the bar.
         * Falls back to 60dp when the host gives no size.
         */
        private fun barDp(context: Context, kind: Kind, id: Int, caseDp: Float): Float {
            val w = AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            if (w <= 0) return 60f
            val g = geo(context)
            return (w - 2 * g.side - 2 * g.row - caseDp * 496f / 400f - 6f - 2f).coerceAtLeast(20f)
        }

        /**
         * 2x2: the case row's content height in dp, all the height the rings leave (only
         * paddings between them): page padding, ring panel (paddings, ring, percentage with its margin, Classic's
         * wear label), gap, case row padding ([Geo]). Falls back to 30dp when the host gives no size.
         */
        private fun caseRowDp(context: Context, kind: Kind, id: Int): Float {
            val o = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (w <= 0 || h <= 0) return 30f
            val g = geo(context)
            val ring = ringWidthDp(context, kind, w)
            val pct = textDp(context, pctPaint(context, Kind.BATTERY))
            val label = if (WidgetSettings.nothingStyle(context)) 0f else textDp(context, Paint().apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                textSize = 11.5f * context.resources.displayMetrics.scaledDensity
            })
            return (h - 2 * g.vert - (g.top + ring + g.pm + pct + label + g.bottom) - g.gap - 2 * g.row).coerceIn(20f, 120f)
        }

        /** Nothing, 2x2: the battery rings' dot pitch in dp ([RING_CELLS] across the ring). */
        private fun ringCellDp(context: Context, kind: Kind, id: Int): Float {
            val w = AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            if (w <= 0) return DOT_DP
            return ringWidthDp(context, kind, w) / RING_CELLS
        }

        /**
         * A percentage as the layout draws it: dot style Doto bold 17sp on the 2x2 20sp on
         * the 4x2; Classic sans-serif bold 16sp on the 2x2.
         */
        private fun pctPaint(context: Context, kind: Kind) = Paint().apply {
            val nothing = WidgetSettings.nothingStyle(context)
            typeface = android.graphics.Typeface.create(if (nothing) com.spizganed.quickbuds.ui.ThemeRes.dotFont(context) else android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            textSize = (if (kind.square) (if (nothing) 17f else 16f) else 20f) * context.resources.displayMetrics.scaledDensity
        }

        /** 5x7 dot digits for the case bar's percentage ([bar]), rows top to bottom: Doto's own digits, sampled from its 5x7 grid. */
        private val GLYPHS = mapOf(
            '0' to listOf("00100", "01010", "10001", "10001", "10001", "01010", "00100"),
            '1' to listOf("00100", "01100", "10100", "00100", "00100", "00100", "11111"),
            '2' to listOf("01110", "10001", "00001", "00110", "01000", "10000", "11111"),
            '3' to listOf("11111", "00001", "00010", "00110", "00001", "10001", "01110"),
            '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
            '5' to listOf("11111", "10000", "10110", "11001", "00001", "10001", "01110"),
            '6' to listOf("00110", "01000", "10000", "10110", "11001", "10001", "01110"),
            '7' to listOf("11111", "00001", "00010", "00010", "00100", "01000", "01000"),
            '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
            '9' to listOf("01110", "10001", "10011", "01101", "00001", "00010", "01100"),
            '—' to listOf("00000", "00000", "00000", "11111", "00000", "00000", "00000")
        )

        /** The level as the widgets show it: "57%" (Classic), just the number (Nothing), or a dash. */
        private fun pctLabel(context: Context, level: Int) =
            if (level !in 0..100) "—" else if (WidgetSettings.nothingStyle(context)) "$level" else "$level%"

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
        private fun receiverPI(context: Context, action: String, id: Int, target: String? = null, swap: Kind? = null, feature: Int = -1): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                this.action = action
                data = Uri.parse("quickbuds-widget://$id/$action/${target.orEmpty()}")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                if (target != null) putExtra(WidgetActions.EXTRA_ANC_TARGET, target)
                if (swap != null) putExtra(WidgetActions.EXTRA_PAGE, swap.name)
                if (feature >= 0) putExtra(WidgetActions.EXTRA_FEATURE, feature)
            }
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

/** 4x2. Keeps the original class name so placed widgets still work. */
class AncWidgetProvider : QuickBudsWidget(Kind.COMBINED) {
    companion object {
        fun refreshAll(context: Context) = QuickBudsWidget.refreshAll(context)
    }
}

/** 2x2, battery and controls pages. */
class BatteryWidgetProvider : QuickBudsWidget(Kind.BATTERY)

/** 2x2, battery page only. */
class BatteryOnlyWidgetProvider : QuickBudsWidget(Kind.BATTERY_ONLY)

/** 2x2, controls page only. */
class ControlsWidgetProvider : QuickBudsWidget(Kind.CONTROLS)
