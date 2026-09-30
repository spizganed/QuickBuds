package com.spizganed.quickbuds.bluetooth

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Dev tools › Bridge: passes the raw RFCOMM bytes to one TCP client on 127.0.0.1:[PORT], both ways, so
 * the desktop app can run on this phone (Linux in Termux, `QB_BRIDGE=127.0.0.1:7979`) and talk to the
 * buds through our link. Loopback only; off until switched on, and never persisted.
 * The app keeps its own traffic: both sides see every reply.
 */
class RfcommBridge(private val write: (ByteArray) -> Unit) {

    companion object {
        const val PORT = 7979
        @Volatile var running = false
            private set
    }

    // Not getLoopbackAddress(): on Android that is ::1, and QB_BRIDGE says 127.0.0.1.
    private val server = ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))
    @Volatile private var client: Socket? = null

    init {
        running = true
        Thread {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (_: IOException) { break }
                dropClient()
                c.tcpNoDelay = true
                client = c
                Thread { pump(c) }.start()
            }
        }.start()
    }

    private fun pump(c: Socket) {
        val buf = ByteArray(1024)
        try {
            val input = c.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) write(buf.copyOf(n))
            }
        } catch (_: IOException) {}
        if (client === c) dropClient()
    }

    /** RFCOMM bytes as read, before framing. */
    fun forward(buf: ByteArray, n: Int) {
        val c = client ?: return
        try { c.getOutputStream().write(buf, 0, n) } catch (_: IOException) { dropClient() }
    }

    /** Ends the client's session (link lost), so it sees the drop and reconnects. */
    fun dropClient() {
        try { client?.close() } catch (_: IOException) {}
        client = null
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: IOException) {}
        dropClient()
    }
}
