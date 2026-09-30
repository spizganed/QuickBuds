package com.spizganed.quickbuds.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.Switch
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.protocol.OpoProtocol

/**
 * Wear detection, a bottom sheet: the buds' own auto play/pause (`0x0403` feature `0x04`, `[CAPTURE]`
 * 2026-09-25) and our smart auto-pause (BudsService.smartPause), which pauses only when BOTH buds are out.
 *
 * The two are mutually exclusive: the firmware pauses as soon as ONE bud is out, which would defeat the
 * smart one. Turning either on turns the other off.
 */
class WearSheet(private val activity: Activity, private val setFirmware: (Boolean) -> Unit, initial: Int?) {

    private val prefs = activity.getSharedPreferences(ThemeRes.PREFS_NAME, Activity.MODE_PRIVATE)
    private var syncing = false
    private val firmwareSwitch: Switch = SettingRowFactory.buildSwitch(activity, initial == 1)
    private val smartSwitch: Switch = SettingRowFactory.buildSwitch(activity, prefs.getBoolean(BudsService.PREF_SMART_PAUSE, false))

    fun show() {
        firmwareSwitch.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            setFirmware(on)
            if (on && smartSwitch.isChecked) smartSwitch.isChecked = false
        }
        smartSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BudsService.PREF_SMART_PAUSE, on).apply()
            if (on && firmwareSwitch.isChecked) firmwareSwitch.isChecked = false
        }
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(SettingRowFactory.build(activity, 0, R.string.wear_firmware_title, R.string.wear_firmware_sub, firmwareSwitch) { firmwareSwitch.performClick() })
            addView(SettingRowFactory.buildDivider(activity))
            addView(SettingRowFactory.build(activity, 0, R.string.wear_smart_title, R.string.wear_smart_sub, smartSwitch) { smartSwitch.performClick() })
        }
        BottomSheetDialog(activity).title(activity.getString(R.string.wear_title)).content(rows).show()
    }

    /** The buds' own switch changed (a re-read); shown quietly so no write goes out. */
    fun onFeatureStates(states: Map<Int, Int>) {
        val v = states[OpoProtocol.FEATURE_AUTO_PLAY_PAUSE] ?: return
        syncing = true
        firmwareSwitch.isChecked = v == 1
        syncing = false
    }
}
