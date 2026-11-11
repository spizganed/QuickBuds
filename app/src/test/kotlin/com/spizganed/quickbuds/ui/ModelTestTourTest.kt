package com.spizganed.quickbuds.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tour step of a feature ([ModelTest.step]), its log line, and the automatic mark from the log. */
class ModelTestTourTest {

    @Test fun everyFeatureHasAStep() {
        assertTrue(ModelTest.FEATURES.all { ModelTest.step(it.key) != null })
    }

    @Test fun aStepLineHasOneFormat() {
        assertEquals("MODEL TEST: step anc start", ModelTest.stepLine("anc", end = false, observed = false))
        assertEquals("MODEL TEST: step anc end observed=true", ModelTest.stepLine("anc", end = true, observed = true))
        assertEquals("MODEL TEST: step eq end observed=false", ModelTest.stepLine("eq", end = true, observed = false))
    }

    @Test fun theLogShowsTheEffectOfTheStep() {
        val log = """
            12:00:00.000  MODEL TEST: step game start
            12:00:00.821  TX[Feature 0x06 -> true]: AA 09 00 00 03 04 C1 02 00 06 01
            12:00:00.972  GAME EVT: ON
        """.trimIndent()
        assertTrue(ModelTest.stepObserved(log, "game"))
        assertFalse(ModelTest.stepObserved(log, "anc"))
    }

    @Test fun anEndLineClosesTheWindow() {
        val log = """
            MODEL TEST: step anc start
            MODEL TEST: step anc end observed=false
            ANC EVT: raw=0x0100 (bit 8) -> Transparency
        """.trimIndent()
        assertFalse(ModelTest.stepObserved(log, "anc"))
    }

    @Test fun onlyTheLastStartCounts() {
        val log = """
            MODEL TEST: step eq start
            EQ: current=4 bassWave=2
            MODEL TEST: step eq start
        """.trimIndent()
        assertFalse(ModelTest.stepObserved(log, "eq"))
    }

    @Test fun eachObservableFeatureHasItsLine() {
        assertTrue(ModelTest.stepObserved("MODEL TEST: step wear start\nWEAR EVT: L=out R=out", "wear"))
        assertTrue(ModelTest.stepObserved("MODEL TEST: step golden start\nGOLDEN STATUS: 1", "golden"))
        assertTrue(ModelTest.stepObserved("MODEL TEST: step anc start\nANC EVT: raw=0x0100", "anc"))
    }

    @Test fun aFeatureWithoutAnObservableReplyStaysManual() {
        for (key in listOf("battery", "hires", "spatial", "wind", "dual", "gestures", "find")) {
            assertNull(key, ModelTest.autoMark(key, observed = true))
        }
        assertEquals(ModelTest.WORKS, ModelTest.autoMark("game", observed = true))
        assertNull(ModelTest.autoMark("game", observed = false))
    }
}
