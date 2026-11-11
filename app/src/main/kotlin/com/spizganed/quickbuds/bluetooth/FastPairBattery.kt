package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.spizganed.quickbuds.protocol.FastPairStream
import java.util.UUID

/**
 * Battery only, over the Fast Pair Message Stream. It runs when another app holds `079A` (issue #18).
 * The link is secure: on Buds 4 the insecure open fails. It writes nothing; the buds send battery on open and on change.
 */
class FastPairBattery(
    private val log: (String) -> Unit,
    private val onBattery: (FastPairStream.Battery) -> Unit,
    private val onEnd: () -> Unit,
) {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stopped = false

    @SuppressLint("MissingPermission")
    fun start(device: BluetoothDevice) = Thread {
        try {
            val s = device.createRfcommSocketToServiceRecord(UUID.fromString(FastPairStream.UUID))
            socket = s
            s.connect()
            log("FAST PAIR: stream open, battery only")
            val buf = ByteArray(512)
            var have = 0
            val input = s.inputStream
            while (!stopped) {
                val n = input.read(buf, have, buf.size - have)
                if (n <= 0) break
                have += n
                val (msgs, used) = FastPairStream.split(buf, have)
                System.arraycopy(buf, used, buf, 0, have - used)
                have -= used
                if (have == buf.size) have = 0
                msgs.forEach { m ->
                    FastPairStream.battery(m)?.let { b ->
                        log("FAST PAIR: battery L=${b.left.level} R=${b.right.level} C=${b.case.level}")
                        onBattery(b)
                    }
                }
            }
        } catch (e: Exception) {
            if (!stopped) log("FAST PAIR: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            log("FAST PAIR: stream closed")
            onEnd()
        }
    }.start()

    fun stop() {
        stopped = true
        try { socket?.close() } catch (_: Exception) {}
    }
}
