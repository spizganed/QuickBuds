package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Packets built from the layouts in PROTOCOL.md section 13. No capture yet. */
class FastPairAdvertTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun oppoBatteryAfterTheStatusByte() {
        // Product 065414, flags: fast pairing, status, battery. Status 01. L 100, R unknown, C 50.
        val b = FastPairAdvert.oppo(bytes(0x14, 0x54, 0x06, 0x19, 0x00, 0x01, 0x98, 0x01, 0xC9, 0x00, 0x65))!!
        assertEquals(0x065414, b.productId)
        assertEquals(100, b.left)
        assertNull(b.right)
        assertEquals(50, b.case)
    }

    @Test fun oppoSkipsThePairingStateAndTheAddress() {
        val b = FastPairAdvert.oppo(bytes(0x14, 0x54, 0x06, 0x17, 0x00, 0x01, 0x98, 0x00, 1, 2, 3, 4, 5, 6, 0xC9, 0xC9, 0x15))!!
        assertEquals(10, b.case)
    }

    @Test fun oppoNeedsTheFastPairingAndBatteryFlags() {
        assertNull(FastPairAdvert.oppo(bytes(0x14, 0x54, 0x06, 0x18, 0x00, 0x01, 0x98, 0x01, 0xC9, 0x00, 0x65)))
        assertNull(FastPairAdvert.oppo(bytes(0x14, 0x54, 0x06, 0x09, 0x00, 0x01, 0x98, 0x01)))
        assertNull(FastPairAdvert.oppo(bytes(0x14, 0x54, 0x06, 0x19, 0x00, 0x01, 0x97, 0x01, 0xC9, 0x00, 0x65)))
    }

    @Test fun googleBatteryAfterTheFilterAndSalt() {
        // Flags 00, account key filter (4 bytes), salt (1 byte), battery shown: L 100, R unknown, C 70 charging.
        val b = FastPairAdvert.google(bytes(0x00, 0x40, 1, 2, 3, 4, 0x11, 0xAB, 0x33, 0x64, 0x7F, 0xC6))!!
        assertNull(b.productId)
        assertEquals(100, b.left)
        assertNull(b.right)
        assertEquals(70, b.case)
        assertEquals(true, b.caseCharging)
    }

    @Test fun googleModelIdOnlyHasNoBattery() {
        assertNull(FastPairAdvert.google(bytes(0x75, 0x41, 0x0D)))
        assertNull(FastPairAdvert.google(bytes(0x00, 0x40, 1, 2, 3, 4, 0x11, 0xAB)))
    }

    @Test fun matchLinkPicksTheSenderWithTheLinkLevels() {
        // Buds 4 log 2026-11-13: three senders, the link gives L 90 R 90.
        val own = FastPairAdvert.Battery(null, 90, 90, 100, false)
        val others = listOf(FastPairAdvert.Battery(null, null, 99, 97, false), FastPairAdvert.Battery(null, 100, 90, 80, false))
        assertEquals(own, FastPairAdvert.matchLink(others + own, 90, 90))
        assertEquals(own, FastPairAdvert.matchLink(others + own, 90, null))
        assertNull(FastPairAdvert.matchLink(others + own, null, 90))  // two senders have R 90
        assertNull(FastPairAdvert.matchLink(others + own, null, null))
    }
}
