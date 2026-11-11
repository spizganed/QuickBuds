package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService

/**
 * Settings › Notifications: the background service (its notification keeps the link), the battery notification,
 * the Android 16 status chip and the lock. Same layout as Settings: title, then one card.
 */
class NotificationSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.setup_perm_notif))
        fun toggle(title: Int, sub: Int, key: String, default: Boolean) =
            SettingRowFactory.prefSwitch(this, R.drawable.ic_battery, title, sub, key, default)
        val rows = mutableListOf<android.view.View>(
            backgroundRow(prefs),
            toggle(R.string.settings_full_notif_title, R.string.settings_full_notif_sub, BudsService.PREF_FULL_NOTIFICATION, true),
            toggle(R.string.settings_lock_notif_title, R.string.settings_lock_notif_sub, BudsService.PREF_LOCK_NOTIFICATION, false),
        )
        // The chip is an Android 16 live update: older versions do not get the row.
        if (android.os.Build.VERSION.SDK_INT >= 36)
            rows.add(2, toggle(R.string.settings_live_chip_title, R.string.settings_live_chip_sub, BudsService.PREF_LIVE_CHIP, false))
        val card = SettingRowFactory.card(this)
        rows.forEach { SettingRowFactory.addRow(card, it) }
        root.addView(card)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@NotificationSettingsActivity, R.attr.appColorBg))
            addView(root)
        })
    }

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

}
