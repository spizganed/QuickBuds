package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** The `0x8123` mask lists the offered codecs; LHDC (7) is dropped when LHDC V5 (8) is offered too (PROTOCOL.md section 18). */
class CodecMaskTest {
    @Test fun dropsLhdcWhenLhdcV5IsOffered() {
        assertEquals(listOf(1, 2, 3, 6, 8), Decode.codecsOf(0x00E7))
    }

    @Test fun keepsLhdcWhenLhdcV5IsNotOffered() {
        assertEquals(listOf(2, 3, 7), Decode.codecsOf(0x0046))
    }
}
