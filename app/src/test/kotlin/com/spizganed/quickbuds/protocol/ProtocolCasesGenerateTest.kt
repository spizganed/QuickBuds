package com.spizganed.quickbuds.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Writes `testdata/protocol_cases.json` from the protocol layer. It is skipped unless
 * `-Dqb.generate=true` is set, so a normal test run only reads the frozen file.
 */
class ProtocolCasesGenerateTest {

    @Test fun generate() {
        Assume.assumeTrue(
            "set -Dqb.generate=true to write testdata/protocol_cases.json",
            System.getProperty("qb.generate") == "true"
        )
        val seeds = seeds()
        val file = File("../testdata/protocol_cases.json")
        file.parentFile?.mkdirs()
        write(seeds, file)

        println("PROTOCOL CASES: ${seeds.size}")
        for ((kind, n) in seeds.groupingBy { it.kind }.eachCount().toSortedMap()) {
            println("  kind $kind: $n")
        }
        for ((topic, n) in seeds.groupingBy { it.topic }.eachCount().toSortedMap()) {
            println("  topic $topic: $n")
        }
        println("WROTE: ${file.absolutePath}")
    }

    /** Writes the keys in the documented order; org.json alone keeps them in hash order. */
    private fun write(seeds: List<Seed>, file: File) {
        val sb = StringBuilder()
        sb.append("{\n \"version\": 1,\n \"cases\": [\n")
        seeds.forEachIndexed { i, seed ->
            val c = seed.case()
            sb.append("  {\n")
            field(sb, "id", JSONObject.quote(c.getString("id")), true)
            field(sb, "topic", JSONObject.quote(c.getString("topic")), true)
            field(sb, "kind", JSONObject.quote(c.getString("kind")), true)
            field(sb, "input", pretty(c.get("input")), true)
            field(sb, "model", pretty(c.get("model")), true)
            if (c.has("clock")) field(sb, "clock", c.get("clock").toString(), true)
            field(sb, "expect", pretty(c.get("expect")), true)
            field(sb, "evidence", JSONObject.quote(c.getString("evidence")), true)
            field(sb, "source", JSONObject.quote(c.getString("source")), false)
            sb.append("  }").append(if (i == seeds.lastIndex) "\n" else ",\n")
        }
        sb.append(" ]\n}\n")
        file.writeText(sb.toString())
    }

    private fun field(sb: StringBuilder, name: String, value: String, comma: Boolean) {
        sb.append("   ").append(JSONObject.quote(name)).append(": ").append(value)
        if (comma) sb.append(',')
        sb.append('\n')
    }

    private fun pretty(value: Any?): String = when {
        value == null || value == JSONObject.NULL -> "null"
        value is String -> JSONObject.quote(value)
        value is JSONObject -> value.toString(1).replace("\n", "\n   ")
        value is JSONArray -> value.toString(1).replace("\n", "\n   ")
        else -> value.toString()
    }

    private data class Seed(
        val id: String,
        val topic: String,
        val kind: String,
        val input: Any,
        val evidence: String,
        val source: String,
        val model: String? = null,
        val clock: Long? = null
    ) {
        fun case(): JSONObject {
            val c = JSONObject()
                .put("id", id)
                .put("topic", topic)
                .put("kind", kind)
                .put("input", input)
                .put("model", model ?: JSONObject.NULL)
            if (clock != null) c.put("clock", clock)
            c.put("expect", ProtocolCases.actual(c) ?: JSONObject.NULL)
            c.put("evidence", evidence)
            c.put("source", source)
            return c
        }
    }

    private fun seeds(): List<Seed> = listOf(
        // --- frame: whole, split, noise, bad length, slices, over 127 bytes ---
        frame("frame-battery-01", "battery", one(BATTERY_01), "CAPTURE", LOG_14 + ":37"),
        frame("frame-handshake-01", "handshake", one(HANDSHAKE_09), "CAPTURE", "docs/PROTOCOL.md"),
        frame("frame-split-01", "battery", split(BATTERY_01, 5), "TEST-LITERAL", PROTOCOL),
        frame("frame-multi-01", "wire", concat(BATTERY_01, WEARING_01), "TEST-LITERAL", PROTOCOL),
        frame("frame-noise-01", "wire", concat("13 37", BATTERY_01), "TEST-LITERAL", PROTOCOL),
        frame("frame-badlen-01", "wire", concat("AA 80 80 80", BATTERY_01), "TEST-LITERAL", FRAMER_TEST + ":36"),
        frame("frame-badlen-02", "wire", one(WIRE_02), "TEST-LITERAL", WIRE_TEST + ":52"),
        frame("frame-badlen-03", "wire", one(WIRE_07), "TEST-LITERAL", MODEL_TEST + ":74"),
        frame("frame-shortpay-01", "wire", one(WIRE_03), "TEST-LITERAL", WIRE_TEST + ":53"),
        frame("frame-slice-01", "wire", slices(), "TEST-LITERAL", FRAMER_TEST + ":20"),
        frame("frame-slice-02", "wire", chunksOf(slice(3, 2, ByteArray(10))), "TEST-LITERAL", FRAMER_TEST + ":30"),
        frame("frame-over127-01", "other", one(OTHER_06), "CAPTURE", LOG_14 + ":259"),
        frame("frame-over127-02", "wire", one(packetHex(0x0418, 3, ByteArray(124) { it.toByte() })), "TEST-LITERAL", WIRE_TEST + ":14"),

        // --- decode: battery 0x8106 and push 01 ---
        decode("decode-battery-01", "battery", BATTERY_01, "CAPTURE", LOG_14 + ":37"),
        decode("decode-battery-02", "battery", BATTERY_03, "CAPTURE", LOG_14 + ":62"),
        decode("decode-battery-03", "battery", packetHex(0x8106, 0xF0, ProtocolCases.bytes("00 03 01 64 02 64 03 D0")), "TEST-LITERAL", PROTOCOL),
        decode("decode-battery-04", "battery", packetHex(0x0204, 0xFF, ProtocolCases.bytes("01 03 01 5A 02 5A 03 00")), "TEST-LITERAL", PROTOCOL),
        decode("decode-battery-05", "battery", WIRE_03, "TEST-LITERAL", WIRE_TEST + ":53"),
        decode("decode-battery-06", "battery", packetHex(0x8106, 0xF0, ProtocolCases.bytes("00 00")), "TEST-LITERAL", PROTOCOL),
        decode("decode-battery-07", "battery", WIRE_06, "TEST-LITERAL", "desktop/src/protocol.rs:553"),

        // --- decode: wearing 0x8109 and push 02 ---
        decode("decode-wearing-01", "wearing", WEARING_01, "CAPTURE", LOG_14 + ":15"),
        decode("decode-wearing-02", "wearing", WEARING_03, "CAPTURE", LOG_14 + ":67"),
        decode("decode-wearing-03", "wearing", WEARING_04, "CAPTURE", LOG_14 + ":226"),
        decode("decode-wearing-04", "wearing", packetHex(0x8109, 0x11, ProtocolCases.bytes("00 03 01 07 02 07 03 04")), "TEST-LITERAL", PROTOCOL),

        // --- decode: ANC push 03 ---
        decode("decode-anc-01", "anc", ANC_02, "CAPTURE", LOG_14 + ":40"),
        decode("decode-anc-02", "anc", ANC_04, "CAPTURE", LOG_14 + ":44"),
        decode("decode-anc-03", "anc", ANC_06, "CAPTURE", LOG_14 + ":60"),
        decode("decode-anc-04", "anc", packetHex(0x0204, 0xFF, ProtocolCases.bytes("03 02 01 07 08")), "TEST-LITERAL", PROTOCOL),

        // --- decode: game mode push 05, with the per-model byte-3 rule ---
        decode("decode-gamemode-01", "gamemode", GAMEMODE_02, "CAPTURE", LOG_14 + ":505"),
        decode("decode-gamemode-02", "gamemode", packetHex(0x0204, 0xFF, ProtocolCases.bytes("05 01 00 00")), "TEST-LITERAL", PROTOCOL, "062410"),
        decode("decode-gamemode-03", "gamemode", packetHex(0x0204, 0xFF, ProtocolCases.bytes("05 01 00 00")), "TEST-LITERAL", PROTOCOL, "065414"),
        decode("decode-gamemode-04", "gamemode", packetHex(0x0204, 0xFF, ProtocolCases.bytes("05 00 00 01")), "TEST-LITERAL", PROTOCOL, "062410"),

        // --- decode: gesture table 0x8108 and F1 push ---
        decode("decode-gesture-01", "gesture", GESTURE_09, "CAPTURE", LOG_20 + ":801"),
        decode("decode-gesture-02", "gesture", GESTURE_11, "CAPTURE", LOG_20 + ":810"),
        decode("decode-gesture-03", "gesture", packetHex(0x8108, 0x12, ProtocolCases.bytes("00 02 01 01 01")), "TEST-LITERAL", PROTOCOL),
        decode("decode-gesture-04", "gesture", GESTURE_01, "CAPTURE", LOG_14 + ":52"),
        decode("decode-gesture-05", "gesture", GESTURE_04, "CAPTURE", LOG_14 + ":63"),
        decode("decode-gesture-06", "gesture", GESTURE_06, "CAPTURE", LOG_14 + ":449"),
        decode("decode-gesture-07", "gesture", packetHex(0x8108, 0x14, ByteArray(0)), "TEST-LITERAL", PROTOCOL),

        // --- decode: custom EQ list 0x8122 and batch reply 0x812F ---
        decode("decode-eq-01", "eq", EQ_04, "CAPTURE", LOG_14 + ":132"),
        decode("decode-eq-02", "eq", packetHex(0x8122, 0x13, ProtocolCases.bytes("00 01 01")), "TEST-LITERAL", PROTOCOL),
        decode("decode-eq-03", "eq", packetHex(0x8122, 0x13, ByteArray(0)), "TEST-LITERAL", PROTOCOL),
        decode("decode-batch-01", "reply", REPLY_11, "CAPTURE", LOG_14 + ":17"),

        // --- decode: feature push 0x0503 ---
        decode("decode-featurepush-01", "featurepush", FEATUREPUSH_01, "TEST-LITERAL", FEATURE_PUSH_TEST + ":13"),
        decode("decode-featurepush-02", "featurepush", FEATUREPUSH_02, "TEST-LITERAL", FEATURE_PUSH_TEST + ":37"),
        decode("decode-featurepush-03", "featurepush", FEATUREPUSH_03, "TEST-LITERAL", FEATURE_PUSH_TEST + ":20"),
        decode("decode-featurepush-04", "featurepush", packetHex(0x0503, 0xFF, ProtocolCases.bytes("02 06")), "TEST-LITERAL", FEATURE_PUSH + ":20"),

        // --- reply: requests from the buds ---
        reply("reply-time-01", OTHER_03, "CAPTURE", LOG_14 + ":75", 1750000000L),
        reply("reply-applist-01", APPLIST_REQ, "TEST-LITERAL", PROTOCOL),
        reply("reply-capabilities-01", CAPABILITIES_REQ, "TEST-LITERAL", PROTOCOL),
        reply("reply-spatial-01", SPATIAL_REQ, "TEST-LITERAL", PROTOCOL),
        reply("reply-f2-01", F2_REQ, "TEST-LITERAL", PROTOCOL),
        reply("reply-f4-01", F4_REQ, "TEST-LITERAL", PROTOCOL),
        reply("reply-unknown-01", UNKNOWN_REQ, "TEST-LITERAL", PROTOCOL),
        reply("reply-known-01", KNOWN_REQ, "TEST-LITERAL", PROTOCOL),
        reply("reply-already-01", ALREADY_REPLY_REQ, "TEST-LITERAL", PROTOCOL),

        // --- build: connection and subscriptions ---
        build("build-handshake-01", "handshake", "handshake", args()),
        build("build-product-01", "handshake", "productId", args()),
        build("build-broadcast-01", "handshake", "broadcastCodes", args()),
        build("build-register-01", "handshake", "registerNotifications", args("ids" to JSONArray(listOf(1, 2, 3)))),
        build("build-register-one-01", "handshake", "registerOne", args("id" to 4)),

        // --- build: reads with a fixed sequence number ---
        build("build-battery-01", "battery", "queryBattery", args()),
        build("build-wearing-01", "wearing", "queryWearingStatus", args()),
        build("build-status-01", "status810D", "queryStatus", args("statusQuery" to "05 04 0B 11 18 06 1B 1D 1C", "windNoise" to false, "seq" to 0)),
        build("build-status-02", "status810D", "queryStatus", args("statusQuery" to null, "windNoise" to true, "seq" to 0)),

        // --- build: writes and feature switches ---
        build("build-feature-01", "other", "setFeature", args("id" to 0x18, "on" to false, "seq" to 0x69)),
        build("build-feature-game-01", "gamemode", "setFeature", args("id" to 0x06, "on" to true, "seq" to 0x45)),
        build("build-anc-01", "anc", "anc", args("bit" to 5)),
        build("build-anc-02", "anc", "anc", args("bit" to 11)),
        build("build-hold-01", "anc", "setHoldAncModes", args("mask" to 0x0807, "type" to 1)),
        build("build-find-01", "other", "findTone", args("on" to true)),
        build("build-fit-01", "other", "fitTest", args("on" to true)),
        build("build-alert-01", "other", "setAlertVolume", args("level" to 0)),
        build("build-eq-builtin-01", "eq", "setBuiltInEq", args("id" to 0)),
        build("build-bass-01", "eq", "setBassWaveLevel", args("level" to 2)),
        build("build-eq-custom-01", "eq", "customEq", args("action" to EqCodec.ACTION_SAVE, "preset" to presetJson())),
        build("build-oncall-01", "gesture", "setOnCall", args("act" to OpoProtocol.ON_CALL_ACT_DOUBLE_TAP, "fn" to OpoProtocol.ON_CALL_FN_ANSWER_END)),
        build("build-keyfunction-01", "gesture", "setKeyFunction", args("entries" to gestureEntries())),
        build("build-preferred-01", "other", "setPreferred", args("mac" to null)),
        build("build-dual-01", "other", "dualFollowup", args("dualOn" to true)),
        build("build-earscan-01", "other", "earScan", args("on" to true, "uid" to 0x7C076D50)),
        build("build-tone-01", "other", "hearingTone", args("side" to 1, "freq" to 1, "value" to -30)),
        build("build-hearingtest-01", "other", "hearingTest", args("on" to true)),
        build("build-gamesound-01", "other", "setGameSoundType", args("type" to 0)),
        build("build-spatial-01", "other", "setSpatialType", args("type" to 2)),
        build("build-codec-01", "other", "setCodec", args("codec" to 3, "hiRes" to true)),
        build("build-tap-01", "other", "setTapLevel", args("level" to 3)),
        build("build-head-01", "other", "setHeadMotionType", args("type" to 1)),
        build("build-personal-01", "other", "personalNoise", args("action" to OpoProtocol.PERSONAL_NOISE_TEST)),
        build("build-gameeq-01", "other", "gameEqState", args("inGame" to true)),
        build("build-query-key-01", "gesture", "queryKeyFunction", args()),
        build("build-query-anc-01", "anc", "queryAncMode", args()),
        build("build-query-smart-01", "anc", "querySmartLevel", args())
    )

    // --- seed helpers ---

    private fun frame(id: String, topic: String, input: JSONArray, evidence: String, source: String) =
        Seed(id, topic, "frame", input, evidence, source)

    private fun decode(
        id: String,
        topic: String,
        hex: String,
        evidence: String,
        source: String,
        model: String? = null
    ) = Seed(id, topic, "decode", hex, evidence, source, model)

    private fun reply(id: String, hex: String, evidence: String, source: String, clock: Long? = null) =
        Seed(id, "reply", "reply", hex, evidence, source, clock = clock)

    private fun build(id: String, topic: String, builder: String, input: JSONObject) =
        Seed(id, topic, "build", JSONObject().put("builder", builder).put("args", input), "TEST-LITERAL", PROTOCOL)

    private fun args(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        for ((key, value) in pairs) put(key, value ?: JSONObject.NULL)
    }

    private fun presetJson(): JSONObject = JSONObject()
        .put("id", 4)
        .put("name", "GoodEQ")
        .put("freqs", JSONArray(listOf(62, 250, 1000, 4000, 8000, 16000)))
        .put("gains", JSONArray(listOf(-1, -1, -2, 1, 2, 5)))
        .put("selected", true)
        .put("tag", "FA 06")

    private fun gestureEntries(): JSONArray {
        val table = KeyFunctionParser.parse(ProtocolCases.packet(ProtocolCases.bytes(GESTURE_09)).payload)
        return JSONArray(table.entries.map { JSONArray(listOf(it.deviceType, it.button, it.action, it.function)) })
    }

    private fun one(hex: String) = chunksOf(ProtocolCases.bytes(hex))

    private fun packetHex(cmd: Int, seq: Int, payload: ByteArray) =
        ProtocolCases.hex(OpoProtocol.buildPacket(cmd, seq = seq, payload = payload))

    private fun chunksOf(vararg parts: ByteArray): JSONArray =
        JSONArray(parts.map { ProtocolCases.hex(it) })

    private fun concat(vararg parts: String): JSONArray =
        chunksOf(parts.map { ProtocolCases.bytes(it) }.reduce { a, b -> a + b })

    private fun split(hex: String, at: Int): JSONArray {
        val b = ProtocolCases.bytes(hex)
        return chunksOf(b.copyOfRange(0, at), b.copyOfRange(at, b.size))
    }

    private fun slice(kind: Int, fsn: Int, data: ByteArray): ByteArray {
        val totalLen = 3 + data.size
        require(totalLen < 128) { "slice data too long: ${data.size}" }
        return byteArrayOf(0xAA.toByte(), totalLen.toByte(), kind.toByte(), 0, fsn.toByte()) + data
    }

    private fun slices(): JSONArray {
        val payload = ByteArray(300) { it.toByte() }
        val msg = ProtocolCases.bytes("15 81 04 2C 01") + payload
        return chunksOf(
            slice(1, 0, msg.copyOfRange(0, 100)),
            slice(2, 1, msg.copyOfRange(100, 200)),
            slice(3, 2, msg.copyOfRange(200, msg.size))
        )
    }

    private companion object {
        private const val PROTOCOL = "app/src/main/kotlin/com/spizganed/quickbuds/protocol/Protocol.kt"
        private const val FEATURE_PUSH = "app/src/main/kotlin/com/spizganed/quickbuds/protocol/FeaturePush.kt"
        private const val FEATURE_PUSH_TEST = "app/src/test/kotlin/com/spizganed/quickbuds/protocol/FeaturePushTest.kt"
        private const val FRAMER_TEST = "app/src/test/kotlin/com/spizganed/quickbuds/protocol/FramerTest.kt"
        private const val WIRE_TEST = "app/src/test/kotlin/com/spizganed/quickbuds/protocol/WireTest.kt"
        private const val MODEL_TEST = "app/src/test/kotlin/com/spizganed/quickbuds/ui/ModelTestJsonTest.kt"
        private const val LOG_14 = "local/logs/buds4_session_20261014.log.txt"
        private const val LOG_20 = "local/logs/buds4_session_20261020.log.txt"

        private const val BATTERY_01 = "AA 0D 00 00 04 02 FF 06 00 01 02 01 64 02 64"
        private const val BATTERY_03 = "AA 0F 00 00 04 02 FF 08 00 01 03 01 5A 02 5A 03 50"
        private const val WEARING_01 = "AA 0F 00 00 04 02 0C 08 00 02 03 01 07 02 07 03 04"
        private const val WEARING_03 = "AA 0F 00 00 04 02 FF 08 00 02 03 01 00 02 00 03 00"
        private const val WEARING_04 = "AA 0F 00 00 04 02 FF 08 00 02 03 01 05 02 07 03 04"
        private const val HANDSHAKE_09 = "AA 07 00 00 00 01 01 00 00"
        private const val ANC_02 = "AA 0C 00 00 04 02 FF 05 00 03 01 01 00 01"
        private const val ANC_04 = "AA 0C 00 00 04 02 FF 05 00 03 01 01 20 00"
        private const val ANC_06 = "AA 0C 00 00 04 02 FF 05 00 03 01 01 08 00"
        private const val GAMEMODE_02 = "AA 09 00 00 04 02 FF 02 00 05 01"
        private const val REPLY_11 = "AA 1F 00 00 2F 81 7F 18 00 00 01 0D 01 12 00 00 08 05 00 04 00 0B 00 11 01 18 01 06 00 1B 00 1D 01"
        private const val GESTURE_01 = "AA 0D 00 00 04 02 FF 06 00 F1 01 01 07 0B 02"
        private const val GESTURE_04 = "AA 0D 00 00 04 02 FF 06 00 F1 01 01 00 00 03"
        private const val GESTURE_06 = "AA 0D 00 00 04 02 FF 06 00 F1 02 01 04 08 03"
        private const val GESTURE_09 = "AA 51 00 00 08 81 C9 4A 00 00 12 01 01 01 01 01 01 02 01 01 01 03 00 01 01 04 08 01 01 06 00 01 01 05 07 02 01 01 00 02 01 02 01 02 01 03 00 02 01 04 08 02 01 06 00 02 01 05 07 01 06 02 00 01 06 03 00 01 06 06 00 02 06 02 00 02 06 03 00 02 06 06 00"
        private const val GESTURE_11 = "AA 51 00 00 08 81 CB 4A 00 00 12 01 01 01 00 01 01 02 01 01 01 03 00 01 01 04 08 01 01 06 00 01 01 05 07 02 01 01 00 02 01 02 01 02 01 03 00 02 01 04 08 02 01 06 00 02 01 05 07 01 06 02 00 01 06 03 00 01 06 06 00 02 06 02 00 02 06 03 00 02 06 06 00"
        private const val EQ_04 = "AA 46 00 00 22 81 A6 3F 00 00 02 01 FA 06 04 06 47 6F 6F 64 45 51 06 3E 00 FF FA 00 FF E8 03 FE A0 0F 01 40 1F 02 80 3E 05 00 FA 06 05 07 43 75 73 74 6F 6D 31 06 3E 00 00 FA 00 00 E8 03 00 A0 0F 00 40 1F 00 80 3E 00"
        private const val FEATUREPUSH_01 = "AA 0A 00 00 03 05 FF 03 00 01 06 01"
        private const val FEATUREPUSH_02 = "AA 0B 00 00 03 05 FF 04 00 01 1B 01 30"
        private const val FEATUREPUSH_03 = "AA 2A 00 00 03 05 FF 23 00 11 00 00 01 00 02 00 04 01 05 00 06 01 08 00 09 00 0B 00 0C 00 0D 00 10 00 11 00 18 00 1A 01 1B 00 1D 00"
        private const val OTHER_03 = "AA 07 00 00 00 05 02 00 00"
        private const val OTHER_06 = "AA B5 01 00 00 1F 01 D5 AE 00 A8 00 00 00 00 00 00 00 00 00 03 04 A5 00 01 00 00 00 40 00 A0 8C 44 00 00 00 40 01 00 00 00 C0 00 40 1C 45 00 00 00 40 01 00 00 00 40 00 E8 FD 45 5D 60 55 40 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01 00 00 00 40 00 A0 8C 44 AD 51 01 40 01 00 00 00 C0 00 40 1C 45 00 00 00 40 01 96 98 F3 BF 00 98 B7 45 5C D6 90 40 01 00 00 00 40 00 E8 FD 45 C5 04 4E 40 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 61 B1 EA 3F"
        private const val WIRE_02 = "AA 00"
        private const val WIRE_03 = "AA 09 00 00 06 81 01 09 00 07 07"
        private const val WIRE_06 = "AA 0E 00 00 06 81 F0 07 00 03 01 64 02 64 03 D0"
        private const val WIRE_07 = "AA 00 00 00 23 81 00 00 00"
        private const val APPLIST_REQ = "AA 07 00 00 0C 05 07 00 00"
        private const val CAPABILITIES_REQ = "AA 07 00 00 1C 05 08 00 00"
        private const val SPATIAL_REQ = "AA 07 00 00 1D 05 09 00 00"
        private const val F2_REQ = "AA 08 00 00 04 02 FF 01 00 F2"
        private const val F4_REQ = "AA 1C 00 00 04 02 FF 15 00 F4 7B 22 63 6D 64 22 3A 22 61 32 64 70 5F 63 61 74 6F 6E 22 7D"
        private const val UNKNOWN_REQ = "AA 07 00 00 00 06 03 00 00"
        private const val KNOWN_REQ = "AA 07 00 00 01 01 04 00 00"
        private const val ALREADY_REPLY_REQ = "AA 07 00 00 00 85 02 00 00"
    }
}
