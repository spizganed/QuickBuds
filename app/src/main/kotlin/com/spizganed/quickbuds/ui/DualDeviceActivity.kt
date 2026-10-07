package com.spizganed.quickbuds.ui

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol

/**
 * Dual connection, as HeyMelody has it (`[CAPTURE]` 2026-09-25, PROTOCOL.md §9): one switch
 * (`0x0403` feature `0x11`) and the devices the buds are connected to (`0x0112`, pushed as
 * `0x0204` subType `06`). "Add device" is only pairing instructions, as in HeyMelody.
 * On models whose HeyMelody entry has the device manager (`multiConnect`), every paired device is
 * listed, a tap connects or disconnects it and a preferred device can be set (`0x0429`, PROTOCOL.md §9).
 */
class DualDeviceActivity : Activity(), BudsConnectionManager.Listener {

    private var manager: BudsConnectionManager? = null
    private var bound = false
    private var syncing = false
    private lateinit var dualSwitch: Switch
    private lateinit var deviceCard: LinearLayout
    private var preferredText: TextView? = null
    private var preferred: String? = null

    /** A device-manager function of this model (`[VENDOR]` `multiConnectFunctions`), on buds that take `0x0429`. */
    private fun has(fn: String): Boolean {
        val list = ModelCatalog.current(this)?.json?.optJSONArray("multiConnect") ?: return false
        if ((0 until list.length()).none { list.getString(it) == fn }) return false
        return Capabilities.supports(this, OpoProtocol.CMD_MULTI_CONNECT) || ModelCatalog.manual(this) != null
    }
    private val manages by lazy { has("connectDisconnectDevice") }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            manager = (service as BudsService.LocalBinder).getService().manager
            manager?.addListener(this@DualDeviceActivity)
            manager?.featureStates?.let { onFeatureStates(it) }
            manager?.devices?.let { onDevices(it) }
            manager?.refreshDevices()
            manager?.requestFullStatus()
            if (preferredText != null && Capabilities.supports(this@DualDeviceActivity, OpoProtocol.CMD_QUERY_PREFERRED))
                manager?.refreshPreferred()
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
        root.addView(SettingRowFactory.title(this, R.string.dual_title))

        dualSwitch = SettingRowFactory.buildSwitch(this, false)
        dualSwitch.setOnCheckedChangeListener { _, on ->
            if (!syncing) manager?.setDualDevice(on)
        }
        root.addView(cardView().apply {
            addView(
                SettingRowFactory.build(
                    this@DualDeviceActivity, 0, R.string.dual_title, R.string.dual_switch_sub, dualSwitch
                ) { dualSwitch.performClick() }
            )
        })

        if (has("setPriorityDevice")) {
            val row = SettingRowFactory.build(this, 0, R.string.dual_preferred, 0, SettingRowFactory.buildChevron(this)) {
                preferredSheet()
            }
            preferredText = SettingRowFactory.subtitle(this, row)
            paintPreferred()
            root.addView(cardView().apply { addView(row) }.also {
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12f)
            })
        }

        root.addView(SettingRowFactory.sectionLabel(this, if (manages) R.string.dual_section_all else R.string.dual_section_devices))
        deviceCard = cardView()
        root.addView(deviceCard)
        onDevices(emptyList())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun cardView() = SettingRowFactory.card(this)

    /**
     * This phone's Bluetooth name: the fallback when the list's flag (bit 0) does not mark this phone.
     * Null if Android will not tell us.
     */
    private fun ownName(): String? = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.name
    } catch (e: SecurityException) {
        com.spizganed.quickbuds.bluetooth.PacketLogger.error("phone Bluetooth name", e)
        null
    }

    override fun onDevices(list: List<BudsConnectionManager.PairedDevice>) {
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val own = ownName()
        deviceCard.removeAllViews()
        // HeyMelody's plain screen lists only connected devices; its device manager lists every one.
        list.filter { it.connected || manages }.forEach { d ->
            val self = d.thisPhone || d.name == own
            if (deviceCard.childCount > 0) deviceCard.addView(SettingRowFactory.buildDivider(this))
            deviceCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(56f)
                setPadding(dp(18f), dp(10f), dp(14f), dp(10f))
                addView(TextView(this@DualDeviceActivity).apply {
                    text = d.name
                    setTextColor(ThemeRes.color(this@DualDeviceActivity, R.attr.appColorTextPrimary))
                    textSize = 15f
                    typeface = ThemeRes.bold(context)
                })
                addView(TextView(this@DualDeviceActivity).apply {
                    setText(when {
                        self -> R.string.dual_connected_this
                        d.connected -> R.string.dual_connected
                        else -> R.string.dual_not_connected
                    })
                    setTextColor(ThemeRes.color(this@DualDeviceActivity, R.attr.appColorTextSecondary))
                    textSize = 12f
                    setPadding(0, dp(2f), 0, 0)
                })
                if (manages && !self) {
                    background = ThemeRes.ripple(context)
                    setOnClickListener { confirmConnect(d) }
                }
            })
        }
        paintPreferred()
        if (deviceCard.childCount > 0) deviceCard.addView(SettingRowFactory.buildDivider(this))
        deviceCard.addView(
            SettingRowFactory.build(
                this, 0, R.string.dual_add_title, 0, SettingRowFactory.buildChevron(this)
            ) { showAddDevice() }
        )
    }

    /** Connect or disconnect another device, asked first as HeyMelody does. */
    private fun confirmConnect(d: BudsConnectionManager.PairedDevice) {
        val on = !d.connected
        ConfirmDialog.show(this, getString(if (on) R.string.dual_connect_q else R.string.dual_disconnect_q, d.name), null,
            getString(if (on) R.string.conn_action_connect else R.string.conn_action_disconnect)) {
            manager?.connectDevice(d.mac, on)
        }
    }

    private fun paintPreferred() {
        preferredText?.text = preferred?.let { mac -> manager?.devices?.firstOrNull { it.mac == mac }?.name ?: mac }
            ?: getString(R.string.dual_preferred_auto)
    }

    private fun preferredSheet() {
        val sheet = BottomSheetDialog(this)
        val devices = manager?.devices.orEmpty()
        sheet.title(getString(R.string.dual_preferred))
            .message(getString(R.string.dual_preferred_sub))
            .items(listOf(BottomSheetDialog.Item(getString(R.string.dual_preferred_auto), preferred == null) {
                manager?.setPreferred(null)
            }) + devices.map { d ->
                BottomSheetDialog.Item(d.name, preferred == d.mac) { manager?.setPreferred(d.mac) }
            })
            .show()
    }

    override fun onPreferred(mac: String?) {
        preferred = mac
        paintPreferred()
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

    override fun onConnected(connected: Boolean) {}
    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {}
}
