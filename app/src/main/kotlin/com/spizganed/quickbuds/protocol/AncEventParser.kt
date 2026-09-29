package com.spizganed.quickbuds.protocol

/**
 * Parser for the 0x0204 subType 0x03 "ANC changed on the buds" active report.
 *
 * Frame shape (14 bytes):
 *
 *     AA 0C 00 00 04 02 FF 05 00 03 01 01 20 00
 *     |  |  |  |  |  |  |  |  |  |  |  |  +--+-- value (2 bytes, little endian)
 *     |  |  |  |  |  |  |  |  |  |  +--+-------- 0x01 0x01 (constant)
 *     |  |  |  |  |  |  |  |  |  +--------------- payload[0] = subType 0x03
 *     |  |  |  |  |  |  |  |  +------------------ payLen = 5
 *     |  |  |  |  |  |  +---------------------- 0xFF (event marker)
 *     |  |  |  |  |  +------------------------- sub-command 0x02
 *     |  |  |  |  +---------------------------- 0x04 (active report)
 *     |  |  |  +------------------------------- 0x00 0x00
 *     +--+--+---------------------------------- sync 0xAA + length (total - 2)
 *
 * PAYLOAD is exactly 5 bytes: `03 01 01 LO HI`.
 *
 * THE MAPPING IS NOW CONFIRMED (2026-09-18, capture with a known starting mode).
 * He cycled from Off on BOTH buds and reported the order, and both buds produced
 * the identical three values, so this is not side-dependent:
 *
 *     start        Off
 *     1st gesture  03 01 01 20 00   -> 0x0020   ANC on   (Medium)
 *     2nd gesture  03 01 01 00 01   -> 0x0100   Transparency
 *     3rd gesture  03 01 01 08 00   -> 0x0008   Off
 *
 * An earlier capture began from a different level and gave 0x0010 for its
 * "ANC on" stop, which is exactly the pattern to expect: the value is the buds'
 * own ANC bitmask (bit `index` set), so the ANC-on stop restores whichever LEVEL
 * was last used — 0x0010 = Deep (bit 4), 0x0020 = Medium (bit 5). The bit numbers
 * are the model's own tree, see [AncModes]. So this must NOT be treated as a
 * fixed enum of three values.
 *
 * WHY THE FRAME WAS INVISIBLE FOR THREE CAPTURES: `noteUnattributed` in
 * BudsConnectionManager blanket-excludes cmd 0x0204, so an undecoded 0x0204
 * subType prints NOTHING AT ALL. The F1 button frame fires for every gesture with
 * identical content, so it says WHEN but never WHAT. Both are documented below
 * because that combination is what made ANC look silent.
 */
object AncEventParser {

    /** 0x0204 subType: ANC mode changed on the buds. */
    const val EVT_ANC = 0x03

    /**
     * True if this 0x0204 payload is a GENUINE ANC-changed event.
     *
     * `[CAPTURE]`-caused bug, found and fixed 2026-09-22: `setHoldAncModes()`'s write also raises
     * a `subType 0x03` frame (PROTOCOL.md §5's `[CAPTURE]` note on this), but its payload is
     * `03 02 01 <mask LE>` — the `0x010C` switch-list QUERY's own echo shape, not this event's.
     * The real event is always `03 01 01 <value LE>` (see the class doc). Before this fix,
     * `isAncEvent` only checked `payload[0] == 0x03`, so the mask-write's echo passed as a real
     * event, its MASK got misread as an ANC raw value, and the bogus name it produced
     * (`ANC-Light` was observed) got written into the persisted display state via
     * `onAncModeState` — a real, user-visible bug, not the "cosmetic, log-reading trap" it was
     * first filed as. Now checks the constant `01 01` bytes 1/2 as well, which the mask-write's
     * `02 01` echo fails.
     */
    fun isAncEvent(payload: ByteArray): Boolean {
        if (payload.size < 5) return false
        return payload[0].toInt() and 0xFF == EVT_ANC &&
            payload[1].toInt() and 0xFF == 0x01 &&
            payload[2].toInt() and 0xFF == 0x01
    }

    /**
     * Raw 16-bit value from the event, little endian, or -1 if too short.
     * Kept separate from the name lookup so a capture can always be read back as
     * numbers, even when the name table is incomplete.
     */
    fun rawValue(payload: ByteArray): Int {
        if (payload.size < 5) return -1
        return OpoProtocol.u16(payload, 3)
    }

    /**
     * One-liner for the log, e.g. "raw=0x0020 (bit 5)". The mode name depends on the model
     * ([AncModes.modeForRaw]); the manager logs it next to this.
     */
    fun describe(payload: ByteArray): String {
        val raw = rawValue(payload)
        if (raw <= 0) return "ANC event (no value)"
        return "raw=0x%04X (bit %d)".format(raw, Integer.numberOfTrailingZeros(raw))
    }
}
