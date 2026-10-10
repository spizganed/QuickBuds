package com.spizganed.quickbuds.protocol

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
