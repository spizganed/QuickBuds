package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.widget.WidgetSettings

/**
 * Widget settings (design/widgets/WIDGETS.md 5, w6): the style, how the pages swap, and two toggles.
 * Every change is saved at once and repaints the placed widgets.
 */
class WidgetSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val p = ThemeRes.palette(this)

        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.widget_settings_title))
        fun hint(res: Int) = TextView(this).apply {
            setText(res)
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(ThemeRes.dp(this@WidgetSettingsActivity, 4f), ThemeRes.dp(this@WidgetSettingsActivity, 8f), 0, 0)
        }

        root.addView(SettingRowFactory.sectionLabel(this, R.string.widget_style_title))
        root.addView(AncSegmentedView(this, listOf(getString(R.string.widget_style_classic), getString(R.string.widget_style_nothing))).apply {
            selected = if (WidgetSettings.nothingStyle(this@WidgetSettingsActivity)) 1 else 0
            onSegmentTapped = { i ->
                selected = i
                WidgetSettings.setNothingStyle(this@WidgetSettingsActivity, i == 1)
            }
        })

        root.addView(SettingRowFactory.sectionLabel(this, R.string.widget_pages_title))
        root.addView(AncSegmentedView(
            this, listOf(getString(R.string.widget_pages_button), getString(R.string.widget_pages_double)),
            listOf(R.drawable.ic_swap_page, R.drawable.ic_tap_double)
        ).apply {
            selected = if (WidgetSettings.doubleTapSwaps(this@WidgetSettingsActivity)) 1 else 0
            onSegmentTapped = { i ->
                selected = i
                WidgetSettings.setDoubleTapSwaps(this@WidgetSettingsActivity, i == 1)
            }
        })
        root.addView(hint(R.string.widget_pages_hint))

        root.addView(SettingRowFactory.sectionLabel(this, R.string.widget_section))
        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, toggle(R.drawable.ic_low_latency, R.string.widget_ll_title, R.string.widget_ll_sub,
                WidgetSettings.lowLatencyShown(this@WidgetSettingsActivity)) { WidgetSettings.setLowLatencyShown(this@WidgetSettingsActivity, it) })
            SettingRowFactory.addRow(this, toggle(R.drawable.ic_open_app, R.string.widget_open_title, R.string.widget_open_sub,
                WidgetSettings.openAppOnTap(this@WidgetSettingsActivity)) { WidgetSettings.setOpenAppOnTap(this@WidgetSettingsActivity, it) })
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
    }

    private fun toggle(icon: Int, title: Int, sub: Int, checked: Boolean, save: (Boolean) -> Unit): LinearLayout {
        val sw = SettingRowFactory.buildSwitch(this, checked)
        sw.setOnCheckedChangeListener { _, v -> save(v) }
        return SettingRowFactory.build(this, icon, title, sub, sw) { sw.performClick() }
    }
}
