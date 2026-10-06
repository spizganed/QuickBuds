package com.spizganed.quickbuds.protocol

/**
 * OPPO / OnePlus / realme earbud RFCOMM protocol constants.
 *
 * PROVENANCE — almost nothing here was documented by the vendor. The command
 * numbers and payload shapes are reverse-engineered, and the main source is
 * Zhaoyi-ya/OppoPodsManager (https://github.com/Zhaoyi-ya/OppoPodsManager),
 * with additional framing details from
 * Star-ZER0/Pods-Protocol-Reverse-Engineering.
 *
 * READ docs/PROTOCOL.md BEFORE EDITING THIS FILE. It documents the frame layout,
 * every known command, the ANC set-vs-notify trap, and the assumptions that were
 * already proven wrong once. Its [OSS] tags and the README's Credits record which
 * source each area came from; tag a new constant there when you add it here.
 *
 * Do not copy code from those projects without checking their licenses first.
 */
object OpoProtocol {

    const val SPP_UUID_PRIMARY = "00001107-D102-11E1-9B23-00025B00A5A5"
    const val SPP_UUID_FALLBACK = "0000079A-D102-11E1-9B23-00025B00A5A5"

    const val CMD_HANDSHAKE = 0x0100
    const val CMD_QUERY_PRODUCT_ID = 0x0103
    const val CMD_QUERY_BROADCAST = 0x0200
    const val CMD_FIND_BUDS = 0x0400
    /** Earbud fit test, `01` start / `00` stop. `[VENDOR]` `switchCompactnessDetectionStatus` (PROTOCOL.md §9). */
    const val CMD_FIT_TEST = 0x0405
    const val CMD_SET_FEATURE = 0x0403
    const val CMD_SET_ANC = 0x0404
    const val CMD_SET_SPATIAL = 0x0422
    const val CMD_QUERY_BATTERY = 0x0106
    /** Firmware version, empty payload -> `0x8105`. `[CAPTURE]` 2026-09-27 (PROTOCOL.md §3). */
    const val CMD_QUERY_FIRMWARE = 0x0105
    const val CMD_QUERY_ANC = 0x010C
    const val CMD_QUERY_STATUS = 0x010D
    const val CMD_QUERY_EQ = 0x010F
    const val CMD_QUERY_EQ_ALL = 0x0122
    const val CMD_QUERY_BASSWAVE_LEVEL = 0x0124
    const val CMD_SET_EQ = 0x0406
    const val CMD_SAVE_CUSTOM_EQ = 0x0418
    const val CMD_SET_BASSWAVE_LEVEL = 0x041B
    const val CMD_EQ_CHANGED = 0x0504     // push: `<id>` after any EQ change
    /** Alert-sound (prompt) volume, `[level]` 1..10. `[CAPTURE]` 2026-09-25, PROTOCOL.md §9. */
    const val CMD_SET_ALERT_VOLUME = 0x0427
    /** -> `0x8130` `00 <level>`. Empty payload, as HeyMelody sends it. */
    const val CMD_QUERY_ALERT_VOLUME = 0x0130
    /** Paired-device list, empty payload -> `0x8112`. `[CAPTURE]` 2026-09-25, PROTOCOL.md §9. */
    const val CMD_QUERY_DEVICES = 0x0112
    /**
     * Another device on the buds' list, `[VENDOR]` (PROTOCOL.md §9): `0x0429 01 <MAC>` connect, `02 <MAC>`
     * disconnect, `04 00` preferred device automatic, `04 01 <MAC>`; the MAC in written order (the list
     * reply carries it reversed). Read the preferred one with `0x0132 02` -> `00 02 <00 auto | 01 <MAC>>`.
     */
    const val CMD_MULTI_CONNECT = 0x0429
    const val CMD_QUERY_PREFERRED = 0x0132
    /** Sent by HeyMelody after every dual-device toggle, meaning unknown. `[CAPTURE]` 2026-09-25. */
    const val CMD_DUAL_FOLLOWUP = 0x0413

    // --- gesture / key-function bindings ---
    const val CMD_QUERY_KEY_FUNCTION = 0x0108  // getKeyFunction — current bindings
    const val CMD_RESP_KEY_FUNCTION = 0x8108
    /**
     * setKeyFunction — write the gesture bindings back.
     *
     * **`0x0401` IS CONFIRMED ON THE DEVICE (2026-09-22).** Every write is acked
     * immediately — `RX AA 08 00 00 01 84 .. 01 00 00`, payload `00` = success — and the
     * `0x8108` read-back then shows a real `KEYFN DIFF:` change. The feature works.
     *
     * How the wrong number got in, kept because it is the trap:
     *   - `0x0402` was sent repeatedly in a WELL-FORMED frame (`TotalLen 80 = 7 + 73`,
     *     payload `12` + 18x4) and the buds ignored it in total silence: no ack, and the
     *     0x8108 read-back still showed the old table. **A wrong command number fails
     *     silently** — "it worked" and "it did nothing" are indistinguishable without
     *     reading the table back, which is why every write re-reads and diffs.
     *   - `0x0401` is what the Melody-derived setting tables list for `setKeyFunction`
     *     (`BtOperate.m2699L`), and those tables run 0x0400, 0x0401, 0x0403, 0x0404 —
     *     0x0402 does not appear among them at all.
     *   - OppoPodsManager mentions 0x0402 only inside a COMMENT on its 0x0108 query line,
     *     and defines no constant for it. An earlier note here took that comment as a table
     *     entry, which is how the wrong number got in — and it cost a session.
     *
     * Payload SHAPE: `<count> [deviceType, button, buttonAction, function]...`, which is
     * what the `0x8108` read reply returns minus its leading status byte.
     */
    const val CMD_SET_KEY_FUNCTION = 0x0401

    // --- wearing / in-case status (reverse-engineered from OppoPodsManager) ---
    const val CMD_QUERY_WEARING = 0x0109      // getEarBudsStatus — THE in-case query
    const val CMD_RESP_WEARING = 0x8109
    const val CMD_ACTIVE_REPORT = 0x0204      // spontaneous notification, payload[0] = subType
    const val CMD_REGISTER_NOTIFY = 0x0205    // subscribe to spontaneous notifications
    const val EVT_WEARING = 0x02              // 0x0204 subType: wearing status changed
    const val EVT_GAME_MODE = 0x05            // 0x0204 subType: game mode changed
    const val EVT_FIT_TEST = 0x04             // 0x0204 subType: fit test result, [VENDOR]
    const val EVT_GOLDEN_STATUS = 0x08        // 0x0204 subType: Golden Sound test status, [VENDOR]
    const val EVT_EAR_SCAN = 0x0E             // 0x0204 subType: ear scan result, [CAPTURE] (pushed unregistered)

    const val FEATURE_GAME_MODE = 0x06
    /** Firmware auto play/pause on wear. `04 01` / `04 00`, `[CAPTURE]` 2026-09-25. */
    const val FEATURE_AUTO_PLAY_PAUSE = 0x04
    const val FEATURE_DUAL_DEVICE = 0x11
    const val FEATURE_SPATIAL_SOUND = 0x1B
    /** Hi-Res (LHDC) codec. Switching it makes the buds drop and reconnect. `[CAPTURE]` 2026-09-23. */
    const val FEATURE_HIRES_CODEC = 0x18
    /** BassWave on/off. Level is its own command, [setBassWaveLevel]. `[CAPTURE]` 2026-09-23. */
    const val FEATURE_BASSWAVE = 0x1D
    /** Golden Sound (hearing enhancement) on/off. `[VENDOR]` `setSwitchFeature(11)` (PROTOCOL.md §9). */
    const val FEATURE_GOLDEN_SOUND = 0x0B
    /**
     * Game mode's switch on buds whose `0x8100` bitmap has [CMD_GAME_SOUND]: HeyMelody writes and reads
     * `0x28` there instead of [FEATURE_GAME_MODE], and `0x27` is its game sound effects. `[VENDOR]` (PROTOCOL.md §9).
     */
    const val FEATURE_GAME_MODE_MAIN = 0x28
    const val FEATURE_GAME_SOUND = 0x27
    const val CMD_GAME_SOUND = 0x0423
    /** Game sound type: `0x0423 <type> 01`, read `0x012B` -> `00 <selected> <count> <types>`. `[VENDOR]` (PROTOCOL.md §9). */
    const val CMD_QUERY_GAME_SOUND = 0x012B
    /** Spatial type (`0` off, `1` fixed, `2` head tracking): `0x0422 <type>`, read `0x012A` -> `00 <type>`, pushed as `0x0510 <type>`. `[VENDOR]` */
    const val CMD_SET_SPATIAL_TYPE = 0x0422
    const val CMD_QUERY_SPATIAL_TYPE = 0x012A
    const val CMD_SPATIAL_TYPE_PUSH = 0x0510
    /**
     * Codec picker on `highAudio` models (PROTOCOL.md §9), `[VENDOR]`, unverified on buds: read the
     * current codec with `0x0114` -> `00 <codec>`, the offered ones with `0x0123` -> `00 <u16 LE mask>`
     * (bit k = codec k + 1), write `0x041A <codec> <hiRes> 00`. The buds restart after the write.
     */
    const val CMD_QUERY_CODEC = 0x0114
    const val CMD_QUERY_CODEC_LIST = 0x0123
    const val CMD_SET_CODEC = 0x041A
    /** Plain on/off switches from HeyMelody, `[VENDOR]`, unverified on buds (PROTOCOL.md §9). */
    const val FEATURE_VOCAL_ENHANCE = 0x09
    const val FEATURE_POWER_SAVING = 0x17
    const val FEATURE_SMART_VOLUME = 0x1C
    const val FEATURE_ADAPTIVE_VOLUME = 0x30
    const val FEATURE_ADAPTIVE_EAR = 0x31
    const val FEATURE_SLEEP_PAUSE = 0x3A
    const val FEATURE_SPEECH_PERCEPTION = 0x32
    const val FEATURE_LONG_PRESS_VOLUME = 0x35
    const val FEATURE_SWIFT_PAIR = 0x37
    const val FEATURE_HEARING_OPTIMIZE = 0x38
    const val FEATURE_HEAD_MOTION = 0x3B
    /** Head gestures' mapping: `0x0431 <type>`, `0` nod answers / shake declines, `1` the reverse. `[VENDOR]` */
    const val CMD_SET_HEAD_MOTION_TYPE = 0x0431
    /** Read with `0x0134` (empty); the type comes back as the `0x0204` push [EVT_HEAD_MOTION_TYPE]. `[VENDOR]` */
    const val CMD_QUERY_HEAD_MOTION_TYPE = 0x0134
    const val EVT_HEAD_MOTION_TYPE = 0xF5
    /**
     * Personalized noise cancellation (PROTOCOL.md §9), `[VENDOR]`: switch [FEATURE_PERSONAL_NOISE] (off only;
     * the buds turn it on themselves), `0x0412 <action>`, a stored result read with `0x011A` -> `00 <exist>`,
     * the test's result pushed as [EVT_PERSONAL_NOISE] `0B <result>` (0 done, 1-5 a reason it failed).
     */
    const val FEATURE_PERSONAL_NOISE = 0x0C
    const val CMD_PERSONAL_NOISE = 0x0412
    const val CMD_QUERY_PERSONAL_NOISE = 0x011A
    const val EVT_PERSONAL_NOISE = 0x0B
    const val PERSONAL_NOISE_TEST = 1
    const val PERSONAL_NOISE_USE_STORED = 2
    const val PERSONAL_NOISE_CANCEL = 3
    /** Tap sensitivity 1..5 (lower triggers more easily): `0x042D <level>`, read `0x0133` -> `00 <level> <default>`. `[VENDOR]` */
    const val CMD_SET_TAP_LEVEL = 0x042D
    const val CMD_QUERY_TAP_LEVEL = 0x0133

    private var seqCounter = 0x01

    @Synchronized
    private fun nextSeq(): Int {
        val seq = seqCounter
        seqCounter = if (seqCounter >= 0xFE) 0x01 else seqCounter + 1
        return seq
    }

    /** `TotalLen` is LEB128: one byte up to 127, more after (PROTOCOL.md §2). */
    @Synchronized
    fun buildPacket(cmd: Int, seq: Int? = null, payload: ByteArray = ByteArray(0)): ByteArray {
        val s = seq ?: nextSeq()
        val payLen = payload.size
        var totalLen = 7 + payLen
        val len = mutableListOf<Byte>()
        do {
            val b = totalLen and 0x7F
            totalLen = totalLen shr 7
            len += (if (totalLen != 0) b or 0x80 else b).toByte()
        } while (totalLen != 0)
        val head = byteArrayOf(
            0xAA.toByte(), *len.toByteArray(), 0x00, 0x00,
            (cmd and 0xFF).toByte(), ((cmd shr 8) and 0xFF).toByte(), s.toByte(),
            (payLen and 0xFF).toByte(), ((payLen shr 8) and 0xFF).toByte()
        )
        return head + payload
    }

    /** Time request `0x0500` from the buds (PROTOCOL.md §9). */
    const val REQ_TIME = 0x0500
    /** Command groups HeyMelody knows; a request from the buds in any other gets `01`. */
    private val KNOWN_GROUPS = setOf(0x0100, 0x0200, 0x0300, 0x0400, 0x0500, 0x0F00)

    /**
     * HeyMelody's answer to a frame the buds send on their own, or null (PROTOCOL.md §9 "Requests
     * from the buds"). [cmd], [seq], [payload] are the incoming frame's. `[VENDOR]`
     */
    fun answerFor(cmd: Int, seq: Int, payload: ByteArray, unixSeconds: Long): ByteArray? {
        if (cmd and 0x8000 != 0) return null
        val answer = when (cmd) {
            REQ_TIME -> unixSeconds.toInt().let { t ->
                byteArrayOf(0, t.toByte(), (t shr 8).toByte(), (t shr 16).toByte(), (t shr 24).toByte())
            }
            0x050C -> byteArrayOf(0, 1, 0)
            0x051C -> byteArrayOf(0, 0, 0)
            // ponytail: always "invalid"; HeyMelody answers the phone's spatial mode, which we do not have.
            0x051D -> byteArrayOf(1)
            CMD_ACTIVE_REPORT -> when (payload.firstOrNull()?.toInt()?.and(0xFF)) {
                0xF2 -> byteArrayOf(0, 0xF2.toByte())
                0xF4 -> jsonAck(payload.copyOfRange(1, payload.size))
                else -> null
            }
            else -> if (cmd and 0x7F00 !in KNOWN_GROUPS) byteArrayOf(1) else null
        } ?: return null
        return buildPacket(cmd or 0x8000, seq, answer)
    }

    /** `F4` JSON push: `00 F4 {"cmd":<its cmd>}`, `01 F4` if it does not parse. */
    private fun jsonAck(json: ByteArray): ByteArray {
        val head = byteArrayOf(0, 0xF4.toByte())
        val text = String(json, Charsets.UTF_8)
        if (text.isEmpty()) return head
        return try {
            val cmd = org.json.JSONObject(text).opt("cmd")
            head + org.json.JSONObject().apply { if (cmd is String) put("cmd", cmd) }.toString().toByteArray()
        } catch (e: org.json.JSONException) {
            byteArrayOf(1, 0xF4.toByte())
        }
    }

    fun buildHandshake(): ByteArray = buildPacket(CMD_HANDSHAKE)
    fun buildQueryProductId(): ByteArray = buildPacket(CMD_QUERY_PRODUCT_ID)
    fun buildQueryBroadcastCodes(): ByteArray = buildPacket(CMD_QUERY_BROADCAST)

    /** getEarBudsStatus (0x0109) — returns [count][comp,st] pairs, st=4 means in case. */
    fun queryWearingStatus(): ByteArray = buildPacket(CMD_QUERY_WEARING, seq = 0xF2)

    /**
     * Subscribe to spontaneous 0x0204 notifications (battery + wearing + ANC).
     *
     * Payload is canonical: a count byte followed by event ids.
     *
     * CONFIRMED on device: with count=2 and events battery (0x01) + wearing (0x02),
     * the ACK lists BOTH and the buds then emit 0x0204 subtype 02 on every wear
     * change. Wear updates are effectively instant — no polling needed.
     *
     * The old legacy literal 01 01 02 02 was a misread: under the count-first
     * shape it means count=1 (register battery only) with 02 02 left over. The
     * firmware ACKed that happily and silently never sent wear events, which is
     * why wear appeared to be poll-only. Do NOT revert to that.
     *
     * 0x03 (ANC) WAS ADDED on 2026-09-18. The buds advertise their event ids in
     * the 0x8200 broadcast-codes reply, which lists `01 02 03 04 08 0B F1 F2 F3` —
     * 03 is offered, and the ANC subType 0x03 push arrived only unreliably while we
     * had not subscribed to it. Subscribing is the documented way to make a 0x0204
     * subType arrive, and it is how wear was fixed, so the same reasoning applies.
     *
     * CONFIRMED ON DEVICE 2026-09-19. The 0x8205 ACK lists `01 02 03`, and a bud-side
     * gesture now updates the app circles AND the widget, including a gesture REBOUND
     * to a different mode cycle. If ANC ever stops following gestures, check the ACK
     * still lists `01 02 03` first — a firmware that rejects the longer list would ACK
     * a shorter one.
     *
     * 0x04 (fit test result) is added when the buds have the fit test (`0x0405`), 2026-09-29:
     * HeyMelody registers every id the buds list in 0x8200 (`registerMultiNotification`).
     * 0x08 (Golden Sound test status) likewise, when the buds have the test (`0x040D`), 2026-09-29.
     */
    /**
     * The events to subscribe to: ours, kept to those the buds offer in `0x8200` when they sent one,
     * as HeyMelody subscribes to the offered list only (PROTOCOL.md §4). Some firmwares never ack a
     * list with others.
     */
    fun notifyIds(fitTest: Boolean, golden: Boolean, personalNoise: Boolean, offered: Set<Int>?): List<Int> =
        (listOf(0x01, 0x02, 0x03) + (if (fitTest) listOf(EVT_FIT_TEST) else emptyList()) +
            (if (golden) listOf(EVT_GOLDEN_STATUS) else emptyList()) +
            (if (personalNoise) listOf(EVT_PERSONAL_NOISE) else emptyList()))
            .filter { offered == null || it in offered }

    fun registerNotifications(ids: List<Int>): ByteArray =
        buildPacket(CMD_REGISTER_NOTIFY, payload = byteArrayOf(ids.size.toByte()) + ids.map { it.toByte() })

    /** Subscribe one event, for buds without `0x0205` in their bitmap. `[VENDOR]` */
    const val CMD_REGISTER_ONE = 0x0201
    fun registerOne(id: Int): ByteArray = buildPacket(CMD_REGISTER_ONE, payload = byteArrayOf(id.toByte()))

    /** `0x8200` = `00 <count> <codes>`, or null. */
    fun offeredEvents(payload: ByteArray): Set<Int>? {
        if (payload.size < 2 || payload[0].toInt() != 0) return null
        val n = payload[1].toInt() and 0xFF
        if (payload.size < 2 + n) return null
        return (0 until n).map { payload[2 + it].toInt() and 0xFF }.toSet()
    }

    // --- Golden Sound test (PROTOCOL.md §9), `[CAPTURE]` 2026-09-29 + `[VENDOR]` ---
    const val CMD_GOLDEN_DETECT = 0x040D      // q0: ear scan `04 01|00 <uid>`, hearing test `02 01|00`
    const val CMD_GOLDEN_RECORD = 0x040E      // w0 / m1: tone, stop tone, apply record
    const val CMD_GOLDEN_RESTORE = 0x0411     // F0: restore data (the description id)
    const val CMD_GOLDEN_SCAN_DATA = 0x0415   // v0: ear-scan data
    const val CMD_GOLDEN_FILTER = 0x0116      // W: hearing filter, the reply carries the enhance type
    const val CMD_GOLDEN_SCAN_FILTER = 0x011F // M: ear-scan filter (the graph only)
    const val CMD_GOLDEN_ACTIVE = 0x0115      // the record on the buds
    const val CMD_GOLDEN_ACTIVE_SCAN = 0x011E // the ear-scan data on the buds

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    /** 12 x `<side> <freq> <value>`: left 1..6 then right 1..6, as HeyMelody sends them. */
    private fun hearingInfo(values: IntArray) = ByteArray(36) { i ->
        when (i % 3) { 0 -> (i / 18 + 1).toByte(); 1 -> (i / 3 % 6 + 1).toByte(); else -> values[i / 3].toByte() }
    }

    /** Ear scan start / stop, `04 01|00 <uid>`; the result comes as event `0x0E`. */
    fun earScan(on: Boolean, uid: Int): ByteArray =
        buildPacket(CMD_GOLDEN_DETECT, payload = byteArrayOf(0x04, if (on) 0x01 else 0x00) + int32(uid))

    /** Hearing test start / stop, `02 01|00`. */
    fun hearingTest(on: Boolean): ByteArray =
        buildPacket(CMD_GOLDEN_DETECT, payload = byteArrayOf(0x02, if (on) 0x01 else 0x00))

    /** Plays a tone on one bud: `03 01 <side 1 L / 2 R> <freq 1..6> <value>`. */
    fun hearingTone(side: Int, freq: Int, value: Int): ByteArray =
        buildPacket(CMD_GOLDEN_RECORD, payload = byteArrayOf(0x03, 0x01, side.toByte(), freq.toByte(), value.toByte()))

    /** Stops the tone, `04`; HeyMelody sends it before every new level. */
    fun hearingToneStop(): ByteArray = buildPacket(CMD_GOLDEN_RECORD, payload = byteArrayOf(0x04))

    /** Asks for the filter of a result; the `0x8116` reply's byte 9 is the enhance type. */
    fun hearingFilter(uid: Int, values: IntArray): ByteArray =
        buildPacket(CMD_GOLDEN_FILTER, payload = byteArrayOf(0x0C) + hearingInfo(values) + int32(uid))

    /** Asks for the ear-scan filter of a record: `<length little-endian> <data> <uid>`. */
    fun earScanFilter(uid: Int, data: ByteArray): ByteArray =
        buildPacket(CMD_GOLDEN_SCAN_FILTER, payload = le16(data.size) + data + int32(uid))

    /** Applies a record: `03 0c <12 x info> <uid> <name>`. */
    fun hearingRecord(uid: Int, name: String, values: IntArray): ByteArray =
        buildPacket(CMD_GOLDEN_RECORD, payload = byteArrayOf(0x03, 0x0C) + hearingInfo(values) + int32(uid) + name.toByteArray())

    /** The record's description id, `01 01 01 00 <id>` (count 1, type 1, length 1 little-endian). */
    fun hearingRestore(descId: Int): ByteArray =
        buildPacket(CMD_GOLDEN_RESTORE, payload = byteArrayOf(0x01, 0x01, 0x01, 0x00, descId.toByte()))

    /** Applies ear-scan data: `03 <length little-endian> <data> <uid>`. */
    fun earScanData(uid: Int, data: ByteArray): ByteArray =
        buildPacket(CMD_GOLDEN_SCAN_DATA, payload = byteArrayOf(0x03) + le16(data.size) + data + int32(uid))

    fun queryGoldenActive(): ByteArray = buildPacket(CMD_GOLDEN_ACTIVE)
    fun queryGoldenActiveScan(): ByteArray = buildPacket(CMD_GOLDEN_ACTIVE_SCAN)

    fun personalNoise(action: Int): ByteArray = buildPacket(CMD_PERSONAL_NOISE, payload = byteArrayOf(action.toByte()))
    fun queryPersonalNoise(): ByteArray = buildPacket(CMD_QUERY_PERSONAL_NOISE)
    fun setTapLevel(level: Int): ByteArray = buildPacket(CMD_SET_TAP_LEVEL, payload = byteArrayOf(level.toByte()))
    fun queryTapLevel(): ByteArray = buildPacket(CMD_QUERY_TAP_LEVEL)

    /** Earbud fit test: `0x0405` `01` start / `00` stop (HeyMelody stops it when its sheet closes). */
    fun fitTest(on: Boolean): ByteArray =
        buildPacket(CMD_FIT_TEST, payload = byteArrayOf(if (on) 0x01 else 0x00))

    /**
     * SET_ANC (0x0404): `01 01` then a little-endian bit field with bit [bit] set, `index / 8 + 1`
     * bytes long. The bit is the mode's `protocolIndex` for the connected model ([AncModes],
     * PROTOCOL.md §5). Buds 4: Off 0 `01 01 01`, Transparency 2 `01 01 04`, Deep 4, Medium 5,
     * Light 6, Adaptive 11 `01 01 00 08`.
     *
     * Reports (`0x810C`, `0x0204` subType 3) name the SAME tree's bits, but a mode's child where it
     * has one (Buds 4 reports Off as bit 3, Transparency as bit 8). Setting a child bit was tried
     * once and sent the wrong mode: SET uses the parent. Adaptive's bit is 11, not 8: `8` gives
     * `01 01 00 01`, a different mode (fixed 2026-09-22).
     */
    fun anc(bit: Int): ByteArray {
        val arr = ByteArray(2 + bit / 8 + 1)
        arr[0] = 0x01
        arr[1] = 0x01
        arr[2 + bit / 8] = (1 shl (bit % 8)).toByte()
        return buildPacket(CMD_SET_ANC, payload = arr)
    }

    private fun featurePayload(featureId: Int, on: Boolean): ByteArray =
        byteArrayOf(featureId.toByte(), if (on) 0x01 else 0x00)

    /** Find my earbuds — `0x0400` `01` start / `00` stop, both buds, no side byte. `[CAPTURE]` 2026-09-23. */
    fun findTone(on: Boolean): ByteArray =
        buildPacket(CMD_FIND_BUDS, payload = byteArrayOf(if (on) 0x01 else 0x00))

    /** Any `0x0403` feature switch. Spatial (`0x1B`) and Hi-Res (`0x18`) are `[CAPTURE]` — PROTOCOL.md §9. */
    fun setFeature(featureId: Int, on: Boolean): ByteArray =
        buildPacket(CMD_SET_FEATURE, payload = featurePayload(featureId, on))

    fun queryFirmware(): ByteArray = buildPacket(CMD_QUERY_FIRMWARE)

    /**
     * The `0x8105` text as HeyMelody shows it: the `versionType` 2 versions in reply order, joined
     * with dots. Buds 4 `1,2,138,2,2,138,3,1,01,3,2,105` -> `138.138.105`, as on HeyMelody's own
     * screen `[CAPTURE]` 2026-09-27. Null when there is none.
     */
    fun firmwareVersion(text: String): String? = text.split(',').chunked(3)
        .filter { it.size == 3 && it[1].trim() == "2" }
        .joinToString(".") { it[2].trim() }
        .ifEmpty { null }
    fun queryBattery(): ByteArray = buildPacket(CMD_QUERY_BATTERY, seq = 0xF0)
    fun queryAncMode(): ByteArray = buildPacket(CMD_QUERY_ANC, payload = byteArrayOf(0x01, 0x01))

    /**
     * getNoiseReductionSwitchMode — WHICH ANC modes the hold cycles through.
     *
     * `0x010C` is one command number carrying several different questions, selected by the
     * payload. `[OSS]`, from the Melody-derived tables:
     *
     *     01 01   getCurrentNoiseReductionMode   what is active right now  <- queryAncMode()
     *     02 01   getNoiseReductionSwitchMode    the HOLD's mode list
     *     02 03   "                              (list may be one of these)
     *     02 04   "                              (…the source lists all three)
     *     04 01   getIntelligentNoiseReductionMode
     *
     * WHY THIS EXISTS: the hold's ANC cycle is the one thing this app cannot yet configure.
     * Its key-function byte (`0x08`) only *reports* that the hold cycles ANC — measured on
     * the device, clearing that byte did not stop the cycle, and which modes it stepped
     * through was decided entirely on the vendor side. The mode list lives HERE instead, and
     * this read is the cheap, read-only way to see it before anyone writes `0x0404`.
     *
     * READ-ONLY, so it cannot change a binding. `[VENDOR]`: `02 01` on
     * most models, `02 03` (left) and `02 04` (right) on models with a per-bud hold
     * (`longPressType`), see [HOLD_TYPE_SHARED].
     */
    fun queryNoiseSwitchModes(type: Int = HOLD_TYPE_SHARED): ByteArray =
        buildPacket(CMD_QUERY_ANC, payload = byteArrayOf(0x02, type.toByte()))

    /**
     * The hold cycle's noise type, the second byte of [setHoldAncModes] and [queryNoiseSwitchModes].
     * `[VENDOR]`: 1 for one cycle shared by both buds, 3 (left) / 4
     * (right) on models whose `longPressType` gives each bud its own hold.
     */
    const val HOLD_TYPE_SHARED = 1
    const val HOLD_TYPE_LEFT = 3
    const val HOLD_TYPE_RIGHT = 4

    /**
     * setSupportNoiseReduction — WRITE which ANC modes the hold cycles through.
     *
     * `[CAPTURE]` 2026-09-22, via an HCI capture of HeyMelody itself (Option C,
     * PACKET-CAPTURE.md): he added Adaptive to a 3-stop hold cycle in HeyMelody, and the
     * app sent `AA 0A 00 00 04 04 <seq> 04 00 02 01 07 08` — `0x0404` action `02`, noiseType
     * `01`, then the mask `07 08` (little-endian `0x0807`). Acked (`0x8404` status `00`), and
     * the immediate `0x010C` `02 01` read-back returned the same `07 08`, up from `07 00`
     * (`0x0007`) beforehand.
     *
     * THIS CONFIRMS THE MASK REUSES [anc]'S OWN BIT NUMBERING, not a separate scheme —
     * bit 11 (`0x0800`) is exactly Adaptive's bit in the plain SET_ANC table above, and it is
     * the only bit that moved. PROTOCOL.md §5 previously carried this as `[INFERRED]`; this
     * capture settles it. Off = bit 0, Transparency = bit 2 (matching [anc]); bit 1 is
     * some generic "On" that resolves to whichever level was last hand-set, seen set in every
     * capture so far and never independently isolated.
     *
     * A DIFFERENT COMMAND FROM [anc], same command NUMBER. `0x0404`'s first payload
     * byte selects the question: `01 01 <bits>` sets the CURRENT mode (see [ancOff] etc.),
     * `02 01 <mask LE>` sets the cycle's MEMBERSHIP. Do not merge these two payload shapes.
     */
    fun setHoldAncModes(mask: Int, type: Int = HOLD_TYPE_SHARED): ByteArray {
        // `[VENDOR]` `NoiseReductionInfo.getData()`: the mask in as few bytes as it needs (1-4), LE.
        var len = 4
        while (len > 1 && (mask ushr ((len - 1) * 8)) and 0xFF == 0) len--
        return buildPacket(
            CMD_SET_ANC,
            payload = byteArrayOf(0x02, type.toByte()) + ByteArray(len) { (mask ushr (it * 8)).toByte() }
        )
    }

    /**
     * setKeyFunction write for the on-call group (`btn 0x06`) — `[CAPTURE]`, same capture as
     * [setHoldAncModes]. HeyMelody writes ONE entry, `deviceType = 0x04`
     * ([KeyFunctionParser.DEVICE_TYPE_BOTH]), which the buds fan out to both sides: the
     * following `0x8108` read-back always showed the SAME `fn` on `dev=0x01` AND `dev=0x02`,
     * never `dev=0x04` itself. See [KeyFunctionParser.BUTTON_ON_CALL] for the full capture and
     * what is still `[INFERRED]` (which act is which UI row).
     */
    /**
     * `act 0x02`, toggled `[CAPTURE]` between `fn 0x00` and `fn 0x1D` while he switched
     * HeyMelody's on-call **double tap** row between None and Answer/end call, twice. The
     * bytes are exactly what shipped from the vendor app; only the ENGLISH LABEL ("double
     * tap") is `[INFERRED]` from the order he described the two rows in, not read off the
     * wire — see [KeyFunctionParser.BUTTON_ON_CALL].
     *
     * Named (not inlined) so [com.spizganed.quickbuds.ui.OnCallConfigStore] can reverse-map a
     * device reading back to a row without duplicating these numbers.
     */
    const val ON_CALL_ACT_DOUBLE_TAP = 0x02
    const val ON_CALL_FN_ANSWER_END = 0x1D

    /**
     * `act 0x06`, toggled `[CAPTURE]` between `fn 0x00` and `fn 0x1C` while he switched
     * HeyMelody's on-call **long hold** row between None and Decline call, twice. Same
     * caveat as the double-tap pair above: the bytes are measured, the "long hold" label is
     * `[INFERRED]`.
     */
    const val ON_CALL_ACT_LONG_HOLD = 0x06
    const val ON_CALL_FN_DECLINE = 0x1C

    private fun onCallPayload(act: Int, fn: Int, button: Int) = byteArrayOf(
        0x01,
        KeyFunctionParser.DEVICE_TYPE_BOTH.toByte(),
        button.toByte(),
        act.toByte(),
        fn.toByte()
    )

    /**
     * `[VENDOR]`: callControl 28 / 32 are `act 0x01` (single tap),
     * 29 / 33 / 36 `act 0x02`, 30 / 34 `act 0x03`, 31 / 35 `act 0x06`; all `btn 0x06`, `dev 0x04`.
     */
    const val ON_CALL_ACT_SINGLE_TAP = 0x01

    /** [button] is [KeyFunctionParser.BUTTON_ON_CALL], or `btn 01` for the realme both-buds hold. */
    fun setOnCall(act: Int, fn: Int, button: Int = KeyFunctionParser.BUTTON_ON_CALL): ByteArray =
        buildPacket(CMD_SET_KEY_FUNCTION, payload = onCallPayload(act, fn, button))

    // --- Equalizer, all [CAPTURE] 2026-09-23 (PROTOCOL.md §9) ---

    /** Current EQ -> `0x810F` `00 <id>` (00-02 built-in, 04+ custom). */
    fun queryEq(): ByteArray = buildPacket(CMD_QUERY_EQ)
    /** Custom preset list -> `0x8122`, see [EqCodec.parseList]. Empty payload, as HeyMelody sends it. */
    fun queryEqAll(): ByteArray = buildPacket(CMD_QUERY_EQ_ALL)
    fun setBuiltInEq(id: Int): ByteArray = buildPacket(CMD_SET_EQ, payload = byteArrayOf(id.toByte()))
    fun customEq(action: Int, p: EqCodec.Preset): ByteArray =
        buildPacket(CMD_SAVE_CUSTOM_EQ, payload = EqCodec.encode(action, p))
    /** BassWave level, signed -5..+5; `FB 05` is the range (min, max) HeyMelody sends. */
    fun setBassWaveLevel(level: Int): ByteArray =
        buildPacket(CMD_SET_BASSWAVE_LEVEL, payload = byteArrayOf(0xFB.toByte(), 0x05, level.toByte()))
    /** -> `0x8124` `00 FB 05 <level>`. */
    fun queryBassWaveLevel(): ByteArray = buildPacket(CMD_QUERY_BASSWAVE_LEVEL)

    fun setAlertVolume(level: Int): ByteArray =
        buildPacket(CMD_SET_ALERT_VOLUME, payload = byteArrayOf(level.coerceIn(1, 10).toByte()))
    fun queryAlertVolume(): ByteArray = buildPacket(CMD_QUERY_ALERT_VOLUME)

    fun setSpatialType(type: Int): ByteArray = buildPacket(CMD_SET_SPATIAL_TYPE, payload = byteArrayOf(type.toByte()))
    fun querySpatialType(): ByteArray = buildPacket(CMD_QUERY_SPATIAL_TYPE)
    fun queryCodec(): ByteArray = buildPacket(CMD_QUERY_CODEC)
    fun queryCodecList(): ByteArray = buildPacket(CMD_QUERY_CODEC_LIST)
    /** HeyMelody sends Hi-Res only with LDAC (`3`) or LHDC V5 (`8`), else `0`; the last byte is always `0`. */
    fun setCodec(codec: Int, hiRes: Boolean): ByteArray = buildPacket(CMD_SET_CODEC,
        payload = byteArrayOf(codec.toByte(), if (hiRes && (codec == 3 || codec == 8)) 1 else 0, 0))
    /** HeyMelody sends every pick, Off (`0`) included, with enable `01`. */
    fun setGameSoundType(type: Int): ByteArray = buildPacket(CMD_GAME_SOUND, payload = byteArrayOf(type.toByte(), 0x01))
    fun queryGameSound(): ByteArray = buildPacket(CMD_QUERY_GAME_SOUND)
    fun setHeadMotionType(type: Int): ByteArray = buildPacket(CMD_SET_HEAD_MOTION_TYPE, payload = byteArrayOf(type.toByte()))
    fun queryHeadMotionType(): ByteArray = buildPacket(CMD_QUERY_HEAD_MOTION_TYPE)

    fun queryDevices(): ByteArray = buildPacket(CMD_QUERY_DEVICES)
    private fun macBytes(mac: String) = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
    fun connectDevice(mac: String, on: Boolean): ByteArray =
        buildPacket(CMD_MULTI_CONNECT, payload = byteArrayOf(if (on) 0x01 else 0x02) + macBytes(mac))
    /** [mac] null = automatic. */
    fun setPreferred(mac: String?): ByteArray = buildPacket(CMD_MULTI_CONNECT,
        payload = if (mac == null) byteArrayOf(0x04, 0x00) else byteArrayOf(0x04, 0x01) + macBytes(mac))
    fun queryPreferred(): ByteArray = buildPacket(CMD_QUERY_PREFERRED, payload = byteArrayOf(0x02))
    /** `08 00 01` after dual OFF, `08 00 00` after ON — copied from HeyMelody, not understood. */
    fun dualFollowup(dualOn: Boolean): ByteArray =
        buildPacket(CMD_DUAL_FOLLOWUP, payload = byteArrayOf(0x08, 0x00, if (dualOn) 0x00 else 0x01))

    /**
     * getKeyFunction (0x0108) — read the CURRENT gesture bindings.
     *
     * READ-ONLY, and deliberately so: it reports what the buds are already doing,
     * so it cannot change state or break a binding. The 0x8108 reply is the cheapest
     * shot at learning the `function` enum, which is the one thing blocking gesture
     * configuration in our app (PROTOCOL.md §6). It may also hand us the enum straight
     * from the device, which would remove the need for a HeyMelody capture.
     *
     * Payload is a bare query (no payload), matching the other 0x01xx reads. The buds
     * DO answer it: the reply is `<status> <count> <4-byte entries>...` and its layout
     * is `[CAPTURE]`-confirmed — see PROTOCOL.md §6 and `KeyFunctionParser`, which
     * logs it RAW and grouped so two readings can be diffed.
     *
     * The `function` VALUES are no longer a mystery either: they were MEASURED by
     * diffing two of these replies around a change made in the vendor app. See
     * `GestureAction.functionByte`.
     */
    fun queryKeyFunction(): ByteArray = buildPacket(CMD_QUERY_KEY_FUNCTION)

    /**
     * setKeyFunction (0x0401) — write gesture bindings back.
     *
     * Payload is `<count>` then 4 bytes per entry, and the entries are the SAME
     * [KeyFunctionParser.Entry] type the read reply decodes into, deliberately: using
     * one type for both directions is what stops the two from drifting, and it means a
     * table read back from the buds can be written out again unmodified.
     *
     * NO LEADING STATUS BYTE, unlike the reply. The reply is `<status> <count> ...`
     * (`[CAPTURE]`), but a status byte is something the device REPORTS; a write supplies
     * only the count and its entries.
     *
     * WHY THE CALLER STILL RE-READS: the payload layout above is `[OSS]`, and the ONE
     * thing the device has taught us about this command is that it ignores a wrong
     * command number in total silence (see CMD_SET_KEY_FUNCTION). A silent ignore looks
     * exactly like success unless the table is read back, so the read-back is not
     * belt-and-braces — it is the only evidence there is.
     *
     * LEB128 is safe here: the largest realistic payload is an 18-entry table,
     * `1 + 18*4 = 73` bytes, so `TotalLen = 80` fits in one LEB128 byte. `buildPacket()`
     * would only need real LEB128 above ~30 entries, which this device does not have.
     */
    fun setKeyFunction(entries: List<KeyFunctionParser.Entry>): ByteArray {
        val payload = ByteArray(1 + entries.size * KeyFunctionParser.ENTRY_SIZE)
        payload[0] = entries.size.toByte()
        for ((i, e) in entries.withIndex()) {
            val o = 1 + i * KeyFunctionParser.ENTRY_SIZE
            payload[o] = e.deviceType.toByte()
            payload[o + 1] = e.button.toByte()
            payload[o + 2] = e.action.toByte()
            payload[o + 3] = e.function.toByte()
        }
        return buildPacket(CMD_SET_KEY_FUNCTION, payload = payload)
    }

    fun queryStatus(): ByteArray = buildPacket(
        CMD_QUERY_STATUS,
        seq = 0x00,
        payload = byteArrayOf(
            // count, then feature ids. 0x1D (BassWave) added 2026-09-23 — HeyMelody asks for it too;
            // 0x09 onwards on the second line 2026-09-29, the Earbud settings Features switches.
            // 0x32 onwards on the third line 2026-09-29, the rest of HeyMelody's list; 0x0C personalized ANC.
            0x17, 0x05, 0x04, 0x0B, 0x11, 0x13, 0x18, 0x06, 0x1B, 0x1C, 0x27, 0x28, 0x1D,
            0x09, 0x17, 0x30, 0x31, 0x3A,
            0x32, 0x35, 0x37, 0x38, 0x3B, 0x0C
        )
    )

    /** Little-endian unsigned 16-bit value at [i]. */
    fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }
}
