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
 * The modes a Noise cycle steps through, in [WidgetSettings.CYCLE_ORDER]. From Settings: the Noise control tile's list.
 * With a widget id (the 1x1 widget's setup, or a widget's setup screen): that widget's own list. Lists only the modes
 * the buds have. At least 2 stay on.
 */
class NoiseCycleActivity : Activity() {

    private val switches = LinkedHashMap<String, Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, WidgetSettings.NO_ID)
        // A widget's setup: OK from the start, so Back keeps the widget.
        if (id != WidgetSettings.NO_ID) setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val p = ThemeRes.palette(this)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, if (id != WidgetSettings.NO_ID) R.string.widget_cycle else R.string.settings_tile_title))
        root.addView(TextView(this).apply {
            setText(R.string.cycle_intro)
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(ThemeRes.dp(this@NoiseCycleActivity, 4f), 0, 0, ThemeRes.dp(this@NoiseCycleActivity, 12f))
        })
        val keys = WidgetSettings.cycleOffered(this)
        val picked = WidgetSettings.cycleModes(this, id).toMutableSet()
        val card = SettingRowFactory.card(this)
        for (key in keys) {
            val mode = WidgetSettings.MODES.first { it.key == key }
            val sw = SettingRowFactory.buildSwitch(this, key in picked)
            switches[key] = sw
            sw.setOnCheckedChangeListener { v, on ->
                if (on) picked.add(key) else picked.remove(key)
                WidgetSettings.setCyclePicked(this, picked, id)
                QuickBudsWidget.refreshAll(this)
                Haptics.commit(v)
                limit(picked.size)
            }
            if (card.childCount > 0) card.addView(SettingRowFactory.buildDivider(this))
            card.addView(SettingRowFactory.build(this, mode.icon, mode.name, 0, sw) { if (sw.isEnabled) sw.performClick() })
        }
        root.addView(card)
        limit(picked.size)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
    }

    /** At 2 picked modes, the picked switches cannot turn off. */
    private fun limit(count: Int) {
        for (sw in switches.values) sw.isEnabled = !sw.isChecked || count > 2
    }
}
