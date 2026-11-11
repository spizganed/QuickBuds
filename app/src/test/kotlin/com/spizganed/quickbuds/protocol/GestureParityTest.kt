package com.spizganed.quickbuds.protocol

import com.spizganed.quickbuds.protocol.Wire.Message

import org.junit.Assert.assertEquals
import org.junit.Test

/** The gesture table parser is tolerant: a status byte, trailing bytes and a short payload are all kept. */
class GestureParityTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun reply(vararg p: Int) = Message(0x8108, 1, b(*p))

    private fun assertSame(m: Message) {
        val old = KeyFunctionParser.parse(m.payload)
        val new = Decode.decode(m) as Msg.Gestures
        assertEquals(
            "entries ${m.payload.toList()}",
            old.entries.map { listOf(it.deviceType, it.button, it.action, it.function) },
            new.table.map { listOf(it.dev, it.button, it.act, it.func) },
        )
        assertEquals("status ${m.payload.toList()}", old.status, new.status)
        assertEquals("trailing ${m.payload.toList()}", old.trailing.map { it.toInt() and 0xFF }, new.trailing)
    }

    @Test fun nonZeroStatusKeepsTheTable() {
        assertSame(reply(1, 1, 1, 1, 1, 1))
    }

    @Test fun trailingBytesKeepTheTable() {
        assertSame(reply(0, 2, 1, 1, 1))
        assertSame(reply(0, 1, 1, 1, 1, 1, 9))
    }

    @Test fun anEmptyTableIsNotRejected() {
        assertSame(reply(0, 0))
        assertSame(reply())
    }

    @Test fun shortAndMismatchedPayloadsKeepTheTable() {
        assertSame(reply(0, 1, 1, 1, 1, 1))
        assertSame(reply(0, 1, 1, 1, 1, 1, 0, 9, 9, 9, 9))
    }
}
