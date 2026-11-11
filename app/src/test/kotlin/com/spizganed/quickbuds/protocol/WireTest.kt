package com.spizganed.quickbuds.protocol

import com.spizganed.quickbuds.protocol.Wire.Message

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    private fun msg(cmd: Int, seq: Int, n: Int) = Message(cmd, seq, ByteArray(n) { it.toByte() })
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun capturedFrames() {
        assertArrayEquals(bytes(0xAA, 7, 0, 0, 0, 1, 1, 0, 0), Wire.encode(Message(0x0100, 1, ByteArray(0)))[0])
        assertArrayEquals(bytes(0xAA, 0x0A, 0, 0, 4, 4, 6, 3, 0, 1, 1, 1), Wire.encode(Message(0x0404, 6, bytes(1, 1, 1)))[0])
        // Len above 127 takes two bytes: a 124-byte payload goes out as `AA 83 01`.
        assertArrayEquals(bytes(0xAA, 0x83, 0x01), Wire.encode(msg(0x0418, 3, 124))[0].copyOfRange(0, 3))
    }

    @Test fun sameBytesAsTheEncoder() {
        for (n in listOf(0, 1, 3, 100, 120, 121, 122, 250, 600)) {
            val m = msg(0x0403, 9, n)
            val old = OpoProtocol.buildPacket(m.cmd, seq = m.seq, payload = m.payload)
            assertArrayEquals("payload $n", old, Wire.encode(m)[0])
        }
    }

    @Test fun slicesJoinAgain() {
        for ((n, budget) in listOf(1500 to 660, 600 to 100, 5000 to 129, 700 to 660)) {
            val m = msg(0x8115, 4, n)
            val frames = Wire.encode(m, budget)
            assertTrue(frames.size > 1 || n < 660)
            assertTrue("frame over budget $budget", frames.all { it.size <= budget })
            val r = Wire.Reassembler()
            assertEquals("payload $n budget $budget", listOf(m), frames.flatMap { r.push(it) })
        }
    }

    @Test fun streamInPiecesAndNoise() {
        val stream = bytes(0x13, 0x37) + Wire.encode(msg(0x0106, 1, 4))[0] + Wire.encode(msg(0x0204, 0xFF, 130))[0]
        val noise = mutableListOf<Byte>()
        val r = Wire.Reassembler { noise += it.toList() }
        val got = stream.toList().chunked(5).flatMap { r.push(it.toByteArray()) }
        assertEquals(listOf(msg(0x0106, 1, 4), msg(0x0204, 0xFF, 130)), got)
        assertEquals(listOf<Byte>(0x13, 0x37), noise)
    }

    @Test fun badLengthDoesNotStallTheFrameAfterIt() {
        val w = Wire.encode(msg(0x8106, 0xF0, 2))[0]
        val r = Wire.Reassembler()
        assertTrue(r.push(bytes(0xAA, 0x80, 0x80, 0x80)).isEmpty())
        assertEquals(listOf(msg(0x8106, 0xF0, 2)), r.push(w))
        assertEquals(
            listOf(msg(0x0204, 1, 3)),
            Wire.Reassembler().push(bytes(0xAA, 0x80, 0x80, 0x80) + Wire.encode(msg(0x0204, 1, 3))[0])
        )
    }

    @Test fun wholeFrameUnderSevenIsRejectedThenTheNextFrameParses() {
        val next = Wire.encode(msg(0x0106, 1, 2))[0]
        assertEquals(listOf(msg(0x0106, 1, 2)), Wire.Reassembler().push(bytes(0xAA, 6, 0, 0) + next))
    }

    @Test fun sliceUnderFourIsRejectedThenTheNextFrameParses() {
        val next = Wire.encode(msg(0x0106, 1, 2))[0]
        assertEquals(listOf(msg(0x0106, 1, 2)), Wire.Reassembler().push(bytes(0xAA, 3, 1) + next))
    }

    @Test fun frameOver2048IsRejected() {
        val payload = ByteArray(2042) { 0x11 }
        val frame = bytes(0xAA, 0x81, 0x10, 0, 0, 0x06, 0x81, 1, 0xFA, 0x07) + payload
        assertTrue(Wire.Reassembler().push(frame).isEmpty())
    }

    @Test fun badInputIsDropped() {
        val r = Wire.Reassembler()
        assertTrue(r.push(bytes(0xAA, 6, 2, 0, 1, 1, 2, 3)).isEmpty())
        assertTrue(r.push(bytes(0xAA, 0)).isEmpty())
        assertEquals(listOf(Message(0x8106, 1, bytes(7, 7))), r.push(bytes(0xAA, 9, 0, 0, 0x06, 0x81, 1, 9, 0, 7, 7)))
    }
}
