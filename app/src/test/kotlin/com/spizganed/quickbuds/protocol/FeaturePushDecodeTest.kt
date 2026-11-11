package com.spizganed.quickbuds.protocol

import com.spizganed.quickbuds.protocol.Wire.Message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `0x0503` frames in `.orchestration/out/pb-frames/frames.json`. */
class FeaturePushDecodeTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun decode(vararg v: Int) = Decode.decode(Message(0x0503, 0xFF, b(*v))) as Msg.FeaturePush?
    private fun frame(hex: String) =
        Wire.Reassembler().push(hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()).single()

    /** The captured frames, header included, decode the same way. */
    @Test fun capturedFrames() {
        assertEquals(Msg.FeaturePush(mapOf(0x06 to 1)), Decode.decode(frame("AA 0A 00 00 03 05 FF 03 00 01 06 01")))
        assertEquals(Msg.FeaturePush(mapOf(0x1B to 1)), Decode.decode(frame("AA 0B 00 00 03 05 FF 04 00 01 1B 01 30")))
        assertEquals(17, (Decode.decode(frame("AA 2A 00 00 03 05 FF 23 00 11 00 00 01 00 02 00 04 01 05 00 06 01 08 00 09 00 0B 00 0C 00 0D 00 10 00 11 00 18 00 1A 01 1B 00 1D 00")) as Msg.FeaturePush).values.size)
    }

    /** featurepush-01, realme Buds T200x: game mode on after the write `06 01`. */
    @Test fun onePair() {
        assertEquals(Msg.FeaturePush(mapOf(0x06 to 1)), decode(0x01, 0x06, 0x01))
    }

    /** featurepush-02, OPPO Enco Buds3 Pro: one extra byte follows the pair. */
    @Test fun trailingByteIsIgnored() {
        assertEquals(Msg.FeaturePush(mapOf(0x1B to 1)), decode(0x01, 0x1B, 0x01, 0x30))
        assertEquals(Msg.FeaturePush(mapOf(0x1B to 0)), decode(0x01, 0x1B, 0x00, 0x47))
    }

    /** featurepush-03, realme Buds Air 5 Pro: 0x11 = 17 pairs in 35 bytes. */
    @Test fun seventeenPairs() {
        val m = decode(
            0x11,
            0x00, 0x00, 0x01, 0x00, 0x02, 0x00, 0x04, 0x01, 0x05, 0x00,
            0x06, 0x01, 0x08, 0x00, 0x09, 0x00, 0x0B, 0x00, 0x0C, 0x00,
            0x0D, 0x00, 0x10, 0x00, 0x11, 0x00, 0x18, 0x00, 0x1A, 0x01,
            0x1B, 0x00, 0x1D, 0x00,
        )!!
        assertEquals(17, m.values.size)
        assertEquals(1, m.values[0x06])
        assertEquals(1, m.values[0x04])
        assertEquals(0, m.values[0x1B])
        assertEquals(1, m.values[0x1A])
    }

    @Test fun truncatedFrameIsRejected() {
        assertNull(decode(0x02, 0x06, 0x01))
        assertNull(decode(0x01))
        assertNull(decode())
    }

    /** A count of 0 is an empty list; [FeaturePushParser] accepts it. */
    @Test fun countZeroIsAnEmptyList() {
        assertEquals(Msg.FeaturePush(emptyMap()), decode(0x00))
    }

    @Test fun unknownIdIsKept() {
        assertEquals(Msg.FeaturePush(mapOf(0x7F to 1)), decode(0x01, 0x7F, 0x01))
    }

    /** The same bytes give the same pairs as [FeaturePushParser]. */
    @Test fun matchesFeaturePushParser() {
        val payloads = listOf(
            b(0x01, 0x06, 0x01),
            b(0x01, 0x1B, 0x01, 0x30),
            b(0x00),
            b(0x01, 0x7F, 0x01),
            b(
                0x11,
                0x00, 0x00, 0x01, 0x00, 0x02, 0x00, 0x04, 0x01, 0x05, 0x00,
                0x06, 0x01, 0x08, 0x00, 0x09, 0x00, 0x0B, 0x00, 0x0C, 0x00,
                0x0D, 0x00, 0x10, 0x00, 0x11, 0x00, 0x18, 0x00, 0x1A, 0x01,
                0x1B, 0x00, 0x1D, 0x00,
            ),
        )
        for (payload in payloads) {
            assertEquals(Msg.FeaturePush(FeaturePushParser.parse(payload)!!), Decode.decode(Message(0x0503, 0xFF, payload)))
        }
    }
}
