package com.spizganed.quickbuds.ui

import android.content.Context
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.Feature as ProtoFeature
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.protocol.RowRules
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test my earbuds ([ModelTestActivity]): the owner marks each feature the app shows, and one report with the log goes
 * to the Worker as category "Test". A model's `confirmed` list in `models.json` names the features an owner
 * confirmed; a model whose list covers every feature shown gets no prompt. The desktop app does the same
 * (desktop/src/modeltest.rs), with the same keys and the same report text.
 */
object ModelTest {

    /** One feature: its key (also in `confirmed`), its label, its name in the report, and whether the buds have it. */
    class Feature(val key: String, val label: Int, val english: String, val shown: (Context) -> Boolean)

    /** The home rows, noise control, and the three Earbud settings rows most models have. */
    val FEATURES = listOf(
        Feature("battery", R.string.problem_cat_battery, "Battery") { true },
        Feature("anc", R.string.anc_section, "Noise control") {
            Capabilities.supports(it, OpoProtocol.CMD_SET_ANC) && !AncModes.of(it).isEmpty
        },
        Feature("wind", R.string.row_wind_noise_title, "Wind noise reduction") { MainActivity.rowSupported(it, "wind") },
        Feature("game", R.string.row_game_title, "Low latency") { MainActivity.rowSupported(it, "game") },
        Feature("hires", R.string.row_hires_title, "High-quality audio") { MainActivity.rowSupported(it, "hires") },
        Feature("spatial", R.string.row_spatial_title, "3D audio") { MainActivity.rowSupported(it, "spatial") },
        Feature("golden", R.string.row_golden_title, "Hearing profile") { MainActivity.rowSupported(it, "golden") },
        Feature("eq", R.string.row_eq_title, "Equalizer") { MainActivity.rowSupported(it, "eq") },
        Feature("dual", R.string.row_dual_title, "Dual connection") { MainActivity.rowSupported(it, "dual") },
        Feature("gestures", R.string.row_gesture_title, "Gestures") {
            Capabilities.supports(it, OpoProtocol.CMD_SET_KEY_FUNCTION) && !GestureModel.of(it).isEmpty
        },
        Feature("wear", R.string.row_wear_title, "Wear detection") {
            Capabilities.hasFeature(it, com.spizganed.quickbuds.protocol.Feature.AUTO_PLAY_PAUSE)
        },
        Feature("find", R.string.row_find_title, "Find my earbuds") {
            Capabilities.supports(it, OpoProtocol.CMD_FIND_BUDS) && ModelCatalog.current(it)?.json?.optInt("noFind") != 1
        },
    )

    /** The report's category; the Worker requires the log with it (report-worker/src/index.js). */
    const val CATEGORY = "Test"

    /** A feature's mark. */
    const val WORKS = "works"
    const val BROKEN = "broken"
    const val SKIP = "skip"

    /** The automatic check: (key, label, name in the report). */
    val CHECKS = listOf(
        Triple("battery", R.string.test_check_battery, "battery level"),
        Triple("features", R.string.test_check_features, "feature list"),
        Triple("states", R.string.test_check_states, "feature states"),
    )

    /** The start of the machine-readable line. The Worker and `scripts/import-model-test.py` find the JSON after it. */
    const val JSON_PREFIX = "MODEL TEST JSON "

    /**
     * The one machine-readable line of this test, for the packet log. It carries the model, the firmware,
     * the two replies that decide the rows, the rows the app showed and expected, the marks, and the commands
     * the app did not decode. A report then needs no heuristics: `scripts/import-model-test.py` reads this line
     * and makes a row fixture from it.
     */
    fun jsonLine(c: Context, s: State, features: List<Feature>, log: String): String = jsonLine(
        id = prefs(c).getString(Capabilities.KEY_PRODUCT_ID, null).orEmpty(),
        name = ModelCatalog.current(c)?.name ?: "unknown model",
        firmware = prefs(c).getString(BudsConnectionManager.KEY_FIRMWARE, null).orEmpty(),
        caps = Capabilities.commands(c).orEmpty(),
        status = Capabilities.reply(c).orEmpty(),
        shown = features.map { it.key },
        expected = MainActivity.rowsOf(c),
        marks = features.mapNotNull { f -> s.marks[f.key]?.let { f.key to it } }.toMap(),
        unknownRx = unknownRx(log)
    )

    /** [jsonLine] from plain values, so a unit test can call it. `caps` and `status` are sorted; `marks` keeps its order. */
    fun jsonLine(
        id: String, name: String, firmware: String, caps: Set<Int>, status: Map<Int, Int>,
        shown: List<String>, expected: Set<String>, marks: Map<String, String>, unknownRx: Set<Int>
    ): String = JSON_PREFIX + JSONObject()
        .put("id", id)
        .put("name", name)
        .put("fw", firmware)
        .put("caps", caps.sorted().joinToString(" ") { "%04X".format(it) })
        .put("status", JSONObject(status.toSortedMap().mapKeys { "%02X".format(it.key) }))
        .put("shown", JSONArray(shown))
        .put("expected", JSONArray(expected.sorted()))
        .put("marks", JSONObject(marks))
        .put("unknownRx", JSONArray(unknownRx.sorted().map { "0x%04X".format(it) }))

    /**
     * The command ids of the `UNATTR RX:` lines in [log]: frames the app does not decode. The line is written by
     * `BudsConnectionManager.noteUnattributed`; this only reads it back.
     */
    fun unknownRx(log: String): Set<Int> = log.lineSequence()
        .mapNotNull { UNATTR.find(it)?.groupValues?.get(1)?.toInt(16) }
        .toSet()

    private val UNATTR = Regex("UNATTR RX: cmd=0x([0-9A-Fa-f]{4})")

    /** The row key of a feature key, where the two differ. A feature without a row (battery) is absent. */
    private val ROW_KEY = mapOf(
        "wind" to RowRules.WIND, "game" to RowRules.GAME, "hires" to RowRules.HIRES,
        "spatial" to RowRules.SPATIAL, "golden" to RowRules.GOLDEN, "eq" to RowRules.EQ,
        "dual" to RowRules.DUAL, "anc" to RowRules.ANC, "gestures" to RowRules.CONTROLS,
        "wear" to RowRules.WEAR, "find" to RowRules.FIND,
    )

    /**
     * What the buds' answers do not match ([diff]):
     *  - `missing`: a switch the app asked for in `0x010D`, and the reply did not list;
     *  - `extra`: a switch in the reply that the app did not ask for;
     *  - `hidden`: a feature the person marked `works`, but the app showed no row for it. The row rule then hides
     *    something that works.
     */
    class Diff(val missing: List<Int>, val extra: List<Int>, val hidden: List<String>) {

        val empty: Boolean get() = missing.isEmpty() && extra.isEmpty() && hidden.isEmpty()

        /** One line for the report's description, in English for the developer. */
        fun text(): String = listOfNotNull(
            missing.takeIf { it.isNotEmpty() }?.let { "asked, no answer: " + it.joinToString(" ") { "0x%02X".format(it) } },
            extra.takeIf { it.isNotEmpty() }?.let { "answered, not asked: " + it.joinToString(" ") { "0x%02X".format(it) } },
            hidden.takeIf { it.isNotEmpty() }?.let { keys ->
                "marked works, no row: " + FEATURES.filter { it.key in keys }.joinToString(", ") { it.english }
            },
        ).joinToString("; ")
    }

    /** [diff] from the saved replies and the marks of the test in progress. */
    fun diff(c: Context, s: State): Diff = diff(
        ModelCatalog.current(c)?.json, Capabilities.reply(c), Capabilities.commands(c),
        AncModes.of(c).slots().takeIf { it.isNotEmpty() }, s.marks
    )

    /** The features whose effect the log shows by itself, with the line that proves it ([stepObserved]). */
    private val EVIDENCE = mapOf(
        "anc" to "ANC EVT:",
        "wear" to "WEAR ",
        "game" to "GAME EVT:",
        "golden" to "GOLDEN STATUS:",
        "eq" to "EQ: current=",
    )

    /** The features a step can mark `works` for ([autoMark]). */
    val OBSERVABLE: Set<String> get() = EVIDENCE.keys

    /** A tour step: the screen where the feature is tried, and the instruction. */
    class Step(val screen: Class<*>, val hint: Int)

    /** The step for [key], or null for a key with no step. Every feature in [FEATURES] has one. */
    fun step(key: String): Step? = STEPS[key]

    private val STEPS = mapOf(
        "battery" to Step(MainActivity::class.java, R.string.test_step_battery),
        "anc" to Step(MainActivity::class.java, R.string.test_step_anc),
        "wind" to Step(MainActivity::class.java, R.string.test_step_wind),
        "game" to Step(MainActivity::class.java, R.string.test_step_game),
        "hires" to Step(MainActivity::class.java, R.string.test_step_hires),
        "spatial" to Step(MainActivity::class.java, R.string.test_step_spatial),
        "golden" to Step(GoldenSoundActivity::class.java, R.string.test_step_golden),
        "eq" to Step(EqActivity::class.java, R.string.test_step_eq),
        "dual" to Step(MainActivity::class.java, R.string.test_step_dual),
        "gestures" to Step(GestureActivity::class.java, R.string.test_step_gestures),
        "wear" to Step(EarbudSettingsActivity::class.java, R.string.test_step_wear),
        "find" to Step(EarbudSettingsActivity::class.java, R.string.test_step_find),
    )

    /** The log line for a step: `MODEL TEST: step <key> start`, or `end observed=<bool>` on the return. */
    fun stepLine(key: String, end: Boolean, observed: Boolean): String =
        "MODEL TEST: step $key " + if (end) "end observed=$observed" else "start"

    /**
     * True when the log shows [key]'s effect between its last `start` line and its `end` line. The log is the only
     * evidence that survives: the home screen is `singleTask`, so opening it destroys this activity and no callback
     * of this screen runs while the person tries the feature.
     */
    fun stepObserved(log: String, key: String): Boolean {
        val marker = EVIDENCE[key] ?: return false
        val lines = log.lines()
        val start = lines.indexOfLast { "MODEL TEST: step $key start" in it }
        if (start < 0) return false
        for (i in start + 1 until lines.size) {
            if ("MODEL TEST: step $key end" in lines[i]) return false
            if (marker in lines[i]) return true
        }
        return false
    }

    /** The mark a returning step writes: `works` when the log shows the effect, null when it shows nothing. */
    fun autoMark(key: String, observed: Boolean): String? =
        if (observed && key in EVIDENCE) WORKS else null

    /** The step the person has not returned from yet; it survives the home screen's `singleTask` clear. */
    fun pendingStep(c: Context): String? = prefs(c).getString(KEY_STEP, null)

    fun saveStep(c: Context, key: String) {
        prefs(c).edit().putString(KEY_STEP, key).apply()
    }

    fun clearStep(c: Context) {
        prefs(c).edit().remove(KEY_STEP).apply()
    }

    /** [diff] from plain values, so a unit test can call it. */
    fun diff(
        model: JSONObject?, reply: Map<Int, Int>?, bitmap: Set<Int>?, productAnc: Map<String, Int>?,
        marks: Map<String, String>
    ): Diff {
        val asked = RowRules.liveAskIds(model, bitmap)
        val rows = RowRules.rows(model, reply, bitmap, productAnc)
        return Diff(
            missing = reply?.keys?.let { answered ->
                val declared = declaredIds(model)
                asked.filter { it !in answered && !otherGameId(answered, it) && (declared == null || it in declared) }.sorted()
            }.orEmpty(),
            extra = reply?.keys?.filter { it !in asked }?.sorted().orEmpty(),
            hidden = FEATURES.filter { marks[it.key] == WORKS }
                .filter { ROW_KEY[it.key]?.let { row -> row !in rows } == true }
                .map { it.key }
        )
    }

    /**
     * The ids the model's own `statusQuery` promises. A flag such as `autoVolume` adds `0x1C` to the ask list, but a
     * model that does not answer it is no missing answer: the diagnosis reports only what the model declares. Null when
     * the model has no list, so the whole ask list counts.
     */
    private fun declaredIds(model: JSONObject?): Set<Int>? = model?.optString("statusQuery")
        ?.takeIf { it.isNotBlank() }?.split(' ')?.mapNotNull { it.toIntOrNull(16) }?.toSet()

    /**
     * The two game-mode ids are alternatives: a model answers one of them. When the reply holds one, the other is
     * not a missing answer, so the diagnosis does not report it (OnePlus Buds 4 answers `06`, not `28`).
     */
    private fun otherGameId(answered: Set<Int>, id: Int): Boolean = when (id) {
        ProtoFeature.GAME_MODE -> ProtoFeature.GAME_MODE_MAIN in answered
        ProtoFeature.GAME_MODE_MAIN -> ProtoFeature.GAME_MODE in answered
        else -> false
    }

    /** The test in progress, as JSON: model id, checks, marks and notes. Kept while the person tries features. */
    private const val KEY_STATE = "modelTestState"
    /** The tour step in progress, by feature key. It outlives this activity when the home screen clears the task. */
    private const val KEY_STEP = "modelTestStep"
    /** Model ids the launch prompt has asked about, comma-separated. It asks once per model. */
    private const val KEY_ASKED = "modelTestAsked"

    fun shown(c: Context): List<Feature> = FEATURES.filter { it.shown(c) }

    /** The features an owner confirmed on [model] (`models.json` `confirmed`). */
    fun confirmed(model: ModelCatalog.Model): Set<String> {
        val list = model.json.optJSONArray("confirmed") ?: return emptySet()
        return (0 until list.length()).map { list.getString(it) }.toSet()
    }

    /** The launch prompt asks: a known model, not asked before, with a feature shown that nobody confirmed. */
    fun shouldAsk(c: Context): Boolean {
        val model = ModelCatalog.current(c) ?: return false
        if (model.id in asked(c)) return false
        val done = confirmed(model)
        return shown(c).any { it.key !in done }
    }

    fun markAsked(c: Context) {
        val model = ModelCatalog.current(c) ?: return
        prefs(c).edit().putString(KEY_ASKED, (asked(c) + model.id).joinToString(",")).apply()
    }

    private fun asked(c: Context) = prefs(c).getString(KEY_ASKED, null)?.split(',').orEmpty().toSet()

    /** The test in progress for the current model; null before Start, or when the model changed. */
    class State(val model: String, val checks: Map<String, Boolean>, val marks: MutableMap<String, String>,
                val notes: MutableMap<String, String>)

    fun load(c: Context): State? {
        val model = ModelCatalog.current(c) ?: return null
        val j = runCatching { JSONObject(prefs(c).getString(KEY_STATE, null) ?: return null) }.getOrNull() ?: return null
        if (j.optString("model") != model.id) return null
        fun map(name: String) = j.optJSONObject(name)?.let { o -> o.keys().asSequence().associateWith { o.get(it) } }.orEmpty()
        return State(model.id, map("checks").mapValues { it.value == true },
            map("marks").mapValues { it.value.toString() }.toMutableMap(),
            map("notes").mapValues { it.value.toString() }.toMutableMap())
    }

    fun save(c: Context, s: State) {
        val j = JSONObject().put("model", s.model).put("checks", JSONObject(s.checks))
            .put("marks", JSONObject(s.marks.toMap())).put("notes", JSONObject(s.notes.toMap()))
        prefs(c).edit().putString(KEY_STATE, j.toString()).apply()
    }

    fun clear(c: Context) = prefs(c).edit().remove(KEY_STATE).apply()

    /** Every feature shown has a mark, and every "does not work" has a note. */
    fun complete(s: State, features: List<Feature>) = features.all { f ->
        val mark = s.marks[f.key] ?: return@all false
        mark != BROKEN || !s.notes[f.key].isNullOrBlank()
    }

    /** The report's description, in English for the developer: the check, the diagnosis, then one line per feature. */
    fun description(s: State, features: List<Feature>, platform: String, diff: Diff): String {
        val check = CHECKS.joinToString(", ") { (key, _, name) ->
            "$name ${if (s.checks[key] == true) "answered" else "no answer"}"
        }
        val lines = features.map { f ->
            when (s.marks[f.key]) {
                WORKS -> "${f.english}: works"
                BROKEN -> "${f.english}: does not work: ${s.notes[f.key].orEmpty().trim()}"
                else -> "${f.english}: skipped"
            }
        }
        val diagnosis = listOfNotNull(diff.takeIf { !it.empty }?.let { "Diagnosis: " + it.text() })
        return (listOf("Model test ($platform)", "Automatic check: $check") + diagnosis + lines).joinToString("\n")
    }

    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
}
