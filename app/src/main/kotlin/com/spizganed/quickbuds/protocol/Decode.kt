package com.spizganed.quickbuds.protocol

import com.spizganed.quickbuds.protocol.Wire.Message

/**
 * The typed reading of docs/PROTOCOL.md: decodes what the buds send. The topic decoders of this
 * package do the parsing. The replies and batch come from [Replies]. Input is a [Message] from the
 * link layer; output is [Msg]. The Rust port is `core/src/msg.rs`.
 */

object Decode {
    const val PUSH = 0x0204
    private const val REPLY = 0x8000
    private val NAMED = setOf(1, 2, 3, 4, 5, 6, 8, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F, 0xF1, 0xF2, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8)

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int): Int? = if (i + 2 <= b.size) u(b, i) or (u(b, i + 1) shl 8) else null
    private fun be32(b: ByteArray, i: Int): Int? =
        if (i >= 0 && i + 4 <= b.size) (u(b, i) shl 24) or (u(b, i + 1) shl 16) or (u(b, i + 2) shl 8) or u(b, i + 3) else null
    /** 1 to 4 bytes, little endian. */
    private fun leN(b: ByteArray, from: Int): Int = (from until minOf(b.size, from + 4)).reversed().fold(0) { v, i -> v shl 8 or u(b, i) }
    /** A MAC on the wire is reversed; this returns it in written order. */
    private fun mac(b: ByteArray, i: Int): Mac? = if (i + 6 <= b.size) (5 downTo 0).map { u(b, i + it) } else null
    private fun ints(b: ByteArray, from: Int = 0) = (from until b.size).map { u(b, it) }

    /** Decodes one message. null: not a message this layer knows, or a malformed one. */
    fun decode(m: Message): Msg? {
        val p = m.payload
        if (m.cmd == PUSH) return push(m)
        return when (m.cmd) {
            0x8100 -> Bitmap.parse(p)?.let { Msg.BitmapReply(it) }
            0x8103 -> if (p.size == 4 && p[0].toInt() == 0) Msg.ProductId(u(p, 1) or (u(p, 2) shl 8) or (u(p, 3) shl 16)) else null
            0x8105 -> firmware(p)?.let { Msg.Firmware(it) }
            0x8200 -> if (p.size >= 2 && p[0].toInt() == 0 && p.size >= 2 + u(p, 1)) Msg.Offered(ints(p, 2).take(u(p, 1))) else null
            0x8205 -> Msg.Registered
            0x8106 -> BatteryParser.parse(frame(m))?.let { Msg.Battery(cells(it)) }
            0x8109 -> WearingStatusParser.parseQueryResponse(p)?.let { Msg.Wear(wear(it)) }
            0x810C -> ok(p)?.let { anc(it, 0) }?.let { Msg.AncMsg(it) }
            0x8108 -> {
                val t = KeyFunctionParser.parse(p)
                Msg.Gestures(
                    t.entries.map { Binding(it.deviceType, it.button, it.action, it.function) },
                    t.status,
                    t.trailing.map { it.toInt() and 0xFF },
                )
            }
            0x810D -> ok(p)?.let { features(it) }?.let { Msg.Features(it) }
            0x810F -> ok(p)?.takeIf { it.isNotEmpty() }?.let { Msg.EqCurrent(u(it, 0)) }
            0x8122 -> EqCodec.parseList(p)?.let { list -> Msg.EqCustom(list.map { preset(it) }) }
            0x8124 -> ok(p)?.takeIf { it.size >= 3 }?.let { Msg.BassLevel(it[2].toInt()) }
            0x812C -> Commands.parseBassBands(p)?.let { Msg.BassBands(it) }
            0x0503 -> featurePush(p)?.let { Msg.FeaturePush(it) }
            0x0504 -> if (p.isNotEmpty()) Msg.EqCurrent(u(p, 0)) else null
            0x8130 -> ok(p)?.firstOrNull()?.let { Msg.AlertVolume(it.toInt() and 0xFF) }
            0x8133 -> ok(p)?.takeIf { it.size >= 2 }?.let { Msg.TapLevel(u(it, 0), u(it, 1)) }
            0x812A -> ok(p)?.firstOrNull()?.let { Msg.Spatial(it.toInt() and 0xFF) }
            0x0510 -> if (p.isNotEmpty()) Msg.Spatial(u(p, 0)) else null
            0x8134 -> ok(p)?.firstOrNull()?.let { Msg.HeadGesture(it.toInt() and 0xFF) }
            0x8114 -> ok(p)?.firstOrNull()?.let { Msg.Codec(it.toInt() and 0xFF) }
            0x8123 -> ok(p)?.let { le16(it, 0) }?.let { Msg.CodecMask(it) }
            0x812B -> ok(p)?.takeIf { it.size >= 2 }?.let { Msg.GameSound(u(it, 0), ints(it, 2).take(u(it, 1))) }
            0x811A -> ok(p)?.firstOrNull()?.let { Msg.PersonalAncStored(it.toInt() != 0) }
            0x8112 -> ok(p)?.let { devices(it) }?.let { Msg.Devices(it) }
            0x8132 -> when {
                p.size >= 3 && p[0].toInt() == 0 && p[1].toInt() == 2 && p[2].toInt() == 0 -> Msg.Preferred(null)
                p.size >= 9 && p[0].toInt() == 0 && p[1].toInt() == 2 -> Msg.Preferred(ints(p.copyOfRange(3, 9)))
                else -> null
            }
            0x8115 -> ok(p)?.let { goldenRecord(it) }
            0x811E -> ok(p)?.let { earScanBody(it, 0) }?.let { Msg.GoldenScan(it.first, it.second) }
            0x8116 -> ok(p)?.takeIf { it.size >= 9 }?.let { Msg.GoldenFilter(be32(it, 0)!!, u(it, 8), curves(it, false)) }
            0x811F -> ok(p)?.let { d -> be32(d, 0)?.let { id -> curves(d, true)?.let { Msg.GoldenScanFilter(id, it) } } }
            else -> when {
                m.cmd and 0x8000 == 0 -> request(m.cmd)?.let { Msg.Ask(it) }
                m.cmd and 0xFF00 == 0x8400 && p.isNotEmpty() -> Msg.Ack(m.cmd and 0x7FFF, u(p, 0), ints(p, 1))
                else -> null
            }
        }
    }

    /** The codecs in a `0x8123` mask (bit k = codec k + 1); LHDC (7) is dropped when LHDC V5 (8) is offered too. */
    fun codecsOf(mask: Int): List<Int> =
        (1..8).filter { mask and (1 shl (it - 1)) != 0 }.let { if (8 in it) it - 7 else it }

    /** What a request from the buds is. null: the command is in a known group and not a request. */
    private fun request(cmd: Int): Request? = when (cmd) {
        0x0500 -> Request.Time
        0x050C -> Request.AppList
        0x051C -> Request.Capabilities
        0x051D -> Request.SpatialMode
        0x0501, 0x0502 -> Request.Camera(cmd)
        else -> if (!Replies.knownGroup(cmd)) Request.Unknown(cmd) else null
    }

    /** `<kind> <len u16 LE> <data> <id u32 BE>`, from [from]. */
    private fun earScanBody(p: ByteArray, from: Int): Pair<Int, List<Int>>? {
        val len = le16(p, from + 1) ?: return null
        val id = be32(p, from + 3 + len)
        return if (len == 0 || id == null) null else id to ints(p, from + 3).take(len)
    }

    /** `03 0C <12 x (side freq value)> <id u32 BE> <name>`. */
    private fun goldenRecord(d: ByteArray): Msg? {
        if (d.size < 2 || u(d, 1) != 12) return null
        val end = 2 + 36
        val id = be32(d, end) ?: return null
        val values = IntArray(12)
        for (i in 0 until 12) {
            val side = u(d, 2 + i * 3); val freq = u(d, 3 + i * 3)
            if (side !in 1..2 || freq !in 1..6) return null
            values[(side - 1) * 6 + freq - 1] = d[4 + i * 3].toInt()
        }
        return Msg.GoldenRecord(id, String(d, end + 4, d.size - end - 4, Charsets.UTF_8), values.toList())
    }

    /**
     * Filter floats, little endian, left ear then right. `0x8116`: `<id> 04 <count LE> <packet> <type> <floats>`.
     * `0x811F`: `<id> 04 <rate LE> <count LE> <packet> <floats>`. [d] starts at the id; a count is a multiple of 12.
     */
    private fun curves(d: ByteArray, scan: Boolean): Curves? {
        if (d.size < 10 || d[4].toInt() != 4) return null
        val rate = if (scan) le16(d, 5)!! else 44100
        val count = if (scan) le16(d, 7)!! else le16(d, 5)!!
        val start = if (scan) 10 else 9
        if (count == 0 || count % 12 != 0 || start + count * 4 > d.size) return null
        val f = (0 until count).map { java.lang.Float.intBitsToFloat(u(d, start + it * 4) or (u(d, start + it * 4 + 1) shl 8) or (u(d, start + it * 4 + 2) shl 16) or (u(d, start + it * 4 + 3) shl 24)) }
        return Curves(rate, f.subList(0, count / 2), f.subList(count / 2, count))
    }

    /** The answer to a message the buds sent on their own, or null (no answer is due). [unix]: the phone's clock. */
    fun answer(m: Message, unix: Long): Message? =
        Replies.answerBody(m.cmd, m.payload, unix)?.let { Message(m.cmd or REPLY, m.seq, it) }

    /** The bytes after a `00` status byte. A reply with another status carries no data. */
    private fun ok(p: ByteArray): ByteArray? = if (p.isNotEmpty() && p[0].toInt() == 0) p.copyOfRange(1, p.size) else null

    /** ANC data: `01 01 <mode LE>`, `01 02 <level>`, `02 <kind> <mask LE>`, `04 01 <mask LE>`. Used by `0x810C` and push `03`. */
    private fun anc(p: ByteArray, from: Int): Anc? {
        val n = p.size - from
        fun d(i: Int) = u(p, from + i)
        return when {
            n >= 3 && d(0) == 1 && d(1) == 1 -> Anc.Mode(leN(p, from + 2))
            n >= 3 && d(0) == 1 && d(1) == 2 -> Anc.Level(d(2))
            n >= 3 && d(0) == 2 -> Anc.HoldCycle(d(1), leN(p, from + 2))
            n >= 3 && d(0) == 4 && d(1) == 1 -> Anc.Smart(leN(p, from + 2))
            else -> null
        }
    }

    /** The preset keeps the tag bytes; the range comes from them. */
    private fun preset(p: EqCodec.Preset) = EqPreset(
        p.id, p.name, p.selected, p.tag[0].toInt() to p.tag[1].toInt(), p.freqs.zip(p.gains),
    )

    /** `00 <count>` then UTF-8 `device,type,version` triples joined by commas. */
    private fun firmware(p: ByteArray): List<Triple<Int, Int, String>>? {
        if (p.size < 2 || p[0].toInt() != 0) return null
        val parts = String(p, 2, p.size - 2, Charsets.UTF_8).split(',').map { it.trim() }
        val v = parts.chunked(3).filter { it.size == 3 }.mapNotNull { c ->
            val d = c[0].toIntOrNull(); val t = c[1].toIntOrNull()
            if (d == null || t == null) null else Triple(d, t, c[2])
        }
        return v.ifEmpty { null }
    }

    /** `0x0503`: `<count> <id> <value>...`; bytes after the last pair are ignored. */
    private fun featurePush(p: ByteArray): Map<Int, Int>? {
        if (p.isEmpty()) return null
        val count = u(p, 0)
        if (p.size < 1 + count * 2) return null
        return (0 until count).associate { u(p, 1 + it * 2) to u(p, 2 + it * 2) }
    }

    /** `0x810D`: `<count>` then `<id> <value>` pairs. A count of 0 is an empty map; a short list keeps the whole pairs. */
    private fun features(p: ByteArray): List<Pair<Int, Int>>? {
        if (p.isEmpty()) return null
        val n = u(p, 0)
        return (0 until n).mapNotNull { i ->
            val o = 1 + i * 2
            if (o + 1 < p.size) u(p, o) to u(p, o + 1) else null
        }
    }

    /** The battery decoder reads a whole packet, so rebuild one from the message. */
    private fun frame(m: Message) = OpoProtocol.buildPacket(m.cmd, m.seq, m.payload)

    /** Map the battery result to cells: left, right, case, with level 0 left out. */
    private fun cells(r: BatteryParser.Result) = listOfNotNull(
        r.left?.let { Cell(1, it.level, it.isCharging) },
        r.right?.let { Cell(2, it.level, it.isCharging) },
        r.case?.let { Cell(3, it.level, it.isCharging) },
    )

    /** Map the wearing result to (part, state) pairs in part order. */
    private fun wear(r: WearingStatusParser.Result) = listOfNotNull(
        if (r.leftValid) 1 to r.leftStatus else null,
        if (r.rightValid) 2 to r.rightStatus else null,
        if (r.caseStatus != -1) 3 to r.caseStatus else null,
    )

    /** `<count>` then `<MAC 6> <len> <state> <flags> <nameLen> <name>`. */
    fun devices(p: ByteArray, from: Int = 0): List<Device>? {
        if (from >= p.size) return null
        var i = from + 1
        val out = ArrayList<Device>()
        repeat(u(p, from)) {
            if (i + 10 > p.size) return null
            val k = u(p, i + 9)
            if (i + 10 + k > p.size) return null
            out += Device(mac(p, i)!!, String(p, i + 10, k, Charsets.UTF_8), u(p, i + 7) == 2, u(p, i + 8))
            i += 10 + k
        }
        return out
    }

    /** `<count>` then `<MAC 6> <times u16 LE> <nameLen> <name>`. */
    private fun linked(p: ByteArray, from: Int): List<Linked>? {
        if (from >= p.size) return null
        var i = from + 1
        val out = ArrayList<Linked>()
        repeat(u(p, from)) {
            if (i + 9 > p.size) return null
            val k = u(p, i + 8)
            if (i + 9 + k > p.size) return null
            out += Linked(mac(p, i)!!, le16(p, i + 6)!!, String(p, i + 9, k, Charsets.UTF_8))
            i += 9 + k
        }
        return out
    }

    private fun public(p: ByteArray, from: Int): Public? {
        if (p.size - from < 3) return null
        val flags = le16(p, from)!!
        val d = from + 2
        return when {
            flags and 1 != 0 -> Public.AlertVolume(u(p, d))
            flags and 2 != 0 -> Public.Other(flags)
            flags and 4 != 0 -> {
                val auto = u(p, d) == 0
                if (auto) Public.Handheld(true, null) else if (p.size - from > 8) Public.Handheld(false, mac(p, d + 1)) else null
            }
            flags and 16 != 0 -> Public.TapLevel(u(p, d))
            else -> Public.Other(flags)
        }
    }

    private fun triangle(p: ByteArray, from: Int): Triangle? {
        val flags = le16(p, from) ?: return null
        var i = from + 2
        fun byte(): Int? = if (i < p.size) u(p, i++) else null
        var t = Triangle()
        if (i >= p.size) return t
        if (flags and 1 != 0) t = t.copy(capability = byte())
        if (flags and 2 != 0) t = t.copy(version = byte())
        if (flags and 4 != 0) t = t.copy(wear = byte())
        if (flags and 8 != 0) t = t.copy(status = byte())
        if (flags and 16 != 0) byte()?.let { k -> if (i + k <= p.size) t = t.copy(relatedName = String(p, i, k, Charsets.UTF_8)) }
        return t
    }

    /** `payload[0]` is the event code. */
    private fun push(m: Message): Msg? {
        val p = m.payload
        if (p.isEmpty()) return null
        val code = u(p, 0)
        val n = p.size - 1  // bytes after the code
        fun d(i: Int) = u(p, 1 + i)
        return when {
            code == 0x01 -> BatteryParser.parseActive(frame(m))?.let { Msg.Battery(cells(it)) }
            code == 0x02 -> WearingStatusParser.parseActiveReport(p)?.let { Msg.Wear(wear(it)) }
            code == 0x03 -> anc(p, 1)?.let { Msg.AncMsg(it) }
            code == 0x04 -> {
                val r = (0 until n / 2).map { d(it * 2) to d(it * 2 + 1) }
                Msg.FitTest(r.firstOrNull { it.first == 1 }?.second, r.firstOrNull { it.first == 2 }?.second)
            }
            code == 0x05 -> GameModeParser.candidates(p)?.let { (at1, at3) -> Msg.GameMode(at1, at3) }
            code == 0x06 -> devices(p, 1)?.let { Msg.Devices(it) }
            code == 0x08 && n >= 2 -> Msg.Hearing(d(0), d(1))
            code == 0x0A && n >= 1 -> Msg.ZenState(d(0))
            code == 0x0B && n >= 1 -> Msg.PersonalAnc(d(0))
            code == 0x0D -> triangle(p, 1)?.let { Msg.TriangleMsg(it) }
            code == 0x0E -> {
                val len = le16(p, 2) ?: return null
                val id = be32(p, 4 + len)
                if (len == 0 || id == null) null else Msg.EarScan(id, ints(p, 4).take(len))
            }
            code == 0x0F -> public(p, 1)?.let { Msg.PublicMsg(it) }
            code == 0xF1 && n >= 5 -> Msg.Gesture(
                d(0), d(1), d(2), d(3), d(4),
                (0 until (n - 5) / 2).map { (d(5 + it * 2) or (d(6 + it * 2) shl 8)).toShort().toInt() }
            )
            code == 0xF2 -> linked(p, 1)?.let { Msg.LinkedMsg(it) }
            code == 0xF4 -> Msg.Diagnostic(String(p, 1, n, Charsets.UTF_8))
            code == 0xF5 && n >= 1 -> Msg.HeadGesture(d(0))
            code == 0xF6 && n >= 1 -> Msg.HeadTry(d(0))
            code == 0xF7 && n >= 1 -> Msg.VoiceAssist(
                listOf(4 to 2, 2 to 1, 1 to 0).firstOrNull { d(0) and it.first != 0 }?.second, d(0) and 0x80 != 0
            )
            code == 0xF8 && n >= 1 -> Msg.LinkSwitch(d(0) and 1, (d(0) shr 1) and 1, (d(0) shr 2) and 1)
            // A named code with too few bytes is malformed and dropped.
            code !in NAMED -> Msg.Unused(code, ints(p, 1))
            else -> null
        }
    }

    /** `0x812F`: `00 <count>`, then `<cmd LE> <len LE> <reply payload>`. Each answer is the reply `cmd | 0x8000` with the batch's seq. */
    fun unbatch(m: Message): List<Message> =
        Replies.batchReplies(m.payload).map { (cmd, payload) -> Message(cmd, m.seq, payload) }
}
