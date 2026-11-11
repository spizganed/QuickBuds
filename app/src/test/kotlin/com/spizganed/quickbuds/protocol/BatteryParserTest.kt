package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** PROTOCOL.md section 13: a count of 0, or a list that runs past the end, is no list. */
class BatteryParserTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun reply(vararg p: Int) = OpoProtocol.buildPacket(0x8106, 1, bytes(*p))
    private fun push(vararg p: Int) = OpoProtocol.buildPacket(0x0204, 0xFF, bytes(*p))

    @Test fun queryReply() {
        val r = BatteryParser.parse(reply(0, 3, 1, 0x46, 2, 0xC6, 3, 0))!!
        assertEquals(BatteryParser.Info(0x46, false), r.left)
        assertEquals(BatteryParser.Info(0x46, true), r.right)
        assertNull(r.case)
    }

    @Test fun noList() {
        assertNull(BatteryParser.parse(reply(0, 0)))
        assertNull(BatteryParser.parse(reply(0, 0, 1, 0x46)))
        assertNull(BatteryParser.parse(reply(0, 3, 1, 0x46, 2)))
        assertNull(BatteryParser.parse(reply(1, 1, 1, 0x46)))
    }

    @Test fun pushForm() {
        assertNotNull(BatteryParser.parseActive(push(1, 1, 1, 0x64)))
        assertNull(BatteryParser.parseActive(push(1, 0)))
        assertNull(BatteryParser.parseActive(push(1, 2, 1, 0x64)))
    }
}
