package com.spizganed.quickbuds.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs every shared scenario against [FeatureSync]. The JSON file is the contract. */
class FeatureSyncScenariosTest {
    private val file = File("../core/tests/feature_sync_scenarios.json")

    @Test fun scenarios() {
        assertTrue("missing contract: ${file.absolutePath}", file.isFile)
        val scenarios = JSONObject(file.readText()).getJSONArray("scenarios")
        for (i in 0 until scenarios.length()) run(scenarios.getJSONObject(i))
    }

    private fun run(sc: JSONObject) {
        val name = sc.getString("name")
        val sync = FeatureSync()
        sync.init(intMap(sc.getJSONObject("init")))

        val events = sc.getJSONArray("events")
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val now = e.getLong("now")
            val actual = when (e.getString("ev")) {
                "tap" -> sync.tap(op(e), now)
                "ack" -> sync.ack(e.getInt("seq"), e.getInt("status"), now)
                "report" -> sync.report(intMap(e.getJSONObject("values")), if (e.isNull("seq")) null else e.getInt("seq"), now, e.optBoolean("push", false))
                "tick" -> sync.tick(now)
                "disconnect" -> sync.disconnect(now)
                "reconnect" -> sync.reconnect(now)
                else -> error("unknown event ${e.getString("ev")}")
            }
            val expected = e.getJSONArray("expect").toStringList()
            assertEquals("scenario \"$name\", event $i, expected $expected, actual ${actual.map { it.token() }}", expected, actual.map { it.token() })
        }
    }

    private fun op(e: JSONObject): FeatureSync.Operation {
        val raw = e.getJSONArray("writes")
        val writes = (0 until raw.length()).map { i ->
            val pair = raw.getJSONArray(i)
            pair.getInt(0) to pair.getInt(1)
        }
        val confirm = when (e.getString("confirm")) {
            "read" -> FeatureSync.Confirm.READ
            "push" -> FeatureSync.Confirm.PUSH
            "ack" -> FeatureSync.Confirm.ACK
            else -> error("unknown confirm ${e.getString("confirm")}")
        }
        return FeatureSync.Operation(e.getInt("key"), writes, confirm, e.optBoolean("restarts", false))
    }

    private fun intMap(o: JSONObject): Map<Int, Int> =
        o.keys().asSequence().associate { it.toInt() to o.getInt(it) }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
}
