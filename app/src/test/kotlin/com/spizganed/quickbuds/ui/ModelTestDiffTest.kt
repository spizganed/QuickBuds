package com.spizganed.quickbuds.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diagnosis of a model test ([ModelTest.diff]) and its line in the report's description. */
class ModelTestDiffTest {

    private fun diff(
        model: String = """{"id":"X","name":"X","statusQuery":"05 04"}""",
        reply: Map<Int, Int>? = mapOf(0x05 to 1),
        bitmap: Set<Int>? = emptySet(),
        marks: Map<String, String> = emptyMap()
    ) = ModelTest.diff(JSONObject(model), reply, bitmap, null, marks)

    @Test fun aSwitchTheAppAskedForAndTheBudsDidNotAnswer() {
        // The ask list also names both game-mode ids (RowRules.askQuery), and the reply lists neither.
        assertEquals(listOf(0x04), diff().missing)
        assertTrue(diff().extra.isEmpty())
    }

    @Test fun anIdOnlyAModelFlagAsksIsNoMissingAnswer() {
        // `autoVolume` adds 0x1C to the ask list; a model that does not declare it in statusQuery is not at fault.
        val m = """{"id":"X","name":"X","statusQuery":"05 04","autoVolume":1}"""
        assertEquals(listOf(0x04), diff(model = m, reply = mapOf(0x05 to 1)).missing)
    }

    @Test fun theSecondGameIdIsNoMissingAnswerWhenTheFirstIsAnswered() {
        // The app asks 0x06 and 0x28 on purpose; a model answers one of them (OnePlus Buds 4 answers 06).
        val m = """{"id":"X","name":"X","statusQuery":"05 06 28"}"""
        assertTrue(diff(model = m, reply = mapOf(0x05 to 1, 0x06 to 1)).missing.isEmpty())
        assertTrue(diff(model = m, reply = mapOf(0x05 to 1, 0x28 to 0)).missing.isEmpty())
        assertEquals(listOf(0x06, 0x28), diff(model = m, reply = mapOf(0x05 to 1)).missing)
    }

    @Test fun aSwitchTheBudsAnsweredThatTheAppDidNotAskFor() {
        val d = diff(reply = mapOf(0x05 to 1, 0x99 to 0))
        assertEquals(listOf(0x99), d.extra)
        assertEquals(listOf(0x04), d.missing)
    }

    @Test fun nothingReadGivesNoMissingAndNoExtra() {
        val d = diff(reply = null)
        assertTrue(d.missing.isEmpty())
        assertTrue(d.extra.isEmpty())
    }

    @Test fun aFeatureMarkedWorksWithoutARow() {
        // No eqKind and no customEq in the model: the Equalizer row cannot show, yet the person marked it works.
        // ANC is in the same state: no mode table, no bitmap bit, so no row.
        val d = diff(marks = mapOf("eq" to ModelTest.WORKS, "anc" to ModelTest.WORKS))
        assertEquals(listOf("anc", "eq"), d.hidden)
        assertTrue(d.text().contains("marked works, no row: Noise control, Equalizer"))
    }

    @Test fun aFeatureMarkedBrokenWithoutARowIsNoContradiction() {
        val d = diff(marks = mapOf("eq" to ModelTest.BROKEN))
        assertTrue(d.hidden.isEmpty())
    }

    @Test fun aCleanTestHasAnEmptyDiagnosis() {
        val d = diff(model = """{"id":"X","name":"X"}""", reply = null)
        assertTrue(d.empty)
        assertEquals("", d.text())
    }

    @Test fun theDescriptionHoldsTheDiagnosisOnlyWhenThereIsOne() {
        val state = ModelTest.State("X", mapOf("battery" to true), mutableMapOf("eq" to ModelTest.WORKS, "battery" to ModelTest.WORKS), mutableMapOf())
        val features = ModelTest.FEATURES.filter { it.key in setOf("battery", "eq") }
        val with = ModelTest.description(state, features, "Android", diff(marks = mapOf("eq" to ModelTest.WORKS)))
        assertEquals(
            listOf("Model test (Android)", "Automatic check: battery level answered, feature list no answer, " +
                "feature states no answer", "Diagnosis: asked, no answer: 0x04; marked works, no row: Equalizer",
                "Battery: works", "Equalizer: works"),
            with.lines()
        )
        val without = ModelTest.description(state, features, "Android", diff(reply = null))
        assertFalse(without.contains("Diagnosis:"))
    }
}
