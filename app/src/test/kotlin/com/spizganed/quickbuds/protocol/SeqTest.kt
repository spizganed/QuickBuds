package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** The battery and wearing queries use fixed sequence numbers. */
class SeqTest {
    private fun hex(b: ByteArray) = ProtocolCases.hex(b)

    @Test fun batteryUsesSeqF0() {
        val p = ProtocolCases.packet(Commands.queryBattery())
        assertEquals("battery seq", 0xF0, p.seq)
        assertEquals("AA 07 00 00 06 01 F0 00 00", hex(Commands.queryBattery()))
    }

    @Test fun wearingUsesSeqF2() {
        val p = ProtocolCases.packet(Commands.queryWearingStatus())
        assertEquals("wearing seq", 0xF2, p.seq)
        assertEquals("AA 07 00 00 09 01 F2 00 00", hex(Commands.queryWearingStatus()))
    }
}
