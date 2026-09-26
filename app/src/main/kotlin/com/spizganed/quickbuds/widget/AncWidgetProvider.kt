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
import android.os.Parcel
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.WidgetActions
import com.spizganed.quickbuds.ui.MainActivity
import com.spizganed.quickbuds.ui.Palette
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * The home-screen widgets (rebuilt 2026-09-26, [USER]: "follow the design we already have").
 *
 *  - [AncWidgetProvider]   4x2 full: rings with wear labels, all six noise modes, Low latency.
 *                          Keeps the old widget's class name so widgets already placed survive.
 *  - [SmallWidgetProvider] 2x2 compact: rings, Off / L / M / H / Transparency, Low latency.
 *  - [StripWidgetProvider] 4x1 bar: rings, a chip that cycles Off / ANC / Transparency, Low latency.
 *
 * All three are drawn in the ACTIVE app palette: surfaces are white shapes tinted at runtime with
 * ImageView.setColorFilter (works on every API level, unlike background tint lists), and the
 * battery rings are small bitmaps drawn here. Taps go through [WidgetActionReceiver], the same
 * path as before. Disconnected, the widgets show the empty state and any tap opens the app.
 */
open class QuickBudsWidget(private val kind: Kind) : AppWidgetProvider() {

    enum class Kind { FULL, SMALL, STRIP }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, mgr, id, kind)
    }

    companion object {

        /** Stable PendingIntent request codes, one per noise target. */
        private val REQ = mapOf("off" to 200, "trans" to 201, "low" to 202, "med" to 203, "high" to 204, "adapt" to 205)

        private val providers = listOf(
            AncWidgetProvider::class.java to Kind.FULL,
            SmallWidgetProvider::class.java to Kind.SMALL,
            StripWidgetProvider::class.java to Kind.STRIP
        )

        /** Repaints every placed widget of every size: state or palette changed. */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            for ((cls, kind) in providers) {
                val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
                for (id in ids) update(context, mgr, id, kind)
            }
        }

        private fun update(context: Context, mgr: AppWidgetManager, id: Int, kind: Kind) {
            // A throw here would leave the host showing "Can't load widget" with no trace.
            try {
                mgr.updateAppWidget(id, build(context, WidgetStateStore.read(context), kind))
            } catch (t: Throwable) {
                Log.e("BudsWidget", "update failed for $kind #$id", t)
            }
        }

        private fun layoutFor(kind: Kind) = when (kind) {
            Kind.FULL -> R.layout.widget_full
            Kind.SMALL -> R.layout.widget_small
            Kind.STRIP -> R.layout.widget_strip
        }

        fun build(context: Context, state: WidgetStateStore.State, kind: Kind): RemoteViews {
            val p = ThemeRes.palette(context)
            val v = RemoteViews(context.packageName, layoutFor(kind))
            val on = state.connected
            val openApp = openAppPI(context)

            v.setInt(R.id.w_bg, "setColorFilter", p.card)
            v.setInt(R.id.w_stroke, "setColorFilter", p.outline)
            v.setOnClickPendingIntent(R.id.w_root, openApp)

            // Battery rings.
            val ringDp = when (kind) { Kind.FULL -> 38f; Kind.SMALL -> 34f; Kind.STRIP -> 30f }
            val sides = listOf(
                Triple(R.id.w_ring_left, R.id.w_pct_left, 0),
                Triple(R.id.w_ring_case, R.id.w_pct_case, 1),
                Triple(R.id.w_ring_right, R.id.w_pct_right, 2)
            )
            val levels = intArrayOf(state.leftBattery, state.caseBattery, state.rightBattery)
            val statuses = intArrayOf(state.leftStatus, -1, state.rightStatus)
            for ((i, ids) in sides.withIndex()) {
                val level = if (on) levels[i] else -1
                v.setImageViewBitmap(ids.first, ring(context, p, level, i, statuses[i], on, ringDp))
                v.setTextViewText(ids.second, if (level in 0..100) "$level%" else "—")
                v.setTextColor(ids.second, if (level in 0..20) p.accent else if (on) p.text else p.disabled)
            }
            if (kind == Kind.FULL) {
                val labels = listOf(
                    R.id.w_label_left to wearLabel(context, R.string.status_left, state.leftStatus, on),
                    R.id.w_label_case to context.getString(R.string.status_case),
                    R.id.w_label_right to wearLabel(context, R.string.status_right, state.rightStatus, on)
                )
                for ((id, text) in labels) {
                    v.setTextViewText(id, text)
                    v.setTextColor(id, p.textSecondary)
                }
            }

            // Noise control.
            when (kind) {
                Kind.FULL -> {
                    chip(context, v, p, "off", R.drawable.ic_noise_off, R.string.widget_anc_off, state.offIsActive(), on)
                    chip(context, v, p, "low", R.drawable.ic_anc, R.string.widget_anc_low, state.lowIsActive(), on)
                    chip(context, v, p, "med", R.drawable.ic_anc, R.string.widget_anc_med, state.medIsActive(), on)
                    chip(context, v, p, "high", R.drawable.ic_anc, R.string.widget_anc_high, state.highIsActive(), on)
                    chip(context, v, p, "adapt", R.drawable.ic_adaptive, R.string.widget_anc_adapt, state.adaptiveIsActive(), on)
                    chip(context, v, p, "trans", R.drawable.ic_transparency, R.string.widget_anc_trans, state.transIsActive(), on)
                }
                Kind.SMALL -> {
                    chip(context, v, p, "off", R.drawable.ic_noise_off, 0, state.offIsActive(), on)
                    chip(context, v, p, "low", 0, R.string.widget_short_low, state.lowIsActive(), on)
                    chip(context, v, p, "med", 0, R.string.widget_short_med, state.medIsActive(), on)
                    chip(context, v, p, "high", 0, R.string.widget_short_high, state.highIsActive(), on)
                    chip(context, v, p, "trans", R.drawable.ic_transparency, 0, state.transIsActive(), on)
                }
                Kind.STRIP -> {
                    // One chip showing the current mode; a tap moves to the next of Off -> ANC -> Transparency.
                    val (icon, label, next) = when {
                        state.transIsActive() -> Triple(R.drawable.ic_transparency, R.string.widget_anc_trans, "off")
                        state.offIsActive() -> Triple(R.drawable.ic_noise_off, R.string.widget_anc_off, lastLevel(context))
                        state.adaptiveIsActive() -> Triple(R.drawable.ic_adaptive, R.string.widget_anc_adapt, "trans")
                        else -> Triple(R.drawable.ic_anc, levelLabel(state), "trans")
                    }
                    paintChip(context, v, p, "cycle", icon, label, active = on && !state.offIsActive(), on = on)
                    v.setOnClickPendingIntent(R.id.w_chip_cycle, if (on) ancPI(context, next, 300) else openApp)
                    v.setContentDescription(R.id.w_chip_cycle, context.getString(R.string.anc_section))
                }
            }

            // Low latency.
            if (kind == Kind.STRIP) {
                paintChip(context, v, p, "gamechip", R.drawable.ic_bolt, 0, active = on && state.gameMode, on = on)
                v.setOnClickPendingIntent(R.id.w_chip_gamechip, if (on) gamePI(context) else openApp)
                v.setContentDescription(R.id.w_chip_gamechip, context.getString(R.string.row_game_title))
            } else {
                val active = on && state.gameMode
                v.setInt(R.id.w_game_bg, "setColorFilter", if (active) p.accent else p.background)
                val fg = if (!on) p.disabled else if (active) p.onAccent else p.text
                v.setInt(R.id.w_game_icon, "setColorFilter", if (!on) p.disabled else if (active) p.onAccent else p.accent)
                v.setTextColor(R.id.w_game_label, fg)
                v.setOnClickPendingIntent(R.id.w_game, if (on) gamePI(context) else openApp)
            }
            return v
        }

        private fun levelLabel(state: WidgetStateStore.State) = when {
            state.lowIsActive() -> R.string.widget_anc_low
            state.highIsActive() -> R.string.widget_anc_high
            else -> R.string.widget_anc_med
        }

        /** The strength the bar's ANC step applies: the last one the home screen saw, Medium otherwise. */
        private fun lastLevel(context: Context) =
            when (context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getString("homeAncLevel", null)) {
                "ANC-Light" -> "low"
                "ANC-Deep" -> "high"
                else -> "med"
            }

        private fun wearLabel(context: Context, sideRes: Int, status: Int, on: Boolean): String {
            val side = context.getString(sideRes)
            if (!on) return side
            val wear = when (status) {
                3, 7 -> R.string.status_in_ear
                4, 0 -> R.string.status_in_case
                -1 -> return side
                else -> R.string.status_out
            }
            return "$side · ${context.getString(wear)}"
        }

        /** A noise-mode chip that selects [target] ("off", "low", "med", "high", "adapt", "trans"). */
        private fun chip(
            context: Context, v: RemoteViews, p: Palette, target: String,
            icon: Int, label: Int, active: Boolean, on: Boolean
        ) {
            paintChip(context, v, p, target, icon, label, on && active, on)
            val id = chipId(target, "")
            v.setOnClickPendingIntent(id, if (on) ancPI(context, target, REQ.getValue(target)) else openAppPI(context))
            if (label != 0) v.setContentDescription(id, context.getString(label))
        }

        /** Colours one chip: accent fill with on-accent content when active, background fill otherwise. */
        private fun paintChip(
            context: Context, v: RemoteViews, p: Palette, key: String, icon: Int, label: Int, active: Boolean, on: Boolean
        ) {
            val fg = when {
                !on -> p.disabled
                active -> p.onAccent
                else -> p.textSecondary
            }
            v.setInt(chipId(key, "_bg"), "setColorFilter", if (active) p.accent else p.background)
            val iconId = chipId(key, "_icon")
            if (icon != 0) {
                v.setViewVisibility(iconId, View.VISIBLE)
                v.setImageViewResource(iconId, icon)
                v.setInt(iconId, "setColorFilter", fg)
            } else v.setViewVisibility(iconId, View.GONE)
            val labelId = chipId(key, "_label")
            if (label != 0) {
                v.setViewVisibility(labelId, View.VISIBLE)
                v.setTextViewText(labelId, context.getString(label))
                v.setTextColor(labelId, fg)
            } else v.setViewVisibility(labelId, View.GONE)
        }

        private fun chipId(key: String, suffix: String): Int = when ("$key$suffix") {
            "off" -> R.id.w_chip_off; "off_bg" -> R.id.w_chip_off_bg; "off_icon" -> R.id.w_chip_off_icon; "off_label" -> R.id.w_chip_off_label
            "low" -> R.id.w_chip_low; "low_bg" -> R.id.w_chip_low_bg; "low_icon" -> R.id.w_chip_low_icon; "low_label" -> R.id.w_chip_low_label
            "med" -> R.id.w_chip_med; "med_bg" -> R.id.w_chip_med_bg; "med_icon" -> R.id.w_chip_med_icon; "med_label" -> R.id.w_chip_med_label
            "high" -> R.id.w_chip_high; "high_bg" -> R.id.w_chip_high_bg; "high_icon" -> R.id.w_chip_high_icon; "high_label" -> R.id.w_chip_high_label
            "adapt" -> R.id.w_chip_adapt; "adapt_bg" -> R.id.w_chip_adapt_bg; "adapt_icon" -> R.id.w_chip_adapt_icon; "adapt_label" -> R.id.w_chip_adapt_label
            "trans" -> R.id.w_chip_trans; "trans_bg" -> R.id.w_chip_trans_bg; "trans_icon" -> R.id.w_chip_trans_icon; "trans_label" -> R.id.w_chip_trans_label
            "cycle" -> R.id.w_chip_cycle; "cycle_bg" -> R.id.w_chip_cycle_bg; "cycle_icon" -> R.id.w_chip_cycle_icon; "cycle_label" -> R.id.w_chip_cycle_label
            "gamechip" -> R.id.w_chip_gamechip; "gamechip_bg" -> R.id.w_chip_gamechip_bg; "gamechip_icon" -> R.id.w_chip_gamechip_icon; "gamechip_label" -> R.id.w_chip_gamechip_label
            else -> throw IllegalArgumentException("no widget chip '$key$suffix'")
        }

        /**
         * One battery ring as a bitmap: outline track, accent arc from 12 o'clock, and the glyph at
         * its true ratio, tinted by wear (in ear = text, out / in case = secondary, offline = disabled).
         * Small on purpose: RemoteViews bitmaps count against the host's memory limit.
         */
        private fun ring(context: Context, p: Palette, level: Int, slot: Int, status: Int, on: Boolean, sizeDp: Float): Bitmap {
            val px = ThemeRes.dp(context, sizeDp).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val stroke = px * 0.075f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
            val box = RectF(stroke / 2, stroke / 2, px - stroke / 2, px - stroke / 2)
            paint.color = p.outline
            c.drawOval(box, paint)
            if (on && level in 1..100) {
                paint.color = p.accent
                c.drawArc(box, -90f, 360f * level / 100f, false, paint)
            }
            val isCase = slot == 1
            val glyph = context.getDrawable(
                when (slot) { 0 -> R.drawable.ic_bud_left; 1 -> R.drawable.ic_case; else -> R.drawable.ic_bud_right }
            )!!.mutate()
            val ratio = if (isCase) 496f / 400f else 176f / 272f
            val boxH = px * (if (isCase) 0.40f else 0.54f)
            val boxW = px * (if (isCase) 0.56f else 0.40f)
            val (w, h) = if (ratio < boxW / boxH) boxH * ratio to boxH else boxW to boxW / ratio
            glyph.setTint(
                when {
                    !on -> p.disabled
                    isCase || status == 3 || status == 7 -> p.text
                    else -> p.textSecondary
                }
            )
            glyph.setBounds(((px - w) / 2).toInt(), ((px - h) / 2).toInt(), ((px + w) / 2).toInt(), ((px + h) / 2).toInt())
            glyph.draw(c)
            return bmp
        }

        private fun openAppPI(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 400, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        private fun ancPI(context: Context, target: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActions.ACTION_ANC_SELECT
                putExtra(WidgetActions.EXTRA_ANC_TARGET, target)
            }
            return PendingIntent.getBroadcast(context, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun gamePI(context: Context): PendingIntent {
            val intent = Intent(context, WidgetActionReceiver::class.java).apply { action = WidgetActions.ACTION_GAME_TOGGLE }
            return PendingIntent.getBroadcast(context, 101, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /**
         * Dev Tools' widget logic check: builds every size for the current state and round-trips each
         * through a Parcel, as the AppWidgetService does before the launcher sees it.
         */
        fun buildWidgetRemoteViews(context: Context): String {
            val sb = StringBuilder("WIDGET REMOTEVIEWS CHECK\n\n")
            val state = WidgetStateStore.read(context)
            sb.appendLine("state: connected=${state.connected} anc=${state.ancMode} game=${state.gameMode}")
            for ((cls, kind) in providers) {
                val placed = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, cls)).size
                val result = try {
                    val views = build(context, state, kind)
                    val parcel = Parcel.obtain()
                    try { views.writeToParcel(parcel, 0); "OK (${parcel.dataSize()} bytes)" } finally { parcel.recycle() }
                } catch (t: Throwable) {
                    "FAILED: $t\n${t.stackTraceToString()}"
                }
                sb.appendLine("$kind: placed=$placed build+parcel $result")
            }
            return sb.toString()
        }
    }
}

/** 4x2 full widget. Keeps the original class name so widgets placed before the rebuild still work. */
class AncWidgetProvider : QuickBudsWidget(Kind.FULL) {
    companion object {
        fun refreshAll(context: Context) = QuickBudsWidget.refreshAll(context)
        fun buildWidgetRemoteViews(context: Context) = QuickBudsWidget.buildWidgetRemoteViews(context)
    }
}

/** 2x2 compact widget. */
class SmallWidgetProvider : QuickBudsWidget(Kind.SMALL)

/** 4x1 bar widget. */
class StripWidgetProvider : QuickBudsWidget(Kind.STRIP)
