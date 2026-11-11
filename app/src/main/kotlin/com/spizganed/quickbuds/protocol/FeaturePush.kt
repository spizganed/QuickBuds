package com.spizganed.quickbuds.protocol

/**
 * Parser for the `0x0503` feature push.
 *
 * The layout is proven by 95 frames in reports #2, #4, #28, #37, #51, #52, #60, #61, #63 and #69:
 *
 *     <count> <id> <value> <id> <value> ...
 *     1 byte  1 byte 1 byte
 *
 * A 3-byte payload carries one pair: `01 06 01` is game mode on.
 * A 35-byte payload carries 0x11 = 17 pairs (1 + 17 * 2 = 35).
 * Some OPPO Enco Buds3 Pro frames append one extra byte after the last pair.
 * The parser ignores all bytes after the last pair.
 */
object FeaturePushParser {

    /** Returns the id/value pairs, or null when the payload is empty or truncated. */
    fun parse(payload: ByteArray): Map<Int, Int>? {
        if (payload.isEmpty()) return null
        val count = payload[0].toInt() and 0xFF
        if (payload.size < 1 + count * 2) return null
        return (0 until count).associate { i ->
            val at = 1 + i * 2
            (payload[at].toInt() and 0xFF) to (payload[at + 1].toInt() and 0xFF)
        }
    }
}
