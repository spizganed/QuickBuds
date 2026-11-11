package com.spizganed.quickbuds.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.widget.AncWidgetProvider
import com.spizganed.quickbuds.widget.BatteryWidgetProvider
import com.spizganed.quickbuds.widget.NoiseCycleActivity

/**
 * Settings › Widgets: add a widget to the home screen (as in Setup), and the modes of the tile and the widget button.
 * A widget's own settings open from the widget. The style is on the Theme page: it sets the app and the widgets.
 */
class WidgetPageActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
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
        listOf(
            add(R.string.widget_name, AncWidgetProvider::class.java),
            add(R.string.widget_pages_name, BatteryWidgetProvider::class.java),
            SettingRowFactory.build(this, R.drawable.ic_mode_transparency, R.string.settings_tile_title, R.string.settings_cycle_sub,
                SettingRowFactory.buildChevron(this)) { startActivity(Intent(this, NoiseCycleActivity::class.java)) },
        ).forEach { SettingRowFactory.addRow(card, it) }
        root.addView(card)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@WidgetPageActivity, R.attr.appColorBg))
            addView(root)
        })
    }
}
