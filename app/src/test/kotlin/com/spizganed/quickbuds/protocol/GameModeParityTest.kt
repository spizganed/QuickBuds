package com.spizganed.quickbuds.protocol

import com.spizganed.quickbuds.protocol.Wire.Message

import org.junit.Assert.assertEquals
import org.junit.Test

/** The product-id rule is the source of truth for the game-mode state. */
class GameModeParityTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun push(vararg p: Int) = Message(0x0204, 0xFF, b(*p))
    private val models = listOf(null, "062410", "062810", "063010", "063410", "065414", "06241")

    @Test fun productRuleMatchesTheParser() {
        for (p in listOf(
            intArrayOf(5, 1),
            intArrayOf(5, 0, 0, 1),
            intArrayOf(5, 2, 0, 0),
            intArrayOf(5, 0, 0, 2),
            intArrayOf(5),
        )) {
            val m = push(*p)
            val new = Decode.decode(m) as? Msg.GameMode
            for (id in models) {
                assertEquals(
                    "${m.payload.toList()} $id",
                    GameModeParser.parseActive(m.payload, id),
                    new?.state(id),
                )
            }
        }
    }
}
