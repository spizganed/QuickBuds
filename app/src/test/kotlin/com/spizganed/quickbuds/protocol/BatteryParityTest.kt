package com.spizganed.quickbuds.protocol

import com.spizganed.quickbuds.protocol.Wire.Message

import org.junit.Assert.assertEquals
import org.junit.Test

/** The battery decoder is the source of truth for the query and the push. */
class BatteryParityTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun reply(cmd: Int, vararg p: Int) = Message(cmd, 1, b(*p))
    private fun push(vararg p: Int) = Message(0x0204, 0xFF, b(*p))

    private fun queryBattery(m: Message) =
        BatteryParser.parse(OpoProtocol.buildPacket(m.cmd, m.seq, m.payload))

    private fun pushBattery(m: Message) =
        BatteryParser.parseActive(OpoProtocol.buildPacket(m.cmd, m.seq, m.payload))

    private fun cells(r: BatteryParser.Result?) = r?.let {
        listOfNotNull(
            it.left?.let { i -> Cell(1, i.level, i.isCharging) },
            it.right?.let { i -> Cell(2, i.level, i.isCharging) },
            it.case?.let { i -> Cell(3, i.level, i.isCharging) },
        )
    }

    @Test fun queryReplyMatchesTheDecoder() {
        for (p in listOf(
            intArrayOf(0, 3, 1, 0x46, 2, 0xC6, 3, 0),
            intArrayOf(0, 0),
            intArrayOf(0, 3, 1, 0x46, 2),
            intArrayOf(1, 1, 1, 0x46),
            intArrayOf(0, 1, 1, 0x5A),
        )) {
            val m = reply(0x8106, *p)
            assertEquals("${m.payload.toList()}", cells(queryBattery(m)), (Decode.decode(m) as? Msg.Battery)?.cells)
        }
    }

    @Test fun activePushMatchesTheDecoder() {
        for (p in listOf(
            intArrayOf(1, 3, 1, 0x64, 2, 0xE4, 3, 0),
            intArrayOf(1, 0),
            intArrayOf(1, 2, 1, 0x64),
            intArrayOf(2, 1, 1, 0x64),
        )) {
            val m = push(*p)
            assertEquals("${m.payload.toList()}", cells(pushBattery(m)), (Decode.decode(m) as? Msg.Battery)?.cells)
        }
    }

    private fun wear(r: WearingStatusParser.Result?) = r?.let {
        listOfNotNull(
            if (it.leftValid) 1 to it.leftStatus else null,
            if (it.rightValid) 2 to it.rightStatus else null,
            if (it.caseStatus != -1) 3 to it.caseStatus else null,
        )
    }

    @Test fun wearingReplyMatchesTheDecoder() {
        for (p in listOf(
            intArrayOf(3, 1, 7, 2, 7, 3, 4),
            intArrayOf(0, 3, 1, 3, 2, 3, 3, 4),
            intArrayOf(3, 1, 15, 2, 16, 3, 7),
            intArrayOf(9, 1, 7, 2, 7, 3, 4, 1, 7, 2, 7, 3, 4, 1, 7, 2, 7, 3, 4),
        )) {
            val m = reply(0x8109, *p)
            assertEquals(
                "${m.payload.toList()}",
                wear(WearingStatusParser.parseQueryResponse(m.payload)),
                (Decode.decode(m) as? Msg.Wear)?.parts,
            )
        }
    }

    @Test fun wearingPushMatchesTheDecoder() {
        for (p in listOf(
            intArrayOf(2, 3, 1, 7, 2, 7, 3, 4),
            intArrayOf(2, 9, 1, 7, 2, 7, 3, 4, 1, 7, 2, 7, 3, 4, 1, 7, 2, 7, 3, 4),
            intArrayOf(2, 1, 1, 16),
        )) {
            val m = push(*p)
            assertEquals(
                "${m.payload.toList()}",
                wear(WearingStatusParser.parseActiveReport(m.payload)),
                (Decode.decode(m) as? Msg.Wear)?.parts,
            )
        }
    }
}
