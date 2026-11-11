package com.spizganed.quickbuds.protocol

// ===== BatteryParser =====

object BatteryParser {

    /** SubType for 0x0204 active reports that carry a battery update. */
    const val EVT_BATTERY = 0x01

    data class Info(val level: Int, val isCharging: Boolean)
    data class Result(val left: Info?, val right: Info?, val case: Info?)

    /**
     * Parse battery query response (Cmd=0x8106).
     * Payload: [status 00][count][Index, RawValue] pairs. A count of 0, or a list that runs past the end, is no list (null).
     * Index: 1=Left, 2=Right, 3=Case.
     * RawValue: level = val & 0x7F, charging = (val & 0x80) != 0
     * Level 0 is "not known" (the Air7 Pro's closed case, issue #9): left out, as HeyMelody hides it.
     */
    fun parse(data: ByteArray): Result? {
        if (data.size < 9 || data[0] != 0xAA.toByte()) return null
        val cmd = OpoProtocol.u16(data, 4)
        if (cmd != 0x8106) return null
        val payLen = OpoProtocol.u16(data, 7)
        if (data.size < 9 + payLen || payLen < 2 || data[9].toInt() != 0) return null
        val count = data[10].toInt() and 0xFF
        if (count == 0 || payLen < 2 + count * 2) return null

        var l: Info? = null
        var r: Info? = null
        var c: Info? = null
        for (j in 0 until count) {
            val idx = data[11 + j * 2].toInt() and 0xFF
            val raw = data[12 + j * 2].toInt() and 0xFF
            val info = if ((raw and 0x7F) == 0) null else Info(raw and 0x7F, (raw and 0x80) != 0)
            when (idx) {
                1 -> l = info
                2 -> r = info
                3 -> c = info
            }
        }
        return Result(l, r, c)
    }

    /**
     * Parse active/unsolicited battery report (Cmd=0x0204, reportType=0x01).
     * Payload: [reportType=0x01][count][Index, StatusValue]...
     */
    fun parseActive(data: ByteArray): Result? {
        if (data.size < 9 || data[0] != 0xAA.toByte()) return null
        val cmd = OpoProtocol.u16(data, 4)
        if (cmd != 0x0204) return null
        val payLen = OpoProtocol.u16(data, 7)
        if (data.size < 9 + payLen || payLen < 2) return null
        if ((data[9].toInt() and 0xFF) != 0x01) return null
        val count = data[10].toInt() and 0xFF
        if (count == 0 || payLen < 2 + count * 2) return null

        var l: Info? = null
        var r: Info? = null
        var c: Info? = null
        for (j in 0 until count) {
            val idx = 11 + j * 2
            if (idx + 1 >= data.size) break
            val index = data[idx].toInt() and 0xFF
            val raw = data[idx + 1].toInt() and 0xFF
            val info = if ((raw and 0x7F) == 0) null else Info(raw and 0x7F, (raw and 0x80) != 0)
            when (index) {
                1 -> l = info
                2 -> r = info
                3 -> c = info
            }
        }
        return Result(l, r, c)
    }
}

// ===== WearingStatusParser =====

/**
 * Wearing / in-case status parser for OnePlus Buds 4.
 *
 * Source: Zhaoyi-ya/OppoPodsManager (Services/PodManager.Parsing.cs, ParseWearingData),
 * cross-validated against HeyMelody `[VENDOR]` and live captures.
 *
 * Two packet sources carry the same payload:
 *   - 0x8109  response to query 0x0109 (getEarBudsStatus)
 *   - 0x0204  spontaneous notification, subType 0x02 (after 0x0205 registration)
 *
 * Payload: [count][comp, st] x N
 *   comp: 1 = Left bud, 2 = Right bud, 3 = Case
 *   st:   bits `[VENDOR]`: 0 out of the case, 1 in ear, 2 lid open, 3 a flag the app ignores.
 *         So 4 = in case, 1/5 = out, 3/7 = in ear, 0 = in case with the lid closed.
 *         The flag bit is cleared here, so the codes above stay the only ones.
 *
 * Buds 4 query responses sometimes prepend a status byte, so parsing tolerantly
 * tries both offsets and keeps the first layout that yields valid pairs.
 */
object WearingStatusParser {

    const val ST_IN_CASE = 4

    data class Result(
        val leftValid: Boolean,
        val rightValid: Boolean,
        val leftStatus: Int = -1,    // raw st for left bud, -1 = not reported
        val rightStatus: Int = -1,   // raw st for right bud, -1 = not reported
        val caseStatus: Int = -1     // raw st for case (comp 3), -1 = not reported
    )

    private fun isPlausibleSt(st: Int) = st in 0..15
    private fun isPlausibleComp(c: Int) = c == 1 || c == 2 || c == 3

    private fun tryParse(p: ByteArray, off: Int): Result? {
        if (p.size < off + 3) return null
        val count = p[off].toInt() and 0xFF
        if (count == 0 || count > 8) return null

        var leftValid = false
        var rightValid = false
        var leftStatus = -1
        var rightStatus = -1
        var caseStatus = -1
        var used = 0
        var pos = off + 1

        var i = 0
        while (i < count && pos + 1 < p.size) {
            val comp = p[pos].toInt() and 0xFF
            val raw = p[pos + 1].toInt() and 0xFF
            if (!isPlausibleComp(comp) || !isPlausibleSt(raw)) return null
            val st = raw and 0x07
            when (comp) {
                1 -> { leftValid = true; leftStatus = st }
                2 -> { rightValid = true; rightStatus = st }
                3 -> { caseStatus = st }
            }
            used++
            pos += 2
            i++
        }
        if (used == 0) return null
        return Result(leftValid, rightValid, leftStatus, rightStatus, caseStatus)
    }

    /** Parse a 0x8109 wearing query response payload. */
    fun parseQueryResponse(payload: ByteArray): Result? {
        tryParse(payload, 0)?.let { return it }   // strict: [count][pairs]
        return tryParse(payload, 1)               // tolerant: [status][count][pairs]
    }

    /** Parse a 0x0204 active-report payload (payload[0] must be subType 0x02). */
    fun parseActiveReport(payload: ByteArray): Result? {
        if (payload.isEmpty() || payload[0].toInt() and 0xFF != OpoProtocol.EVT_WEARING) return null
        return tryParse(payload, 1)
    }
}
