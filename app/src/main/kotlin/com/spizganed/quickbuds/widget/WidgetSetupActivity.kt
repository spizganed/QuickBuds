package com.spizganed.quickbuds.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.ui.Haptics
import com.spizganed.quickbuds.ui.SettingRowFactory
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * A widget's setup screen: which buttons its controls page shows, up to [WidgetSettings.MAX_BUTTONS], in the
 * order of [WidgetSettings.BUTTONS]. Only the buttons the buds have are listed. Each switch saves at once and
 * repaints the widget. Opens when the widget is placed (optional from Android 12) and from its long-press menu.
 */
class WidgetSetupActivity : Activity() {

    private val switches = LinkedHashMap<String, Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        // OK from the start: Back keeps the widget, with the buttons picked so far.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))

        val p = ThemeRes.palette(this)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.widget_setup_title))
        root.addView(TextView(this).apply {
            setText(R.string.widget_setup_hint)
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(ThemeRes.dp(this@WidgetSetupActivity, 4f), 0, 0, ThemeRes.dp(this@WidgetSetupActivity, 12f))
        })
        val keys = WidgetSettings.BUTTONS.filter { WidgetSettings.available(this, it) }
        val picked = WidgetSettings.buttons(this, id).filter { it in keys }.toMutableSet()
        val card = SettingRowFactory.card(this)
        for (key in keys) {
            val (icon, title) = ROWS.getValue(key)
            val sw = SettingRowFactory.buildSwitch(this, key in picked)
            switches[key] = sw
            sw.setOnCheckedChangeListener { v, on ->
                if (on) picked.add(key) else picked.remove(key)
                WidgetSettings.setButtons(this, id, keys.filter { it in picked })
                QuickBudsWidget.refreshAll(this)
                Haptics.commit(v)
                limit(picked.size)
            }
            if (card.childCount > 0) card.addView(SettingRowFactory.buildDivider(this))
            card.addView(SettingRowFactory.build(this, icon, title, 0, sw) { if (sw.isEnabled) sw.performClick() })
        }
        root.addView(card)
        limit(picked.size)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
    }

    /** At the limit, only the picked switches can change. */
    private fun limit(count: Int) {
        for (sw in switches.values) sw.isEnabled = sw.isChecked || count < WidgetSettings.MAX_BUTTONS
    }

    private companion object {
        /** Button key -> (icon, title). */
        val ROWS = mapOf(
            "anc" to (R.drawable.ic_mode_anc_medium to R.string.anc_section),
            "trans" to (R.drawable.ic_mode_transparency to R.string.anc_seg_trans),
            "adapt" to (R.drawable.ic_mode_adaptive to R.string.anc_seg_adapt),
            "off" to (R.drawable.ic_mode_off to R.string.anc_seg_off),
            "ll" to (R.drawable.ic_low_latency to R.string.row_game_title),
            "wind" to (R.drawable.ic_anc to R.string.row_wind_noise_title)
        )
    }
}
