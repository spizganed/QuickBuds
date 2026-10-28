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
import com.spizganed.quickbuds.ui.GlassArt
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
 * Every widget is resizable: [fit] picks a [Shape] for the size, the 2x2 and 4x2 pages or a sized layout that shows
 * battery and controls at once. A widget with a controls page has a setup screen ([WidgetSetupActivity]) for its buttons.
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
    enum class Kind(val fixed: Kind? = null) {
        BATTERY, CONTROLS, COMBINED, BATTERY_ONLY(BATTERY);

        /** The page shown; CONTROLS is a single-page widget too (its own [fixed] would be a forward reference). */
        val page get() = if (this == CONTROLS) CONTROLS else fixed
    }

    /**
     * A widget's arrangement at its current size ([fit]). PAGES_S and PAGES_M are the 2x2 and 4x2 pages: battery and
     * controls are two pages on the two-page widgets. The others show both at once and are sized at runtime.
     * [caseBar]: the case as a bar under the two bud panels, else a third panel.
     */
    enum class Shape(private val classic: Int, private val nothing: Int, private val material: Int, val paged: Boolean = false, val caseBar: Boolean = false) {
        PAGES_S(R.layout.widget_pages, R.layout.widget_pages_n, R.layout.widget_pages_md, paged = true, caseBar = true),
        PAGES_M(R.layout.widget_pages_m, R.layout.widget_pages_m_n, R.layout.widget_pages_m_md, paged = true),
        STRIP(R.layout.widget_strip, R.layout.widget_strip_n, R.layout.widget_strip_md),
        COLUMN(R.layout.widget_column, R.layout.widget_column_n, R.layout.widget_column_md),
        TALL(R.layout.widget_tall, R.layout.widget_tall_n, R.layout.widget_tall_md, caseBar = true),
        TALL_M1(R.layout.widget_tall_m1, R.layout.widget_tall_m1_n, R.layout.widget_tall_m1_md),
        TALL_M2(R.layout.widget_tall_m2, R.layout.widget_tall_m2_n, R.layout.widget_tall_m2_md),
        LIST(R.layout.widget_list, R.layout.widget_list_n, R.layout.widget_list_md),
        STACK(R.layout.widget_stack, R.layout.widget_stack_n, R.layout.widget_stack_md);

        /** The layout for the chosen style ([WidgetSettings.nothingStyle], [WidgetSettings.materialStyle]). */
        fun layout(c: Context) = if (WidgetSettings.nothingStyle(c)) nothing else if (WidgetSettings.materialStyle(c)) material else classic
    }

    /**
     * [shape] at the widget's size. Sizes in dp: [ring] and [font] (the number) for the sized shapes; [tiles] control
     * tiles shown, each [tileW] x [tileH]; [units] battery panels shown (0, 2 buds, or 3 with the case).
     */
    class Fit(val shape: Shape, val ring: Float = 0f, val font: Float = 0f, val tiles: Int = 4, val units: Int = 3, val tileW: Float = 0f, val tileH: Float = 99f)

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, mgr, id, kind, force = true)
    }

    /** Resized or first sized: the layout and every size follow the real size ([fit]). */
    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, options: android.os.Bundle) {
        update(context, mgr, id, kind, force = true)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) WidgetSettings.forget(context, id)
    }

    /** A button's view ids: the tappable frame, its fill and stroke, its icon and its text. */
    private class Btn(val root: Int, val bg: Int, val stroke: Int, val icon: Int, val label: Int)

    companion object {

        /** The kind of widget [id], from its provider; null for an unknown id. */
        fun kindOf(c: Context, id: Int): Kind? {
            val cls = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className ?: return null
            return providers.firstOrNull { it.first.name == cls }?.second
        }

        private val providers = listOf(
            BatteryWidgetProvider::class.java to Kind.BATTERY,
            BatteryOnlyWidgetProvider::class.java to Kind.BATTERY_ONLY,
            ControlsWidgetProvider::class.java to Kind.CONTROLS,
            AncWidgetProvider::class.java to Kind.COMBINED
        )

        private val CONN = Btn(R.id.w_conn, R.id.w_conn_bg, R.id.w_conn_stroke, R.id.w_conn_dot, R.id.w_conn_text)

        /** The Glass chip's view ids: its capsule bitmap, its dot and its label. It has its own size (44dp), so it needs its own views. */
        private class GlassBtn(val root: Int, val bg: Int, val dot: Int, val label: Int)
        private val GCON = GlassBtn(R.id.w_gconn, R.id.w_gconn_bg, R.id.w_gconn_dot, R.id.w_gconn_text)

        /** The Connect chip's look, one per style: Glass the app's pill, Material a tonal fill, QuickBuds a stroked panel, Pixel art a dashed outline. */
        private enum class ConnStyle { GLASS, MATERIAL, QUICKBUDS, PIXEL }

        /**
         * The chip's one text size: 14sp when the widget is narrower than 175dp, 18sp above it.
         * "Connecting…" at 18sp needs about 169dp with the chip's padding, so a 2x2 uses 14sp and never
         * ellipsizes. An unknown width takes the safe small size.
         */
        private fun connTextSp(c: Context, id: Int): Float {
            val w = AppWidgetManager.getInstance(c).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            return if (w >= 175) 18f else 14f
        }

        private fun connStyle(c: Context, p: Palette) = when {
            p.glass -> ConnStyle.GLASS
            WidgetSettings.materialStyle(c) -> ConnStyle.MATERIAL
            WidgetSettings.nothingStyle(c) -> ConnStyle.PIXEL
            else -> ConnStyle.QUICKBUDS
        }

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

        /** Battery panel frames per slot (0 left, 1 case, 2 right). */
        private val PANEL_ROOTS = intArrayOf(R.id.w_panel_left, R.id.w_panel_case, R.id.w_panel_right)

        /** Battery panel ids per slot (0 left, 1 case, 2 right): panel fill, ring, percentage, label, outline. */
        private val PANELS = listOf(
            intArrayOf(R.id.w_panel_left_bg, R.id.w_ring_left, R.id.w_pct_left, R.id.w_label_left, R.id.w_panel_left_stroke),
            intArrayOf(R.id.w_panel_case_bg, R.id.w_ring_case, R.id.w_pct_case, R.id.w_label_case, R.id.w_panel_case_stroke),
            intArrayOf(R.id.w_panel_right_bg, R.id.w_ring_right, R.id.w_pct_right, R.id.w_label_right, R.id.w_panel_right_stroke)
        )

        /** Repaints every placed widget of every size: state, palette or settings changed. */
        fun refreshAll(context: Context) {
            // The first levels after a connect start the fill-in sweep.
            if (awaitLevels) {
                val st = WidgetStateStore.read(context)
                if (st.leftBattery >= 0 || st.caseBattery >= 0 || st.rightBattery >= 0) { awaitLevels = false; return sweep(context, true, null) }
            }
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
        /** What each widget last showed: an update that would draw the same is not sent (a resend made the widget blink). */
        private val lastSig = java.util.concurrent.ConcurrentHashMap<Int, String>()

        private fun update(context: Context, mgr: AppWidgetManager, id: Int, declared: Kind, force: Boolean = false) {
            val kind = declared
            // A throw here would leave the host showing "Can't load widget" with no trace.
            try {
                val fit = fit(context, kind, id)
                if (sweepK < 0f) {
                    val sig = listOf(kind, fit.shape, fit.ring, fit.tiles, fit.units, WidgetStateStore.read(context), WidgetSettings.palette(context).tokens.toList(),
                        context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).all.toSortedMap().toString(),
                        connecting, connectTick, awaitLevels).joinToString("|")
                    if (!force && lastSig[id] == sig) return
                    lastSig[id] = sig
                } else lastSig.remove(id)
                if (sweepPartial && sweepK >= 0f && fit.shape.paged) {
                    // A sweep frame changes only the rings, the numbers, the case bar and the page's dimming: a partial update is much lighter.
                    val state = WidgetStateStore.read(context)
                    val shown = if (state.connected) state else sweepState ?: state
                    val pv = RemoteViews(context.packageName, fit.shape.layout(context))
                    battery(context, pv, WidgetSettings.palette(context), shown, fit, id, sweepK)
                    pv.setFloat(R.id.w_content, "setAlpha", sweepK)
                    mgr.partiallyUpdateAppWidget(id, pv)
                    return
                }
                val (v, child) = build(context, WidgetStateStore.read(context), kind, id, fit)
                if (child >= 0) {
                    val prev = WidgetSettings.shownChild(context, id)
                    // The list opens from the controls page, so under it the slide shows controls.
                    val outer = if (child == 2) 1 else 0
                    val inner = if (child == 2) 1 else child
                    if (prev < 0 || (prev == 2) != (child == 2)) v.setDisplayedChild(R.id.w_pages, outer)
                    if (prev != child) WidgetSettings.setShownChild(context, id, child)
                    v.setViewVisibility(R.id.w_content, if (outer == 0) View.VISIBLE else View.GONE)
                    v.setViewVisibility(R.id.w_page2, if (outer == 1) View.VISIBLE else View.GONE)
                    // The sized shapes have no pages: battery and controls show together.
                    if (fit.shape.paged) {
                        // One flipper per page (child 1 = the page, 0 = empty), so battery moves on the left, controls on the right.
                        if (prev < 0 || (if (prev == 2) 1 else prev) != inner) {
                            v.setDisplayedChild(R.id.w_slide0, if (inner == 0) 1 else 0)
                            v.setDisplayedChild(R.id.w_slide1, if (inner == 1) 1 else 0)
                        }
                        v.setViewVisibility(R.id.w_page0, if (inner == 0) View.VISIBLE else View.GONE)
                        v.setViewVisibility(R.id.w_page1, if (inner == 1) View.VISIBLE else View.GONE)
                    }
                }
                mgr.updateAppWidget(id, v)
            } catch (t: Throwable) {
                Log.e("BudsWidget", "update failed for $kind #$id", t)
            }
        }

        /** The views and the flipper child to show (-1: the disconnected layout, no flippers); [update] decides whether to send the child. */
        private fun build(context: Context, state: WidgetStateStore.State, provider: Kind, id: Int, fit: Fit): Pair<RemoteViews, Int> {
            val p = WidgetSettings.palette(context)
            val live = state.connected
            // [k]: how connected the battery page looks, 0 ghost rings, 1 full. Between 0 and 1 a connect or disconnect sweeps ([animate]).
            val k = if (sweepK >= 0f) sweepK else if (live && !awaitLevels) 1f else 0f
            val ghost = !live && sweepK < 0f
            val shown = if (live) state else sweepState ?: state.also { it.leftStatus = -1; it.rightStatus = -1 }

            val page = if (!live) Kind.BATTERY else provider.page ?: WidgetSettings.page(context, id, Kind.BATTERY)
            val list = live && page == Kind.CONTROLS && WidgetSettings.listOpen(context, id)
            val v = RemoteViews(context.packageName, fit.shape.layout(context))
            // The host reapplies an update with the same layout onto the views it has, so switching
            // a flipper's child animates. The flip side: every state set here must be set both ways
            // (visibility, click), or the previous update's sticks.
            val child = if (list) 2 else if (page == Kind.BATTERY) 0 else 1
            boxBg(context, v, p, id)
            // Every tap on a two-page widget carries the other page, so a second tap swaps.
            val other = if (page == Kind.BATTERY) Kind.CONTROLS else Kind.BATTERY
            val swap = if (live && !list && provider.page == null) other else null
            v.setOnClickPendingIntent(R.id.w_root, swap?.let { receiverPI(context, WidgetActions.ACTION_OPEN_APP, id, swap = it) })
            // The two-page widgets' dots: the shown page in the accent.
            v.setViewVisibility(R.id.w_dots, if (live && provider.page == null) View.VISIBLE else View.GONE)
            v.setInt(R.id.w_dot0, "setColorFilter", if (page == Kind.BATTERY) p.accent else dim(p))
            v.setInt(R.id.w_dot1, "setColorFilter", if (page == Kind.CONTROLS) p.accent else dim(p))

            // Disconnected: the Connect chip over the dimmed battery page. Both ways, see above.
            val chip = ghost
            val style = connStyle(context, p)
            // Glass has its own chip (a 44dp capsule); the other styles share the 56dp frame.
            val showGlass = chip && style == ConnStyle.GLASS
            val showPanel = chip && style != ConnStyle.GLASS
            v.setViewVisibility(CONN.root, if (showPanel) View.VISIBLE else View.GONE)
            v.setViewVisibility(GCON.root, if (showGlass) View.VISIBLE else View.GONE)
            if (chip) {
                // Four looks, one per style: each style has its own Connect button.
                val label = context.getString(if (connecting) R.string.conn_connecting else R.string.conn_action_connect)
                // Loading: the dot pulses until the link is up (or 15 s pass).
                val dot = if (connecting && connectTick % 2 == 0) R.drawable.ic_status_dot_filled else R.drawable.ic_status_dot_empty
                val root = if (showGlass) GCON.root else CONN.root
                when (style) {
                    ConnStyle.GLASS -> glassChip(context, v, p, label, dot, connTextSp(context, id))
                    ConnStyle.MATERIAL -> {
                        // Material: the chip is the widget's only control, so it keeps its tonal fill (with or without the box).
                        paint(context, v, CONN, p, true)
                        content(context, v, CONN, p, true, dot, label, 12f, 12f / 5, connTextSp(context, id))
                    }
                    ConnStyle.QUICKBUDS -> {
                        // QuickBuds: the stroked panel, the same as the other buttons.
                        paint(context, v, CONN, p, false)
                        content(context, v, CONN, p, false, dot, label, 12f, 12f / 5, connTextSp(context, id))
                    }
                    ConnStyle.PIXEL -> {
                        // Pixel art: the dashed outline, as the other buttons have it.
                        paint(context, v, CONN, p, false)
                        content(context, v, CONN, p, false, dot, label, 12f, 12f / 5, connTextSp(context, id))
                    }
                }
                v.setContentDescription(root, if (connecting) label else context.getString(R.string.conn_off) + ". " + label)
                v.setOnClickPendingIntent(root, if (connecting) null else connectPI(context))
            } else {
                v.setOnClickPendingIntent(CONN.root, null)
                v.setOnClickPendingIntent(GCON.root, null)
            }

            // Behind the chip: the empty rings, softened and faint (issue 23). While a connect waits for levels the page is blank.
            // It fades in with the sweep. Set both ways.
            v.setFloat(R.id.w_content, "setAlpha", if (ghost) GHOST_ALPHA else k)

            // Both pages and the list are filled every time, so the one sliding or fading out still
            // shows current values (a pick's highlight while the list closes).
            if (live) grid(context, v, p, state, fit, id)
            battery(context, v, p, shown, fit, id, k, ghost)
            if (live && !list) controls(context, v, p, state, fit, id, swap)
            return v to child
        }

        /**
         * The shape and sizes for widget [id] at its size (portrait: min width, max height). Mirrors the widget mockup:
         * one row high is a STRIP, one cell wide a COLUMN, and a wide battery-only widget at 3 rows is a STACK;
         * every other size keeps the 2x2 or 4x2 pages. Tiles fill the space the battery leaves, in setup order.
         * Falls back to the provider's own size when the host gives none.
         */
        fun fit(c: Context, kind: Kind, id: Int): Fit {
            val o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat()
            val g = geo(c)
            // The 2x2 and 4x2 pages; their tile size is only for the label choice ([controls]).
            fun pages(wide: Boolean): Fit {
                val pad = if (WidgetSettings.materialStyle(c)) 4f else 8f
                val tw = if (wide) (w - 2 * g.side - 3 * g.gap) / 4 - 2 * pad else (w - 2 * g.side - g.gap) / 2 - 2 * pad
                return Fit(if (wide) Shape.PAGES_M else Shape.PAGES_S, tileW = tw)
            }
            if (w <= 0 || h <= 0) return pages(kind == Kind.COMBINED)
            val battery = kind != Kind.CONTROLS
            val controls = kind != Kind.BATTERY_ONLY
            val keys = if (controls) WidgetSettings.buttons(c, id).size.coerceAtLeast(1) else 0
            // Mirrors scripts/widget-layouts.py: PP the panel padding, GAP between panels and tiles.
            val pp = if (WidgetSettings.materialStyle(c)) 2f else if (WidgetSettings.nothingStyle(c)) 4f else 6f
            val gap = 5f
            val iw = w - 2 * g.side
            val ih = h - 2 * g.vert
            fun num(d: Float) = (d * 0.3f).coerceIn(13f, 36f)
            // A battery panel's height: ring, number line, paddings.
            fun unitH(d: Float) = d + 2f + num(d) * 1.3f + 2 * pp
            // The largest ring whose panel fits [avail] high.
            fun byH(avail: Float): Float { var d = avail; while (d > 16f && unitH(d) > avail) d -= 1f; return d }
            // The shaped sizes start below the 2x2's own minimum (110dp), so the pages keep their size.
            if (h < 110f) {
                if (!battery) return Fit(Shape.STRIP, tiles = keys, units = 0, tileW = (iw - gap * (keys - 1)) / keys, tileH = ih)
                // Units and tiles share the row equally. Up to 4 tiles, while each is 44dp or wider and the
                // rings shrink by 8dp at most.
                fun slot(n: Int) = (iw - gap * (2 + n)) / (3 + n)
                fun ring(n: Int) = minOf(slot(n) - 2 * pp, byH(ih), 60f)
                val n = (keys downTo 0).first { it == 0 || (slot(it) >= 44f && ring(it) >= ring(0) - 8f) }
                return Fit(Shape.STRIP, ring(n), num(ring(n)), n, 3, slot(n), ih)
            }
            if (w < 110f) {
                if (!battery) return Fit(Shape.COLUMN, tiles = keys, units = 0, tileW = iw, tileH = (ih - gap * (keys - 1)) / keys)
                // The case needs about 3 rows.
                val k = if (h >= 250f) 3 else 2
                fun slot(n: Int) = (ih - gap * (k + n - 1)) / (k + n)
                fun ring(n: Int) = minOf(iw - 2 * pp, byH(slot(n)), 56f)
                val n = (keys downTo 0).first { it == 0 || (slot(it) >= 44f && ring(it) >= ring(0) - 8f) }
                return Fit(Shape.COLUMN, ring(n), num(ring(n)), n, k, iw, slot(n))
            }
            // Battery only, 3 rows and wide: both buds on top, the case below.
            if (!controls && w >= 250f && h >= 250f) {
                val d = minOf((iw - gap) / 2 - 2 * pp, byH((ih - gap) / 2), 120f)
                return Fit(Shape.STACK, d, num(d), 0)
            }
            // Every other size keeps the pages: battery, then the modes on a double tap. 4x2 and wider lay them out wide.
            return pages(w >= 250f)
        }

        /** The widget box: Nothing's own widgets' radius in the Nothing style, else per size. */
        private fun bgRes(c: Context) =
            if (WidgetSettings.nothingStyle(c)) R.drawable.widget_bg_n else R.drawable.widget_bg

        /** A panel or button inside the box, its corners parallel to the box's in the Nothing style. */
        /** Widget [id]'s box; Material without [WidgetSettings.background]: none, the content on the wallpaper. */
        private fun boxBg(c: Context, v: RemoteViews, p: Palette, id: Int) {
            if (!WidgetSettings.background(c, id)) return v.setImageViewResource(R.id.w_bg, android.R.color.transparent)
            if (p.glass) {
                // Glass: the plate drawn at the widget's size: translucent, in the app's chosen background colours, with a rim.
                v.setImageViewBitmap(R.id.w_bg, glassPlate(c, p, id))
                return v.setInt(R.id.w_bg, "setColorFilter", 0)
            }
            v.setImageViewResource(R.id.w_bg, bgRes(c))
            v.setInt(R.id.w_bg, "setColorFilter", boxColor(p))
        }

        /**
         * Glass: the widget's plate at its real size (so the corners stay round), about 0.7 of the screen's pixels to keep the
         * RemoteViews bitmap small: a nearly opaque base (a widget has no backdrop blur, so a see-through plate would show the wallpaper sharp), the app's background colours as soft blobs (tinted by the accent for the Accent
         * background), a diagonal sheen and a hairline rim. A launcher gives no blur behind a widget, so the wallpaper shows through.
         */
        private fun glassPlate(c: Context, p: Palette, id: Int): Bitmap {
            val o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id)
            val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 160
            val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 160
            val k = c.resources.displayMetrics.density * 0.7f
            val w = (wDp * k).toInt().coerceIn(40, 900); val h = (hDp * k).toInt().coerceIn(40, 900)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val rad = 26f * k
            val shape = RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f)
            val clip = android.graphics.Path().apply { addRoundRect(shape, rad, rad, android.graphics.Path.Direction.CW) }
            cv.save(); cv.clipPath(clip)
            val light = p.glassLight
            val oled = p.id == com.spizganed.quickbuds.ui.PaletteStore.OLED
            val base = if (light) 0xF4F7F8FF.toInt() else if (oled) 0xF2060609.toInt() else 0xF22A2D3B.toInt()
            cv.drawColor(base)
            val bg = ThemeRes.glassBg(c)
            val blobs = bg.blobs ?: if (bg == ThemeRes.GlassBg.ACCENT) intArrayOf(p.accent, Palette.blend(p.accent, 0xFF4A6DFF.toInt(), 0.5f), Palette.blend(p.accent, 0xFFFF9D6B.toInt(), 0.5f), Palette.blend(p.accent, Color.WHITE, 0.35f)) else null
            if (blobs != null) {
                val at = arrayOf(floatArrayOf(0.12f, 0.18f), floatArrayOf(0.88f, 0.12f), floatArrayOf(0.78f, 0.92f), floatArrayOf(0.08f, 0.95f))
                val alpha = if (light) floatArrayOf(0.5f, 0.42f, 0.36f, 0.4f) else floatArrayOf(0.42f, 0.3f, 0.24f, 0.28f)
                val r = maxOf(w, h) * 0.8f
                for (i in 0..3) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = android.graphics.RadialGradient(at[i][0] * w, at[i][1] * h, r, Palette.withAlpha(blobs[i], alpha[i]), Palette.withAlpha(blobs[i], 0f), android.graphics.Shader.TileMode.CLAMP)
                    }
                    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                }
            }
            val sheen = Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, w * 0.8f, h * 0.8f, if (light) 0x55FFFFFF else 0x22FFFFFF, 0, android.graphics.Shader.TileMode.CLAMP)
            }
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sheen)
            cv.restore()
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = k; color = if (light) 0x2814152B else 0x40FFFFFF }
            cv.drawRoundRect(shape, rad, rad, rim)
            return bmp
        }

        /** A battery panel or the case row's panel. Material draws none (one background). */
        private fun panelBg(c: Context, v: RemoteViews, id: Int, p: Palette) {
            if (p.glass) {
                // Glass: the battery panels and the case row melt into the plate; the bar draws its own capsule.
                return v.setImageViewResource(id, android.R.color.transparent)
            }
            if (WidgetSettings.materialStyle(c)) return v.setImageViewResource(id, android.R.color.transparent)
            v.setImageViewResource(id, panelRes(c))
            v.setInt(id, "setColorFilter", panelColor(c, p))
        }

        private fun panelRes(c: Context) =
            if (WidgetSettings.nothingStyle(c)) R.drawable.widget_panel_n else R.drawable.widget_panel

        /** The widget box: the card colour, lifted toward the text when the card is near black (OLED Black), or it vanishes on a dark wallpaper. */
        private fun boxColor(p: Palette) =
            if (Palette.luminance(p.card) < 0.005) Palette.blend(p.card, p.text, 0.11f) else p.card

        /**
         * Battery panel colour: `card` lightened ~4% toward `text`. The Nothing style lightens
         * the panel 7% toward `text`.
         */
        private fun panelColor(c: Context, p: Palette) =
            // Material: one background, as Google's widgets have; panels melt into the box and only a selection is filled.
            if (WidgetSettings.materialStyle(c)) boxColor(p)
            else if (WidgetSettings.nothingStyle(c)) Palette.blend(boxColor(p), p.text, 0.07f) else Palette.blend(boxColor(p), p.text, 0.04f)

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
            if (p.glass) {
                // Glass, as the app's noise control: every button a faint capsule, the lit one the accent.
                v.setImageViewResource(b.stroke, android.R.color.transparent)
                if (selected) {
                    v.setImageViewResource(b.bg, R.drawable.widget_pill_sel)
                    v.setInt(b.bg, "setColorFilter", p.accent)
                } else {
                    v.setImageViewResource(b.bg, if (p.glassLight) R.drawable.widget_pill_glass_l else R.drawable.widget_pill_glass)
                    v.setInt(b.bg, "setColorFilter", 0)
                }
                return
            }
            v.setImageViewResource(b.bg, panelRes(c))
            if (WidgetSettings.materialStyle(c)) {
                // Material: only a selected button is filled (tonal); no outline, no box otherwise.
                v.setImageViewResource(b.stroke, android.R.color.transparent)
                if (!selected) return v.setImageViewResource(b.bg, android.R.color.transparent)
                v.setInt(b.bg, "setColorFilter",
                    if (p.glass) p.accent   // Glass: the lit button is the accent, as in the app
                    else if (WidgetSettings.systemRoles(c) && android.os.Build.VERSION.SDK_INT >= 31) WidgetSettings.systemSelected(c, p.isLight)
                    else Palette.blend(panelColor(c, p), p.accent, 0.35f))
                return
            }
            v.setInt(b.bg, "setColorFilter", if (selected && !n) p.accent else panelColor(c, p))
            v.setImageViewResource(b.stroke, if (n) (if (selected) R.drawable.widget_panel_select_n else R.drawable.widget_panel_dots_n)
                else R.drawable.widget_panel_stroke)
            v.setInt(b.stroke, "setColorFilter", if (selected) p.accent else if (n) Palette.blend(boxColor(p), p.text, 0.2f) else p.outline)
        }

        /** Paints [b] with [icon] and [text]: selected on-accent (Classic fill) or accent (Nothing outline), else secondary. */
        private fun content(context: Context, v: RemoteViews, b: Btn, p: Palette, selected: Boolean, icon: Int, text: CharSequence?, iconDp: Float, pitch: Float = DOT_DP, textSp: Float? = null) {
            val fg = if (!selected) p.textSecondary else if (WidgetSettings.nothingStyle(context)) p.accent
                else if (p.glass) p.onAccent else if (WidgetSettings.materialStyle(context)) p.text else p.onAccent
            if (p.glass) {
                // Glass: the icon with depth, as in the app (a bitmap; no colour filter).
                val h = ThemeRes.dp(context, iconDp).coerceAtLeast(1)
                val d = context.getDrawable(icon)!!.mutate()
                val w = if (d.intrinsicHeight > 0) Math.round(h * d.intrinsicWidth.toFloat() / d.intrinsicHeight).coerceAtLeast(1) else h
                val pad = Math.round(h * 0.2f)
                val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
                GlassArt.Glyph(d).draw(Canvas(bmp), bmp.width / 2f, bmp.height / 2f - 1f, w.toFloat(), h.toFloat(), fg, h / 30f, p.glassLight)
                v.setImageViewBitmap(b.icon, bmp)
                v.setInt(b.icon, "setColorFilter", 0)
            } else {
                icon(context, v, b.icon, icon, iconDp, pitch)
                v.setInt(b.icon, "setColorFilter", fg)
            }
            if (text == null) v.setViewVisibility(b.label, View.GONE)
            else {
                v.setViewVisibility(b.label, View.VISIBLE)
                setText(context, v, b.label, text, fg, sizeDp = textSp)
            }
        }

        /**
         * Glass: the Connect chip as the app's own pill, drawn as a bitmap like the widget's plate: a capsule 44dp tall,
         * as wide as its content (18dp padding, a 6dp dot, an 8dp gap) and 200dp at most. Disconnected it is the accent
         * fill of a selected pill; while connecting it is neutral glass. The dot pulses and the label is the app's text.
         */
        private fun glassChip(context: Context, v: RemoteViews, p: Palette, text: String, dotRes: Int, textSp: Float) {
            val d = context.resources.displayMetrics.density
            // The label as setText draws it, at the chip's one text size ([connTextSp]).
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = materialFont(context, false); textSize = textSp * d }
            val textDp = Math.ceil(label.measureText(text).toDouble()).toFloat() / d
            // 18dp + 6dp dot + 8dp gap + label + 18dp; the capsule never passes 200dp.
            val widthDp = (50f + textDp).coerceAtMost(200f)
            // The plate's 0.7 scale: the host scales the bitmap up to the chip's size.
            val k = d * 0.7f
            val w = Math.round(widthDp * k).coerceAtLeast(1)
            val h = Math.round(44f * k).coerceAtLeast(1)
            v.setImageViewBitmap(GCON.bg, glassCapsule(context, p, w, h, connecting))
            v.setInt(GCON.bg, "setColorFilter", 0)
            v.setImageViewResource(GCON.dot, dotRes)
            v.setInt(GCON.dot, "setColorFilter", if (connecting) 0xFFFFC857.toInt() else p.textSecondary)
            v.setViewVisibility(GCON.label, View.VISIBLE)
            setText(context, v, GCON.label, text, if (connecting) p.textSecondary else p.onAccent, sizeDp = textSp)
        }

        /**
         * Glass: the chip's capsule. Disconnected, the accent tint of a selected pill (`selected = !connected`),
         * 0.5 to 0.3 from top to bottom. Connecting, neutral glass over the plate. Both have the plate's diagonal
         * sheen at 6% white and a 1dp flat rim.
         */
        private fun glassCapsule(context: Context, p: Palette, w: Int, h: Int, connecting: Boolean): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            // The plate's scale: a 1dp rim and hairline stay crisp when the host scales the bitmap up.
            val d = context.resources.displayMetrics.density * 0.7f
            val box = RectF(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
            val r = box.height() / 2f
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = if (connecting) android.graphics.LinearGradient(box.left, box.top, box.right, box.bottom,
                    ThemeRes.glassAlpha(context, if (p.glassLight) 0x99FFFFFF.toInt() else 0x1CFFFFFF),
                    ThemeRes.glassAlpha(context, if (p.glassLight) 0x66FFFFFF else 0x0FFFFFFF), android.graphics.Shader.TileMode.CLAMP)
                else android.graphics.LinearGradient(0f, box.top, 0f, box.bottom,
                    Palette.withAlpha(p.accent, 0.5f), Palette.withAlpha(p.accent, 0.3f), android.graphics.Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(box, r, r, fill)
            // One diagonal white sheen from the top-left third, as on the plate.
            val capsule = android.graphics.Path().apply { addRoundRect(box, r, r, android.graphics.Path.Direction.CW) }
            c.save(); c.clipPath(capsule)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, w / 3f, h / 3f, 0x0FFFFFFF, 0, android.graphics.Shader.TileMode.CLAMP)
            })
            c.restore()
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = d
                color = if (p.glassLight) 0x1A14152B else 0x2EFFFFFF
            }
            c.drawRoundRect(box, r, r, rim)
            return bmp
        }

        /** [ghost]: disconnected. The empty rings are softened and the texts hide, so the Connect chip reads on top. */
        private fun battery(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, fit: Fit, id: Int, k: Float, ghost: Boolean = false) {
            val paged = fit.shape.paged
            val ringDp = if (paged) ringDp(context, fit.shape, id) else fit.ring
            // k < 1: rings drawn to k of their level and the glyphs part-way to their ghost colour.
            val levels = intArrayOf(state.leftBattery, state.caseBattery, state.rightBattery).map { if (it < 0) -1 else Math.round(it * k) }.toIntArray()
            val statuses = intArrayOf(state.leftStatus, -1, state.rightStatus)
            val names = intArrayOf(R.string.status_left, R.string.status_case, R.string.status_right)
            // 2x2 and TALL show the case as a bar under the two bud panels; the others have a case panel.
            val caseBar = fit.shape.caseBar
            // The sized shapes show 3 units, 2 (the buds, no case) or none (controls only).
            val shown = (if (caseBar) listOf(0, 2) else listOf(0, 1, 2)).filter { paged || fit.units == 3 || (fit.units == 2 && it != 1) }
            if (!paged) for (slot in 0..2) if (!caseBar || slot != 1) v.setViewVisibility(PANEL_ROOTS[slot], if (slot in shown) View.VISIBLE else View.GONE)
            for (slot in shown) {
                val (bgId, ringId, pct, label, strokeId) = PANELS[slot].toList()
                panelBg(context, v, bgId, p)
                panelOutline(context, v, strokeId, p)
                v.setImageViewBitmap(ringId, ring(context, p, levels[slot], slot, statuses[slot], ringDp, k).let { if (ghost && GHOST_BLUR) soften(context, it) else it })
                pctText(context, v, pct, p, levels[slot], if (paged) null else fit.font)
                v.setViewVisibility(pct, if (ghost) View.INVISIBLE else View.VISIBLE)
                val inEar = statuses[slot] == 3 || statuses[slot] == 7
                val text = if (slot == 1) context.getString(names[slot]) else wearLabel(context, statuses[slot]) ?: context.getString(names[slot])
                // Nothing, Material and the sized shapes: no wear text, the glyph's shade says it ([nothingTint]) and the ring's content description reads it.
                val noLabel = WidgetSettings.nothingStyle(context) || WidgetSettings.materialStyle(context) || !paged
                v.setViewVisibility(label, if (noLabel) View.GONE else if (ghost) View.INVISIBLE else View.VISIBLE)
                v.setContentDescription(ringId, if (slot == 1) text else context.getString(names[slot]) + ", " + text)
                if (!noLabel) setText(context, v, label, if (inEar) semibold(text) else text, if (inEar) p.text else p.textSecondary)
            }
            if (caseBar) {
                panelBg(context, v, R.id.w_bar_bg, p)
                panelOutline(context, v, R.id.w_bar_stroke, p)
                // The case icon takes all the height the rings leave ([caseRowDp]); TALL's case row is 32dp. Nothing: at the
                // rings' dot pitch, so the big icon keeps the app's case details (lid cut, LED).
                val caseDp = if (paged) caseRowDp(context, id) else 32f - 2 * geo(context).row
                if (ghost && GHOST_BLUR) {
                    // Disconnected: the case glyph is blurred like the rings, in every style.
                    val hpx = ThemeRes.dp(context, caseDp).coerceAtLeast(1)
                    val wpx = Math.round(hpx * 496f / 400f)
                    val gb = Bitmap.createBitmap(wpx, hpx, Bitmap.Config.ARGB_8888)
                    context.getDrawable(R.drawable.ic_case)!!.mutate().apply {
                        setTint(glyphTint(context, p, 1, -1, k)); setBounds(0, 0, wpx, hpx); draw(Canvas(gb))
                    }
                    v.setImageViewBitmap(R.id.w_case_icon, soften(context, gb))
                    v.setInt(R.id.w_case_icon, "setColorFilter", 0)
                } else if (p.glass) {
                    val hpx = ThemeRes.dp(context, caseDp).coerceAtLeast(1)
                    val wpx = Math.round(hpx * 496f / 400f)
                    val gb = Bitmap.createBitmap(wpx + 12, hpx + 12, Bitmap.Config.ARGB_8888)
                    GlassArt.Glyph(context.getDrawable(R.drawable.ic_case)!!.mutate())
                        .draw(Canvas(gb), gb.width / 2f, gb.height / 2f - 2f, wpx.toFloat(), hpx.toFloat(), glyphTint(context, p, 1, -1, k), hpx / 42f, p.glassLight)
                    v.setImageViewBitmap(R.id.w_case_icon, gb)
                    v.setInt(R.id.w_case_icon, "setColorFilter", 0)
                } else {
                    icon(context, v, R.id.w_case_icon, R.drawable.ic_case, caseDp, if (paged) ringCellDp(context, id) else fit.ring / RING_CELLS)
                }
                // A colour filter keeps its alpha as a lightening of the glyph, so the fading ghost colour is laid on the panel first.
                if (!p.glass && !(ghost && GHOST_BLUR)) v.setInt(R.id.w_case_icon, "setColorFilter",
                    androidx.core.graphics.ColorUtils.compositeColors(glyphTint(context, p, 1, -1, k), p.card))
                v.setImageViewBitmap(R.id.w_case_bar, bar(context, p, levels[1], id, caseDp).let { if (ghost && GHOST_BLUR) soften(context, it) else it })
                // The level is drawn into the bar ([bar]); the bar reads it out.
                v.setViewVisibility(R.id.w_pct_case, View.GONE)
                v.setContentDescription(R.id.w_case_bar, context.getString(R.string.status_case) + ", " + pctLabel(context, levels[1]))
            }
        }

        /**
         * The ring size. 2x2: drawn large enough and shrunk to the panel by the layout. 4x2:
         * the largest ring the panel holds at the widget's real size (portrait: min width, max
         * height), so ring and texts fill the panel as one centred group instead of a small ring
         * over a gap.
         */
        private fun ringDp(context: Context, shape: Shape, id: Int): Float {
            // Drawn large enough; the layout shrinks it to the panel's width.
            if (shape == Shape.PAGES_S) return 96f
            val o = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (w <= 0 || h <= 0) return 56f
            if (WidgetSettings.nothingStyle(context)) {
                // Mirrors scripts/widget-layouts.py: widget inset 3 (7 top and bottom), gaps 4, panel paddings 3 outside /
                // 1 inside and 3 top and bottom, percentage margin 5: the rings 6dp from the sides and apart, as on the 2x2.
                val pct = pctPaint(context, false).fontMetrics.let { it.descent - it.ascent } / context.resources.displayMetrics.density
                return minOf(h - 20f - 5f - pct, (w - 6f - 8f) / 3 - 4f).coerceIn(28f, 160f)
            }
            // Page padding 12 across, 20 down; panel padding 8; texts: percentage + label lines.
            val texts = if (WidgetSettings.materialStyle(context)) 24f * 1.3f + 2f
                else if (WidgetSettings.nothingStyle(context)) 20f * 1.3f + 2f else 18f * 1.3f + 12f * 1.3f + 2f
            val byHeight = h - 20f - 8f - texts - 6f
            val byWidth = (w - 12f - 6f * 2) / 3 - 8f
            return minOf(byHeight, byWidth).coerceIn(28f, 120f)
        }

        /** Percentage, always `text`: nothing in the battery display changes colour by level. [sizeDp]: the sized shapes' number size. */
        private fun pctText(context: Context, v: RemoteViews, id: Int, p: Palette, level: Int, sizeDp: Float? = null) {
            setText(context, v, id, pctLabel(context, level), p.text, strong = true, sizeDp = sizeDp)
        }

        /**
         * A widget text. Classic: a TextView. Dot matrix and Material: an ImageView showing the text drawn in Doto or
         * Google Sans Flex ([strong]: semibold), because launchers ignore `@font/` in widget XML. The layout sets the view's
         * height (sp x 1.2) and the bitmap scales to it, so this draws at one fixed size. With [sizeDp] (the sized shapes),
         * the text is drawn at that size and the view wraps it; Classic sets the TextView's size.
         */
        private fun setText(context: Context, v: RemoteViews, id: Int, text: CharSequence, color: Int, strong: Boolean = false, sizeDp: Float? = null) {
            val material = WidgetSettings.materialStyle(context)
            if (!WidgetSettings.nothingStyle(context) && !material) {
                v.setTextViewText(id, text)
                v.setTextColor(id, color)
                if (sizeDp != null) v.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_DIP, sizeDp)
                return
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = if (material) materialFont(context, strong) else com.spizganed.quickbuds.ui.ThemeRes.dotFont(context)
                textSize = (sizeDp ?: 32f) * context.resources.displayMetrics.density
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

        /** Material's widget font, like Google's widgets: Google Sans Flex (SIL OFL, bundled in res/font), medium or semibold. */
        private fun materialFont(context: Context, strong: Boolean): android.graphics.Typeface =
            androidx.core.content.res.ResourcesCompat.getFont(context, if (strong) R.font.google_sans_flex_semibold else R.font.google_sans_flex_medium)
                ?: android.graphics.Typeface.DEFAULT

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
        private fun controls(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, fit: Fit, id: Int, swap: Kind?) {
            val current = WidgetSettings.modeOf(state.ancMode)
            val levels = WidgetSettings.ancPicker(context)
            val inAnc = levels.any { it.key == current.key }
            fun mode(key: String) = WidgetSettings.MODES.first { it.key == key }
            /** First letters of the words, capitals: "Low latency" -> "LL" (translated names too). */
            fun initials(res: Int) = context.getString(res).split(' ').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }.uppercase()
            /** [label] the short label (initials), [full] the whole name ([labelMode] picks one). */
            class Q(val lit: Boolean, val icon: Int, val label: String, val desc: String, val pi: PendingIntent, val full: String = desc)
            fun quick(target: String) = receiverPI(context, WidgetActions.ACTION_QUICK, id, target, swap)
            fun q(key: String): Q = when (key) {
                "anc" -> Q(inAnc, if (inAnc) current.icon else R.drawable.ic_mode_anc_medium,
                    if (inAnc) "ANC " + context.getString(current.short).take(1).uppercase() else "ANC",
                    context.getString(if (inAnc) current.name else R.string.anc_section), quick("anc"),
                    if (inAnc) context.getString(current.name) else "ANC")
                "trans", "adapt" -> Q(current.key == key, mode(key).icon, initials(mode(key).name),
                    context.getString(mode(key).name), quick(key))
                "off" -> Q(current.key == "off", mode("off").icon, context.getString(R.string.anc_seg_off),
                    context.getString(R.string.anc_seg_off), quick("off"))
                "ll" -> Q(state.gameMode, R.drawable.ic_low_latency, initials(R.string.widget_low_latency),
                    context.getString(R.string.row_game_title), receiverPI(context, WidgetActions.ACTION_GAME_TOGGLE, id, swap = swap),
                    context.getString(R.string.widget_low_latency))
                else -> {
                    val f = WidgetSettings.FEATURE_BUTTONS.getValue(key)
                    Q(WidgetSettings.featureOn(context, f), R.drawable.ic_anc, initials(R.string.row_wind_noise_title),
                        context.getString(R.string.row_wind_noise_title),
                        receiverPI(context, WidgetActions.ACTION_FEATURE_TOGGLE, id, key, swap, f))
                }
            }
            val keys = WidgetSettings.buttons(context, id)
            // The sized shapes show the tiles that fit ([Fit.tiles]); the rest are left out.
            val shown = if (fit.shape.paged) QUICK.size else fit.tiles
            val qs = QUICK.indices.map { i -> keys.getOrNull(i)?.takeIf { i < shown && WidgetSettings.available(context, it) }?.let { q(it) } }
            val mode = qs.filterNotNull().let { l -> labelMode(context, l.map { it.full }, l.map { it.label }, fit) }
            QUICK.forEachIndexed { i, b ->
                val q = qs[i]
                v.setViewVisibility(b.root, if (q != null) View.VISIBLE else if (i < shown) View.INVISIBLE else View.GONE)
                if (q == null) return@forEachIndexed
                paint(context, v, b, p, q.lit)
                // Nothing: the bolt on 25 rows keeps its 2-dot lines close to the mode icons' weight; mode icons have their own grid.
                content(context, v, b, p, q.lit, q.icon, when (mode) { 0 -> q.full; 1 -> q.label; else -> null }, 25f, 1f)
                v.setContentDescription(b.root, q.desc)
                v.setOnClickPendingIntent(b.root, q.pi)
            }
        }

        /**
         * The tile labels, one choice for the whole widget so they match: 0 the full names when every one fits its tile,
         * 1 the short labels (initials, as before), -1 none (a tile under 40dp high, or not even the short ones fit).
         * Measured in the label's own font, so it holds in every language.
         */
        private fun labelMode(context: Context, full: List<String>, short: List<String>, fit: Fit): Int {
            if (fit.tileH < 40f) return -1
            // The label's font and size as the layouts set them: 17sp (Material) or 15sp on the pages, 12sp on the sized shapes.
            val sp = if (!fit.shape.paged) 12f else if (WidgetSettings.materialStyle(context)) 17f else 15f
            val paint = Paint().apply {
                typeface = if (WidgetSettings.materialStyle(context)) materialFont(context, false)
                    else if (WidgetSettings.nothingStyle(context)) ThemeRes.dotFont(context)
                    else android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                textSize = sp * context.resources.displayMetrics.density
            }
            // The tile's padding and a margin.
            val room = (fit.tileW * 0.9f - 4f) * context.resources.displayMetrics.density
            return listOf(full, short).indexOfFirst { names -> names.all { paint.measureText(it) <= room } }
        }

        /**
         * The ANC button's level picker ([controls]): 2x2 when a 2x2 / 3x3 widget has <= 4 levels, 3x2 otherwise.
         * No Off: the lit level turns ANC off.
         */
        private fun grid(context: Context, v: RemoteViews, p: Palette, state: WidgetStateStore.State, fit: Fit, id: Int) {
            val modes = WidgetSettings.ancPicker(context)
            val current = WidgetSettings.modeOf(state.ancMode)
            // STRIP and COLUMN: every level in one row or column (widget_grid_row / _col), no empty cells.
            val line = fit.shape == Shape.STRIP || fit.shape == Shape.COLUMN
            val twoCols = !line && (fit.shape == Shape.PAGES_S || fit.shape == Shape.TALL) && modes.size <= 4
            // Nothing: a one-letter label (L, M, H, S), since the ANC levels' fine rings blur at dot resolution.
            val letters = WidgetSettings.nothingStyle(context)
            val slots = if (twoCols) listOf(0, 1, 3, 4) else (0..5).toList()
            if (!line) listOf(2, 5).forEach { v.setViewVisibility(CELLS[it].root, if (twoCols) View.GONE else View.VISIBLE) }
            // Modes that fit one row: the first row takes the whole height.
            v.setViewVisibility(R.id.w_grid_row1, if (!line && modes.size > slots.size / 2) View.VISIBLE else View.GONE)
            slots.forEachIndexed { i, slot ->
                val b = CELLS[slot]
                val mode = modes.getOrNull(i)
                v.setViewVisibility(b.root, if (mode != null) View.VISIBLE else if (line) View.GONE else View.INVISIBLE)
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
        private fun ring(context: Context, p: Palette, level: Int, slot: Int, status: Int, sizeDp: Float, k: Float = 1f): Bitmap {
            val px = ThemeRes.dp(context, sizeDp).coerceAtLeast(1)
            val tint = glyphTint(context, p, slot, status, k)
            if (WidgetSettings.nothingStyle(context)) return dotRing(context, p, level, slot, tint, px)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            drawRing(context, Canvas(bmp), px.toFloat(), p, level, slot, tint)
            return bmp
        }

        /** How visible the empty battery page is behind the Connect chip. */
        private const val GHOST_ALPHA = 0.42f

        /** Blur the empty page behind the Connect chip. Off: the 2026-10-28 blur never looked good enough on the phone, so the rings stay crisp and faint. */
        private const val GHOST_BLUR = false

        /**
         * A blurred copy of [src]: three box passes on the premultiplied channels, which closely match a Gaussian. The radius
         * is the same 2.5dp for every part (rings, bar, case), so they look alike. [src] is drawn a little smaller first, so
         * the blur has room (3 x the radius) and is not cut flat at the bitmap's edge. (A scale-down and scale-up blurs into visible blocks.)
         */
        private fun soften(context: Context, src: Bitmap): Bitmap {
            val r = ThemeRes.dp(context, 2.5f).coerceAtLeast(3)
            val w = src.width
            val h = src.height
            val framed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // Three box passes reach about 3r from the edge of the drawing, so that is the room (at most a quarter of the size).
            val ix = (3f * r).coerceAtMost(w / 4f)
            val iy = (3f * r).coerceAtMost(h / 4f)
            Canvas(framed).drawBitmap(src, null, RectF(ix, iy, w - ix, h - iy), Paint(Paint.FILTER_BITMAP_FLAG))
            val px = IntArray(w * h)
            framed.getPixels(px, 0, w, 0, 0, w, h)
            val ch = Array(4) { IntArray(w * h) }
            for (i in px.indices) {
                val c = px[i]
                val a = c ushr 24
                ch[0][i] = a
                ch[1][i] = (c shr 16 and 0xFF) * a / 255
                ch[2][i] = (c shr 8 and 0xFF) * a / 255
                ch[3][i] = (c and 0xFF) * a / 255
            }
            val tmp = IntArray(w * h)
            for (c in ch) repeat(3) {
                boxPass(c, tmp, w, h, r, true)
                boxPass(tmp, c, w, h, r, false)
            }
            for (i in px.indices) {
                val a = ch[0][i]
                if (a == 0) { px[i] = 0; continue }
                val rr = (ch[1][i] * 255 / a).coerceAtMost(255)
                val gg = (ch[2][i] * 255 / a).coerceAtMost(255)
                val bb = (ch[3][i] * 255 / a).coerceAtMost(255)
                px[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            return out
        }

        /** One box blur pass of radius [r] along a row ([horizontal]) or a column; the edges count as empty. */
        private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val n = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val step = if (horizontal) 1 else w
            val div = 2 * r + 1
            for (line in 0 until lines) {
                val base = if (horizontal) line * w else line
                var sum = 0
                for (k in 0..minOf(r, n - 1)) sum += src[base + k * step]
                for (i in 0 until n) {
                    dst[base + i * step] = sum / div
                    val add = i + r + 1
                    val drop = i - r
                    if (add < n) sum += src[base + add * step]
                    if (drop >= 0) sum -= src[base + drop * step]
                }
            }
        }

        /** A bud's or the case's glyph colour by wear, [k] of the way from the disconnected (ghost) colour. */
        private fun glyphTint(context: Context, p: Palette, slot: Int, status: Int, k: Float): Int {
            val nothing = WidgetSettings.nothingStyle(context)
            val live = if (nothing) nothingTint(p, slot == 1, status)
                else if (p.glass) Palette.withAlpha(p.ink, if (slot == 1 || status == 3 || status == 7) 0.95f else if (status == 4 || status == 0) 0.45f else 0.7f)
                else BudsStatusView.wearTint(p, slot == 1, status, ThemeRes.material(context))
            if (k >= 1f) return live
            val ghost = if (nothing) nothingTint(p, false, 4) else Palette.withAlpha(p.textSecondary, 0.45f)
            return androidx.core.graphics.ColorUtils.blendARGB(ghost, live, k)
        }

        /** The battery page's look while a connect or disconnect sweeps (0..1, -1 none) and the levels it fades from. */
        @Volatile private var sweepK = -1f
        /** True from a connect until the first levels arrive: the battery page stays blank. */
        @Volatile private var awaitLevels = false
        @Volatile private var sweepState: WidgetStateStore.State? = null
        /** The Connect chip's loading state: set while a connect from the widget (or the app) is under way. */
        @Volatile private var connecting = false
        @Volatile private var connectTick = 0
        private val connectHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var connectTicker: Runnable? = null

        /** Starts or ends the chip's loading state. It ends by itself after 15 s. */
        fun setConnecting(context: Context, on: Boolean) {
            val app = context.applicationContext
            connectTicker?.let { connectHandler.removeCallbacks(it) }
            connecting = on
            connectTick = 0
            refreshAll(app)
            if (!on) return
            val started = System.currentTimeMillis()
            val r = object : Runnable {
                override fun run() {
                    if (System.currentTimeMillis() - started > 15_000) return setConnecting(app, false)
                    connectTick++
                    refreshAll(app)
                    connectHandler.postDelayed(this, 450)
                }
            }
            connectTicker = r
            connectHandler.postDelayed(r, 450)
        }

        /** True for the frames between the first and the last of a sweep; those are partial updates. */
        @Volatile private var sweepPartial = false
        private var sweepRun: Runnable? = null
        private val sweepHandler = android.os.Handler(android.os.Looper.getMainLooper())
        /** The home rings' own timing (BudsStatusView): a fill takes 350 ms, an emptying 500 ms, with a decelerate curve. */
        private const val SWEEP_FILL_MS = 350L
        private const val SWEEP_EMPTY_MS = 500L

        /**
         * Connect: the rings fill from 0 to their level and the glyphs take their colour. Disconnect: the reverse, from
         * [last] (the levels before the link dropped). A widget cannot animate by itself, so this is a few timed updates, only
         * while the screen is on, and the chip shows when it ends. The launcher's own update rate may thin the steps.
         */
        fun animate(context: Context, connecting: Boolean, last: WidgetStateStore.State?) {
            val app = context.applicationContext
            // A connect has no levels yet (the buds report a moment later): the page stays blank until they do, or for 4 s.
            if (connecting) {
                val st = WidgetStateStore.read(app)
                if (st.leftBattery < 0 && st.caseBattery < 0 && st.rightBattery < 0) {
                    sweepRun?.let { sweepHandler.removeCallbacks(it) }
                    sweepK = -1f; sweepState = null; sweepPartial = false
                    awaitLevels = true
                    sweepHandler.postDelayed({ if (awaitLevels) { awaitLevels = false; sweep(app, true, null) } }, 4000)
                    return refreshAll(app)
                }
            }
            awaitLevels = false
            sweep(app, connecting, last)
        }

        private fun sweep(context: Context, connecting: Boolean, last: WidgetStateStore.State?) {
            val app = context.applicationContext
            sweepRun?.let { sweepHandler.removeCallbacks(it) }
            val screenOn = app.getSystemService(android.os.PowerManager::class.java).isInteractive
            val scale = android.provider.Settings.Global.getFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            if (!screenOn || scale == 0f || (!connecting && last == null)) {
                sweepK = -1f; sweepState = null; sweepPartial = false
                return refreshAll(app)
            }
            sweepState = last
            // Time-based, like the app's animator: a slow frame is skipped, never stretched, so the sweep ends when the app's would.
            val total = if (connecting) SWEEP_FILL_MS else SWEEP_EMPTY_MS
            val start = android.os.SystemClock.uptimeMillis()
            var first = true
            val r = object : Runnable {
                override fun run() {
                    val t = ((android.os.SystemClock.uptimeMillis() - start) / total.toFloat()).coerceIn(0f, 1f)
                    val eased = 1f - (1f - t) * (1f - t)
                    val done = t >= 1f
                    // The first and the last frame are full updates; the ones between are partial.
                    sweepPartial = !first && !done
                    first = false
                    if (done) { sweepK = -1f; sweepState = null }
                    else sweepK = if (connecting) eased else 1f - eased
                    refreshAll(app)
                    if (!done) sweepHandler.postDelayed(this, 4)
                }
            }
            sweepRun = r
            r.run()
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
            if (p.glass) {
                // Glass: the same ring and glyph as the app's home tile.
                GlassArt.ring(c, size / 2, size / 2, size, if (level in 1..100) level / 100f else 0f, p.accent, p.ink, p.glassLight)
                val bud = slot != 1
                val d = context.getDrawable(when (slot) { 0 -> R.drawable.ic_bud_left; 1 -> R.drawable.ic_case; else -> R.drawable.ic_bud_right })!!.mutate()
                val gw = size * (if (bud) 31f else 52f) / 90f * fill
                val gh = size * 42f / 90f * fill
                GlassArt.Glyph(d).draw(c, size / 2, size / 2, gw, gh, tint, size / 90f, p.glassLight)
                return android.graphics.Rect(((size - gw) / 2).toInt(), ((size - gh) / 2).toInt(), ((size + gw) / 2).toInt(), ((size + gh) / 2).toInt())
            }
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
            val gl = android.graphics.Rect(((size - w) / 2).toInt(), ((size - h) / 2).toInt(), ((size + w) / 2).toInt(), ((size + h) / 2).toInt())
            glyph.setTint(tint)
            glyph.setBounds(gl)
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

        /**
         * The Nothing style's glyph shade, which replaces the wear text: in ear `text`, out of
         * ear `textSecondary` at 65% over the card, in case a third, darker grey, below the ring's unlit dots ([dim]) so it is not
         * mistaken for them. Opaque, since a dot is either there or not. The case glyph is always `text`.
         */
        fun nothingTint(p: Palette, isCase: Boolean, status: Int): Int = when {
            // The accent for a bud in the ear and the case; darker out of the ear, darkest in the case (as in the other styles).
            isCase || status == 3 || status == 7 -> p.accent
            status == 4 || status == 0 -> Palette.blend(boxColor(p), p.accent, 0.3f)
            else -> Palette.blend(boxColor(p), p.accent, 0.6f)
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
        /** Glass: the case level as a capsule like the app's ring track: faint with a hairline rim, the accent fill, the number in the ink. */
        private fun glassBar(context: Context, p: Palette, level: Int, w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val d = context.resources.displayMetrics.density
            val box = RectF(d, d, w - d, h - d)
            val rad = (h - 2 * d) / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = if (p.glassLight) 0x1A14152B else 0x14FFFFFF
            c.drawRoundRect(box, rad, rad, paint)
            val fillW = if (level in 1..100) box.left + box.width() * level / 100f else 0f
            if (fillW > 0f) {
                c.save(); c.clipRect(0f, 0f, maxOf(fillW, h / 2f), h.toFloat())
                paint.color = p.accent
                c.drawRoundRect(box, rad, rad, paint)
                c.restore()
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = d
            paint.color = if (p.glassLight) 0x2614152B else 0x2EFFFFFF
            c.drawRoundRect(box, rad, rad, paint)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                textSize = h * 0.52f; textAlign = Paint.Align.CENTER; color = p.text
            }
            val y = h / 2f - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2
            val label = pctLabel(context, level)
            c.save(); c.clipRect(maxOf(fillW, 0f), 0f, w.toFloat(), h.toFloat()); c.drawText(label, w / 2f, y, text); c.restore()
            text.color = p.onAccent
            c.save(); c.clipRect(0f, 0f, fillW, h.toFloat()); c.drawText(label, w / 2f, y, text); c.restore()
            return bmp
        }

        private fun bar(context: Context, p: Palette, level: Int, id: Int, caseDp: Float): Bitmap {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (p.glass) Palette.withAlpha(p.ink, 0.14f) else p.outline }
            if (WidgetSettings.nothingStyle(context)) {
                // 7 rows at that pitch, as many as fill 70% of the case icon's height, the percentage inside.
                val pitch = DOT_DP * 20f / 22f * 7 / 5 * context.resources.displayMetrics.density
                // 70% of the case icon's height (as tall as the icon was too big); odd, so the 7-row
                // digits sit exactly in the middle.
                val rows = ((ThemeRes.dp(context, caseDp * 0.7f) / pitch).toInt() - 1 or 1).coerceAtLeast(9)
                val cols = (ThemeRes.dp(context, barDp(context, id, caseDp)) / pitch).toInt().coerceAtLeast(rows + 1)
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
            if (p.glass) return glassBar(context, p, level, ThemeRes.dp(context, barDp(context, id, caseDp)).coerceAtLeast(1), ThemeRes.dp(context, caseDp * 0.7f).coerceAtLeast(1))
            // Classic (the Nothing layout's ideas, smooth): as wide as its slot, the number knocked out.
            val w = ThemeRes.dp(context, barDp(context, id, caseDp)).coerceAtLeast(1)
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
                typeface = if (WidgetSettings.materialStyle(context)) materialFont(context, true)
                    else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                textSize = h * 0.6f
                textAlign = Paint.Align.CENTER
                color = p.text
            }
            val fm = text.fontMetrics
            val y = h / 2f - (fm.ascent + fm.descent) / 2
            // Two passes, so each part of the label reads on what is under it: on-accent over the fill, text over the rest.
            val fillW = if (level in 1..100) w * level / 100f else 0f
            c.save(); c.clipRect(fillW, 0f, w.toFloat(), h.toFloat())
            c.drawText(pctLabel(context, level), w / 2f, y, text)
            c.restore()
            c.save(); c.clipRect(0f, 0f, fillW, h.toFloat())
            text.color = p.onAccent
            c.drawText(pctLabel(context, level), w / 2f, y, text)
            c.restore()
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
        private val GEO_MATERIAL = Geo(5f, 8f, 4f, 3f, 3f, 1f, 3f, 3f, 3f)
        private fun geo(c: Context) = if (WidgetSettings.nothingStyle(c)) GEO_NOTHING else if (WidgetSettings.materialStyle(c)) GEO_MATERIAL else GEO_CLASSIC

        /** The height of a battery text as the layout draws it ([pctPaint]), in dp. */
        private fun textDp(context: Context, paint: Paint) = paint.fontMetrics.let { it.descent - it.ascent } / context.resources.displayMetrics.density

        /** 2x2: a battery ring's size in dp, as wide as its panel. */
        private fun ringWidthDp(context: Context, w: Int): Float {
            val g = geo(context)
            return (w - 2 * g.side - g.gap) / 2 - g.outer - g.inner
        }

        /**
         * The case bar's slot width in dp: the widget's width less everything else in the row (page and row
         * paddings, the case icon and its margin). The level sits inside the bar.
         * Falls back to 60dp when the host gives no size.
         */
        private fun barDp(context: Context, id: Int, caseDp: Float): Float {
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
        private fun caseRowDp(context: Context, id: Int): Float {
            val o = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (w <= 0 || h <= 0) return 30f
            val g = geo(context)
            val ring = ringWidthDp(context, w)
            val pct = textDp(context, pctPaint(context, true))
            val label = if (WidgetSettings.nothingStyle(context) || WidgetSettings.materialStyle(context)) 0f else textDp(context, Paint().apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                textSize = 11.5f * context.resources.displayMetrics.scaledDensity
            })
            return (h - 2 * g.vert - (g.top + ring + g.pm + pct + label + g.bottom) - g.gap - 2 * g.row).coerceIn(20f, 120f)
        }

        /** Nothing, 2x2: the battery rings' dot pitch in dp ([RING_CELLS] across the ring). */
        private fun ringCellDp(context: Context, id: Int): Float {
            val w = AppWidgetManager.getInstance(context).getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            if (w <= 0) return DOT_DP
            return ringWidthDp(context, w) / RING_CELLS
        }

        /**
         * A percentage as the layout draws it: dot style Doto bold 17sp on the 2x2 20sp on
         * the 4x2; Classic sans-serif bold 16sp on the 2x2; Material 21sp on the 2x2, 24sp on the 4x2.
         */
        private fun pctPaint(context: Context, square: Boolean) = Paint().apply {
            val nothing = WidgetSettings.nothingStyle(context)
            val material = WidgetSettings.materialStyle(context)
            typeface = if (material) materialFont(context, true)
                else android.graphics.Typeface.create(if (nothing) com.spizganed.quickbuds.ui.ThemeRes.dotFont(context) else android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            textSize = (if (square) (if (nothing) 17f else if (material) 21f else 16f) else if (material) 24f else 20f) * context.resources.displayMetrics.scaledDensity
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
            if (level !in 0..100) "—" else if (WidgetSettings.nothingStyle(context) || WidgetSettings.materialStyle(context)) "$level" else "$level%"

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
                .putExtra(BudsService.EXTRA_USER_CONNECT, true)
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
        fun animate(context: Context, connecting: Boolean, last: WidgetStateStore.State?) = QuickBudsWidget.animate(context, connecting, last)
        fun setConnecting(context: Context, on: Boolean) = QuickBudsWidget.setConnecting(context, on)
    }
}

/** 2x2, battery and controls pages. */
class BatteryWidgetProvider : QuickBudsWidget(Kind.BATTERY)

/** 2x2, battery page only. */
class BatteryOnlyWidgetProvider : QuickBudsWidget(Kind.BATTERY_ONLY)

/** 2x2, controls page only. */
class ControlsWidgetProvider : QuickBudsWidget(Kind.CONTROLS)
