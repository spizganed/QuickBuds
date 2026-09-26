package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.BudsService

/**
 * Earbud settings — the hub for everything about the buds themselves rather than the sound
 * (`[USER]` 2026-09-25, option A): gestures, wear detection, dual connection, find, and the
 * alert-sound volume. It keeps the main screen to the audio controls. New firmware settings belong
 * here too.
 */
class EarbudSettingsActivity : Activity(), BudsConnectionManager.Listener {

    private var manager: BudsConnectionManager? = null
    private var bound = false
    private lateinit var alertSlider: LevelSliderView
    private lateinit var alertSpeaker: ImageView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            manager = (service as BudsService.LocalBinder).getService().manager
            manager?.addListener(this@EarbudSettingsActivity)
            manager?.alertVolume?.let { onAlertVolume(it) }
            manager?.refreshAlertVolume()
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
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.earbuds_title))

        val card = cardView()
        fun link(icon: Int, title: Int, sub: Int, target: Class<*>) {
            if (card.childCount > 0) card.addView(SettingRowFactory.buildDivider(this))
            card.addView(
                SettingRowFactory.build(this, icon, title, sub, SettingRowFactory.buildChevron(this)) {
                    startActivity(Intent(this, target))
                }
            )
        }
        link(R.drawable.ic_gesture, R.string.row_gesture_title, R.string.row_gesture_sub, GestureActivity::class.java)
        link(R.drawable.ic_bud_left, R.string.row_wear_title, R.string.row_wear_sub, WearActivity::class.java)
        link(R.drawable.ic_devices, R.string.row_dual_title, R.string.row_dual_sub, DualDeviceActivity::class.java)
        link(R.drawable.ic_find_buds, R.string.row_find_title, R.string.row_find_sub, FindBudsActivity::class.java)
        root.addView(card)

        // --- Sounds: alert-sound volume, 1..10: `0x0427`, read back with `0x0130`. [CAPTURE] 2026-09-25 ---
        // Sent on release only, so the buds play one prompt per change, not one per step.
        // No numbers, as in HeyMelody: a speaker icon left of the bar, muted at the lowest step
        // (level 1 is silent on the buds, `[USER]` 2026-09-25).
        root.addView(SettingRowFactory.sectionLabel(this, R.string.earbuds_section_sounds))
        alertSpeaker = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
        }
        alertSlider = LevelSliderView(this, 1, 10).apply {
            showValue = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            onChange = { paintSpeaker(it) }
            onRelease = { manager?.setAlertVolume(it) }
        }
        paintSpeaker(alertSlider.value)
        root.addView(cardView().apply {
            setPadding(dp(18f), dp(14f), dp(10f), dp(6f))
            addView(TextView(this@EarbudSettingsActivity).apply {
                setText(R.string.row_alert_title)
                setTextColor(ThemeRes.color(this@EarbudSettingsActivity, R.attr.appColorTextPrimary))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(LinearLayout(this@EarbudSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(alertSpeaker)
                addView(alertSlider)
            })
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun cardView() = SettingRowFactory.card(this)

    private fun paintSpeaker(level: Int) {
        alertSpeaker.setImageDrawable(ThemeRes.tint(
            this, if (level <= 1) R.drawable.ic_volume_off else R.drawable.ic_volume,
            ThemeRes.color(this, R.attr.appColorAccent)
        ))
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

    override fun onAlertVolume(level: Int) {
        if (alertSlider.dragging) return
        alertSlider.value = level
        paintSpeaker(level)
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
