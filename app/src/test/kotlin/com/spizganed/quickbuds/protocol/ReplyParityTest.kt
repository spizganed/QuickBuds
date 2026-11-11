package com.spizganed.quickbuds.protocol

import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.protocol.Wire.Message
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One reply packet from the reply layer and from the decoder for the same request bytes.
 * A difference is a defect. The cases come from `testdata/protocol_cases.json` and from the
 * request cases in the protocol tests.
 */
class ReplyParityTest {
    private val contract = File("../testdata/protocol_cases.json")

    private data class Packet(val cmd: Int, val seq: Int, val payload: ByteArray)

    private fun bytes(text: String): ByteArray =
        text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /** cmd, seq and payload of a whole packet with a fixed 9-byte header. */
    private fun packet(frame: ByteArray): Packet {
        val lenBytes = if (frame[1].toInt() and 0x80 != 0) 2 else 1
        val at = lenBytes + 3
        return Packet(
            (frame[at].toInt() and 0xFF) or ((frame[at + 1].toInt() and 0xFF) shl 8),
            frame[at + 2].toInt() and 0xFF,
            frame.copyOfRange(at + 5, frame.size),
        )
    }

    /** Both paths must return the same bytes, or no answer at all. */
    private fun assertParity(what: String, cmd: Int, seq: Int, payload: ByteArray, clock: Long) {
        val old = Replies.answerFor(cmd, seq, payload, clock)
        val new = Decode.answer(Message(cmd, seq, payload), clock)?.let {
            OpoProtocol.buildPacket(it.cmd, it.seq, it.payload)
        }
        if (old == null) assertNull("$what 0x%04X: the decoder answered".format(cmd), new)
        else assertArrayEquals("$what 0x%04X: the reply packet differs".format(cmd), old, new)
    }

    @Test fun replyPacketsMatchTheContract() {
        assertTrue("missing contract: ${contract.absolutePath}", contract.isFile)
        val cases = JSONObject(contract.readText()).getJSONArray("cases")
        var checked = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            if (c.getString("kind") != "reply") continue
            val p = packet(bytes(c.getString("input")))
            assertParity(c.getString("id"), p.cmd, p.seq, p.payload, c.optLong("clock", 0L))
            checked++
        }
        assertTrue("no reply cases in $contract", checked > 0)
    }

    @Test fun replyPacketsMatchTheRequestKindsInTheTests() {
        val clock = 0x67001234L
        val cases = listOf(
            Triple(0x0500, byteArrayOf(), "time empty"),
            Triple(0x0500, byteArrayOf(0, 0, 0, 0), "time with data"),
            Triple(0x050C, byteArrayOf(), "app list"),
            Triple(0x051C, byteArrayOf(), "capabilities"),
            Triple(0x051D, byteArrayOf(), "spatial mode"),
            Triple(0x0204, byteArrayOf(0xF2.toByte(), 1), "F2"),
            Triple(0x0204, byteArrayOf(0xF4.toByte()), "F4 empty"),
            Triple(0x0204, byteArrayOf(0xF4.toByte()) + "{\"cmd\":\"a2dp_caton\",\"ver\":\"1\"}".toByteArray(), "F4 capture"),
            Triple(0x0204, byteArrayOf(0xF4.toByte()) + "{\"x\":[1,{\"a\":null}],\"cmd\":3}".toByteArray(), "F4 non-string cmd"),
            Triple(0x0204, byteArrayOf(0xF4.toByte()) + "not json".toByteArray(), "F4 bad JSON"),
            Triple(0x0204, byteArrayOf(0xF4.toByte()) + "{'cmd':'a2dp_caton'}".toByteArray(), "F4 vendor-lenient JSON"),
            // One request in each known group, then commands outside the groups.
            Triple(0x0101, byteArrayOf(), "known group 01"),
            Triple(0x0205, byteArrayOf(), "known group 02"),
            Triple(0x0300, byteArrayOf(), "known group 03"),
            Triple(0x0400, byteArrayOf(), "known group 04"),
            Triple(0x0501, byteArrayOf(), "known group 05"),
            Triple(0x0F00, byteArrayOf(), "known group 0F"),
            Triple(0x0600, byteArrayOf(), "outside the groups"),
            Triple(0x0900, byteArrayOf(9), "outside the groups with data"),
            Triple(0x0000, byteArrayOf(), "outside the groups low"),
            Triple(0x8105, byteArrayOf(0), "an already-set reply bit"),
        )
        // The JSON error path logs through android.util.Log, which a JVM test does not mock.
        // The log line is not a protocol byte, so the test runs with packet logging off.
        val wasEnabled = PacketLogger.ENABLED
        PacketLogger.ENABLED = false
        try {
            for ((cmd, payload, what) in cases) assertParity(what, cmd, 0x14, payload, clock)
        } finally {
            PacketLogger.ENABLED = wasEnabled
        }
    }
}
