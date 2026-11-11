package com.spizganed.quickbuds.protocol


import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.protocol.Wire.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MsgTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun m(cmd: Int, vararg v: Int) = Message(cmd, 1, b(*v))
    private fun push(vararg v: Int) = Message(0x0204, 0xFF, b(*v))
    private fun dec(msg: Message) = Decode.decode(msg)

    @Test fun bitmapMatchesTheCapabilitiesTable() {
        val maps = mutableListOf(
            b(0, 0xFF, 0x77, 0x5A, 0xEA, 0x67, 0x0E, 0x20, 0x07), b(0, 0x9B, 0x2C, 0x50, 0x80),
        )
        for (bit in 0 until 72) maps += ByteArray(11).also { it[1 + bit / 8] = (1 shl (bit % 8)).toByte() }
        for (map in maps) {
            val new = Bitmap.parse(map)!!
            val old = Capabilities.parse(map)!!
            for (cmd in 0..0x0FFF) assertEquals("cmd $cmd", cmd in old, new.allows(cmd))
        }
        assertTrue(Bitmap.UNKNOWN.allows(0x0422) && !Bitmap.UNKNOWN.known)
        assertTrue(Bitmap.parse(b(0, 0xFF, 0x77, 0x5A, 0xEA, 0x67, 0x0E, 0x20, 0x07))!!.allows(0x012F))
        assertNull(Bitmap.parse(b(1, 0xFF)))
        assertNull(Bitmap.parse(b(0)))
    }

    @Test fun repliesFromTheBuds4Capture() {
        assertEquals(Msg.ProductId(0x065414), dec(m(0x8103, 0, 0x14, 0x54, 0x06)))
        assertNull(dec(m(0x8103, 1, 0, 0, 0)))
        assertEquals(Msg.Offered(listOf(1, 2, 3, 4, 8, 0x0B, 0xF1, 0xF2, 0xF3)), dec(m(0x8200, 0, 9, 1, 2, 3, 4, 8, 0x0B, 0xF1, 0xF2, 0xF3)))
        assertEquals(Msg.Offered(listOf(2, 1)), dec(m(0x8200, 0, 2, 2, 1)))
        val text = "1,2,138,2,2,138,3,1,01,3,2,105".toByteArray()
        val fw = Decode.decode(Message(0x8105, 1, byteArrayOf(0, 4) + text)) as Msg.Firmware
        assertEquals("138.138.105", fw.version())
        assertNull(Msg.Firmware(listOf(Triple(3, 1, "01"))).version())
        assertEquals(Msg.Registered, dec(m(0x8205, 1, 2, 1, 0, 2, 0)))
    }

    @Test fun batchMatchesTheReplySplit() {
        val replies = listOf(0x0106 to b(0, 1, 1, 0x5A), 0x010C to b(0, 1, 1, 8, 0), 0x010F to b(0, 2))
        var pl = b(0, replies.size)
        for ((c, p) in replies) pl += b(c and 0xFF, c shr 8, p.size, 0) + p
        val parts = Decode.unbatch(Message(0x812F, 7, pl))
        assertEquals(3, parts.size)
        val old = Replies.batchReplies(pl)
        old.zip(parts).forEach { (o, n) -> assertEquals(o.first or 0x8000, n.cmd); assertTrue(o.second.contentEquals(n.payload)); assertEquals(7, n.seq) }
        assertEquals(old.size, parts.size)
        assertEquals(2, Decode.unbatch(Message(0x812F, 7, pl.copyOf(pl.size - 1))).size)
    }

    @Test fun pushesMatchTheDecoders() {
        // Battery, from PROTOCOL.md section 13: level 0 left out, charging bit.
        val p = intArrayOf(1, 3, 1, 0x64, 2, 0xE4, 3, 0)
        val new = dec(push(*p)) as Msg.Battery
        val old = BatteryParser.parseActive(OpoProtocol.buildPacket(0x0204, 0xFF, b(*p)))!!
        assertEquals(listOf(Cell(1, 100, false), Cell(2, 100, true)), new.cells)
        assertEquals(old.left!!.level, 100); assertEquals(old.right!!.isCharging, true); assertNull(old.case)
        assertEquals(Msg.AncMsg(Anc.Mode(8)), dec(push(3, 1, 1, 0x08, 0x00)))
        // Push 03 has the 0x810C shapes, as in core: the level Smart chose, and the echo of a hold-cycle write.
        assertEquals(Msg.AncMsg(Anc.Smart(0x20)), dec(push(3, 4, 1, 0x20, 0)))
        assertEquals(8, AncEventParser.rawValue(b(3, 1, 1, 8, 0)))
        assertEquals(Msg.AncMsg(Anc.HoldCycle(1, 7)), dec(push(3, 2, 1, 7)))
        assertEquals(Msg.FitTest(1, 1), dec(push(4, 1, 1, 2, 1)))
        assertEquals(Msg.FitTest(null, 6), dec(push(4, 2, 6)))
        assertEquals(Msg.GameMode(1, null), dec(push(5, 1)))
        assertEquals(Msg.GameMode(0, 1), dec(push(5, 0, 0, 1)))
        assertEquals(true, com.spizganed.quickbuds.protocol.GameModeParser.parseActive(b(5, 0, 0, 1), "062410"))
        assertEquals(Msg.PersonalAnc(2), dec(push(0x0B, 2)))
        assertEquals(Msg.HeadGesture(1), dec(push(0xF5, 1)))
        assertEquals(Msg.Hearing(2, 5), dec(push(0x08, 2, 5)))
        assertEquals(Msg.PublicMsg(Public.TapLevel(4)), dec(push(0x0F, 0x10, 0, 4)))
    }

    @Test fun devicesAndEarScan() {
        val p = ArrayList<Int>().apply {
            add(6); add(2)
            for ((mac, st, fl, name) in listOf(
                listOf(listOf(6, 5, 4, 3, 2, 1), 2, 1, "Phone"), listOf(listOf(12, 11, 10, 9, 8, 7), 0, 0, "PC"),
            )) {
                @Suppress("UNCHECKED_CAST") addAll(mac as List<Int>)
                val n = name as String
                add(3 + n.length); add(st as Int); add(fl as Int); add(n.length); addAll(n.map { it.code })
            }
        }
        val d = (dec(push(*p.toIntArray())) as Msg.Devices).list
        assertEquals(listOf(1, 2, 3, 4, 5, 6), d[0].mac)
        assertTrue(d[0].connected && d[0].thisPhone && !d[1].connected)
        assertNull(dec(push(6, 1, 1, 2, 3)))
        val scan = IntArray(168) { 9 }
        val e = dec(push(0x0E, 3, 168, 0, *scan, 1, 2, 3, 4)) as Msg.EarScan
        assertEquals(0x01020304, e.id); assertEquals(168, e.data.size)
        assertNull(dec(push(0x0E, 3, 0, 0)))
    }

    @Test fun pushesTheDecoderIgnores() {
        assertEquals(Msg.Gesture(2, 1, 4, 8, 3, emptyList()), dec(push(0xF1, 2, 1, 4, 8, 3)))
        assertNull(dec(push(0xF1, 1, 1)))
        assertEquals(
            Msg.LinkedMsg(listOf(Linked(listOf(1, 2, 3, 4, 5, 6), 3, "PC"))),
            dec(push(0xF2, 1, 6, 5, 4, 3, 2, 1, 3, 0, 2, 'P'.code, 'C'.code)),
        )
        assertEquals(Msg.Diagnostic("{\"x\":1}"), Decode.decode(Message(0x0204, 0xFF, byteArrayOf(0xF4.toByte()) + "{\"x\":1}".toByteArray())))
        assertEquals(Msg.ZenState(1), dec(push(0x0A, 1)))
        assertEquals(Msg.HeadTry(2), dec(push(0xF6, 2)))
        assertEquals(Msg.VoiceAssist(2, true), dec(push(0xF7, 0x84)))
        assertEquals(Msg.VoiceAssist(null, false), dec(push(0xF7, 0)))
        assertEquals(Msg.LinkSwitch(1, 0, 1), dec(push(0xF8, 5)))
        assertEquals(Msg.Unused(0xF3, listOf(9)), dec(push(0xF3, 9)))
        assertEquals(Msg.Unused(0x10, listOf(1, 2)), dec(push(0x10, 1, 2)))
    }

    @Test fun publicEventsAndTriangle() {
        assertEquals(Msg.PublicMsg(Public.AlertVolume(6)), dec(push(0x0F, 1, 0, 6)))
        assertEquals(Msg.PublicMsg(Public.Handheld(true, null)), dec(push(0x0F, 4, 0, 0)))
        assertEquals(Msg.PublicMsg(Public.Handheld(false, listOf(1, 2, 3, 4, 5, 6))), dec(push(0x0F, 4, 0, 1, 6, 5, 4, 3, 2, 1)))
        assertNull(dec(push(0x0F, 4, 0, 1, 6, 5)))
        assertNull(dec(push(0x0F, 1, 0)))
        assertEquals(
            Msg.TriangleMsg(Triangle(2, 1, 7, 3, "PC")), dec(push(0x0D, 0x1F, 0, 2, 1, 7, 3, 2, 'P'.code, 'C'.code)),
        )
        assertEquals(Msg.TriangleMsg(Triangle(wear = 5)), dec(push(0x0D, 0x04, 0, 5)))
        assertEquals(Msg.TriangleMsg(Triangle(capability = 2)), dec(push(0x0D, 0x03, 0, 2)))
        assertFalse(Bitmap.UNKNOWN.known)
    }

    private fun payloadOf(packet: ByteArray) = packet.copyOfRange(9, packet.size)
    @Test fun batteryAndWearReplies() {
        assertEquals(
            listOf(Cell(1, 100, false), Cell(2, 100, false), Cell(3, 80, false)),
            (dec(m(0x8106, 0, 3, 1, 0x64, 2, 0x64, 3, 0x50)) as Msg.Battery).cells,
        )
        assertEquals(listOf(Cell(1, 90, false)), (dec(m(0x8106, 0, 1, 1, 0x5A)) as Msg.Battery).cells)
        assertNull(dec(m(0x8106, 1, 1, 1, 0x5A)))
        assertNull(dec(push(1, 0)))
        assertEquals(Msg.Wear(listOf(1 to 7, 2 to 7, 3 to 4)), dec(m(0x8109, 3, 1, 7, 2, 7, 3, 4)))
        assertEquals(Msg.Wear(listOf(1 to 3, 2 to 3, 3 to 4)), dec(m(0x8109, 0, 3, 1, 3, 2, 3, 3, 4)))
        assertNull(dec(m(0x8109, 3, 9, 7, 2, 7, 3, 4)))
        // Same facts through the parser.
        val old = com.spizganed.quickbuds.protocol.WearingStatusParser.parseQueryResponse(b(3, 1, 7, 2, 7, 3, 4))!!
        assertEquals(7 and 7, old.leftStatus and 7)
    }

    @Test fun ancRepliesAndPushes() {
        assertEquals(Msg.AncMsg(Anc.Mode(8)), dec(m(0x810C, 0, 1, 1, 0x08, 0x00)))
        assertEquals(Msg.AncMsg(Anc.Mode(0x0800)), dec(m(0x810C, 0, 1, 1, 0x00, 0x08)))
        assertEquals(Msg.AncMsg(Anc.Smart(0x40)), dec(m(0x810C, 0, 4, 1, 0x40)))
        assertEquals(Msg.AncMsg(Anc.HoldCycle(1, 0x0807)), dec(m(0x810C, 0, 2, 1, 0x07, 0x08)))
        assertEquals(Msg.AncMsg(Anc.Level(3)), dec(m(0x810C, 0, 1, 2, 3)))
        assertNull(dec(m(0x810C, 0xE, 1, 1, 8, 0)))
        assertEquals(Msg.Ack(0x0404, 14, emptyList()), dec(m(0x8404, 0x0E)))
        assertEquals(Msg.Ack(0x0404, 0, emptyList()), dec(m(0x8404, 0)))
        assertEquals(Msg.Ack(0x0427, 0, listOf(6)), dec(m(0x8427, 0, 6)))
    }

    @Test fun gestureTableFromTheBuds4Capture() {
        val rows = listOf(
            Triple(1, 1, listOf(1 to 0, 2 to 0x11, 3 to 0, 4 to 8, 6 to 0, 5 to 7)), Triple(1, 6, listOf(2 to 0, 3 to 0, 6 to 0)),
            Triple(2, 1, listOf(1 to 0, 2 to 0, 3 to 0, 4 to 8, 6 to 0, 5 to 7)), Triple(2, 6, listOf(2 to 0, 3 to 0, 6 to 0)),
        ).flatMap { (d, bt, acts) -> acts.map { (a, fn) -> Binding(d, bt, a, fn) } }
        assertEquals(18, rows.size)
        val payload = byteArrayOf(0, 18) + rows.flatMap { listOf(it.dev, it.button, it.act, it.func) }.map { it.toByte() }
        val g = (Decode.decode(Message(0x8108, 1, payload)) as Msg.Gestures).table
        assertEquals(rows, g)
        val old = com.spizganed.quickbuds.protocol.KeyFunctionParser.parse(payload)
        assertEquals(old.entries.map { listOf(it.deviceType, it.button, it.action, it.function) }, g.map { listOf(it.dev, it.button, it.act, it.func) })
        assertEquals(Msg.Gestures(emptyList(), 0, emptyList()), dec(m(0x8108, 0, 0)))
        assertEquals(2, (dec(m(0x8108, 0, 1, 1, 1, 1, 0, 9, 9, 9, 9)) as Msg.Gestures).table.size)
    }

    @Test fun featureAndEqReplies() {
        val f = dec(m(0x810D, 0, 8, 5, 0, 4, 0, 0x0B, 1, 0x11, 1, 0x18, 1, 6, 0, 0x1B, 0, 0x1D, 1)) as Msg.Features
        assertEquals(8, f.list.size)
        assertEquals(4 to 0, f.list[1])
        assertEquals(Msg.Features(emptyList()), dec(m(0x810D, 0, 0)))
        assertEquals(Msg.Features(listOf(5 to 0)), dec(m(0x810D, 0, 3, 5, 0, 4)))
        assertNull(dec(m(0x810D, 0)))
        assertEquals(Msg.EqCurrent(2), dec(m(0x810F, 0, 2)))
        assertEquals(Msg.EqCurrent(1), Decode.decode(Message(0x0504, 0xFF, b(1))))
        assertEquals(Msg.BassLevel(-2), dec(m(0x8124, 0, 0xFB, 5, 0xFE)))
        // realme Buds T310 capture: low 3, medium -3, high 2.
        assertEquals(Msg.BassBands(listOf(1 to 3, 2 to -3, 3 to 2)),
            dec(m(0x812C, 0, 3, 0xFB, 5, 3, 1, 0xFB, 5, 0xFD, 2, 0xFB, 5, 2, 3)))
        assertNull(dec(m(0x812C, 0, 2, 0xFB, 5, 3, 1)))
        val body = byteArrayOf(0, 1, 1, 0xFA.toByte(), 6, 4, 4) + "Rock".toByteArray() + b(2, 62, 0, 3, 0xFA, 0, 0xFF)
        val list = (Decode.decode(Message(0x8122, 1, body)) as Msg.EqCustom).list
        assertEquals(EqPreset(4, "Rock", true, -6 to 6, listOf(62 to 3, 250 to -1)), list[0])
        val old = com.spizganed.quickbuds.protocol.EqCodec.parseList(body)!!
        assertEquals(old[0].freqs, list[0].bands.map { it.first })
        assertEquals(old[0].gains, list[0].bands.map { it.second })
        assertNull(dec(m(0x8122, 0, 2, 0, 0xFA, 6, 1, 1, 'a'.code, 0)))
    }

    private fun at(n: Int, vararg tail: Int) = IntArray(n) + tail

    @Test fun settingRepliesMatchTheDecoder() {
        assertEquals(Msg.AlertVolume(6), dec(m(0x8130, 0, 6)))
        assertNull(dec(m(0x8130, 1, 6)))
        assertEquals(Msg.TapLevel(2, 3), dec(m(0x8133, 0, 2, 3)))
        assertEquals(Msg.Spatial(2), dec(m(0x812A, 0, 2)))
        assertEquals(Msg.Spatial(1), Decode.decode(Message(0x0510, 0xFF, b(1))))
        assertEquals(Msg.HeadGesture(1), dec(m(0x8134, 0, 1)))
        assertEquals(Msg.Codec(8), dec(m(0x8114, 0, 8)))
        assertEquals(Msg.CodecMask(0x0146), dec(m(0x8123, 0, 0x46, 0x01)))
        assertEquals(Msg.GameSound(3, listOf(0, 3)), dec(m(0x812B, 0, 3, 2, 0, 3)))
        assertEquals(Msg.PersonalAncStored(true), dec(m(0x811A, 0, 1)))
        assertEquals(Msg.Preferred(null), dec(m(0x8132, 0, 2, 0)))
        assertEquals(Msg.Preferred(listOf(1, 2, 3, 4, 5, 6)), dec(m(0x8132, 0, 2, 1, 1, 2, 3, 4, 5, 6)))
        val list = b(0, 1, 6, 5, 4, 3, 2, 1, 8, 2, 1, 5) + "Phone".toByteArray()
        val d = (Decode.decode(Message(0x8112, 1, list)) as Msg.Devices).list[0]
        assertEquals(Device(listOf(1, 2, 3, 4, 5, 6), "Phone", true, 1), d)
    }

    @Test fun goldenSoundReplies() {
        val v = listOf(-55, -49, -41, -35, -25, -19, -11, -5, 3, 7, 13, 17)
        val apply = listOf(
            HearingCommands.hearingRestore(11),
            HearingCommands.hearingRecord(0x01020304, "2026/09/29 01:53", v.toIntArray()),
            HearingCommands.earScanData(0x01020304, ByteArray(168) { 9 }),
            Commands.feature(Feature.GOLDEN_SOUND, true),
        )
        assertEquals(listOf(0x0411, 0x040E, 0x0415, 0x0403), apply.map { ProtocolCases.packet(it).cmd })
        // The record the apply frame writes reads back as the active record.
        assertEquals(Msg.GoldenRecord(0x01020304, "2026/09/29 01:53", v), Decode.decode(Message(0x8115, 1, byteArrayOf(0) + ProtocolCases.packet(apply[1]).payload)))
        val scan = byteArrayOf(0, 3) + b(168, 0) + ByteArray(168) { 9 } + b(0, 0, 0, 7)
        assertEquals(Msg.GoldenScan(7, List(168) { 9 }), dec(Message(0x811E, 1, scan)))
        val floats = (0 until 12).flatMap { java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(it.toFloat()).array().toList() }.toByteArray()
        val hf = byteArrayOf(0) + b(0, 0, 0, 9, 4, 12, 0, 0, 2) + floats
        val g = dec(Message(0x8116, 1, hf)) as Msg.GoldenFilter
        assertEquals(9, g.id); assertEquals(2, g.enhance)
        assertEquals(Curves(44100, (0..5).map { it.toFloat() }, (6..11).map { it.toFloat() }), g.curves)
        val sf = byteArrayOf(0) + b(0, 0, 0, 9, 4, 0x44, 0xAC, 12, 0, 0) + floats
        assertEquals(44100, (dec(Message(0x811F, 1, sf)) as Msg.GoldenScanFilter).curves.rate)
        assertNull(dec(m(0x811F, 0, 0, 0, 0, 9, 3, 0, 0, 0, 0, 0)))
    }

    @Test fun requestsFromTheBudsAreAnswered() {
        val unix = 0x67001234L
        val cases = listOf(
            0x0500 to b(0, 0, 0, 0), 0x0500 to b(), 0x050C to b(), 0x051C to b(), 0x051D to b(),
            0x0204 to b(0xF2, 1), 0x0204 to b(0xF4), 0x0204 to b(3, 2, 3, 1, 3), 0x0501 to b(), 0x0502 to b(), 0x0504 to b(1), 0x0600 to b(), 0x0900 to b(9),
            0x8105 to b(0), 0x0F00 to b(),
        )
        for ((cmd, p) in cases) {
            val new = Decode.answer(Message(cmd, 0x14, p), unix)
            val old = Replies.answerFor(cmd, 0x14, p, unix)
            if (old == null) assertNull("$cmd", new)
            else {
                assertTrue("$cmd", new != null)
                // The answer is a whole packet: its cmd at 4, payload from 9.
                assertEquals(OpoProtocol.u16(old, 4), new!!.cmd)
                assertTrue("$cmd ${p.toList()}", payloadOf(old).contentEquals(new.payload))
            }
        }
        // The JSON echo uses org.json; the error path logs through android.util.Log,
        // which a JVM test does not mock, so the log is off for the bad JSON case.
        val ack = Decode.answer(Message(0x0204, 0xFF, byteArrayOf(0xF4.toByte()) + "{\"cmd\":\"a2dp_caton\",\"ver\":\"1\"}".toByteArray()), 0)!!
        assertEquals(0x8204, ack.cmd)
        assertEquals("{\"cmd\":\"a2dp_caton\"}", String(ack.payload, 2, ack.payload.size - 2))
        assertEquals("{}", String(Decode.answer(Message(0x0204, 1, byteArrayOf(0xF4.toByte()) + "{\"x\":[1,{\"a\":null}],\"cmd\":3}".toByteArray()), 0)!!.payload, 2, 2))
        val wasEnabled = PacketLogger.ENABLED
        PacketLogger.ENABLED = false
        val bad = try {
            Decode.answer(Message(0x0204, 1, byteArrayOf(0xF4.toByte()) + "not json".toByteArray()), 0)!!
        } finally {
            PacketLogger.ENABLED = wasEnabled
        }
        assertTrue(bad.payload.contentEquals(byteArrayOf(1, 0xF4.toByte())))
        assertEquals(0x21, Decode.answer(Message(0x0500, 0x21, b(0, 0, 0, 0)), 0)!!.seq)
        assertEquals(listOf(0, 0x34, 0x12, 0, 0x67), Decode.answer(Message(0x0500, 0x14, b()), unix)!!.payload.map { it.toInt() and 0xFF })
    }
}
