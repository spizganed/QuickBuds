package com.spizganed.quickbuds.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.widget.AncWidgetProvider
import com.spizganed.quickbuds.widget.BatteryOnlyWidgetProvider
import com.spizganed.quickbuds.widget.BatteryStripWidgetProvider
import com.spizganed.quickbuds.widget.BatteryWidgetProvider
import com.spizganed.quickbuds.widget.ControlsWidgetProvider
import com.spizganed.quickbuds.widget.NoiseCycleActivity
import com.spizganed.quickbuds.widget.NoiseCycleWidgetProvider

/**
 * Settings › Widgets: add a widget to the home screen (as in Setup), the settings of each placed widget (the same
 * screen as the launcher's long press), and the modes of the tile and the widget button. The style is on the Theme
 * page: it sets the app and the widgets.
 */
class WidgetPageActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
    }

    // Built on each resume: a widget added from here shows in the placed list on return.
    override fun onResume() {
        super.onResume()
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.setup_widgets_title))
        val manager = getSystemService(AppWidgetManager::class.java)
        // A launcher that cannot pin gets the rows without the Add pill.
        val canPin = manager?.isRequestPinAppWidgetSupported == true
        fun add(name: Int, provider: Class<*>) = SettingRowFactory.build(this, R.drawable.ic_widgets, name, 0, null,
            value = if (canPin) SettingRowFactory.pillButton(this, R.string.setup_widgets_add) {
                manager.requestPinAppWidget(ComponentName(this, provider), null, null)
            } else null)
        val card = SettingRowFactory.card(this)
        PROVIDERS.forEach { (name, provider) -> SettingRowFactory.addRow(card, add(name, provider)) }
        SettingRowFactory.addRow(card, SettingRowFactory.build(this, R.drawable.ic_mode_transparency, R.string.settings_tile_title,
            R.string.settings_cycle_sub, SettingRowFactory.buildChevron(this)) { startActivity(Intent(this, NoiseCycleActivity::class.java)) })
        root.addView(card)
        placed(manager)?.let {
            root.addView(SettingRowFactory.sectionLabel(this, R.string.widget_placed_section))
            root.addView(it)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@WidgetPageActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    /** One row for each placed widget that has a settings screen; null when there is none. */
    private fun placed(manager: AppWidgetManager?): android.view.View? {
        manager ?: return null
        val card = SettingRowFactory.card(this)
        for ((_, provider) in PROVIDERS) {
            val ids = manager.getAppWidgetIds(ComponentName(this, provider))
            ids.forEachIndexed { i, id ->
                val info = manager.getAppWidgetInfo(id) ?: return@forEachIndexed
                val configure = info.configure ?: return@forEachIndexed
                val name = info.loadLabel(packageManager) + if (ids.size > 1) " ${i + 1}" else ""
                val row = SettingRowFactory.build(this, R.drawable.ic_widgets, 0, 0, SettingRowFactory.buildChevron(this)) {
                    startActivity(Intent().setComponent(configure).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                }
                row.findViewWithTag<android.widget.TextView>(SettingRowFactory.TITLE_TAG)?.text = name
                SettingRowFactory.addRow(card, row)
            }
        }
        return card.takeIf { it.childCount > 0 }
    }

    private companion object {
        val PROVIDERS = listOf(
            R.string.widget_name to AncWidgetProvider::class.java,
            R.string.widget_pages_name to BatteryWidgetProvider::class.java,
            R.string.widget_battery_name to BatteryOnlyWidgetProvider::class.java,
            R.string.widget_strip_name to BatteryStripWidgetProvider::class.java,
            R.string.widget_controls_name to ControlsWidgetProvider::class.java,
            R.string.widget_cycle_name to NoiseCycleWidgetProvider::class.java,
        )
    }
}
