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
    private lateinit var radar: HearingRadarView
    private lateinit var radarCard: LinearLayout
    /** The ear the radar shows: 0 left, 1 right ([USER] 2026-09-29: one ear at a time reads better). */
    private var ear = 0
    /** Filters per record uid, read from the buds for the graph: hearing (left, right), ear scan (fs, left, right). */
    private val hearingCurves = HashMap<Int, Pair<FloatArray, FloatArray>>()
    private val scanCurves = HashMap<Int, Triple<Int, FloatArray, FloatArray>>()

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

        // The active profile as HeyMelody's radar, drawn from the filters the buds compute for it.
        radar = HearingRadarView(this)
        radarCard = SettingRowFactory.card(this).apply {
            visibility = android.view.View.GONE
            val pad = ThemeRes.dp(this@GoldenSoundActivity, 12f)
            setPadding(pad, pad, pad, pad)
            addView(AncSegmentedView(this@GoldenSoundActivity,
                listOf(getString(R.string.golden_left), getString(R.string.golden_right))).apply {
                selected = ear
                onSegmentTapped = { i -> selected = i; ear = i; paintRadar() }
            })
            addView(radar)
        }
        root.addView(radarCard.also {
            (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = ThemeRes.dp(this, 12f)
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

    /** Asks the buds for the active record's filters (queries only, as HeyMelody's result screen). */
    private fun requestCurves() {
        val r = GoldenSound.records(this).firstOrNull { it.uid == activeUid } ?: return
        if (hearingCurves.containsKey(r.uid)) { paintRadar(); return }
        manager?.golden(*listOfNotNull(
            OpoProtocol.hearingFilter(r.uid, r.values),
            if (r.scan.isNotEmpty()) OpoProtocol.earScanFilter(r.uid, r.scan) else null
        ).toTypedArray())
    }

    private fun paintRadar() {
        val h = hearingCurves[activeUid]
        radarCard.visibility = if (h == null) android.view.View.GONE else android.view.View.VISIBLE
        if (h == null) return
        val s = scanCurves[activeUid]
        radar.values = if (ear == 0) GoldenSound.radar(h.first, s?.second, s?.first ?: 44100)
        else GoldenSound.radar(h.second, s?.third, s?.first ?: 44100)
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
        paintRadar()
        requestCurves()
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
        paintRadar()
        // After the ear-scan read (`0x811E`, right behind this one) has filled the record in.
        radar.postDelayed({ requestCurves() }, 600)
    }

    override fun onGoldenActiveScan(uid: Int, data: ByteArray) {
        val r = GoldenSound.records(this).firstOrNull { it.uid == uid && it.scan.isEmpty() } ?: return
        GoldenSound.put(this, GoldenSound.Record(r.uid, r.name, r.values, data, r.descId))
    }

    override fun onGoldenCurves(uid: Int, scan: Boolean, fs: Int, left: FloatArray, right: FloatArray) {
        if (scan) scanCurves[uid] = Triple(fs, left, right) else hearingCurves[uid] = left to right
        if (uid == activeUid) paintRadar()
    }

    override fun onGoldenStatus(kind: Int, status: Int) { testSheet?.status(kind, status) }
    override fun onEarScan(uid: Int, data: ByteArray) { testSheet?.earScan(uid, data) }
    override fun onGoldenFilter(uid: Int, enhanceType: Int) { testSheet?.filter(uid, enhanceType) }

    override fun onConnected(connected: Boolean) {}
    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {}
}
