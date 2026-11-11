package com.spizganed.quickbuds.protocol

/**
 * The Google Fast Pair Message Stream (public spec): `<group> <code> <length u16 BE> <data>`.
 * QuickBuds reads only the battery message when another app holds `079A` (PROTOCOL.md section 1).
 */
object FastPairStream {
    const val UUID = "df21fe2c-2515-4fdb-8886-f12c4d67927c"

    data class Message(val group: Int, val code: Int, val data: ByteArray)

    /** One battery value: level 0-100, or null for `7F` (not known). Bit 7 = charging. */
    data class Level(val level: Int?, val charging: Boolean)
    data class Battery(val left: Level, val right: Level, val case: Level)

    /** Takes whole messages from the front of [buf]. Returns them and the count of bytes used. */
    fun split(buf: ByteArray, size: Int = buf.size): Pair<List<Message>, Int> {
        val out = mutableListOf<Message>()
        var p = 0
        while (size - p >= 4) {
            val len = (buf[p + 2].toInt() and 0xFF shl 8) or (buf[p + 3].toInt() and 0xFF)
            if (size - p - 4 < len) break
            out += Message(buf[p].toInt() and 0xFF, buf[p + 1].toInt() and 0xFF, buf.copyOfRange(p + 4, p + 4 + len))
            p += 4 + len
        }
        return out to p
    }

    /** Group `03` (device information), code `03` (battery): left, right, case. */
    fun battery(m: Message): Battery? {
        if (m.group != 3 || m.code != 3 || m.data.size < 3) return null
        fun level(b: Byte): Level {
            val v = b.toInt() and 0xFF
            val pct = v and 0x7F
            return Level(if (pct > 100) null else pct, v and 0x80 != 0)
        }
        return Battery(level(m.data[0]), level(m.data[1]), level(m.data[2]))
    }

    /**
     * The stream's case level that stands in for the link's, or null. It stands in only when the link gives no case
     * level or `0`, and only while it is younger than [maxAgeMs]. [caseHidden]: "Hide old battery levels" is on and
     * the last link report left the case out, so no case level shows.
     */
    fun caseStandIn(stream: Level?, ageMs: Long, maxAgeMs: Long, linkCase: Int?, caseHidden: Boolean): Level? =
        stream?.takeIf { ageMs < maxAgeMs && (linkCase == null || linkCase == 0) && !caseHidden }
}
