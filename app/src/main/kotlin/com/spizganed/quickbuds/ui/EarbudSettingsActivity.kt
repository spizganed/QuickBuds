package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.OpoProtocol

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
    private var firmwareText: TextView? = null
    private var fitSheet: FitTestSheet? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            manager = (service as BudsService.LocalBinder).getService().manager
            manager?.addListener(this@EarbudSettingsActivity)
            if (::alertSlider.isInitialized) {
                manager?.alertVolume?.let { onAlertVolume(it) }
                manager?.refreshAlertVolume()
            }
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
        // Only what these buds have (see Capabilities); a model without it never sees the row.
        fun link(icon: Int, title: Int, sub: Int, target: Class<*>, supported: Boolean) {
            if (!supported) return
            if (card.childCount > 0) card.addView(SettingRowFactory.buildDivider(this))
            card.addView(
                SettingRowFactory.build(this, icon, title, sub, SettingRowFactory.buildChevron(this)) {
                    startActivity(Intent(this, target))
                }
            )
        }
        link(R.drawable.ic_gesture, R.string.row_gesture_title, R.string.row_gesture_sub, GestureActivity::class.java,
            Capabilities.supports(this, OpoProtocol.CMD_SET_KEY_FUNCTION) && !GestureModel.of(this).isEmpty)
        link(R.drawable.ic_bud_left, R.string.row_wear_title, R.string.row_wear_sub, WearActivity::class.java,
            Capabilities.hasFeature(this, OpoProtocol.FEATURE_AUTO_PLAY_PAUSE))
        link(R.drawable.ic_find_buds, R.string.row_find_title, R.string.row_find_sub, FindBudsActivity::class.java,
            Capabilities.supports(this, OpoProtocol.CMD_FIND_BUDS))
        // Earbud fit test: a sheet, as in HeyMelody's More settings (PROTOCOL.md §9).
        if (Capabilities.supports(this, OpoProtocol.CMD_FIT_TEST)) {
            if (card.childCount > 0) card.addView(SettingRowFactory.buildDivider(this))
            card.addView(SettingRowFactory.build(this, R.drawable.ic_bud_right, R.string.fit_title, R.string.fit_sub,
                SettingRowFactory.buildChevron(this)) {
                fitSheet = FitTestSheet(this) { on -> manager?.fitTest(on) }.also { it.show() }
            })
        }
        if (card.childCount > 0) root.addView(card)
        if (Capabilities.supports(this, OpoProtocol.CMD_SET_ALERT_VOLUME)) sounds(root)

        // --- About: the firmware version as HeyMelody shows it (read on connect, `0x0105`) ---
        if (Capabilities.supports(this, OpoProtocol.CMD_QUERY_FIRMWARE)) {
            root.addView(SettingRowFactory.sectionLabel(this, R.string.earbuds_section_about))
            val row = SettingRowFactory.build(this, R.drawable.ic_info, R.string.row_firmware_title, 0, null)
            firmwareText = SettingRowFactory.subtitle(this, row)
            paintFirmware()
            root.addView(cardView().apply { addView(row) })
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun paintFirmware(version: String? = getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        .getString(BudsConnectionManager.KEY_FIRMWARE, null)) {
        firmwareText?.text = version ?: "—"
    }

    private fun sounds(root: LinearLayout) {
        val dp = { v: Float -> ThemeRes.dp(this, v) }
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
                typeface = ThemeRes.bold(context)
            })
            addView(LinearLayout(this@EarbudSettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(alertSpeaker)
                addView(alertSlider)
            })
        })
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
        if (!::alertSlider.isInitialized || alertSlider.dragging) return
        alertSlider.value = level
        paintSpeaker(level)
    }

    override fun onFitResult(left: Int, right: Int) { fitSheet?.result(left, right) }

    override fun onStatus(msg: String) {}
    override fun onConnected(connected: Boolean) {}
    override fun onPacketReceived(bytes: ByteArray) {
        // A firmware reply (`0x8105`, `00 <count>` + text) that lands while this screen is open.
        if (bytes.size > 11 && bytes[4].toInt() and 0xFF == 0x05 && bytes[5].toInt() and 0xFF == 0x81 && bytes[9].toInt() == 0)
            OpoProtocol.firmwareVersion(String(bytes, 11, bytes.size - 11, Charsets.UTF_8))?.let { paintFirmware(it) }
    }
    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {}
    override fun onBudState(state: String) {}
}
