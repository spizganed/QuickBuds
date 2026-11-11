package com.spizganed.quickbuds.ui

import android.app.Activity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.protocol.Feature

/**
 * Wear detection, a bottom sheet: the buds' own auto play/pause (`0x0403` feature `0x04`, `[CAPTURE]`)
 * and our smart auto-pause (BudsService.smartPause), which pauses only when BOTH buds are out.
 *
 * The two are mutually exclusive: the firmware pauses as soon as ONE bud is out, which would defeat the
 * smart one. Turning either on turns the other off.
 */
class WearSheet(private val activity: Activity, private val requestFirmware: (Boolean) -> Unit, initial: Int?) {

    private val prefs = activity.getSharedPreferences(ThemeRes.PREFS_NAME, Activity.MODE_PRIVATE)
    private val firmwareSwitch: Switch = SettingRowFactory.buildSwitch(activity, initial == 1)
    private val smartSwitch: Switch = SettingRowFactory.buildSwitch(activity, prefs.getBoolean(BudsService.PREF_SMART_PAUSE, false))
    private lateinit var firmwareRow: LinearLayout

    fun show() {
        // A tap asks for the opposite value; only the buds' report moves this switch.
        (firmwareSwitch as RequestSwitch).onRequest = { on -> requestFirmware(on) }
        smartSwitch.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(BudsService.PREF_SMART_PAUSE, on).apply()
            if (on && firmwareSwitch.isChecked) requestFirmware(false)
        }
        val rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            firmwareRow = SettingRowFactory.build(activity, 0, R.string.wear_firmware_title, R.string.wear_firmware_sub, firmwareSwitch) { firmwareSwitch.performClick() }
            addView(firmwareRow)
            addView(SettingRowFactory.buildDivider(activity))
            addView(SettingRowFactory.build(activity, 0, R.string.wear_smart_title, R.string.wear_smart_sub, smartSwitch) { smartSwitch.performClick() })
        }
        BottomSheetDialog(activity).title(activity.getString(R.string.wear_title)).content(rows).show()
    }

    /** The buds' own switch changed (a report): painted from the buds' value only. */
    fun onFeatureStates(states: Map<Int, Int>) {
        val v = states[Feature.AUTO_PLAY_PAUSE] ?: return
        firmwareSwitch.setFromBuds(v == 1)
        if (v == 1 && smartSwitch.isChecked) smartSwitch.isChecked = false
    }

    /** The engine applies a tap: the row says "Applying…" and its switch stays still. */
    fun onSettingBusy(key: Int, on: Boolean) {
        if (key != Feature.AUTO_PLAY_PAUSE) return
        firmwareSwitch.isEnabled = !on
        val sub: TextView = SettingRowFactory.subtitle(activity, firmwareRow)
        sub.setText(if (on) R.string.feature_applying else R.string.wear_firmware_sub)
    }
}
