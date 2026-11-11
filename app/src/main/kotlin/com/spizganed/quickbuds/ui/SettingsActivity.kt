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
 * Settings, a full screen reached from the header cog. Same layout pattern
 * as Earbud settings: title, then sectioned cards. Toggles save on change. The background service switch asks first.
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)

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

        fun toggle(icon: Int, title: Int, sub: Int, key: String, default: Boolean) =
            SettingRowFactory.prefSwitch(this, icon, title, sub, key, default)

        val themeRow = link(R.drawable.ic_palette, R.string.theme_title, 0) {
            startActivity(Intent(this, ThemeActivity::class.java))
        }
        themeSubtitle = SettingRowFactory.subtitle(this, themeRow)
        val layoutRow = link(R.drawable.ic_layout, R.string.settings_layout_title, R.string.settings_layout_sub) {
            startActivity(Intent(this, HomeLayoutActivity::class.java))
        }
        section(R.string.settings_appearance, themeRow, layoutRow)

        // Notifications and widgets have their own pages (the service, battery and chip rows; the widgets and the tile).
        val general = mutableListOf<android.view.View>(
            link(R.drawable.ic_notifications, R.string.setup_perm_notif, 0) {
                startActivity(Intent(this, NotificationSettingsActivity::class.java))
            },
            link(R.drawable.ic_widgets, R.string.setup_widgets_title, 0) {
                startActivity(Intent(this, WidgetPageActivity::class.java))
            },
            toggle(R.drawable.ic_haptics, R.string.settings_haptics_title, R.string.settings_haptics_sub, KEY_HAPTICS, true),
            toggle(R.drawable.ic_anc, R.string.settings_anc_pick_title, R.string.settings_anc_pick_sub, KEY_ANC_PICK_FIRST, false),
            toggle(R.drawable.ic_battery, R.string.settings_clear_battery_title, R.string.settings_clear_battery_sub,
                BudsService.PREF_CLEAR_BATTERY, false),
            toggle(R.drawable.ic_dev_tools, R.string.settings_devtools_title, R.string.settings_devtools_sub, KEY_DEV_TOOLS_BUTTON, false),
            toggle(R.drawable.ic_info, R.string.settings_ping_title, R.string.settings_ping_sub, UsagePing.KEY_ENABLED, true)
        )
        general.add(0, link(R.drawable.ic_language, R.string.settings_language_title, R.string.settings_language_sub) {
            startActivity(Intent(this, LanguageActivity::class.java))
        })
        general.add(link(R.drawable.ic_info, R.string.settings_setup_title, 0) {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_AGAIN, true))
        })
        section(R.string.settings_general, *general.toTypedArray())

        val version = UpdateChecker.installed(this)
        val updateRow = link(R.drawable.ic_update, R.string.row_update_title, 0) {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
        SettingRowFactory.subtitle(this, updateRow).text = getString(R.string.settings_update_sub, version)
        section(
            R.string.settings_app,
            updateRow,
            link(R.drawable.ic_earbud, R.string.test_title, R.string.test_sub) {
                startActivity(Intent(this, ModelTestActivity::class.java))
            },
            link(R.drawable.ic_dev_tools, R.string.problem_title, R.string.problem_sub) {
                startActivity(Intent(this, ReportActivity::class.java))
            },
            link(R.drawable.ic_info, R.string.settings_about_title, R.string.settings_about_sub) {
                startActivity(Intent(this, AboutActivity::class.java))
            },
            link(R.drawable.ic_info, R.string.settings_issues_title, R.string.settings_issues_sub) {
                startActivity(Intent(this, KnownIssuesActivity::class.java))
            }
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

    companion object {
        const val KEY_HAPTICS = "haptics"
        /** On: the ANC segment waits for a strength pick. Off (default): it applies the last strength. */
        const val KEY_ANC_PICK_FIRST = "ancPickFirst"
        const val KEY_DEV_TOOLS_BUTTON = "devToolsButton"
    }
}
