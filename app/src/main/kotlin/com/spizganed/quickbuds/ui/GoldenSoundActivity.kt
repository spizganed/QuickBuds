package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.GoldenSound
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol

/**
 * Golden Sound (PROTOCOL.md §9): the on/off switch, the profiles kept on this phone (a tap applies
 * one; the one on the buds is checked) and the hearing test. The buds' own profile is read on open
 * (`0x0115` / `0x011E`) and added to the list, so one made in HeyMelody shows up too.
 */
class GoldenSoundActivity : Activity(), BudsConnectionManager.Listener {

    private var manager: BudsConnectionManager? = null
    private var bound = false
    private lateinit var toggle: Switch
    private lateinit var list: LinearLayout
    private var syncing = false
    private var activeUid = 0
    private var testSheet: GoldenTestSheet? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            manager = (service as BudsService.LocalBinder).getService().manager
            manager?.addListener(this@GoldenSoundActivity)
            manager?.featureStates?.let { onFeatureStates(it) }
            manager?.golden(OpoProtocol.queryGoldenActive(), OpoProtocol.queryGoldenActiveScan())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            manager = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate — see ThemeRes.select.
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)

        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.row_golden_title))

        toggle = SettingRowFactory.buildSwitch(this, false)
        toggle.setOnCheckedChangeListener { _, on ->
            if (!syncing) manager?.setFeatures(OpoProtocol.FEATURE_GOLDEN_SOUND to on)
        }
        root.addView(SettingRowFactory.card(this).apply {
            addView(SettingRowFactory.build(this@GoldenSoundActivity, R.drawable.ic_hearing,
                R.string.row_golden_title, R.string.row_golden_sub, toggle) { toggle.performClick() })
        })

        root.addView(SettingRowFactory.sectionLabel(this, R.string.golden_profiles))
        list = SettingRowFactory.card(this)
        root.addView(list)
        paintList()

        if (Capabilities.supports(this, OpoProtocol.CMD_GOLDEN_DETECT)) {
            root.addView(SettingRowFactory.card(this).apply {
                (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ThemeRes.dp(this@GoldenSoundActivity, 12f)
                addView(SettingRowFactory.build(this@GoldenSoundActivity, R.drawable.ic_hearing,
                    R.string.golden_test_row, R.string.golden_test_sub, SettingRowFactory.buildChevron(this@GoldenSoundActivity)) {
                    startTest()
                })
            })
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startTest() {
        // Ear scan only where HeyMelody's model list has it (`earScan`, PROTOCOL.md §9).
        val scan = ModelCatalog.current(this)?.json?.optInt("earScan") == 1 &&
            Capabilities.supports(this, OpoProtocol.CMD_GOLDEN_SCAN_DATA)
        testSheet = GoldenTestSheet(this, scan, { manager?.golden(*it) }) { r ->
            apply(r)
        }.also { it.show() }
    }

    /** Applies a record as HeyMelody does: description id, record, ear scan, then the switch on. */
    private fun apply(r: GoldenSound.Record) {
        manager?.golden(*listOfNotNull(
            if (r.descId > 0) OpoProtocol.hearingRestore(r.descId) else null,
            OpoProtocol.hearingRecord(r.uid, r.name, r.values),
            if (r.scan.isNotEmpty()) OpoProtocol.earScanData(r.uid, r.scan) else null,
            OpoProtocol.setFeature(OpoProtocol.FEATURE_GOLDEN_SOUND, true)
        ).toTypedArray())
        activeUid = r.uid
        paintList()
    }

    private fun paintList() {
        list.removeAllViews()
        val records = GoldenSound.records(this)
        if (records.isEmpty()) {
            list.addView(SettingRowFactory.build(this, 0, R.string.golden_none, 0, null))
            return
        }
        val accent = ThemeRes.color(this, R.attr.appColorAccent)
        for (r in records) {
            if (list.childCount > 0) list.addView(SettingRowFactory.buildDivider(this))
            val active = r.uid == activeUid
            val check = if (active) ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ThemeRes.dp(this@GoldenSoundActivity, 20f), ThemeRes.dp(this@GoldenSoundActivity, 20f))
                setImageDrawable(ThemeRes.tint(this@GoldenSoundActivity, R.drawable.ic_check, accent))
            } else null
            val row = SettingRowFactory.build(this, 0, 0, 0,
                SettingRowFactory.iconButton(this, R.drawable.ic_delete, R.string.eq_delete) {
                    ConfirmDialog.show(this, getString(R.string.eq_delete_confirm, r.name), null, getString(R.string.eq_delete)) {
                        GoldenSound.delete(this, r.uid)
                        paintList()
                    }
                }, value = check) { apply(r) }
            row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).apply {
                text = r.name
                if (active) setTextColor(accent)
            }
            list.addView(row)
        }
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
        val v = states[OpoProtocol.FEATURE_GOLDEN_SOUND] ?: return
        syncing = true
        toggle.isChecked = v == 1
        syncing = false
    }

    override fun onGoldenActive(uid: Int, name: String, values: IntArray) {
        activeUid = uid
        if (GoldenSound.records(this).none { it.uid == uid })
            GoldenSound.put(this, GoldenSound.Record(uid, name, values, ByteArray(0), 0))
        paintList()
    }

    override fun onGoldenActiveScan(uid: Int, data: ByteArray) {
        val r = GoldenSound.records(this).firstOrNull { it.uid == uid && it.scan.isEmpty() } ?: return
        GoldenSound.put(this, GoldenSound.Record(r.uid, r.name, r.values, data, r.descId))
    }

    override fun onGoldenStatus(kind: Int, status: Int) { testSheet?.status(kind, status) }
    override fun onEarScan(uid: Int, data: ByteArray) { testSheet?.earScan(uid, data) }
    override fun onGoldenFilter(uid: Int, enhanceType: Int) { testSheet?.filter(uid, enhanceType) }

    override fun onStatus(msg: String) {}
    override fun onConnected(connected: Boolean) {}
    override fun onPacketReceived(bytes: ByteArray) {}
    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {}
    override fun onBudState(state: String) {}
}
