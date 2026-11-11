package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL.md section 14: the ANC push carries the mode as 1-4 bytes, little endian. */
class AncEventParserTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun modeWidths() {
        for ((p, raw) in listOf(
            bytes(3, 1, 1, 0x20, 0) to 0x20, bytes(3, 1, 1, 2) to 2,
            bytes(3, 1, 1, 0, 8) to 0x800, bytes(3, 1, 1, 1, 2, 3) to 0x030201,
        )) {
            assertTrue(AncEventParser.isAncEvent(p))
            assertEquals(raw, AncEventParser.rawValue(p))
        }
    }

    @Test fun notAMode() {
        assertFalse(AncEventParser.isAncEvent(bytes(3, 2, 1, 7, 8)))
        assertFalse(AncEventParser.isAncEvent(bytes(3, 1, 1)))
        assertEquals(-1, AncEventParser.rawValue(bytes(3, 1, 1)))
    }
}
