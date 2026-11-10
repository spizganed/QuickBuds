package com.spizganed.quickbuds.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The row rule against real logs from developer captures and the model data in
 * `assets/models.json`. Each fixture keeps only the ids today's ask list asks.
 */
class RowRulesTest {

    private val fixtures: List<JSONObject> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("row_fixtures.json")!!
            .bufferedReader().use { it.readText() }
        val a = JSONObject(text).getJSONArray("fixtures")
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    private val models: List<JSONObject> by lazy {
        val file = listOf(File("src/main/assets/models.json"), File("app/src/main/assets/models.json"))
            .first { it.exists() }
        val a = JSONObject(file.readText()).getJSONArray("whiteList")
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun model(name: String) = models.first { it.getString("name") == name }

    private fun features(line: String): Map<Int, Int> =
        line.split(' ').filter { it.isNotBlank() }.associate { e ->
            e.substringBefore('=').toInt(16) to e.substringAfter('=').toInt()
        }

    private fun commands(line: String): Set<Int> =
        line.split(' ').filter { it.isNotBlank() }.map { it.toInt(16) }.toSet()

    private fun askedIds(model: JSONObject?, bitmap: Set<Int>?): Set<Int> =
        RowRules.liveAskIds(model, bitmap)

    @Test fun realLogsShowTheRowsTheBudsReport() {
        for (f in fixtures) {
            val m = model(f.getString("model"))
            val bitmap = commands(f.getString("capabilities"))
            val raw = features(f.getString("features"))
            // The log can list an id today's model data no longer asks for (OPPO Enco Buds3 Pro asks no
            // 1B), and it can miss an id the table now asks (both game ids). The live reply has the ids
            // of the live ask list only.
            val ids = askedIds(m, bitmap)
            val reply = raw.filterKeys { it in ids }
            val actual = RowRules.rows(m, reply, bitmap)
            val uncertain = f.getJSONArray("uncertain").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val expect = f.getJSONArray("expect").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            // The fixtures check the home rows; Earbud settings, EQ and dual screen rows have their own tests.
            val home = setOf(
                RowRules.WIND, RowRules.GAME, RowRules.HIRES, RowRules.SPATIAL, RowRules.GOLDEN,
                RowRules.EQ, RowRules.DUAL, RowRules.ANC, RowRules.EARBUDS
            )
            assertEquals(
                "report ${f.getInt("report")} ${f.getString("model")}",
                (expect - uncertain),
                (actual.filter { it in home } - uncertain).toSet()
            )
        }
    }

    @Test fun oppoEncoBuds3ProHasNo3dAudioRow() {
        val f = fixtures.first { it.getString("model") == "OPPO Enco Buds3 Pro" }
        val m = model("OPPO Enco Buds3 Pro")
        val bitmap = commands(f.getString("capabilities"))
        val reply = features(f.getString("features"))
        // The old log lists 1B=0; today's statusQuery "05 11 06" does not ask it, so the live reply has no 1B.
        assertTrue(0x1B in reply)
        val live = reply.filterKeys { it in askedIds(m, bitmap) }
        assertFalse(RowRules.SPATIAL in RowRules.rows(m, live, bitmap))
    }

    @Test fun phoneMade3dAudioIsHidden() {
        val reply = mapOf(0x1B to 1)
        val oneplus = JSONObject("""{"id":"X","name":"OnePlus X"}""")
        val realme = JSONObject("""{"id":"Y","name":"realme Y","spatialToggle":1}""")
        // No 0xEF03: the phone makes the effect, only OPPO and OnePlus phones can.
        assertFalse(RowRules.SPATIAL in RowRules.rows(oneplus, reply, setOf(0x0403)))
        assertTrue(RowRules.SPATIAL in RowRules.rows(oneplus, reply, setOf(0x0403, 0xEF03)))
        // realme models make the effect in the buds; no bitmap read yet hides nothing.
        assertTrue(RowRules.SPATIAL in RowRules.rows(realme, reply, setOf(0x0403)))
        assertFalse(RowRules.phoneSpatial(oneplus, null))
    }

    @Test fun modelGameIdWins() {
        val m = JSONObject("""{"id":"X","name":"X","gameId":6}""")
        assertEquals(Feature.GAME_MODE, RowRules.gameModeId(setOf(OpoProtocol.CMD_GAME_SOUND), m))
        assertEquals(Feature.GAME_MODE_MAIN, RowRules.gameModeId(setOf(OpoProtocol.CMD_GAME_SOUND), JSONObject("{}")))
    }

    @Test fun nothingReadShowsNoConditionalRow() {
        assertEquals(setOf(RowRules.EARBUDS), RowRules.rows(model("OnePlus Buds 4"), null, null))
        assertEquals(setOf(RowRules.EARBUDS), RowRules.rows(null, null, null))
    }

    @Test fun everyGameIdIsAskedAndOneIdIsUsed() {
        val m = JSONObject("""{"id":"X","name":"X","statusQuery":"05 04"}""")
        val withGameSound = setOf(OpoProtocol.CMD_GAME_SOUND)
        val without = emptySet<Int>()
        assertEquals(Feature.GAME_MODE_MAIN, RowRules.gameModeId(withGameSound))
        assertEquals(Feature.GAME_MODE, RowRules.gameModeId(without))
        val asked = askedIds(m, withGameSound)
        assertTrue(Feature.GAME_MODE in asked && Feature.GAME_MODE_MAIN in asked)
        assertEquals(Feature.GAME_MODE_MAIN, RowRules.gameModeId(withGameSound))
    }

    @Test fun modelFlagOnlyAddsItsIdToTheAskList() {
        val m = JSONObject(
            """{"id":"X","name":"X","statusQuery":"05","windMode":1,"voiceBoost":1,""" +
                """"gameSoundFx":1,"autoVolume":1,"holdVolume":1,"fastPair":1}"""
        )
        val asked = askedIds(m, null)
        assertTrue(setOf(0x1A, 0x09, 0x27, 0x1C, 0x35, 0x37).all { it in asked })
        // The flags alone show no row: only the reply does.
        val rows = RowRules.rows(m, mapOf(0x05 to 1), emptySet())
        assertEquals(setOf(RowRules.EARBUDS), rows)
    }

    @Test fun findPhoneFlagAddsItsIdAlsoWithoutAModelList() {
        val listed = JSONObject("""{"id":"X","name":"X","statusQuery":"05 06","findPhone":1}""")
        assertTrue(Feature.FIND_PHONE in askedIds(listed, null))
        // No `statusQuery`: the default list plus the flag's id, not the default list alone.
        val unlisted = JSONObject("""{"id":"Y","name":"Y","findPhone":1}""")
        assertEquals(RowRules.DEFAULT_IDS + Feature.FIND_PHONE, askedIds(unlisted, null))
        assertEquals(RowRules.DEFAULT_IDS, askedIds(JSONObject("""{"id":"Z","name":"Z"}"""), null))
        // The row shows only when the buds answer the id.
        assertTrue(RowRules.FIND_PHONE in RowRules.rows(unlisted, mapOf(Feature.FIND_PHONE to 0), emptySet()))
        assertTrue(RowRules.FIND_PHONE !in RowRules.rows(unlisted, mapOf(0x05 to 1), emptySet()))
    }

    @Test fun idInTheReplyMeansHasTheFeatureWhateverItsValue() {
        val m = JSONObject("""{"id":"X","name":"X"}""")
        val rows = RowRules.rows(m, mapOf(0x1B to 0, 0x11 to 0, 0x0B to 0), emptySet())
        assertTrue(RowRules.SPATIAL in rows)
        assertTrue(RowRules.DUAL in rows)
        assertTrue(RowRules.GOLDEN in rows)
    }

    @Test fun equalizerNeedsEqDataAndItsCommand() {
        val noEq = model("realme Buds Q2s")
        assertEquals(0, noEq.optInt("eqKind"))
        val presets = setOf(OpoProtocol.CMD_SET_EQ)
        assertFalse(RowRules.EQ in RowRules.rows(noEq, mapOf(0x05 to 1), presets))
        val withEq = model("OnePlus Buds 4")
        assertTrue(RowRules.EQ in RowRules.rows(withEq, mapOf(0x05 to 1), presets))
    }

    @Test fun noiseControlNeedsAModeList() {
        val noTable = model("realme Buds Air 2")
        assertFalse(noTable.has("ancModes"))
        val bitmap = setOf(OpoProtocol.CMD_SET_ANC)
        assertFalse(RowRules.ANC in RowRules.rows(noTable, mapOf(0x05 to 1), bitmap))
        val reported = mapOf("Off" to 0, "Transparency" to 1, "ANC-Medium" to 4)
        assertTrue(RowRules.ANC in RowRules.rows(noTable, mapOf(0x05 to 1), bitmap, reported))
    }

    @Test fun headTrackingTestsTheArrayValues() {
        assertFalse(RowRules.spatialHeadTracking(JSONObject("""{"spatialModes":[0,1]}""")))
        assertTrue(RowRules.spatialHeadTracking(JSONObject("""{"spatialModes":[0,2]}""")))
        assertFalse(RowRules.spatialHeadTracking(JSONObject("""{"spatialModes":[0,12]}""")))
        assertFalse(RowRules.spatialHeadTracking(null))
    }

    @Test fun earbudSettingsRowsUseTheReplyAndTheBitmap() {
        val m = JSONObject("""{"id":"X","name":"X","gestures":[{"gesture":1}]}""")
        val bitmap = setOf(
            0x0401, OpoProtocol.CMD_FIND_BUDS, OpoProtocol.CMD_FIT_TEST,
            OpoProtocol.CMD_SET_ALERT_VOLUME, OpoProtocol.CMD_QUERY_FIRMWARE
        )
        val rows = RowRules.rows(m, mapOf(0x04 to 1, 0x09 to 0, 0x27 to 1), bitmap)
        assertTrue(setOf(RowRules.WEAR, RowRules.VOICE_BOOST, RowRules.GAME_SOUND).all { it in rows })
        assertTrue(setOf(RowRules.CONTROLS, RowRules.FIND, RowRules.FIT, RowRules.ALERT_VOLUME, RowRules.FIRMWARE).all { it in rows })
        assertFalse(RowRules.GAME_SOUND_TYPE in rows)
        assertFalse(RowRules.POWER_SAVING in rows)
    }
}
