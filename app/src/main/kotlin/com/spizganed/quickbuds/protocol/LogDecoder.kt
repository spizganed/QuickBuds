package com.spizganed.quickbuds.protocol

/**
 * Decodes raw PacketLogger lines (e.g. "TX[handshake]: AA 07 00 00 ...")
 * into human-readable descriptions for the Dev Tools screen.
 *
 * Lines that don't contain a hex payload (pure status messages like
 * "Already connecting/connected, ignoring." or "WEAR EVT: L=EAR ...")
 * are passed through as-is for the human-readable view.
 */
object LogDecoder {

    /**
     * Replies the connection manager decodes itself (its own log line follows, e.g. `CODEC:`), named here
     * so the raw line does not read "Unknown packet". A reply handled there and missing here = add it.
     */
    val REPLY_NAMES = mapOf(
        0x810F to "EQ current", 0x8112 to "Device list", 0x8114 to "Codec", 0x8115 to "Hearing profile",
        0x8116 to "Hearing profile", 0x811A to "Personalised ANC", 0x811E to "Hearing profile",
        0x811F to "Hearing profile", 0x8123 to "Codec list", 0x8124 to "Bass level", 0x812A to "Spatial type",
        0x812B to "Game sound", 0x812F to "Batch reply (answers follow as RX[batch])", 0x8132 to "Preferred device",
        0x8133 to "Tap sensitivity", 0x8134 to "Head gestures", 0x8200 to "Offered events", 0x8205 to "Notifications registered",
        0x8427 to "Alert volume ack"
    )

    /** `0x0204` subTypes decoded in the connection manager (its own log line follows), named the same way. */
    private val PUSH_NAMES = mapOf(
        0x06 to "dual connection devices", OpoProtocol.EVT_GOLDEN_STATUS to "hearing test status",
        OpoProtocol.EVT_PERSONAL_NOISE to "personalised ANC result", OpoProtocol.EVT_EAR_SCAN to "ear scan",
        0xF2 to "request (answered)", 0xF4 to "JSON request (answered)", OpoProtocol.EVT_HEAD_MOTION_TYPE to "head motion type"
    )

    enum class Direction { TX, RX, STATUS }

    /** Describes a decoded packet for human-readable display. */
    data class DecodedLine(
        val rawTimestamp: String,
        val direction: Direction,
        val label: String?,
        val description: String,
        val hexPayload: String?,
        /** "0x8106 · 12 B · 00 5A ...": command, payload size and the whole payload, for packets. */
        val detail: String? = null,
        /** The description names no field: an unknown command, sub-type or frame. Shown apart in Dev Tools. */
        val unknown: Boolean = false
    )

    /**
     * Timestamp for the human-readable view: "HH:mm:ss", milliseconds removed.
     * The raw hex view keeps the full "HH:mm:ss.SSS" from the log line.
     */
    fun displayTime(timestamp: String): String {
        val dot = timestamp.lastIndexOf('.')
        return if (dot > 0) timestamp.substring(0, dot) else timestamp
    }

    /**
     * Decode a single PacketLogger line into a human-readable description.
     * For lines with hex payloads (TX/RX), the description is a decoded
     * packet interpretation. For all other lines, the message is passed
     * through unchanged.
     */
    fun decode(line: String): DecodedLine {
        // Split: "HH:mm:ss.SSS  message"
        val sep = line.indexOf("  ")
        val timestamp = if (sep >= 0) line.substring(0, sep) else ""
        val msg = if (sep >= 0) line.substring(sep + 2) else line

        var hexPart: String? = null
        var direction: Direction = Direction.STATUS
        var label: String? = null

        if (msg.startsWith("TX[")) {
            direction = Direction.TX
            val bracketEnd = msg.indexOf(']')
            if (bracketEnd > 2) {
                label = msg.substring(3, bracketEnd)
                hexPart = msg.substring(bracketEnd + 3) // skip "]: "
            }
        } else if (msg.startsWith("RX")) {
            direction = Direction.RX
            // Format: "RX: AA 08 ..." or "RX[cmd=0x8106]: AA 08 ..."
            val bracketStart = msg.indexOf('[')
            if (bracketStart >= 0) {
                val bracketEnd = msg.indexOf(']')
                if (bracketEnd > bracketStart) {
                    label = msg.substring(bracketStart + 1, bracketEnd)
                    hexPart = msg.substring(bracketEnd + 3) // skip "]: "
                }
            } else {
                hexPart = msg.substring(3) // skip "RX: "
            }
        } else {
            // Status messages (WEAR EVT/QRY, connection messages, etc.)
            // These are already human-readable — pass through.
            return DecodedLine(
                rawTimestamp = timestamp,
                direction = Direction.STATUS,
                label = null,
                description = msg,
                hexPayload = null,
                unknown = msg.startsWith("UNATTR") || msg.startsWith("DISCARDED")
            )
        }

        // Try to parse the hex payload into a description
        val payload = parseHex(hexPart)
        val description = if (payload != null && payload.isNotEmpty()) {
            describePacket(payload, direction, label)
        } else {
            msg
        }

        return DecodedLine(
            rawTimestamp = timestamp,
            direction = direction,
            label = label,
            description = description,
            hexPayload = hexPart?.trim(),
            detail = payload?.let { detailOf(it) },
            unknown = UNKNOWN_MARKS.any { description.startsWith(it) } || description.contains("(unparsed)")
        )
    }

    /** Description prefixes that mean nothing was decoded. */
    private val UNKNOWN_MARKS = listOf("Unknown", "Unframed", "Malformed")

    /** Longest payload printed in a detail line, in bytes; the raw tab has the rest. */
    private const val DETAIL_BYTES = 48

    private fun detailOf(raw: ByteArray): String? {
        val data = OppoPacketFramer.normalise(raw)
        if (data.size < 9 || data[0] != 0xAA.toByte()) return "${data.size} B  ${OpoProtocol.bytesToHex(data)}"
        val payLen = OpoProtocol.u16(data, 7)
        val payload = data.copyOfRange(9, minOf(data.size, 9 + payLen))
        val shown = OpoProtocol.bytesToHex(payload.copyOfRange(0, minOf(payload.size, DETAIL_BYTES)))
        val more = if (payload.size > DETAIL_BYTES) " …+${payload.size - DETAIL_BYTES}" else ""
        return "0x%04X  seq %d  %d B%s".format(java.util.Locale.ROOT, OpoProtocol.u16(data, 4), data[6].toInt() and 0xFF, payload.size,
            if (payload.isEmpty()) "" else "  $shown$more")
    }

    private fun parseHex(hexStr: String?): ByteArray? {
        if (hexStr == null) return null
        val clean = hexStr.trim().replace(" ", "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun describePacket(raw: ByteArray, direction: Direction, label: String?): String {
        val data = OppoPacketFramer.normalise(raw)
        if (data.isEmpty() || data[0] != 0xAA.toByte()) {
            return "Unframed data: ${OpoProtocol.bytesToHex(data)}"
        }

        if (data.size < 9) {
            return "Malformed: ${OpoProtocol.bytesToHex(data)}"
        }

        val cmd = OpoProtocol.u16(data, 4)
        val payLen = OpoProtocol.u16(data, 7)
        val payload = if (data.size >= 9 + payLen) data.copyOfRange(9, 9 + payLen) else ByteArray(0)

        val cmdHex = "0x${"%04X".format(cmd)}"

        return when (cmd) {
            OpoProtocol.CMD_HANDSHAKE -> "Handshake init"
            OpoProtocol.CMD_QUERY_PRODUCT_ID -> "Query product ID"
            OpoProtocol.CMD_QUERY_BROADCAST -> "Query broadcast codes"
            OpoProtocol.CMD_QUERY_BATTERY -> "Battery query"
            OpoProtocol.CMD_QUERY_STATUS -> "Status query"
            OpoProtocol.CMD_QUERY_ANC -> "ANC query"
            OpoProtocol.CMD_QUERY_WEARING -> "Wearing query (0x0109)"
            OpoProtocol.CMD_QUERY_KEY_FUNCTION -> "Key function query (0x0108)"
            OpoProtocol.CMD_RESP_KEY_FUNCTION -> KeyFunctionParser.describe(payload)
            OpoProtocol.CMD_REGISTER_NOTIFY -> "Register notifications (0x0205)"
            OpoProtocol.CMD_SET_ANC -> {
                val ancStr = ancPayloadToString(payload)
                if (direction == Direction.TX) "ANC -> $ancStr" else "ANC response: $ancStr"
            }
            OpoProtocol.CMD_SET_FEATURE -> {
                val featStr = featurePayloadToString(payload)
                if (direction == Direction.TX) "Feature -> $featStr" else "Feature response"
            }
            OpoProtocol.CMD_SET_SPATIAL -> "Spatial sound set"
            OpoProtocol.CMD_SPATIAL_SOURCE -> "3D audio on the buds"
            OpoProtocol.CMD_QUERY_EQ, OpoProtocol.CMD_QUERY_EQ_ALL -> "EQ query"
            OpoProtocol.CMD_ACTIVE_REPORT -> {
                // 0x0204: subType in payload[0]
                val sb = StringBuilder()
                if (payload.isNotEmpty()) {
                    val subType = payload[0].toInt() and 0xFF
                    when (subType) {
                        BatteryParser.EVT_BATTERY -> {
                            sb.append("Active report: battery")
                            BatteryParser.parseActive(data)?.let { r ->
                                appendBattery(sb, r)
                            }
                        }
                        OpoProtocol.EVT_WEARING -> {
                            sb.append("Active report: wearing")
                            WearingStatusParser.parseActiveReport(payload)?.let { w ->
                                appendWearing(sb, w)
                            }
                        }
                        OpoProtocol.EVT_GAME_MODE -> {
                            sb.append("Active report: game mode")
                            GameModeParser.parseActive(payload)?.let { on ->
                                sb.append(if (on) " ON" else " OFF")
                            }
                        }
                        OpoProtocol.EVT_FIT_TEST -> {
                            // `04 [dev status] [dev status]`, status 1 good / 0 average / 6 poor.
                            sb.append("Active report: fit test ")
                            sb.append(payload.drop(1).chunked(2).filter { it.size == 2 }.joinToString(", ") {
                                "${if (it[0].toInt() == 1) "L" else "R"}=${it[1].toInt() and 0xFF}"
                            })
                        }
                        AncEventParser.EVT_ANC -> {
                            sb.append("Active report: ANC ")
                            sb.append(AncEventParser.describe(payload))
                        }
                        UserInteractionParser.EVT_USER_INTERACTION -> {
                            sb.append("Button/gesture: ")
                            sb.append(UserInteractionParser.describe(payload))
                        }
                        in PUSH_NAMES -> sb.append("Active report: ${PUSH_NAMES[subType]} ")
                            .append(OpoProtocol.bytesToHex(payload.copyOfRange(1, payload.size)))
                        else -> {
                            // A subType we don't decode. Annotated with its payload head
                            // so an unattributed gesture frame (e.g. subType 0xFF /
                            // "02 FF 06 00 F1 ...") stays visible on this screen.
                            val head = OpoProtocol.bytesToHex(payload.take(6).toByteArray())
                            sb.append("Unknown active report: subType=0x${"%02X".format(subType)} [$head]")
                        }
                    }
                } else {
                    sb.append("Active report (empty payload)")
                }
                sb.toString()
            }
            0x8100 -> "Handshake response"
            0x8103 -> "Product ID response"
            0x8106 -> {
                val sb = StringBuilder("Battery response")
                BatteryParser.parse(data)?.let { r ->
                    appendBattery(sb, r)
                }
                sb.toString()
            }
            0x810C -> {
                // 0x010C IS ONE COMMAND NUMBER CARRYING SEVERAL QUESTIONS, chosen by the
                // REQUEST payload: `01 01` asks the current mode, while `02 01`/`02 03`/`02 04`
                // ask the hold's switch list (OpoProtocol.queryNoiseSwitchModes). The reply
                // cannot name which was asked from the command alone, which is why the raw
                // payload is printed — the hold probe used to come back as the bare words
                // "ANC query response (0x810C)" and told us nothing.
                //
                // The reply reports a mode's child bit (Buds 4: Off = bit 3, Transparency = bit 8),
                // not the bit a SET sends. The bit is printed; the manager's "ANC QUERY:" line names
                // it with the model's table (AncModes).
                //
                // The caveat, stated so the line is not over-trusted: for the SWITCH-LIST
                // query (`02 01`) the reply's shape is unknown, so the name is only meaningful
                // for the current-mode query. The hex is the part that is always true.
                val hex = OpoProtocol.bytesToHex(payload)
                "ANC query response (0x810C): ${AncEventParser.describe(payload)} [$hex]"
            }
            0x8109 -> {
                val sb = StringBuilder("Wearing query response (0x8109)")
                WearingStatusParser.parseQueryResponse(payload)?.let { w ->
                    appendWearing(sb, w)
                }
                sb.toString()
            }
            0x810D -> "Status query response ($cmdHex)"
            0x8105 -> "Firmware version response ($cmdHex): ${OpoProtocol.bytesToHex(data)}"
            0x8122 -> "EQ query response ($cmdHex)"
            0x8130 -> "Alert volume response ($cmdHex): ${OpoProtocol.bytesToHex(data)}"
            // Byte values observed, not confirmed.
            0x0501 -> if (payload.isEmpty()) "Bud state (unparsed)" else "Bud state: " + when (val v = payload[0].toInt() and 0xFF) {
                0x00 -> "Both in case"
                0x01, 0x02 -> "Both out"
                0x05 -> "Right out"
                0x06 -> "Left out"
                else -> "Unknown(0x${"%02X".format(v)})"
            }
            // Acks for the 0x04xx SET commands come back as 0x84xx (cmd | 0x8000). NAMED
            // rather than left to the catch-all, because an unnamed ack is a large part of
            // what made a broken write so hard to read: our successful gesture writes
            // printed as "0x8401 - 00" with nothing saying that was the confirmation being
            // waited for, and the FAILING variant produced no ack line at all. Stating both
            // clearly is what makes "ignored" vs "accepted" visible at a glance.
            in 0x8400..0x84FF -> {
                val setCmd = cmd and 0x7FFF
                val ok = payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0
                val status = if (payload.isEmpty()) "no payload"
                else "status=0x%02X".format(payload[0].toInt() and 0xFF)
                "Ack for set 0x%04X (%s%s)".format(setCmd, status, if (ok) " = ok" else "")
            }
            else -> REPLY_NAMES[cmd]?.let { "$it ($cmdHex): ${OpoProtocol.bytesToHex(payload)}" }
                ?: "Unknown packet $cmdHex"
        }
    }

    private fun appendBattery(sb: StringBuilder, r: BatteryParser.Result) {
        val parts = mutableListOf<String>()
        r.left?.let { parts.add("L=${it.level}${if (it.isCharging) "!" else ""}") }
        r.right?.let { parts.add("R=${it.level}${if (it.isCharging) "!" else ""}") }
        r.case?.let { parts.add("C=${it.level}${if (it.isCharging) "!" else ""}") }
        if (parts.isEmpty()) {
            sb.append(" (no data)")
        } else {
            sb.append(" (${parts.joinToString(" ")})")
        }
    }

    private fun appendWearing(sb: StringBuilder, w: WearingStatusParser.Result) {
        val parts = mutableListOf<String>()
        if (w.leftValid) parts.add("L=${wearLabel(w.leftStatus)}")
        if (w.rightValid) parts.add("R=${wearLabel(w.rightStatus)}")
        if (w.caseStatus >= 0) parts.add("Case=${wearLabel(w.caseStatus)}")
        sb.append(" (${parts.joinToString(" ")})")
    }

    private fun wearLabel(st: Int): String = when (st) {
        3, 7 -> "EAR"
        4 -> "CASE"
        1, 5 -> "OUT"
        0 -> "OFF"
        else -> "?"
    }

    /**
     * The bit a SET_ANC payload sets. Its mode depends on the model (AncModes); the TX line's
     * label names it. Read little endian across bytes: Adaptive's bit 11 is in the second byte.
     */
    private fun ancPayloadToString(payload: ByteArray): String {
        var bits = 0
        for (i in 2 until payload.size) bits = bits or ((payload[i].toInt() and 0xFF) shl ((i - 2) * 8))
        return if (bits == 0) "?" else "bit ${Integer.numberOfTrailingZeros(bits)}"
    }

    private fun featurePayloadToString(payload: ByteArray): String {
        if (payload.size < 2) return "?"
        val fid = payload[0].toInt() and 0xFF
        val on = (payload[1].toInt() and 0xFF) != 0
        val name = when (fid) {
            OpoProtocol.FEATURE_GAME_MODE -> "Game Mode"
            OpoProtocol.FEATURE_AUTO_PLAY_PAUSE -> "Auto Play/Pause"
            OpoProtocol.FEATURE_DUAL_DEVICE -> "Dual Device"
            OpoProtocol.FEATURE_SPATIAL_SOUND -> "Spatial Sound"
            else -> "Feature 0x${"%02X".format(fid)}"
        }
        return "$name ${if (on) "ON" else "OFF"}"
    }
}
