package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import com.spizganed.quickbuds.R

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
            sw.setOnCheckedChangeListener { v, on ->
                prefs.edit().putBoolean(key, on).apply()
                Haptics.commit(v)
            }
            return SettingRowFactory.build(this, icon, title, sub, sw) { sw.performClick() }
        }

        val themeRow = link(R.drawable.ic_palette, R.string.theme_title, 0) {
            startActivity(Intent(this, ThemeActivity::class.java))
        }
        themeSubtitle = SettingRowFactory.subtitle(this, themeRow)
        // Home layout has no screen in this pass ([USER] 2026-09-26): shown, disabled.
        val layoutRow = link(R.drawable.ic_layout, R.string.settings_layout_title, R.string.settings_layout_sub) {}
            .apply { isEnabled = false; alpha = 0.35f }
        section(R.string.settings_appearance, themeRow, layoutRow)

        section(
            R.string.settings_general,
            toggle(R.drawable.ic_haptics, R.string.settings_haptics_title, R.string.settings_haptics_sub, KEY_HAPTICS, true)
        )
        section(
            R.string.settings_developer,
            toggle(R.drawable.ic_dev_tools, R.string.settings_devtools_title, R.string.settings_devtools_sub, KEY_DEV_TOOLS_BUTTON, true)
        )

        val version = installedVersion(this)
        val updateRow = link(R.drawable.ic_update, R.string.row_update_title, 0) {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
        SettingRowFactory.subtitle(this, updateRow).text = getString(R.string.settings_update_sub, version)
        section(
            R.string.settings_app,
            updateRow,
            link(R.drawable.ic_info, R.string.settings_about_title, R.string.settings_about_sub) { showAbout(version) }
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@SettingsActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    private var themeSubtitle: android.widget.TextView? = null

    override fun onResume() {
        super.onResume()
        themeSubtitle?.text = ThemeRes.palette(this).name
    }

    private fun showAbout(version: String) {
        ConfirmDialog.show(
            this, getString(R.string.app_name), getString(R.string.about_body, version),
            getString(R.string.about_github), cancelRes = R.string.dialog_close
        ) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
        }
    }

    companion object {
        const val KEY_HAPTICS = "haptics"
        const val KEY_DEV_TOOLS_BUTTON = "devToolsButton"
        private const val GITHUB_URL = "https://github.com/spizganed/QuickBuds"

        fun installedVersion(c: Context): String = try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
}
