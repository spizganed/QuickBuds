package com.spizganed.quickbuds.protocol

/*
 * The protocol: the command constants, the packet envelope and the parsers that have no
 * topic file yet. The builders are in Commands.kt and HearingCommands.kt; the replies and batch
 * are in Replies.kt; the framer is in Wire.kt; the topic decoders are in Battery.kt, Anc.kt,
 * GameMode.kt, Gestures.kt and Eq.kt. The typed reading is in Messages.kt and Decode.kt.
 * Shared data is not here: Feature.kt, Capabilities.kt, ModelCatalog.kt, AncModes.kt.
 */

// ===== OpoProtocol =====

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
 * already proven wrong once. Its evidence tags record the source of each area; tag a new
 * constant there when you add it here.
 *
 * The builders are in [Commands] and [HearingCommands]. The app calls them directly.
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
    /** Earbud fit test, `01` start / `00` stop. `[VENDOR]` `switchCompactnessDetectionStatus` (PROTOCOL.md §20). */
    const val CMD_FIT_TEST = 0x0405
    const val CMD_SET_FEATURE = 0x0403
    const val CMD_SET_ANC = 0x0404
    const val CMD_SET_SPATIAL = 0x0422
    const val CMD_QUERY_BATTERY = 0x0106
    /** Firmware version, empty payload -> `0x8105`. `[CAPTURE]` 2026-09-27 (PROTOCOL.md §7). */
    const val CMD_QUERY_FIRMWARE = 0x0105
    const val CMD_QUERY_ANC = 0x010C
    const val CMD_QUERY_STATUS = 0x010D
    /** Several queries in one frame (bitmap bit 56), answered by one or more [CMD_BATCH_REPLY]. */
    const val CMD_BATCH = 0x012F
    const val CMD_BATCH_REPLY = 0x812F
    const val CMD_QUERY_EQ = 0x010F
    const val CMD_QUERY_EQ_ALL = 0x0122
    const val CMD_QUERY_BASSWAVE_LEVEL = 0x0124
    /** realme dynamic sound: all band levels in one reply (bitmap bit 54). */
    const val CMD_QUERY_BASS_BANDS = 0x012C
    const val CMD_SET_EQ = 0x0406
    const val CMD_SAVE_CUSTOM_EQ = 0x0418
    const val CMD_SET_BASSWAVE_LEVEL = 0x041B
    const val CMD_EQ_CHANGED = 0x0504     // push: `<id>` after any EQ change
    /** Feature changed, push `<count> <id> <value>...` after a [CMD_SET_FEATURE] write or a buds gesture. */
    const val CMD_FEATURE_PUSH = 0x0503
    /** Alert-sound (prompt) volume, `[level]` 1..10. `[CAPTURE]` 2026-09-25, PROTOCOL.md §18. */
    const val CMD_SET_ALERT_VOLUME = 0x0427
    /** -> `0x8130` `00 <level>`. Empty payload, as HeyMelody sends it. */
    const val CMD_QUERY_ALERT_VOLUME = 0x0130
    /** Paired-device list, empty payload -> `0x8112`. `[CAPTURE]` 2026-09-25, PROTOCOL.md §19. */
    const val CMD_QUERY_DEVICES = 0x0112
    /**
     * Another device on the buds' list, `[VENDOR]` (PROTOCOL.md §19): `0x0429 01 <MAC>` connect, `02 <MAC>`
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
     * **`0x0401` IS CONFIRMED ON THE DEVICE.** Every write is acked
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
    const val EVT_PUBLIC = 0x0F               // 0x0204 subType: `<flags u16 LE> <data>`; the first flag wins: bit 0 alert-sound volume, bit 4 tap level, [VENDOR]
    const val PUBLIC_FLAG_ALERT_VOLUME = 0x01
    const val PUBLIC_FLAG_TAP_LEVEL = 0x10
    const val EVT_GOLDEN_STATUS = 0x08        // 0x0204 subType: Golden Sound test status, [VENDOR]
    const val EVT_EAR_SCAN = 0x0E             // 0x0204 subType: ear scan result, [CAPTURE] (pushed unregistered)

    const val CMD_GAME_SOUND = 0x0423
    /** Game sound type: `0x0423 <type> 01`, read `0x012B` -> `00 <selected> <count> <types>`. `[VENDOR]` (PROTOCOL.md §18). */
    const val CMD_QUERY_GAME_SOUND = 0x012B
    /** Spatial type (`0` off, `1` fixed, `2` head tracking): `0x0422 <type>`, read `0x012A` -> `00 <type>`, pushed as `0x0510 <type>`. `[VENDOR]` */
    const val CMD_SET_SPATIAL_TYPE = 0x0422
    const val CMD_QUERY_SPATIAL_TYPE = 0x012A
    const val CMD_SPATIAL_TYPE_PUSH = 0x0510
    /** Who renders 3D audio: `0x041E 00` the buds, `01` the phone. realme Link sends `00` on connect. `[VENDOR]` (PROTOCOL.md §18). */
    const val CMD_SPATIAL_SOURCE = 0x041E
    /**
     * Codec picker on `hiRes` models (PROTOCOL.md §18), `[VENDOR]`, unverified on buds: read the
     * current codec with `0x0114` -> `00 <codec>`, the offered ones with `0x0123` -> `00 <u16 LE mask>`
     * (bit k = codec k + 1), write `0x041A <codec> <hiRes> 00`. The buds restart after the write.
     */
    const val CMD_QUERY_CODEC = 0x0114
    const val CMD_QUERY_CODEC_LIST = 0x0123
    const val CMD_SET_CODEC = 0x041A
    const val CMD_GAME_EQ_STATE = 0x0420
    /** Head gestures' mapping: `0x0431 <type>`, `0` nod answers / shake declines, `1` the reverse. `[VENDOR]` */
    const val CMD_SET_HEAD_MOTION_TYPE = 0x0431
    /** Read with `0x0134` (empty) → `00 <type>`; a change also comes as the `0x0204` push [EVT_HEAD_MOTION_TYPE]. `[VENDOR]` */
    const val CMD_QUERY_HEAD_MOTION_TYPE = 0x0134
    const val EVT_HEAD_MOTION_TYPE = 0xF5
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

    /** 0xC0..0xDF belongs to the feature-sync engine (see docs/FEATURE-SYNC.md): skip it. */
    @Synchronized
    private fun nextSeq(): Int {
        val seq = seqCounter
        seqCounter = if (seqCounter >= 0xBF) 0x01 else seqCounter + 1
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

    /** Time request `0x0500` from the buds (PROTOCOL.md §11). Moved to [Replies]. */
    const val REQ_TIME = Replies.REQ_TIME

    /** Find phone request from realme buds: `0x0502 05` ring, `06` stop (PROTOCOL.md §11). Not answered. `[VENDOR]` */
    const val REQ_FIND_PHONE = 0x0502

    /** Subscribe one event, for buds without `0x0205` in their bitmap. `[VENDOR]` */
    const val CMD_REGISTER_ONE = 0x0201

    /** `0x8200` = `00 <count> <codes>`, or null. */
    fun offeredEvents(payload: ByteArray): Set<Int>? {
        if (payload.size < 2 || payload[0].toInt() != 0) return null
        val n = payload[1].toInt() and 0xFF
        if (payload.size < 2 + n) return null
        return (0 until n).map { payload[2 + it].toInt() and 0xFF }.toSet()
    }

    // --- Golden Sound test (PROTOCOL.md §20), `[CAPTURE]` 2026-09-29 + `[VENDOR]` ---
    const val CMD_GOLDEN_DETECT = 0x040D      // q0: ear scan `04 01|00 <uid>`, hearing test `02 01|00`
    const val CMD_GOLDEN_RECORD = 0x040E      // w0 / m1: tone, stop tone, apply record
    const val CMD_GOLDEN_RESTORE = 0x0411     // F0: restore data (the description id)
    const val CMD_GOLDEN_SCAN_DATA = 0x0415   // v0: ear-scan data
    const val CMD_GOLDEN_FILTER = 0x0116      // W: hearing filter, the reply carries the enhance type
    const val CMD_GOLDEN_SCAN_FILTER = 0x011F // M: ear-scan filter (the graph only)
    const val CMD_GOLDEN_ACTIVE = 0x0115      // the record on the buds
    const val CMD_GOLDEN_ACTIVE_SCAN = 0x011E // the ear-scan data on the buds

    /**
     * The `0x8105` text as HeyMelody shows it: the `versionType` 2 versions in reply order, joined
     * with dots. Buds 4 `1,2,138,2,2,138,3,1,01,3,2,105` -> `138.138.105`, as on HeyMelody's own
     * screen `[CAPTURE]` 2026-09-27. Null when there is none.
     */
    fun firmwareVersion(text: String): String? = text.split(',').chunked(3)
        .filter { it.size == 3 && it[1].trim() == "2" }
        .joinToString(".") { it[2].trim() }
        .ifEmpty { null }

    /**
     * The hold cycle's noise type, the second byte of [Commands.holdAncModes] and [Commands.queryNoiseSwitchModes].
     * `[VENDOR]`: 1 for one cycle shared by both buds, 3 (left) / 4
     * (right) on models whose `longPressType` gives each bud its own hold.
     */
    const val HOLD_TYPE_SHARED = 1
    const val HOLD_TYPE_LEFT = 3
    const val HOLD_TYPE_RIGHT = 4

    /**
     * `act 0x02`, toggled `[CAPTURE]` between `fn 0x00` and `fn 0x1D` while he switched
     * HeyMelody's on-call **double tap** row between None and Answer/end call, twice. The
     * bytes are exactly what shipped from the vendor app; the ENGLISH LABEL ("double tap")
     * is `[TESTED]` on a real call — see [KeyFunctionParser.BUTTON_ON_CALL].
     *
     * Named (not inlined) so [com.spizganed.quickbuds.ui.OnCallConfigStore] can reverse-map a
     * device reading back to a row without duplicating these numbers.
     */
    const val ON_CALL_ACT_DOUBLE_TAP = 0x02
    const val ON_CALL_FN_ANSWER_END = 0x1D

    /**
     * `act 0x06`, toggled `[CAPTURE]` between `fn 0x00` and `fn 0x1C` while he switched
     * HeyMelody's on-call **long hold** row between None and Decline call, twice. Same
     * evidence as the double-tap pair above: the bytes are measured, and the "long hold"
     * label is `[TESTED]` on a real call.
     */
    const val ON_CALL_ACT_LONG_HOLD = 0x06
    const val ON_CALL_FN_DECLINE = 0x1C

    /**
     * `[VENDOR]`: callControl 28 / 32 are `act 0x01` (single tap),
     * 29 / 33 / 36 `act 0x02`, 30 / 34 `act 0x03`, 31 / 35 `act 0x06`; all `btn 0x06`, `dev 0x04`.
     */
    const val ON_CALL_ACT_SINGLE_TAP = 0x01

    // --- Equalizer, all [CAPTURE] 2026-09-23 (PROTOCOL.md §17) ---

    /** Little-endian unsigned 16-bit value at [i]. */
    fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }
}

// ===== UserInteractionParser =====

/**
 * Decoder for the 0x0204 "user interaction" active report — the F1 family.
 *
 * Frame shape (captured on Buds 4, 5 samples across several sessions):
 *
 *     AA 0D 00 00 04 02 FF 06 00 F1 01 01 XX YY 02
 *     |  |  |  |  |  |  |  |  |  |  |  |  |  |  +-- payload[5] = context/field
 *     |  |  |  |  |  |  |  |  |  |  |  |  +--+----- payload[4] = function
 *     |  |  |  |  |  |  |  |  |  |  |  +---------- payload[3] = action
 *     |  |  |  |  |  |  |  |  |  |  +------------- payload[2] = button id
 *     |  |  |  |  |  |  |  |  +------------------- payload[1] = side (0x01 L, 0x02 R)
 *     |  |  |  |  |  |  |  +---------------------- payload[0] = subType 0xF1
 *     |  |  |  |  |  |  +------------------------- 0x06  (payload length)
 *     |  |  |  |  |  +---------------------------- 0xFF  (event marker)
 *     |  |  |  |  +------------------------------- 0x02  (sub-command)
 *     |  |  |  +---------------------------------- 0x04  (active report)
 *     +--+--+------------------------------------- length / flags
 *
 * SOURCE (not guessed): Zhaoyi-ya/OppoPodsManager, `Models/UserInteractionEventInfo.cs`.
 * That class is explicitly documented as the data body of "0x0204 subType=0xF1", derived
 * from HeyMelody `[VENDOR]`. Its field order matches this
 * frame byte for byte, including the trailing int16 options array.
 *
 *     byte0 = side        0x01 = left, 0x02 = right (confirmed by that project)
 *     byte1 = button id   model dependent (0x06 on Enco Air5 Pro)
 *     byte2 = action      0x00 single, 0x02 double, 0x03 triple,
 *                         0x04 long press, 0x07 slide up, 0x08 slide down
 *                         for the taps this is ALSO the key-function `act` — see below
 *     byte3 = the bound FUNCTION, as the buds report it — matching the 0x8108 reply's
 *                         `function` byte. See the correspondence below.
 *     byte4 = context
 *     then  = int16 options from payload[6], little endian
 *
 * THE OLD CLAIM HERE WAS WRONG — do not restore it. This comment used to say "ANC
 * changes made on the BUDS raise no 0x0204 subtype event — verified three times".
 * They DO: subType 0x03, mapped in AncEventParser, and the belief survived three
 * captures only because `noteUnattributed` blanket-excludes cmd 0x0204, so an
 * undecoded subType printed nothing at all (PROTOCOL.md §14).
 *
 * WHAT THE F1 FRAME IS FOR: it says WHEN, and which side and action. On a real ANC
 * hold, F1 lands FIRST and the 0x0204 subType 0x03 frame follows 1.3-2.0 s later.
 * So a capture window that stops at the F1 line misses the answer, and the F1 log
 * line stays diagnostic evidence, never a control signal.
 *
 * THE CONCLUSION THAT USED TO SIT HERE WAS TOO STRONG, and it is kept only so it is not
 * restored. It read "byte3 is identical for every gesture of the same action+side,
 * therefore it cannot say what the gesture did", with two long presses — one cycling ANC,
 * one firing voice assistant — offered as the proof. That example cannot exist: the
 * ANC-cycle hold is offered only the ANC modes, so that hold cannot be bound to voice
 * assistant, and a binding is one function per (device, button, action) anyway. The
 * observation never supported the conclusion.
 *
 * BYTE2 AND BYTE3 ARE THE KEY-FUNCTION MAPPING. PROTOCOL.md §15 asked whether byte3
 * carries the bound function. It does — and byte2 carries the key-function *action*
 * number, so the F1 frame is the key-function entry for the gesture that fired:
 *
 *     F1 payload      byte2  byte3   key-function entry      its fn
 *     01 01 01 01 0x    01     01     dev=01/btn=01 act 01     0x01
 *     01 01 02 06 0x    02     06     dev=01/btn=01 act 02     0x06
 *     01 01 03 05 0x    03     05     dev=01/btn=01 act 03     0x05
 *     01 01 04 08 0x    04     08     dev=01/btn=01 act 04     0x08
 *
 * Four independent slots, four DIFFERENT function values, all matching — that is not a
 * coincidence, and byte2 == the key-function `act` in every one of them. The F1 numbering
 * is therefore NOT the key-function numbering (as PROTOCOL.md section 15 already warned); byte2 here is
 * already a key-function `act`, not an F1 action id.
 *
 * AN EARLIER REVISION OF THIS COMMENT CLAIMED THE OPPOSITE, using this frame as its
 * counter-example:
 *
 *     payload F1 01 01 00 00 03   byte2 = 0x00, byte3 = 0x00 (parsed as "single tap")
 *     key-function table:         act 01 -> fn 0x01
 *
 * byte3 (0x00) is not fn (0x01), so the theory was declared dead. That was too hasty:
 * byte2 = 0x00 is not a key-function `act` at all (the table has none — its acts are
 * 1..6), so this frame is not the single-tap BINDING entry and is not comparable to one.
 * It is very likely a different event (a touch/tap report rather than a binding firing),
 * which also means the [OSS] reading of 0x00 as "single tap" is suspect.
 *
 * The frame carries the function: byte2 is the key-function act and byte3 its fn.
 * A slide reports 0B/0C or 05/06, so byte3 is the effective function, not the slot's own
 * `fn`. The frame is diagnostic only; do not use it as a control signal.
 */
object UserInteractionParser {

    /** SubType of the user-interaction active report. */
    const val EVT_USER_INTERACTION = 0xF1

    /** True if this 0x0204 payload is a user-interaction (button/gesture) event. */
    fun isUserInteractionEvent(payload: ByteArray): Boolean {
        if (payload.size < 3) return false
        return payload[0].toInt() and 0xFF == EVT_USER_INTERACTION
    }

    private fun sideName(v: Int): String = when (v) {
        0x01 -> "L"
        0x02 -> "R"
        else -> "?0x%02X".format(v)
    }

    private fun actionName(v: Int): String = when (v) {
        0x00 -> "single tap"
        0x02 -> "double tap"
        0x03 -> "triple tap"
        0x04 -> "long press"
        0x07 -> "slide up"
        0x08 -> "slide down"
        else -> "action=0x%02X".format(v)
    }

    /**
     * Human-readable one-liner for the log, e.g.
     * "L btn0x01 long press (ctx=0x02 opts=[2])".
     */
    fun describe(payload: ByteArray): String {
        if (!isUserInteractionEvent(payload)) return "not a user-interaction event"
        // payload[0] = 0xF1, fields start at payload[1].
        val side = payload.getOrElse(1) { 0 }.toInt() and 0xFF
        val button = payload.getOrElse(2) { 0 }.toInt() and 0xFF
        val action = payload.getOrElse(3) { 0 }.toInt() and 0xFF
        val modifier = payload.getOrElse(4) { 0 }.toInt() and 0xFF
        val context = payload.getOrElse(5) { 0 }.toInt() and 0xFF

        // Options: little-endian int16 pairs from payload[5] onward, matching
        // UserInteractionEventInfo's own walk (pos += 2 while pos+1 < end).
        // TODO(capture): this loop starts the options at payload[5], but the class comment
        // and Decode start them at payload[6]. payload[5] is also the context byte.
        // A capture with an option must decide which offset is correct.
        val options = mutableListOf<Int>()
        var pos = 5
        while (pos + 1 < payload.size) {
            options.add(OpoProtocol.u16(payload, pos))
            pos += 2
        }

        val sb = StringBuilder()
        sb.append(sideName(side))
        sb.append(" btn=0x").append("%02X".format(button))
        sb.append(' ').append(actionName(action))
        if (modifier != 0) sb.append(" +0x").append("%02X".format(modifier))
        sb.append(" ctx=0x").append("%02X".format(context))
        if (options.isNotEmpty()) sb.append(" opts=[").append(options.joinToString(",")).append(']')
        return sb.toString()
    }
}
