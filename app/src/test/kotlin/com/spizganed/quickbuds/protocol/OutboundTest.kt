package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden packet tests of [Commands] and [HearingCommands]. Each row holds the command and the
 * payload bytes. A fixed sequence byte is in SeqTest. No byte expectation is dropped.
 */
class OutboundTest {
    private fun check(label: String, packet: ByteArray) {
        val g = GOLDEN[label] ?: error("no golden row: $label")
        val p = ProtocolCases.packet(packet)
        assertEquals("$label command", g.first, p.cmd)
        assertEquals("$label payload", g.second, ProtocolCases.hex(p.payload))
    }

    @Test fun connection() {
        val ids = listOf(1, 2, 3, 4, 8, 11)
        check("handshake", Commands.handshake())
        check("product id", Commands.queryProductId())
        check("broadcast", Commands.queryBroadcastCodes())
        check("firmware", Commands.queryFirmware())
        check("register", Commands.registerNotifications(ids))
        check("register 1 2 3", Commands.registerNotifications(listOf(1, 2, 3)))
        check("registerOne", Commands.registerOne(4))
        check("spatialOnBuds", Commands.spatialOnBuds())
    }

    @Test fun notifyIdsFollowTheBitmapAndTheOffer() {
        val buds4 = Bitmap.parse(byteArrayOf(0, 0xFF.toByte(), 0x77, 0x5A, 0xEA.toByte(), 0x67, 0x0E, 0x20, 0x07))!!
        val offered = setOf(1, 2, 3, 4, 8, 0x0B, 0xF1, 0xF2, 0xF3)
        assertEquals(listOf(1, 2, 3, 4, 8), Commands.notifyIds(buds4.allows(0x0405), buds4.allows(0x040D), buds4.allows(0x0412), offered))
        assertEquals(listOf(1, 2), Commands.notifyIds(false, false, false, setOf(2, 1)))
    }

    @Test fun fixedSequenceQueries() {
        check("battery", Commands.queryBattery())
        check("wearing", Commands.queryWearingStatus())
    }

    @Test fun reads() {
        check("devices", Commands.queryDevices())
        check("eq", Commands.queryEq())
        check("eq all", Commands.queryEqAll())
        check("bass", Commands.queryBassWaveLevel())
        check("alert", Commands.queryAlertVolume())
        check("tap", Commands.queryTapLevel())
        check("head", Commands.queryHeadMotionType())
        check("spatial", Commands.querySpatialType())
        check("codec", Commands.queryCodec())
        check("codec list", Commands.queryCodecList())
        check("game sound", Commands.queryGameSound())
        check("personal anc", Commands.queryPersonalNoise())
        check("gestures", Commands.queryKeyFunction())
        check("golden record", HearingCommands.queryGoldenActive())
        check("golden scan", HearingCommands.queryGoldenActiveScan())
        check("preferred", Commands.queryPreferred())
        check("anc mode", Commands.queryAncMode())
        check("smart level", Commands.querySmartLevel())
        for (t in listOf(OpoProtocol.HOLD_TYPE_SHARED, OpoProtocol.HOLD_TYPE_LEFT, OpoProtocol.HOLD_TYPE_RIGHT))
            check("hold modes $t", Commands.queryNoiseSwitchModes(t))
    }

    @Test fun status() {
        check("default list", Commands.queryStatus())
        check("wind list", Commands.queryStatus(null, true))
        check("model list", Commands.queryStatus("05 04 0B"))
    }

    @Test fun anc() {
        for (bit in listOf(0, 1, 2, 4, 5, 6, 11)) check("anc $bit", Commands.anc(bit))
        for ((kind, mask) in listOf(1 to 3, 1 to 0x0807, 3 to 1, 4 to 0x0100, 1 to 0x01020304, 1 to 0))
            check("hold $kind $mask", Commands.holdAncModes(mask, kind))
    }

    @Test fun featureSwitches() {
        for (id in listOf(0x06, 0x0B, 0x11, 0x1B, 0x1D, 0x28)) for (on in listOf(true, false))
            check("feature $id $on", Commands.feature(id, on))
    }

    @Test fun gestures() {
        val e = listOf(KeyFunctionParser.Entry(1, 1, 2, 0x11), KeyFunctionParser.Entry(4, 6, 6, 0x1C), KeyFunctionParser.Entry(2, 3, 1, 0x05))
        check("table", Commands.setKeyFunction(e))
        check("one entry", Commands.setKeyFunction(e.drop(1).take(1)))
    }

    @Test fun eq() {
        val p = EqCodec.Preset(5, "Mine", listOf(62, 250, 1000, 4000), listOf(-6, 0, 3, 6), false, EqCodec.DEFAULT_TAG)
        for (a in listOf(EqCodec.ACTION_CREATE, EqCodec.ACTION_SAVE, EqCodec.ACTION_DELETE))
            check("eq $a", Commands.customEq(a, p))
        val n = EqCodec.newPreset("Ünï", listOf(100, 200, 300))
        check("new", Commands.customEq(EqCodec.ACTION_CREATE, n))
        for (l in -5..5) check("bass $l", Commands.setBassWaveLevel(l))
        for (b in 1..3) check("bass band $b", Commands.setBassWaveLevel(4, b))
        check("eq select 2", Commands.setBuiltInEq(2))
    }

    @Test fun alertVolumeClampsTo1To10() {
        for ((l, b) in listOf(-1 to 1, 0 to 1, 1 to 1, 10 to 10, 11 to 10, 255 to 10)) {
            assertEquals("alert $l", listOf(b.toByte()), ProtocolCases.packet(Commands.alertVolume(l)).payload.toList())
        }
    }

    @Test fun settings() {
        for (l in listOf(-1, 0, 1, 6, 10, 11)) check("alert $l", Commands.alertVolume(l))
        for (l in 1..5) check("tap $l", Commands.setTapLevel(l))
        for (t in 0..1) check("head $t", Commands.headMotionType(t))
        for (t in 0..2) check("spatial $t", Commands.spatialType(t))
        for (c in 1..8) for (h in listOf(true, false)) check("codec $c $h", Commands.codec(c, h))
        for (t in 0..3) check("game sound $t", Commands.gameSoundType(t))
        for (g in listOf(true, false)) {
            check("game eq $g", Commands.gameEqState(g))
            check("find $g", Commands.findTone(g))
            check("fit $g", Commands.fitTest(g))
        }
        for (a in 1..3) check("personal $a", Commands.personalNoise(a))
    }

    @Test fun dualConnection() {
        val s = "0A:1B:2C:3D:4E:5F"
        for (on in listOf(true, false)) {
            check("connect $on", Commands.connectDevice(s, on))
            check("followup $on", Commands.dualFollowup(on))
        }
        check("preferred set", Commands.setPreferred(s))
        check("preferred auto", Commands.setPreferred(null))
    }

    @Test fun goldenSound() {
        val v = IntArray(12) { -it * 5 }
        val scan = ByteArray(168) { it.toByte() }
        for (on in listOf(true, false)) {
            check("scan $on", HearingCommands.earScan(on, 0x01020304))
            check("test $on", HearingCommands.hearingTest(on))
        }
        check("tone", HearingCommands.hearingTone(2, 3, -30))
        check("tone stop", HearingCommands.hearingToneStop())
        check("filter", HearingCommands.hearingFilter(7, v))
        check("scan filter", HearingCommands.earScanFilter(7, scan))
        check("restore", HearingCommands.hearingRestore(11))
        check("record", HearingCommands.hearingRecord(0x01020304, "2026/09/29 01:53", v))
        check("scan data", HearingCommands.earScanData(0x01020304, scan))
        check("golden switch", Commands.feature(Feature.GOLDEN_SOUND, true))
    }

    private companion object {
        val GOLDEN = mapOf(
        "handshake" to (0x0100 to ""),
        "product id" to (0x0103 to ""),
        "broadcast" to (0x0200 to ""),
        "firmware" to (0x0105 to ""),
        "register" to (0x0205 to "06 01 02 03 04 08 0B"),
        "register 1 2 3" to (0x0205 to "03 01 02 03"),
        "registerOne" to (0x0201 to "04"),
        "spatialOnBuds" to (0x041E to "00"),
        "battery" to (0x0106 to ""),
        "wearing" to (0x0109 to ""),
        "devices" to (0x0112 to ""),
        "eq" to (0x010F to ""),
        "eq all" to (0x0122 to ""),
        "bass" to (0x0124 to ""),
        "alert" to (0x0130 to ""),
        "tap" to (0x0133 to ""),
        "head" to (0x0134 to ""),
        "spatial" to (0x012A to ""),
        "codec" to (0x0114 to ""),
        "codec list" to (0x0123 to ""),
        "game sound" to (0x012B to ""),
        "personal anc" to (0x011A to ""),
        "gestures" to (0x0108 to ""),
        "golden record" to (0x0115 to ""),
        "golden scan" to (0x011E to ""),
        "preferred" to (0x0132 to "02"),
        "anc mode" to (0x010C to "01 01"),
        "smart level" to (0x010C to "04 01"),
        "hold modes 1" to (0x010C to "02 01"),
        "hold modes 3" to (0x010C to "02 03"),
        "hold modes 4" to (0x010C to "02 04"),
        "default list" to (0x010D to "17 05 04 0B 11 13 18 06 1B 1C 27 28 1D 09 17 30 31 3A 32 35 37 38 3B 0C"),
        "wind list" to (0x010D to "18 05 04 0B 11 13 18 06 1B 1C 27 28 1D 09 17 30 31 3A 32 35 37 38 3B 0C 1A"),
        "model list" to (0x010D to "03 05 04 0B"),
        "anc 0" to (0x0404 to "01 01 01"),
        "anc 1" to (0x0404 to "01 01 02"),
        "anc 2" to (0x0404 to "01 01 04"),
        "anc 4" to (0x0404 to "01 01 10"),
        "anc 5" to (0x0404 to "01 01 20"),
        "anc 6" to (0x0404 to "01 01 40"),
        "anc 11" to (0x0404 to "01 01 00 08"),
        "hold 1 3" to (0x0404 to "02 01 03"),
        "hold 1 2055" to (0x0404 to "02 01 07 08"),
        "hold 3 1" to (0x0404 to "02 03 01"),
        "hold 4 256" to (0x0404 to "02 04 00 01"),
        "hold 1 16909060" to (0x0404 to "02 01 04 03 02 01"),
        "hold 1 0" to (0x0404 to "02 01 00"),
        "feature 6 true" to (0x0403 to "06 01"),
        "feature 6 false" to (0x0403 to "06 00"),
        "feature 11 true" to (0x0403 to "0B 01"),
        "feature 11 false" to (0x0403 to "0B 00"),
        "feature 17 true" to (0x0403 to "11 01"),
        "feature 17 false" to (0x0403 to "11 00"),
        "feature 27 true" to (0x0403 to "1B 01"),
        "feature 27 false" to (0x0403 to "1B 00"),
        "feature 29 true" to (0x0403 to "1D 01"),
        "feature 29 false" to (0x0403 to "1D 00"),
        "feature 40 true" to (0x0403 to "28 01"),
        "feature 40 false" to (0x0403 to "28 00"),
        "table" to (0x0401 to "03 01 01 02 11 04 06 06 1C 02 03 01 05"),
        "one entry" to (0x0401 to "01 04 06 06 1C"),
        "eq 1" to (0x0418 to "01 FA 06 05 04 4D 69 6E 65 04 3E 00 FA FA 00 00 E8 03 03 A0 0F 06"),
        "eq 2" to (0x0418 to "02 FA 06 05 04 4D 69 6E 65 04 3E 00 FA FA 00 00 E8 03 03 A0 0F 06"),
        "eq 3" to (0x0418 to "03 FA 06 05 04 4D 69 6E 65 04 3E 00 FA FA 00 00 E8 03 03 A0 0F 06"),
        "new" to (0x0418 to "01 FA 06 00 05 C3 9C 6E C3 AF 03 64 00 00 C8 00 00 2C 01 00"),
        "bass -5" to (0x041B to "FB 05 FB"),
        "bass -4" to (0x041B to "FB 05 FC"),
        "bass -3" to (0x041B to "FB 05 FD"),
        "bass -2" to (0x041B to "FB 05 FE"),
        "bass -1" to (0x041B to "FB 05 FF"),
        "bass 0" to (0x041B to "FB 05 00"),
        "bass 1" to (0x041B to "FB 05 01"),
        "bass 2" to (0x041B to "FB 05 02"),
        "bass 3" to (0x041B to "FB 05 03"),
        "bass 4" to (0x041B to "FB 05 04"),
        "bass 5" to (0x041B to "FB 05 05"),
        "bass band 1" to (0x041B to "FB 05 04 01"),
        "bass band 2" to (0x041B to "FB 05 04 02"),
        "bass band 3" to (0x041B to "FB 05 04 03"),
        "eq select 2" to (0x0406 to "02"),
        "alert -1" to (0x0427 to "01"),
        "alert 0" to (0x0427 to "01"),
        "alert 1" to (0x0427 to "01"),
        "alert 6" to (0x0427 to "06"),
        "alert 10" to (0x0427 to "0A"),
        "alert 11" to (0x0427 to "0A"),
        "tap 1" to (0x042D to "01"),
        "tap 2" to (0x042D to "02"),
        "tap 3" to (0x042D to "03"),
        "tap 4" to (0x042D to "04"),
        "tap 5" to (0x042D to "05"),
        "head 0" to (0x0431 to "00"),
        "head 1" to (0x0431 to "01"),
        "spatial 0" to (0x0422 to "00"),
        "spatial 1" to (0x0422 to "01"),
        "spatial 2" to (0x0422 to "02"),
        "codec 1 true" to (0x041A to "01 00 00"),
        "codec 1 false" to (0x041A to "01 00 00"),
        "codec 2 true" to (0x041A to "02 00 00"),
        "codec 2 false" to (0x041A to "02 00 00"),
        "codec 3 true" to (0x041A to "03 01 00"),
        "codec 3 false" to (0x041A to "03 00 00"),
        "codec 4 true" to (0x041A to "04 00 00"),
        "codec 4 false" to (0x041A to "04 00 00"),
        "codec 5 true" to (0x041A to "05 00 00"),
        "codec 5 false" to (0x041A to "05 00 00"),
        "codec 6 true" to (0x041A to "06 00 00"),
        "codec 6 false" to (0x041A to "06 00 00"),
        "codec 7 true" to (0x041A to "07 00 00"),
        "codec 7 false" to (0x041A to "07 00 00"),
        "codec 8 true" to (0x041A to "08 01 00"),
        "codec 8 false" to (0x041A to "08 00 00"),
        "game sound 0" to (0x0423 to "00 01"),
        "game sound 1" to (0x0423 to "01 01"),
        "game sound 2" to (0x0423 to "02 01"),
        "game sound 3" to (0x0423 to "03 01"),
        "game eq true" to (0x0420 to "01 01"),
        "find true" to (0x0400 to "01"),
        "fit true" to (0x0405 to "01"),
        "game eq false" to (0x0420 to "01 00"),
        "find false" to (0x0400 to "00"),
        "fit false" to (0x0405 to "00"),
        "personal 1" to (0x0412 to "01"),
        "personal 2" to (0x0412 to "02"),
        "personal 3" to (0x0412 to "03"),
        "connect true" to (0x0429 to "01 0A 1B 2C 3D 4E 5F"),
        "followup true" to (0x0413 to "08 00 00"),
        "connect false" to (0x0429 to "02 0A 1B 2C 3D 4E 5F"),
        "followup false" to (0x0413 to "08 00 01"),
        "preferred set" to (0x0429 to "04 01 0A 1B 2C 3D 4E 5F"),
        "preferred auto" to (0x0429 to "04 00"),
        "scan true" to (0x040D to "04 01 01 02 03 04"),
        "test true" to (0x040D to "02 01"),
        "scan false" to (0x040D to "04 00 01 02 03 04"),
        "test false" to (0x040D to "02 00"),
        "tone" to (0x040E to "03 01 02 03 E2"),
        "tone stop" to (0x040E to "04"),
        "filter" to (0x0116 to "0C 01 01 00 01 02 FB 01 03 F6 01 04 F1 01 05 EC 01 06 E7 02 01 E2 02 02 DD 02 03 D8 02 04 D3 02 05 CE 02 06 C9 00 00 00 07"),
        "scan filter" to (0x011F to "A8 00 00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12 13 14 15 16 17 18 19 1A 1B 1C 1D 1E 1F 20 21 22 23 24 25 26 27 28 29 2A 2B 2C 2D 2E 2F 30 31 32 33 34 35 36 37 38 39 3A 3B 3C 3D 3E 3F 40 41 42 43 44 45 46 47 48 49 4A 4B 4C 4D 4E 4F 50 51 52 53 54 55 56 57 58 59 5A 5B 5C 5D 5E 5F 60 61 62 63 64 65 66 67 68 69 6A 6B 6C 6D 6E 6F 70 71 72 73 74 75 76 77 78 79 7A 7B 7C 7D 7E 7F 80 81 82 83 84 85 86 87 88 89 8A 8B 8C 8D 8E 8F 90 91 92 93 94 95 96 97 98 99 9A 9B 9C 9D 9E 9F A0 A1 A2 A3 A4 A5 A6 A7 00 00 00 07"),
        "restore" to (0x0411 to "01 01 01 00 0B"),
        "record" to (0x040E to "03 0C 01 01 00 01 02 FB 01 03 F6 01 04 F1 01 05 EC 01 06 E7 02 01 E2 02 02 DD 02 03 D8 02 04 D3 02 05 CE 02 06 C9 01 02 03 04 32 30 32 36 2F 30 39 2F 32 39 20 30 31 3A 35 33"),
        "scan data" to (0x0415 to "03 A8 00 00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12 13 14 15 16 17 18 19 1A 1B 1C 1D 1E 1F 20 21 22 23 24 25 26 27 28 29 2A 2B 2C 2D 2E 2F 30 31 32 33 34 35 36 37 38 39 3A 3B 3C 3D 3E 3F 40 41 42 43 44 45 46 47 48 49 4A 4B 4C 4D 4E 4F 50 51 52 53 54 55 56 57 58 59 5A 5B 5C 5D 5E 5F 60 61 62 63 64 65 66 67 68 69 6A 6B 6C 6D 6E 6F 70 71 72 73 74 75 76 77 78 79 7A 7B 7C 7D 7E 7F 80 81 82 83 84 85 86 87 88 89 8A 8B 8C 8D 8E 8F 90 91 92 93 94 95 96 97 98 99 9A 9B 9C 9D 9E 9F A0 A1 A2 A3 A4 A5 A6 A7 01 02 03 04"),
        "golden switch" to (0x0403 to "0B 01"),
        )
    }
}
