package com.spizganed.quickbuds.ui

import android.app.Activity
import android.bluetooth.BluetoothManager
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
 * Dual connection, as HeyMelody has it (`[CAPTURE]` 2026-09-25, PROTOCOL.md §9): one switch
 * (`0x0403` feature `0x11`) and the devices the buds are connected to (`0x0112`, pushed as
 * `0x0204` subType `06`). "Add device" is only pairing instructions, as in HeyMelody.
 */
class DualDeviceActivity : Activity(), BudsConnectionManager.Listener {

    private var manager: BudsConnectionManager? = null
    private var bound = false
    private var syncing = false
    private lateinit var dualSwitch: Switch
    private lateinit var deviceCard: LinearLayout

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            manager = (service as BudsService.LocalBinder).getService().manager
            manager?.addListener(this@DualDeviceActivity)
            manager?.featureStates?.let { onFeatureStates(it) }
            manager?.devices?.let { onDevices(it) }
            manager?.refreshDevices()
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
            setBackgroundColor(ThemeRes.color(this@DualDeviceActivity, R.attr.appColorBg))
            setPadding(dp(16f), dp(44f), dp(16f), dp(24f))
        }
        root.addView(TextView(this).apply {
            setText(R.string.dual_title)
            setTextColor(ThemeRes.color(this@DualDeviceActivity, R.attr.appColorTextPrimary))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(16f))
        })

        dualSwitch = SettingRowFactory.buildSwitch(this, false)
        dualSwitch.setOnCheckedChangeListener { _, on ->
            SettingRowFactory.refreshSwitch(this, dualSwitch, on)
            if (!syncing) manager?.setDualDevice(on)
        }
        root.addView(cardView().apply {
            addView(
                SettingRowFactory.build(
                    this@DualDeviceActivity, 0, R.string.dual_title, R.string.dual_switch_sub, dualSwitch
                ) { dualSwitch.performClick() }
            )
        })

        root.addView(TextView(this).apply {
            setText(R.string.dual_section_devices)
            setTextColor(ThemeRes.color(this@DualDeviceActivity, R.attr.appColorTextSecondary))
            textSize = 13f
            setPadding(dp(4f), dp(22f), 0, dp(8f))
        })
        deviceCard = cardView()
        root.addView(deviceCard)
        onDevices(emptyList())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun cardView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ThemeRes.card(context)
        val p = ThemeRes.dp(this@DualDeviceActivity, 4f)
        setPadding(p, p, p, p)
    }

    /**
     * The buds do not say which entry is this phone (two list bytes are still undecoded), so it
     * is matched by the phone's own Bluetooth name. Null if Android will not tell us.
     */
    private fun ownName(): String? = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.name
    } catch (e: SecurityException) {
        null
    }

    override fun onDevices(list: List<BudsConnectionManager.PairedDevice>) {
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val own = ownName()
        deviceCard.removeAllViews()
        // HeyMelody lists only connected devices; the buds also report ones that dropped off.
        list.filter { it.connected }.forEach { d ->
            if (deviceCard.childCount > 0) deviceCard.addView(SettingRowFactory.buildDivider(this))
            deviceCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(56f)
                setPadding(dp(18f), dp(10f), dp(14f), dp(10f))
                addView(TextView(this@DualDeviceActivity).apply {
                    text = d.name
                    setTextColor(ThemeRes.color(this@DualDeviceActivity, R.attr.appColorTextPrimary))
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@DualDeviceActivity).apply {
                    setText(if (d.name == own) R.string.dual_connected_this else R.string.dual_connected)
                    setTextColor(ThemeRes.color(this@DualDeviceActivity, R.attr.appColorTextSecondary))
                    textSize = 12f
                    setPadding(0, dp(2f), 0, 0)
                })
            })
        }
        if (deviceCard.childCount > 0) deviceCard.addView(SettingRowFactory.buildDivider(this))
        deviceCard.addView(
            SettingRowFactory.build(
                this, 0, R.string.dual_add_title, 0, SettingRowFactory.buildChevron(this)
            ) { showAddDevice() }
        )
    }

    private fun showAddDevice() {
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(R.string.dual_add_title))
            .message(getString(R.string.dual_add_message))
            .confirm(getString(R.string.dual_add_ok)) { sheet.close() }
            .show()
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
        val v = states[OpoProtocol.FEATURE_DUAL_DEVICE] ?: return
        syncing = true
        dualSwitch.isChecked = v == 1
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
