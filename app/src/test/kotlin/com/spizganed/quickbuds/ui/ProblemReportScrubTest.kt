package com.spizganed.quickbuds.ui

import com.spizganed.quickbuds.protocol.OpoProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report scrub: a frame that carries device addresses and names keeps only its header
 * (PROTOCOL.md §19). The header comes from [OpoProtocol.buildPacket], so the test uses the bytes the
 * logger writes: `AA <len LEB128> ... 00 <cmd LE> <seq> <payload len LE>`. The payload starts after the header,
 * at byte 9 for a one-byte length and byte 10 for a two-byte length.
 */
class ProblemReportScrubTest {

    /** One packet-log line as `PacketLogger` holds it: time, two spaces, then the RX line. */
    private fun line(cmd: Int, payload: ByteArray): String =
        "12:00:00.000  RX: " + OpoProtocol.bytesToHex(OpoProtocol.buildPacket(cmd, 0x11, payload))

    /** The real header of the frame: `AA`, the LEB128 length, `00 00`, cmd, seq and payload length. */
    private fun header(cmd: Int, payload: ByteArray): String {
        val frame = OpoProtocol.buildPacket(cmd, 0x11, payload)
        val head = if (frame[1].toInt() and 0x80 == 0) 9 else 10
        return OpoProtocol.bytesToHex(frame.take(head).toByteArray())
    }

    /** Push `0x0204` sub `F2`: count, MAC 6 bytes reversed, connect count u16 LE, name length, name. */
    private val f2 = byteArrayOf(
        0xF2.toByte(), 0x01,
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
        0x02, 0x00,
        0x04,
        0x42, 0x75, 0x64, 0x73
    )

    @Test fun f2ConnectedDevicesLosesItsAddressesAndName() {
        val out = ProblemReport.dropDeviceBytes(line(0x0204, f2))
        assertTrue(out, out.endsWith(" [device data removed]"))
        assertTrue(out, out.contains(header(0x0204, f2)))
        assertFalse(out, out.contains("11 22 33 44 55 66"))
        assertFalse(out, out.contains("42 75 64 73"))
    }

    /**
     * Four devices with a 25-byte name each: the frame is over 127 bytes, so its length field takes 2 bytes.
     * The old scrub read byte 9 as the push sub and left the frame in the report.
     */
    @Test fun longF2ConnectedDevicesLosesItsAddressesAndNames() {
        val payload = longF2()
        val out = ProblemReport.dropDeviceBytes(line(0x0204, payload))
        assertTrue(out, out.endsWith(" [device data removed]"))
        assertTrue(out, out.contains(header(0x0204, payload)))
        assertFalse(out, out.contains("11 22 33 44 55 66"))
        assertFalse(out, out.contains("41 42 43 44 45"))
    }

    /** Push `0x0204` sub `F2` with 4 devices: count, then MAC 6 bytes, connect count u16 LE, name length, name. */
    private fun longF2(): ByteArray {
        val out = mutableListOf<Byte>(0xF2.toByte(), 0x04.toByte())
        for (d in 0 until 4) {
            out += byteArrayOf(0x11, 0x22, 0x33, 0x44, (0x55 + d).toByte(), (0x66 + d).toByte()).toList()
            out += byteArrayOf(0x02, 0x00).toList()
            out += 25.toByte()
            out += ByteArray(25) { (0x41 + d + it).toByte() }.toList()
        }
        return out.toByteArray()
    }

    @Test fun subSixDeviceListStillLosesItsBytes() {
        val payload = byteArrayOf(0x06, 0x01, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val out = ProblemReport.dropDeviceBytes(line(0x0204, payload))
        assertTrue(out, out.endsWith(" [device data removed]"))
        assertFalse(out, out.contains("11 22 33 44"))
    }

    @Test fun anotherPushSubIsLeftAlone() {
        val payload = byteArrayOf(0xF4.toByte(), 0x7B, 0x7D)
        assertEquals(line(0x0204, payload), ProblemReport.dropDeviceBytes(line(0x0204, payload)))
    }

    @Test fun batteryPushIsLeftAlone() {
        val payload = byteArrayOf(0x01, 0x02, 0x01, 0x64, 0x02, 0x64)
        assertEquals(line(0x0204, payload), ProblemReport.dropDeviceBytes(line(0x0204, payload)))
    }
}
