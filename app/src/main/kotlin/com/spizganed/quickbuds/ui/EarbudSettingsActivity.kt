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
import com.spizganed.quickbuds.protocol.ModelCatalog
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
    private var gameSoundText: TextView? = null
    private var headMotionText: TextView? = null
    private var fitSheet: FitTestSheet? = null
    private val featureSwitches = HashMap<Int, android.widget.Switch>()
    private var syncing = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            manager = (service as BudsService.LocalBinder).getService().manager
            manager?.addListener(this@EarbudSettingsActivity)
            manager?.featureStates?.let { onFeatureStates(it) }
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
        features(root)
        if (Capabilities.supports(this, OpoProtocol.CMD_SET_ALERT_VOLUME)) sounds(root)

        // --- About: the firmware version as HeyMelody shows it (read on connect, `0x0105`) ---
        if (Capabilities.supports(this, OpoProtocol.CMD_QUERY_FIRMWARE)) {
            root.addView(SettingRowFactory.sectionLabel(this, R.string.earbuds_section_about))
            // No firmware updates here ([USER] 2026-09-29): a failed flash can brick the buds, so a tap
            // points to HeyMelody instead.
            val row = SettingRowFactory.build(this, R.drawable.ic_info, R.string.row_firmware_title, 0, null) {
                ConfirmDialog.show(this, getString(R.string.firmware_dialog_title),
                    getString(R.string.firmware_dialog_body), getString(R.string.dialog_close), cancelRes = null)
            }
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

    /**
     * `0x0403` on/off switches HeyMelody has and Buds 4 lacks (PROTOCOL.md §9), each shown only where
     * [Capabilities.offered]. Unverified on buds until an owner reads a write back.
     */
    private fun features(root: LinearLayout) {
        val card = cardView()
        fun switch(id: Int, flag: String?, icon: Int, title: Int, sub: Int, confirm: Int = 0) {
            if (!Capabilities.offered(this, id, flag)) return
            val sw = SettingRowFactory.buildSwitch(this, manager?.featureStates?.get(id) == 1)
            featureSwitches[id] = sw
            sw.setOnCheckedChangeListener { _, on ->
                if (syncing) return@setOnCheckedChangeListener
                // Power saving restarts the buds (asks both ways); adaptive sound costs battery (asks only to
                // turn on). As HeyMelody does.
                if (confirm == 0 || (!on && id == OpoProtocol.FEATURE_HEARING_OPTIMIZE))
                    return@setOnCheckedChangeListener manager?.setFeatures(id to on) ?: Unit
                quiet { sw.isChecked = !on }
                ConfirmDialog.show(this, getString(title), getString(confirm), getString(R.string.dual_add_ok)) {
                    quiet { sw.isChecked = on }
                    manager?.setFeatures(id to on)
                }
            }
            if (card.childCount > 0) card.addView(SettingRowFactory.buildDivider(this))
            card.addView(SettingRowFactory.build(this, icon, title, sub, sw) { sw.performClick() })
        }
        switch(OpoProtocol.FEATURE_VOCAL_ENHANCE, "vocalEnhance", R.drawable.ic_equalizer,
            R.string.row_vocal_title, R.string.row_vocal_sub)
        switch(OpoProtocol.FEATURE_GAME_SOUND, "gameSound", R.drawable.ic_low_latency,
            R.string.row_game_sound_title, R.string.row_game_sound_sub)
        // Which effect it applies: `0x0423 <type> 01`, HeyMelody's radio list (PROTOCOL.md §9).
        if (featureSwitches.containsKey(OpoProtocol.FEATURE_GAME_SOUND) &&
            (Capabilities.supports(this, OpoProtocol.CMD_GAME_SOUND) || ModelCatalog.manual(this) != null)) {
            card.addView(SettingRowFactory.buildDivider(this))
            val row = SettingRowFactory.build(this, R.drawable.ic_low_latency, R.string.game_sound_type_title,
                0, SettingRowFactory.buildChevron(this)) { gameSoundSheet() }
            gameSoundText = SettingRowFactory.subtitle(this, row)
            paintGameSound()
            card.addView(row)
        }
        switch(OpoProtocol.FEATURE_SMART_VOLUME, "controlAutoVolumeSupport", R.drawable.ic_volume,
            R.string.row_smart_volume_title, R.string.row_smart_volume_sub)
        switch(OpoProtocol.FEATURE_ADAPTIVE_VOLUME, null, R.drawable.ic_volume,
            R.string.row_adaptive_volume_title, R.string.row_adaptive_volume_sub)
        switch(OpoProtocol.FEATURE_ADAPTIVE_EAR, null, R.drawable.ic_bud_left,
            R.string.row_adaptive_ear_title, R.string.row_adaptive_ear_sub)
        switch(OpoProtocol.FEATURE_SLEEP_PAUSE, null, R.drawable.ic_bud_right,
            R.string.row_sleep_title, R.string.row_sleep_sub)
        switch(OpoProtocol.FEATURE_SPEECH_PERCEPTION, null, R.drawable.ic_transparency,
            R.string.row_speech_title, R.string.row_speech_sub)
        switch(OpoProtocol.FEATURE_HEARING_OPTIMIZE, null, R.drawable.ic_hearing,
            R.string.row_hearing_optimize_title, R.string.row_hearing_optimize_sub, R.string.hearing_optimize_confirm)
        switch(OpoProtocol.FEATURE_LONG_PRESS_VOLUME, "longPressVolume", R.drawable.ic_volume,
            R.string.row_long_press_volume_title, R.string.row_long_press_volume_sub)
        switch(OpoProtocol.FEATURE_HEAD_MOTION, null, R.drawable.ic_gesture,
            R.string.row_head_motion_title, R.string.row_head_motion_sub)
        // Which gesture answers: `0x0431 <type>`, HeyMelody's nod / shake choice (PROTOCOL.md §9).
        if (featureSwitches.containsKey(OpoProtocol.FEATURE_HEAD_MOTION) &&
            Capabilities.supports(this, OpoProtocol.CMD_SET_HEAD_MOTION_TYPE)) {
            card.addView(SettingRowFactory.buildDivider(this))
            val row = SettingRowFactory.build(this, R.drawable.ic_gesture, R.string.head_motion_type_title,
                0, SettingRowFactory.buildChevron(this)) { headMotionSheet() }
            headMotionText = SettingRowFactory.subtitle(this, row)
            paintHeadMotion()
            card.addView(row)
        }
        switch(OpoProtocol.FEATURE_SWIFT_PAIR, "swiftPair", R.drawable.ic_devices,
            R.string.row_swift_pair_title, R.string.row_swift_pair_sub)
        switch(OpoProtocol.FEATURE_POWER_SAVING, null, R.drawable.ic_power,
            R.string.row_power_saving_title, R.string.row_power_saving_sub, R.string.power_saving_confirm)
        if (card.childCount == 0) return
        root.addView(SettingRowFactory.sectionLabel(this, R.string.earbuds_section_features))
        root.addView(card)
    }

    private fun quiet(block: () -> Unit) { syncing = true; block(); syncing = false }

    override fun onFeatureStates(states: Map<Int, Int>) {
        quiet { for ((id, sw) in featureSwitches) states[id]?.let { sw.isChecked = it == 1 } }
        paintGameSound()
        paintHeadMotion()
    }

    private fun headMotionLabel(type: Int?) = when (type) {
        0 -> getString(R.string.head_motion_nod)
        1 -> getString(R.string.head_motion_shake)
        else -> "—"
    }

    private fun paintHeadMotion() { headMotionText?.text = headMotionLabel(manager?.headMotionType) }

    private fun headMotionSheet() {
        val m = manager ?: return
        BottomSheetDialog(this)
            .title(getString(R.string.head_motion_type_title))
            .items(listOf(0, 1).map { t ->
                BottomSheetDialog.Item(headMotionLabel(t), t == m.headMotionType) {
                    m.setHeadMotionType(t)
                    paintHeadMotion()
                }
            })
            .show()
    }

    /** HeyMelody's names for the game sound types; a type without one is not offered. */
    private fun gameSoundLabel(type: Int) = when (type) {
        0 -> getString(R.string.anc_seg_off)
        1 -> getString(R.string.game_sound_type_peace)
        3 -> getString(R.string.game_sound_type_shooter)
        else -> null
    }

    private fun paintGameSound() {
        gameSoundText?.text = manager?.gameSoundType?.let { gameSoundLabel(it) } ?: "—"
    }

    /** The types the buds offer (`0x812B`), Off first; before a read, HeyMelody's usual Off + shooting. */
    private fun gameSoundSheet() {
        val m = manager ?: return
        val types = (listOf(0) + m.gameSoundTypes.ifEmpty { listOf(3) }).distinct().filter { gameSoundLabel(it) != null }
        BottomSheetDialog(this)
            .title(getString(R.string.game_sound_type_title))
            .items(types.map { t ->
                BottomSheetDialog.Item(gameSoundLabel(t)!!, t == m.gameSoundType) {
                    m.setGameSoundType(t)
                    paintGameSound()
                }
            })
            .show()
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
