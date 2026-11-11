package com.spizganed.quickbuds.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The machine-readable line of the model test ([ModelTest.jsonLine]). `scripts/import-model-test.py` reads it, so a
 * change to its names or its formats breaks the import: this test holds both.
 *
 * The JVM `org.json` keeps no key order (`JSONObject` is a `HashMap`), so the tests compare key sets. The two arrays
 * built from lists keep their order in both implementations.
 */
class ModelTestJsonTest {

    private fun line(
        caps: Set<Int> = emptySet(), status: Map<Int, Int> = emptyMap(),
        marks: Map<String, String> = emptyMap(), unknownRx: Set<Int> = emptySet()
    ) = ModelTest.jsonLine(
        id = "065414", name = "OnePlus Buds 4", firmware = "1.0.1.108", caps = caps, status = status,
        shown = listOf("battery", "anc"), expected = setOf("eq", "anc", "earbuds"),
        marks = marks, unknownRx = unknownRx
    )

    private fun json(line: String) = JSONObject(line.removePrefix(ModelTest.JSON_PREFIX))

    private fun strings(a: org.json.JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test fun theLineHasTheNineFieldsAndOneLine() {
        val line = line()
        assertFalse("a line break splits the log line", line.contains('\n'))
        assertEquals(
            setOf("id", "name", "fw", "caps", "status", "shown", "expected", "marks", "unknownRx"),
            json(line).keys().asSequence().toSet()
        )
    }

    @Test fun capsAndStatusAreSortedAndFormatted() {
        val j = json(line(caps = setOf(0x0400, 0x010D, 0x0100), status = mapOf(0x1B to 0, 0x05 to 1)))
        assertEquals("0100 010D 0400", j.getString("caps"))
        assertEquals(setOf("05", "1B"), j.getJSONObject("status").keys().asSequence().toSet())
        assertEquals(1, j.getJSONObject("status").getInt("05"))
        assertEquals(0, j.getJSONObject("status").getInt("1B"))
    }

    @Test fun shownKeepsTheAppOrderAndExpectedIsSorted() {
        val j = json(line())
        assertEquals(listOf("battery", "anc"), strings(j.getJSONArray("shown")))
        assertEquals(listOf("anc", "earbuds", "eq"), strings(j.getJSONArray("expected")))
    }

    @Test fun onlyMarkedFeaturesAreInMarks() {
        val j = json(line(marks = linkedMapOf("anc" to ModelTest.WORKS, "eq" to ModelTest.BROKEN)))
        assertEquals(setOf("anc", "eq"), j.getJSONObject("marks").keys().asSequence().toSet())
        assertEquals(ModelTest.BROKEN, j.getJSONObject("marks").getString("eq"))
    }

    @Test fun unknownRxIsHexSortedAndUnique() {
        val j = json(line(unknownRx = setOf(0x8400, 0x8123)))
        assertEquals(listOf("0x8123", "0x8400"), strings(j.getJSONArray("unknownRx")))
    }

    @Test fun nothingReadYetGivesEmptyValues() {
        val j = json(line())
        assertEquals("", j.getString("caps"))
        assertEquals(0, j.getJSONObject("status").length())
        assertEquals(0, j.getJSONObject("marks").length())
        assertEquals(0, j.getJSONArray("unknownRx").length())
    }

    @Test fun unknownRxComesFromTheUnattributedLogLines() {
        val log = """
            12:00:00.100  RX: AA 00 00 00 23 81 00 00 00
            12:00:00.100  UNATTR RX: cmd=0x8123 len=2 head=[00 00] ancFlush=no
            12:00:00.200  UNATTR RX: cmd=0x8123 len=2 head=[01 00] ancFlush=no
            12:00:00.300  UNATTR RX: cmd=0x8400 len=1 head=[00] ancFlush=no
            12:00:00.400  MODEL TEST: start, OnePlus Buds 4
        """.trimIndent()
        assertEquals(setOf(0x8123, 0x8400), ModelTest.unknownRx(log))
    }
}
