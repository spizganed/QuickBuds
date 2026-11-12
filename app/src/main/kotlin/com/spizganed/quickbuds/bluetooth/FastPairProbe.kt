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
 * It is a test for a fallback when another app holds `079A`. After a passive read it sends three unsigned messages
 * and logs each reply (`FP PROBE:`): get ANC state `08 11`, ring both buds for 3 s `04 01 03 03`, stop `04 01 00`.
 * The ring is a real sound: test with the buds out of the ears. Message list: local facts, Fast Pair spec.
 */
object FastPairProbe {
    private val FP_UUID: UUID = UUID.fromString(com.spizganed.quickbuds.protocol.FastPairStream.UUID)
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val READ_MS = 3_000L
    private const val REPLY_MS = 2_000L

    /** The unsigned messages the probe sends, in order, with the time to wait for replies after each. */
    private val SENDS = listOf(
        Triple("get ANC state", byteArrayOf(0x08, 0x11, 0x00, 0x00), REPLY_MS),
        Triple("ring both 3 s", byteArrayOf(0x04, 0x01, 0x00, 0x02, 0x03, 0x03), 4_000L),
        Triple("ring stop", byteArrayOf(0x04, 0x01, 0x00, 0x01, 0x00), REPLY_MS),
    )

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
        log("SDP lists the stream = ${device.uuids?.contains(ParcelUuid(FP_UUID)) == true}")
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

    /** Logs what the buds send by themselves (model id, BLE address, battery), then sends [SENDS] one by one. */
    private fun read(s: BluetoothSocket, log: (String) -> Unit) {
        var total = listen(s, READ_MS, log)
        for ((name, msg, wait) in SENDS) {
            log("tx $name: ${hex(msg, msg.size)}")
            s.outputStream.write(msg)
            s.outputStream.flush()
            total += listen(s, wait, log)
        }
        log("done, $total bytes")
    }

    private fun listen(s: BluetoothSocket, ms: Long, log: (String) -> Unit): Int {
        val input = s.inputStream
        val buf = ByteArray(256)
        val end = System.currentTimeMillis() + ms
        var total = 0
        while (System.currentTimeMillis() < end) {
            if (input.available() == 0) { Thread.sleep(50); continue }
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            log("rx ${hex(buf, n)}${label(buf, n)}")
        }
        return total
    }

    private fun hex(b: ByteArray, n: Int) = b.take(n).joinToString(" ") { "%02X".format(it) }

    /** A short name for the first message in a read, from the Fast Pair spec. */
    private fun label(b: ByteArray, n: Int): String {
        if (n < 4) return ""
        val g = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        val d = { i: Int -> if (4 + i < n) b[4 + i].toInt() and 0xFF else -1 }
        return when {
            g == 0xFF && c == 0x01 -> "  (ACK for %02X %02X)".format(d(0), d(1))
            g == 0xFF && c == 0x02 -> "  (NAK reason %02X for %02X %02X: %s)".format(d(0), d(1), d(2), when (d(0)) {
                0 -> "not supported"; 1 -> "busy"; 2 -> "not allowed now"; 3 -> "wrong MAC"; 4 -> "redundant"; else -> "?" })
            g == 0x08 && c == 0x13 -> "  (ANC state: version %02X, shown %02X, settable %02X, current %02X)".format(d(0), d(1), d(2), d(3))
            g == 0x03 && c == 0x03 -> "  (battery)"
            g == 0x03 && c == 0x01 -> "  (model id)"
            g == 0x03 && c == 0x09 -> "  (firmware)"
            g == 0x04 && c == 0x01 -> "  (ring state %02X)".format(d(0))
            else -> ""
        }
    }

    private fun connected(d: BluetoothDevice): Boolean = try {
        d.javaClass.getMethod("isConnected").invoke(d) as Boolean
    } catch (_: Exception) { false }
}
