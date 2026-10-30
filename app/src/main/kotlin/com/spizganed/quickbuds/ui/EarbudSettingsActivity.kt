package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.GameEqWatcher
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.protocol.Feature
import com.spizganed.quickbuds.protocol.FeatureSync

/**
 * Earbud settings — the hub for everything about the buds themselves rather than the sound
 * (option A): gestures, wear detection, dual connection, find, and the
 * alert-sound volume. It keeps the main screen to the audio controls. New firmware settings belong
 * here too.
 */
class EarbudSettingsActivity : Activity(), BudsConnectionManager.Listener {

    private var manager: BudsConnectionManager? = null
    private var bound = false
    private lateinit var alertSlider: LevelSliderView
    private lateinit var alertSpeaker: ImageView
    private var firmwareText: TextView? = null
    private var tapSlider: LevelSliderView? = null
    private var tapWarning: TextView? = null
    private var gameSoundText: TextView? = null
    private var headMotionText: TextView? = null
    private var fitSheet: FitTestSheet? = null
    private var wearSheet: WearSheet? = null
    private val personalNoise by lazy {
        PersonalNoiseFlow(this, { manager?.personalNoise(it) }, { manager?.queryPersonalNoise() }, { manager?.requestFullStatus() })
    }
    private val featureSwitches = HashMap<Int, android.widget.Switch>()
    /** The row and its default subtitle of each feature switch, for the "Applying…" state. */
    private val featureRows = HashMap<Int, LinearLayout>()
    private val featureSubs = HashMap<Int, Int>()
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
            if (tapSlider != null && Capabilities.supports(this@EarbudSettingsActivity, OpoProtocol.CMD_QUERY_TAP_LEVEL))
                manager?.refreshTapLevel()
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
        // Wear detection, Find my earbuds and the fit test are sheets, as in HeyMelody's More settings.
        fun sheetRow(icon: Int, title: Int, sub: Int, supported: Boolean, open: () -> Unit) {
            if (!supported) return
            if (card.childCount > 0) card.addView(SettingRowFactory.buildDivider(this))
            card.addView(SettingRowFactory.build(this, icon, title, sub, SettingRowFactory.buildChevron(this)) { open() })
        }
        sheetRow(R.drawable.ic_earbud, R.string.row_wear_title, R.string.row_wear_sub,
            Capabilities.hasFeature(this, Feature.AUTO_PLAY_PAUSE)) {
            wearSheet = WearSheet(this, { requestFeature(Feature.AUTO_PLAY_PAUSE, it) },
                manager?.featureStates?.get(Feature.AUTO_PLAY_PAUSE)).also { it.show() }
        }
        sheetRow(R.drawable.ic_find_buds, R.string.row_find_title, R.string.row_find_sub,
            Capabilities.supports(this, OpoProtocol.CMD_FIND_BUDS)) {
            FindBudsSheet(this) { on ->
                startService(Intent(this, BudsService::class.java).setAction(BudsService.ACTION_FIND_BUDS)
                    .putExtra(BudsService.EXTRA_FIND_ON, on))
            }.show()
        }
        sheetRow(R.drawable.ic_earbud, R.string.fit_title, R.string.fit_sub, Capabilities.supports(this, OpoProtocol.CMD_FIT_TEST)) {
            fitSheet = FitTestSheet(this) { on -> manager?.fitTest(on) }.also { it.show() }
        }
        if (card.childCount > 0) root.addView(card)
        tapSensitivity(root)
        features(root)
        if (Capabilities.supports(this, OpoProtocol.CMD_SET_ALERT_VOLUME)) sounds(root)

        // --- About: the firmware version as HeyMelody shows it (read on connect, `0x0105`) ---
        if (Capabilities.supports(this, OpoProtocol.CMD_QUERY_FIRMWARE)) {
            root.addView(SettingRowFactory.sectionLabel(this, R.string.earbuds_section_about))
            // No firmware updates here: a failed flash can brick the buds, so a tap
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
     * `0x0403` on/off switches HeyMelody has and Buds 4 lacks (PROTOCOL.md §16), each shown only where
     * [Capabilities.offered]. Unverified on buds until an owner reads a write back.
     */
    private fun features(root: LinearLayout) {
        val card = cardView()
        fun addSwitch(id: Int, icon: Int, title: Int, sub: Int, sw: android.widget.Switch) {
            featureSwitches[id] = sw
            featureSubs[id] = sub
            if (card.childCount > 0) card.addView(SettingRowFactory.buildDivider(this))
            val row = SettingRowFactory.build(this, icon, title, sub, sw) { sw.performClick() }
            featureRows[id] = row
            card.addView(row)
        }
        /** An old-path switch: it moves on the tap, then one write and a status read go out. */
        fun switch(id: Int, flag: String?, icon: Int, title: Int, sub: Int, confirm: Int = 0) {
            if (!Capabilities.offered(this, id, flag)) return
            val sw = SettingRowFactory.buildSwitch(this, manager?.featureStates?.get(id) == 1)
            sw.setOnCheckedChangeListener { _, on ->
                if (syncing) return@setOnCheckedChangeListener
                // Power saving restarts the buds (asks both ways); adaptive sound costs battery (asks only to
                // turn on). As HeyMelody does.
                if (confirm == 0 || (!on && id == Feature.HEARING_OPTIMIZE))
                    return@setOnCheckedChangeListener manager?.setFeatures(id to on) ?: Unit
                quiet { sw.isChecked = !on }
                ConfirmDialog.show(this, getString(title), getString(confirm), getString(R.string.dual_add_ok)) {
                    quiet { sw.isChecked = on }
                    manager?.setFeatures(id to on)
                }
            }
            addSwitch(id, icon, title, sub, sw)
        }
        /** The feature-sync engine sets it: a tap asks, the buds' report moves the switch. Every dialog stays. */
        fun syncSwitch(id: Int, flag: String?, icon: Int, title: Int, sub: Int, confirm: Int = 0) {
            if (!Capabilities.offered(this, id, flag)) return
            val sw = SettingRowFactory.buildSwitch(this, manager?.featureStates?.get(id) == 1)
            (sw as RequestSwitch).onRequest = { wantOn ->
                if (confirm == 0 || (!wantOn && id == Feature.HEARING_OPTIMIZE)) requestFeature(id, wantOn)
                else ConfirmDialog.show(this, getString(title), getString(confirm), getString(R.string.dual_add_ok)) {
                    requestFeature(id, wantOn)
                }
            }
            addSwitch(id, icon, title, sub, sw)
        }
        // Personalized ANC: off is a plain switch write, on runs HeyMelody's test flow (PROTOCOL.md §20).
        // The model's flag too: HeyMelody gates on it alone, and Buds 4 list 0x0C without having the feature.
        if ((ModelCatalog.current(this)?.json?.optInt("personalAnc") ?: 1) == 1 &&
            Capabilities.offered(this, Feature.PERSONAL_NOISE, "personalAnc") &&
            (Capabilities.supports(this, OpoProtocol.CMD_PERSONAL_NOISE) || ModelCatalog.manual(this) != null)) {
            val id = Feature.PERSONAL_NOISE
            val sw = SettingRowFactory.buildSwitch(this, manager?.featureStates?.get(id) == 1)
            featureSwitches[id] = sw
            sw.setOnCheckedChangeListener { _, on ->
                if (syncing) return@setOnCheckedChangeListener
                if (!on) return@setOnCheckedChangeListener manager?.setFeatures(id to false) ?: Unit
                quiet { sw.isChecked = false }   // the buds turn it on once a result applies
                personalNoise.start()
            }
            card.addView(SettingRowFactory.build(this, R.drawable.ic_anc, R.string.pnc_title, R.string.pnc_sub, sw) {
                sw.performClick()
            })
        }
        syncSwitch(Feature.VOCAL_ENHANCE, "voiceBoost", R.drawable.ic_equalizer,
            R.string.row_vocal_title, R.string.row_vocal_sub)
        syncSwitch(Feature.GAME_SOUND, "gameSoundFx", R.drawable.ic_low_latency,
            R.string.row_game_sound_title, R.string.row_game_sound_sub)
        // Which effect it applies: `0x0423 <type> 01`, HeyMelody's radio list (PROTOCOL.md §18).
        if (featureSwitches.containsKey(Feature.GAME_SOUND) &&
            (Capabilities.supports(this, OpoProtocol.CMD_GAME_SOUND) || ModelCatalog.manual(this) != null)) {
            card.addView(SettingRowFactory.buildDivider(this))
            val row = SettingRowFactory.build(this, R.drawable.ic_low_latency, R.string.game_sound_type_title,
                0, SettingRowFactory.buildChevron(this)) { gameSoundSheet() }
            gameSoundText = SettingRowFactory.subtitle(this, row)
            paintGameSound()
            card.addView(row)
        }
        // Game equalizer: the switch, and the Usage access it needs to see which app is open (PROTOCOL.md §18).
        if (Capabilities.supports(this, OpoProtocol.CMD_GAME_EQ_STATE)) {
            syncSwitch(Feature.GAME_EQ, null, R.drawable.ic_low_latency,
                R.string.row_game_eq_title, R.string.row_game_eq_sub)
            if (featureSwitches.containsKey(Feature.GAME_EQ) && !GameEqWatcher.hasUsageAccess(this)) {
                card.addView(SettingRowFactory.buildDivider(this))
                card.addView(SettingRowFactory.build(this, R.drawable.ic_info, R.string.game_eq_access_title,
                    R.string.game_eq_access_sub, SettingRowFactory.buildChevron(this)) {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                })
            }
        }
        syncSwitch(Feature.SMART_VOLUME, "autoVolume", R.drawable.ic_volume,
            R.string.row_smart_volume_title, R.string.row_smart_volume_sub)
        // Write-only: the buds do not report it, so the last tap is remembered (realme Buds T01, report 25).
        if (ModelCatalog.current(this)?.json?.optInt("volumeEnhancer") == 1) {
            val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
            val sw = SettingRowFactory.buildSwitch(this, prefs.getBoolean("volumeEnhancer", true))
            sw.setOnCheckedChangeListener { _, on ->
                if (syncing) return@setOnCheckedChangeListener
                prefs.edit().putBoolean("volumeEnhancer", on).apply()
                manager?.setVolumeEnhancer(on)
            }
            if (card.childCount > 0) card.addView(SettingRowFactory.buildDivider(this))
            card.addView(SettingRowFactory.build(this, R.drawable.ic_volume, R.string.row_volume_enhancer_title,
                R.string.row_volume_enhancer_sub, sw) { sw.performClick() })
        }
        syncSwitch(Feature.ADAPTIVE_VOLUME, null, R.drawable.ic_volume,
            R.string.row_adaptive_volume_title, R.string.row_adaptive_volume_sub)
        syncSwitch(Feature.ADAPTIVE_EAR, null, R.drawable.ic_earbud,
            R.string.row_adaptive_ear_title, R.string.row_adaptive_ear_sub)
        syncSwitch(Feature.SLEEP_PAUSE, null, R.drawable.ic_earbud,
            R.string.row_sleep_title, R.string.row_sleep_sub)
        syncSwitch(Feature.LOST_REMIND, null, R.drawable.ic_find_buds,
            R.string.row_lost_title, R.string.row_lost_sub)
        syncSwitch(Feature.SPEECH_PERCEPTION, null, R.drawable.ic_transparency,
            R.string.row_speech_title, R.string.row_speech_sub)
        syncSwitch(Feature.HEARING_OPTIMIZE, null, R.drawable.ic_hearing,
            R.string.row_hearing_optimize_title, R.string.row_hearing_optimize_sub, R.string.hearing_optimize_confirm)
        syncSwitch(Feature.LONG_PRESS_VOLUME, "holdVolume", R.drawable.ic_volume,
            R.string.row_long_press_volume_title, R.string.row_long_press_volume_sub)
        syncSwitch(Feature.HEAD_MOTION, null, R.drawable.ic_gesture,
            R.string.row_head_motion_title, R.string.row_head_motion_sub)
        // Which gesture answers: `0x0431 <type>`, HeyMelody's nod / shake choice (PROTOCOL.md §18).
        if (featureSwitches.containsKey(Feature.HEAD_MOTION) &&
            Capabilities.supports(this, OpoProtocol.CMD_SET_HEAD_MOTION_TYPE)) {
            card.addView(SettingRowFactory.buildDivider(this))
            val row = SettingRowFactory.build(this, R.drawable.ic_gesture, R.string.head_motion_type_title,
                0, SettingRowFactory.buildChevron(this)) { headMotionSheet() }
            headMotionText = SettingRowFactory.subtitle(this, row)
            paintHeadMotion()
            card.addView(row)
        }
        syncSwitch(Feature.SWIFT_PAIR, "fastPair", R.drawable.ic_devices,
            R.string.row_swift_pair_title, R.string.row_swift_pair_sub)
        switch(Feature.POWER_SAVING, null, R.drawable.ic_power,
            R.string.row_power_saving_title, R.string.row_power_saving_sub, R.string.power_saving_confirm)
        if (card.childCount == 0) return
        root.addView(SettingRowFactory.sectionLabel(this, R.string.earbuds_section_features))
        root.addView(card)
    }

    private fun quiet(block: () -> Unit) { syncing = true; block(); syncing = false }

    /** One tap on a feature switch: the buds' own report moves the switch. */
    private fun requestFeature(id: Int, on: Boolean) = manager?.requestSetting(
        id, listOf(id to (if (on) 1 else 0)), FeatureSync.Confirm.READ,
        // Power saving restarts the link; without this the switch reports a false "no answer".
        restarts = id == Feature.POWER_SAVING
    )

    /** The engine applies a tap: the row says "Applying…" and its switch stays still. */
    override fun onSettingBusy(key: Int, on: Boolean) {
        wearSheet?.onSettingBusy(key, on)
        val sw = featureSwitches[key] ?: return
        val sub = featureRows[key]?.findViewWithTag<TextView>(SettingRowFactory.SUBTITLE_TAG)
        if (on) {
            sw.isEnabled = false
            sub?.setText(R.string.feature_applying)
            return
        }
        sw.isEnabled = true
        featureSubs[key]?.let { sub?.setText(it) }
    }

    override fun onFeatureStates(states: Map<Int, Int>) {
        wearSheet?.onFeatureStates(states)
        quiet { for ((id, sw) in featureSwitches) states[id]?.let { sw.setFromBuds(it == 1) } }
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
        // (level 1 is silent on the buds).
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

    /**
     * Tap sensitivity 1..5 (`0x042D`, read `0x0133`), where HeyMelody's model list has `tapLevelSetting`;
     * the warning shows below the buds' default, as in HeyMelody (PROTOCOL.md §18).
     */
    private fun tapSensitivity(root: LinearLayout) {
        if (ModelCatalog.current(this)?.json?.optInt("tapLevel") != 1) return
        if (!Capabilities.supports(this, OpoProtocol.CMD_SET_TAP_LEVEL) && ModelCatalog.manual(this) == null) return
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val secondary = ThemeRes.color(this, R.attr.appColorTextSecondary)
        tapSlider = LevelSliderView(this, 1, 5).apply { onRelease = { manager?.setTapLevel(it) } }
        tapWarning = TextView(this).apply {
            setText(R.string.tap_level_warning)
            setTextColor(secondary)
            textSize = 13f
            setPadding(0, 0, dp(8f), dp(10f))
            visibility = View.GONE
        }
        root.addView(SettingRowFactory.sectionLabel(this, R.string.tap_level_title))
        root.addView(cardView().apply {
            setPadding(dp(18f), dp(14f), dp(10f), dp(6f))
            addView(TextView(this@EarbudSettingsActivity).apply {
                setText(R.string.tap_level_hint)
                setTextColor(secondary)
                textSize = 13f
            })
            addView(tapSlider)
            addView(tapWarning)
        })
    }

    override fun onTapLevel(level: Int, default: Int) {
        if (tapSlider?.dragging == true) return
        tapSlider?.value = level
        tapWarning?.visibility = if (level < default) View.VISIBLE else View.GONE
    }

    private fun paintSpeaker(level: Int) {
        alertSpeaker.setImageDrawable(ThemeRes.tint(
            this, if (level <= 1) R.drawable.ic_volume_off else R.drawable.ic_volume,
            ThemeRes.color(this, R.attr.appColorAccent)
        ))
    }

    override fun onStart() {
        super.onStart()
        // Back from the Usage access setting: let the service look again.
        startService(Intent(this, BudsService::class.java).setAction(BudsService.ACTION_GAME_EQ_UPDATE))
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
    override fun onPersonalNoiseStored(stored: Boolean) = personalNoise.stored(stored)
    override fun onPersonalNoiseResult(result: Int) = personalNoise.result(result)
    override fun onPersonalNoiseAck(status: Int) = personalNoise.ack(status)

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
}
