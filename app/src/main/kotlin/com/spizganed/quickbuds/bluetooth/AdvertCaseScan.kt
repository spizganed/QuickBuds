package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.ParcelUuid
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.FastPairAdvert
import com.spizganed.quickbuds.protocol.FastPairStream

/**
 * One short BLE scan for the case level in the buds' advertisement ([FastPairAdvert]). It runs on [handler].
 * The OPPO packet must carry [productId]. The Fast Pair packet names no model, so it counts only when one
 * sender alone sent it in the whole scan. [onCase] gets the level once, or never.
 */
class AdvertCaseScan(
    private val adapter: BluetoothAdapter?,
    private val handler: Handler,
    private val productId: String?,
    private val log: (String) -> Unit,
    private val onCase: (FastPairStream.Level) -> Unit,
) {
    private var done = false
    private val google = mutableMapOf<String, FastPairAdvert.Battery>()
    /** Senders of an OPPO packet already logged: a sender repeats its packet many times a second. */
    private val logged = mutableSetOf<String>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { handler.post { take(result) } }
        override fun onScanFailed(errorCode: Int) { handler.post { log("BLE ADV: scan failed $errorCode"); stop() } }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val filters = FastPairAdvert.COMPANY_IDS.map { ScanFilter.Builder().setManufacturerData(it, ByteArray(0)).build() } +
            ScanFilter.Builder().setServiceData(ParcelUuid.fromString(FastPairAdvert.SERVICE_UUID), ByteArray(0)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(filters, settings, callback)
            log("BLE ADV: scan for the case level")
            handler.postDelayed({ finish() }, SCAN_MS)
            true
        } catch (e: Exception) {
            log("BLE ADV: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun take(r: ScanResult) {
        if (done) return
        val rec = r.scanRecord ?: return
        for (id in FastPairAdvert.COMPANY_IDS) {
            val data = rec.getManufacturerSpecificData(id) ?: continue
            val first = logged.add(r.device.address)
            if (first) log("BLE ADV: %04X %s rssi=%d".format(id, hex(data), r.rssi))
            val b = FastPairAdvert.oppo(data) ?: continue
            val pid = Capabilities.productId(b.productId ?: continue)
            if (first) log("BLE ADV: product $pid L=${b.left} R=${b.right} C=${b.case}")
            if (pid == productId && b.case != null) { deliver(FastPairStream.Level(b.case, false)); return }
        }
        rec.getServiceData(ParcelUuid.fromString(FastPairAdvert.SERVICE_UUID))?.let { data ->
            val b = FastPairAdvert.google(data) ?: return
            if (google.put(r.device.address, b) == null) log("BLE ADV: Fast Pair ${hex(data)} rssi=${r.rssi} L=${b.left} R=${b.right} C=${b.case}")
        }
    }

    /** At the end of the scan: the Fast Pair level, when one sender alone sent a case level. */
    private fun finish() {
        if (done) return
        val senders = google.values.filter { it.case != null }
        if (senders.size == 1) { deliver(FastPairStream.Level(senders[0].case, senders[0].caseCharging)); return }
        if (senders.size > 1) log("BLE ADV: ${senders.size} Fast Pair senders, none used")
        stop()
    }

    private fun deliver(level: FastPairStream.Level) {
        log("BLE ADV: case ${level.level}")
        stop()
        onCase(level)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (done) return
        done = true
        try { adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    private companion object { const val SCAN_MS = 10_000L }
}
