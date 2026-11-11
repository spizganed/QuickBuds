package com.spizganed.quickbuds.protocol

import com.spizganed.quickbuds.protocol.Wire.Message

import org.junit.Assert.assertEquals
import org.junit.Test

/** The preset keeps the tag bytes; the range comes from them. */
class EqParityTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun reply(vararg p: Int) = Message(0x8122, 1, b(*p))

    private fun preset(p: EqCodec.Preset) = EqPreset(
        p.id, p.name, p.selected, p.tag[0].toInt() to p.tag[1].toInt(), p.freqs.zip(p.gains),
    )

    @Test fun listMatchesTheDecoderAndKeepsTheTag() {
        val body = intArrayOf(
            0, 2,
            1, 0xFB, 5, 4, 4, 'R'.code, 'o'.code, 'c'.code, 'k'.code, 2, 62, 0, 3, 0xFA, 0, 0xFF,
            0, 0xFA, 6, 5, 7, 'C'.code, 'u'.code, 's'.code, 't'.code, 'o'.code, 'm'.code, '1'.code, 6,
            62, 0, 0, 250, 0, 0, 1000, 0, 0, 4000, 0, 0, 8000, 0, 0, 16000, 0, 0,
        )
        val m = reply(*body)
        assertEquals(EqCodec.parseList(m.payload)?.map { preset(it) }, (Decode.decode(m) as? Msg.EqCustom)?.list)
    }

    @Test fun aShortListIsNullForBoth() {
        for (p in listOf(intArrayOf(0, 1, 1), intArrayOf(1, 1), intArrayOf())) {
            val m = reply(*p)
            assertEquals(
                "${m.payload.toList()}",
                EqCodec.parseList(m.payload)?.map { preset(it) },
                (Decode.decode(m) as? Msg.EqCustom)?.list,
            )
        }
    }
}
