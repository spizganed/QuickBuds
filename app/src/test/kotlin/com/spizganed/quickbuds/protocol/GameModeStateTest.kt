package com.spizganed.quickbuds.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The game-mode byte rule: byte 3 for four product ids, byte 1 otherwise `[VENDOR]`. */
class GameModeStateTest {

    private fun gameMode(at1: Int?, at3: Int?) = Msg.GameMode(at1, at3)
    private fun frame(hex: String) =
        Wire.Reassembler().push(hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()).single()

    @Test fun model062410UsesByte3() {
        assertEquals(true, gameMode(0, 1).state("062410"))
        assertEquals(false, gameMode(1, 0).state("062410"))
    }

    @Test fun model062810UsesByte3() {
        assertEquals(true, gameMode(0, 1).state("062810"))
        assertEquals(false, gameMode(1, 0).state("062810"))
    }

    @Test fun model063010UsesByte3() {
        assertEquals(true, gameMode(0, 1).state("063010"))
        assertEquals(false, gameMode(1, 0).state("063010"))
    }

    @Test fun model063410UsesByte3() {
        assertEquals(true, gameMode(0, 1).state("063410"))
        assertEquals(false, gameMode(1, 0).state("063410"))
    }

    @Test fun anotherModelUsesByte1() {
        assertEquals(true, gameMode(1, 0).state("065414"))
        assertEquals(false, gameMode(0, 1).state("065414"))
        assertEquals(true, gameMode(1, null).state("065414"))
    }

    @Test fun missingChosenByteIsNull() {
        assertNull(gameMode(1, null).state("062410"))
        assertNull(gameMode(null, 1).state("065414"))
    }

    /** A value other than 0 or 1 still reads as ON. */
    @Test fun nonZeroIsOn() {
        assertEquals(true, gameMode(0, 2).state("062410"))
        assertEquals(true, gameMode(2, 0).state("065414"))
    }

    /** gamemode-02: the captured push carries the state on byte 1. */
    @Test fun capturedPushUsesByte1() {
        val m = Decode.decode(frame("AA 09 00 00 04 02 FF 02 00 05 01")) as Msg.GameMode
        assertEquals(true, m.state("065414"))
        assertNull(m.state("062410"))
    }
}
