package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The framer joins slices as PROTOCOL.md section 2 says. */
class FramerTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun feed(f: OppoPacketFramer, b: ByteArray) = f.append(b, b.size)
    private fun slice(kind: Int, fsn: Int, d: ByteArray) = bytes(0xAA, 3 + d.size, kind, 0, fsn) + d

    @Test fun slicesJoinIntoOneFrame() {
        val payload = ByteArray(300) { it.toByte() }
        val msg = bytes(0x15, 0x81, 4, 0x2C, 0x01) + payload
        val f = OppoPacketFramer()
        val stream = slice(1, 0, msg.copyOfRange(0, 100)) + slice(2, 1, msg.copyOfRange(100, 200)) + slice(3, 2, msg.copyOfRange(200, msg.size))
        val got = feed(f, stream)
        assertEquals(1, got.size)
        assertEquals(0x8115, (got[0][4].toInt() and 0xFF) or ((got[0][5].toInt() and 0xFF) shl 8))
        assertArrayEquals(payload, got[0].copyOfRange(9, got[0].size))
    }

    @Test fun lastSliceWithoutFirstIsDropped() {
        assertTrue(feed(OppoPacketFramer(), slice(3, 2, ByteArray(10))).isEmpty())
    }

    @Test fun wholeMessageStaysTheSame() {
        val w = OpoProtocol.buildPacket(0x8106, seq = 0xF0, payload = bytes(1, 2))
        assertArrayEquals(w, feed(OppoPacketFramer(), w).single())
    }

    @Test fun badLengthByteDoesNotStallTheFrameAfterIt() {
        val w = OpoProtocol.buildPacket(0x8106, seq = 0xF0, payload = bytes(1, 2))
        val got = feed(OppoPacketFramer(), bytes(0xAA, 0x80, 0x80, 0x80) + w)
        assertArrayEquals(w, got.single())
    }
}
