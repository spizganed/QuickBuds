package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import com.spizganed.quickbuds.protocol.OpoProtocol
import java.util.UUID

/**
 * Dev tools probe: can the app open the Google Fast Pair Message Stream while the vendor link is up?
 * It is a test for a fallback when another app holds `079A`. It writes nothing and only logs (`FP PROBE:`).
 */
object FastPairProbe {
    private val FP_UUID: UUID = UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val READ_MS = 6_000L

    @SuppressLint("MissingPermission")
    fun run() = Thread {
        val log = { m: String -> PacketLogger.log("FP PROBE: $m") }
        try {
            val vendor = ParcelUuid.fromString(OpoProtocol.SPP_UUID_FALLBACK)
            val buds = BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty()
                .filter { it.uuids?.contains(vendor) == true && connected(it) }
            if (buds.isEmpty()) { log("no connected buds with 079A"); return@Thread }
            buds.forEach { probe(it, log) }
        } catch (e: Exception) {
            log("error ${e.javaClass.simpleName}: ${e.message}")
        }
    }.start()

    @SuppressLint("MissingPermission")
    private fun probe(device: BluetoothDevice, log: (String) -> Unit) {
        log("${device.name}: SDP lists the stream = ${device.uuids?.contains(ParcelUuid(FP_UUID)) == true}")
        for (insecure in listOf(true, false)) {
            val kind = if (insecure) "insecure" else "secure"
            var socket: BluetoothSocket? = null
            val start = System.currentTimeMillis()
            try {
                socket = if (insecure) device.createInsecureRfcommSocketToServiceRecord(FP_UUID)
                    else device.createRfcommSocketToServiceRecord(FP_UUID)
                val s = socket
                val watchdog = Thread { try { Thread.sleep(CONNECT_TIMEOUT_MS); if (!s.isConnected) s.close() } catch (_: Exception) {} }
                watchdog.start()
                s.connect()
                watchdog.interrupt()
                log("open $kind ok (${System.currentTimeMillis() - start} ms)")
                read(s, log)
                return
            } catch (e: Exception) {
                log("open $kind failed (${System.currentTimeMillis() - start} ms): ${e.message ?: e.javaClass.simpleName}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    /** Logs what the buds send by themselves for a few seconds. The spec says: model id, BLE address, battery. */
    private fun read(s: BluetoothSocket, log: (String) -> Unit) {
        val input = s.inputStream
        val buf = ByteArray(256)
        val end = System.currentTimeMillis() + READ_MS
        var total = 0
        while (System.currentTimeMillis() < end) {
            if (input.available() == 0) { Thread.sleep(50); continue }
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            log("rx ${buf.take(n).joinToString(" ") { "%02X".format(it) }}")
        }
        log("done, $total bytes in ${READ_MS / 1000} s")
    }

    private fun connected(d: BluetoothDevice): Boolean = try {
        d.javaClass.getMethod("isConnected").invoke(d) as Boolean
    } catch (_: Exception) { false }
}
