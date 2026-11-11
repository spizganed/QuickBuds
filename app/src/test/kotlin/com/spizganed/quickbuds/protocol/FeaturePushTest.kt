package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `0x0503` frames in the field reports. */
class FeaturePushTest {

    private fun parse(vararg bytes: Int) = FeaturePushParser.parse(ByteArray(bytes.size) { bytes[it].toByte() })

    /** Report #63, realme Buds T200x: game mode on after the write `06 01`. */
    @Test fun oneGameModePair() {
        assertEquals(mapOf(0x06 to 1), parse(0x01, 0x06, 0x01))
        assertEquals(mapOf(0x06 to 0), parse(0x01, 0x06, 0x00))
        assertEquals(mapOf(0x28 to 1), parse(0x01, 0x28, 0x01)) // report #26: the game id on some models
    }

    /** Report #4, realme Buds Air 5 Pro: 0x11 = 17 pairs in 35 bytes. */
    @Test fun seventeenPairs() {
        val frame = intArrayOf(
            0x11,
            0x00, 0x00, 0x01, 0x00, 0x02, 0x00, 0x04, 0x01, 0x05, 0x00,
            0x06, 0x01, 0x08, 0x00, 0x09, 0x00, 0x0B, 0x00, 0x0C, 0x00,
            0x0D, 0x00, 0x10, 0x00, 0x11, 0x00, 0x18, 0x00, 0x1A, 0x01,
            0x1B, 0x00, 0x1D, 0x00
        )
        val values = parse(*frame)
        assertEquals(17, values?.size)
        assertEquals(1, values?.get(0x06))
        assertEquals(1, values?.get(0x04))
        assertEquals(0, values?.get(0x1B))
        assertEquals(1, values?.get(0x1A))
    }

    /** Report #61, OPPO Enco Buds3 Pro: one extra byte follows the pair. */
    @Test fun trailingByteIsIgnored() {
        assertEquals(mapOf(0x1B to 1), parse(0x01, 0x1B, 0x01, 0x30))
        assertEquals(mapOf(0x1B to 0), parse(0x01, 0x1B, 0x00, 0x47))
    }

    @Test fun truncatedFrameIsRejected() {
        assertNull(parse(0x02, 0x06, 0x01))
        assertNull(parse(0x01))
        assertNull(parse())
    }

    @Test fun unknownIdIsKept() {
        assertEquals(mapOf(0x7F to 1), parse(0x01, 0x7F, 0x01))
    }
}
