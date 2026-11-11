package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The first read of the OnePlus Buds 4 on the stream (Dev tools probe, 2026-11-11). */
class FastPairStreamTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val capture = bytes(
        0x03, 0x01, 0x00, 0x03, 0x75, 0x41, 0x0D,
        0x03, 0x02, 0x00, 0x06, 0, 0, 0, 0, 0, 0,
        0x03, 0x03, 0x00, 0x03, 0x64, 0x7F, 0x46,
    )

    @Test fun splitsTheCapture() {
        val (msgs, used) = FastPairStream.split(capture)
        assertEquals(3, msgs.size)
        assertEquals(capture.size, used)
        assertEquals(listOf(1, 2, 3), msgs.map { it.code })
    }

    @Test fun battery() {
        val b = FastPairStream.battery(FastPairStream.split(capture).first[2])!!
        assertEquals(FastPairStream.Level(100, false), b.left)
        assertEquals(FastPairStream.Level(null, false), b.right)
        assertEquals(FastPairStream.Level(70, false), b.case)
        assertEquals(FastPairStream.Level(50, true), FastPairStream.battery(
            FastPairStream.Message(3, 3, bytes(0xB2, 0x32, 0x32)))!!.left)
    }

    @Test fun keepsAPartialMessage() {
        val (msgs, used) = FastPairStream.split(capture.copyOf(20))
        assertEquals(2, msgs.size)
        assertEquals(17, used)
    }

    @Test fun otherMessagesAreNoBattery() {
        assertNull(FastPairStream.battery(FastPairStream.split(capture).first[0]))
    }
}
