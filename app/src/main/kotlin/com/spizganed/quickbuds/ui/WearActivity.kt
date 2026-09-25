package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.protocol.OpoProtocol

/**
 * Wear detection: the buds' own auto play/pause (`0x0403` feature `0x04`, `[CAPTURE]` 2026-09-25)
 * and our smart auto-pause (BudsService.smartPause), which pauses only when BOTH buds are out.
 *
 * The two are mutually exclusive: the firmware pauses as soon as ONE bud is out, which would
 * defeat the smart one. Turning either on turns the other off.
 */
class WearActivity : Activity(), BudsConnectionManager.Listener {

    private var manager: BudsConnectionManager? = null
    private var bound = false
    private var syncing = false
    private lateinit var firmwareSwitch: Switch
    private lateinit var smartSwitch: Switch

    private val prefs get() = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            manager = (service as BudsService.LocalBinder).getService().manager
            manager?.addListener(this@WearActivity)
            manager?.featureStates?.let { onFeatureStates(it) }
            manager?.requestFullStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            manager = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate — see ThemeRes.select.
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)

        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeRes.color(this@WearActivity, R.attr.appColorBg))
            setPadding(dp(16f), dp(44f), dp(16f), dp(24f))
        }
        root.addView(TextView(this).apply {
            setText(R.string.wear_title)
            setTextColor(ThemeRes.color(this@WearActivity, R.attr.appColorTextPrimary))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(16f))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.app_card_bg)
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }

        firmwareSwitch = SettingRowFactory.buildSwitch(this, false)
        firmwareSwitch.setOnCheckedChangeListener { _, on ->
            SettingRowFactory.refreshSwitch(this, firmwareSwitch, on)
            if (syncing) return@setOnCheckedChangeListener
            manager?.setFeatures(OpoProtocol.FEATURE_AUTO_PLAY_PAUSE to on)
            if (on && smartSwitch.isChecked) smartSwitch.isChecked = false
        }
        card.addView(
            SettingRowFactory.build(
                this, R.drawable.ic_bud_left, R.string.wear_firmware_title, R.string.wear_firmware_sub,
                firmwareSwitch
            ) { firmwareSwitch.performClick() }
        )
        card.addView(SettingRowFactory.buildDivider(this))

        smartSwitch = SettingRowFactory.buildSwitch(this, prefs.getBoolean(BudsService.PREF_SMART_PAUSE, false))
        smartSwitch.setOnCheckedChangeListener { _, on ->
            SettingRowFactory.refreshSwitch(this, smartSwitch, on)
            prefs.edit().putBoolean(BudsService.PREF_SMART_PAUSE, on).apply()
            if (on && firmwareSwitch.isChecked) firmwareSwitch.isChecked = false
        }
        card.addView(
            SettingRowFactory.build(
                this, R.drawable.ic_bud_right, R.string.wear_smart_title, R.string.wear_smart_sub,
                smartSwitch
            ) { smartSwitch.performClick() }
        )
        root.addView(card)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, BudsService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        manager?.removeListener(this)
        if (bound) unbindService(connection)
        bound = false
        manager = null
    }

    override fun onFeatureStates(states: Map<Int, Int>) {
        val v = states[OpoProtocol.FEATURE_AUTO_PLAY_PAUSE] ?: return
        syncing = true
        firmwareSwitch.isChecked = v == 1
        syncing = false
    }

    override fun onStatus(msg: String) {}
    override fun onConnected(connected: Boolean) {}
    override fun onPacketReceived(bytes: ByteArray) {}
    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {}
    override fun onBudState(state: String) {}
}
