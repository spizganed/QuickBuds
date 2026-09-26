package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService

/**
 * Settings (design/SPEC.md 3.6), a full screen reached from the header cog. Same layout pattern
 * as Earbud settings: title, then sectioned cards. Toggles save on change; no confirmation.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.settings_title))

        fun section(labelRes: Int, vararg rows: android.view.View) {
            root.addView(SettingRowFactory.sectionLabel(this, labelRes))
            val card = SettingRowFactory.card(this)
            rows.forEach { SettingRowFactory.addRow(card, it) }
            root.addView(card)
        }

        fun link(icon: Int, title: Int, sub: Int, onClick: () -> Unit) =
            SettingRowFactory.build(this, icon, title, sub, SettingRowFactory.buildChevron(this), onClick = onClick)

        fun toggle(icon: Int, title: Int, sub: Int, key: String, default: Boolean): LinearLayout {
            val sw = SettingRowFactory.buildSwitch(this, prefs.getBoolean(key, default))
            sw.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
            return SettingRowFactory.build(this, icon, title, sub, sw) { sw.performClick() }
        }

        val themeRow = link(R.drawable.ic_palette, R.string.theme_title, 0) {
            startActivity(Intent(this, ThemeActivity::class.java))
        }
        themeSubtitle = SettingRowFactory.subtitle(this, themeRow)
        val layoutRow = link(R.drawable.ic_layout, R.string.settings_layout_title, R.string.settings_layout_sub) {
            startActivity(Intent(this, HomeLayoutActivity::class.java))
        }
        section(R.string.settings_appearance, themeRow, layoutRow)

        section(
            R.string.settings_general,
            toggle(R.drawable.ic_haptics, R.string.settings_haptics_title, R.string.settings_haptics_sub, KEY_HAPTICS, true),
            backgroundRow(prefs)
        )
        section(
            R.string.settings_developer,
            toggle(R.drawable.ic_dev_tools, R.string.settings_devtools_title, R.string.settings_devtools_sub, KEY_DEV_TOOLS_BUTTON, true)
        )

        val version = UpdateChecker.installed(this)
        val updateRow = link(R.drawable.ic_update, R.string.row_update_title, 0) {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
        SettingRowFactory.subtitle(this, updateRow).text = getString(R.string.settings_update_sub, version)
        section(
            R.string.settings_app,
            updateRow,
            toggle(R.drawable.ic_update, R.string.update_auto_title, R.string.update_auto_sub, UpdateChecker.KEY_AUTO, true),
            link(R.drawable.ic_info, R.string.settings_about_title, R.string.settings_about_sub) {
                startActivity(Intent(this, AboutActivity::class.java))
            }
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@SettingsActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    private var themeSubtitle: android.widget.TextView? = null

    /**
     * Background service switch. Turning it off asks first (the widget and the automatic reconnect
     * need the service); Cancel puts the switch back without saving.
     */
    private fun backgroundRow(prefs: android.content.SharedPreferences): LinearLayout {
        val key = BudsService.PREF_BACKGROUND
        val sw = SettingRowFactory.buildSwitch(this, prefs.getBoolean(key, true))
        var reverting = false
        sw.setOnCheckedChangeListener { _, on ->
            if (reverting) return@setOnCheckedChangeListener
            if (on) {
                // The service is still bound by the open main screen, so nothing needs restarting.
                prefs.edit().putBoolean(key, true).apply()
                return@setOnCheckedChangeListener
            }
            reverting = true
            sw.isChecked = true
            reverting = false
            ConfirmDialog.show(
                this, getString(R.string.settings_background_warn_title),
                getString(R.string.settings_background_warn_body),
                getString(R.string.settings_background_warn_action)
            ) {
                prefs.edit().putBoolean(key, false).apply()
                reverting = true
                sw.isChecked = false
                reverting = false
            }
        }
        return SettingRowFactory.build(
            this, R.drawable.ic_power, R.string.settings_background_title, R.string.settings_background_sub, sw
        ) { sw.performClick() }
    }

    override fun onResume() {
        super.onResume()
        themeSubtitle?.text = ThemeRes.palette(this).name
    }

    companion object {
        const val KEY_HAPTICS = "haptics"
        const val KEY_DEV_TOOLS_BUTTON = "devToolsButton"
    }
}
