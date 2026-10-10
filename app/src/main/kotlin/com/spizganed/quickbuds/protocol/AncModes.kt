package com.spizganed.quickbuds.protocol

import android.content.Context
import com.spizganed.quickbuds.ui.ThemeRes
import org.json.JSONArray

/**
 * The noise-control modes one model has and the `0x0404` bit of each (PROTOCOL.md §5).
 *
 * `[VENDOR]` HeyMelody's per-model list, `noiseReductionMode` in `assets/models.json`: a tree of
 * `{modeType, protocolIndex, childrenMode}`. A SET sends a mode's own `protocolIndex` as a bit;
 * a report's bit is looked up in the same tree,
 * parents first, then children. That is why Off is SET as bit 0 but reported as bit 3
 * on Buds 4: bit 3 is Off's child, not a second table.
 *
 * Names are the ones the rest of the app stores: [OFF], [TRANSPARENCY], [ADAPTIVE] and the levels.
 */
class AncModes private constructor(
    /** App mode name -> SET bit. Only modes the app can offer. */
    private val set: Map<String, Int>,
    /** Report bit -> app mode name; [NC] where the report names no level. */
    private val report: Map<Int, String>
) {
    fun supports(mode: String) = mode in set
    fun bit(mode: String): Int? = set[mode]
    /** The ANC levels these buds have, low to high. */
    val levels: List<String> = LEVELS.filter { it in set }
    val isEmpty get() = set.isEmpty()
    /** The level (not Smart) whose SET bit this is. */
    fun levelForBit(bit: Int): String? = LEVELS.firstOrNull { it != SMART && set[it] == bit }

    /**
     * The mode a `0x810C` / `0x0204` value reports, or null if the model has no such bit. A plain
     * "noise cancelling" bit names no level: [currentLevel] if these buds have it, else their first.
     */
    fun modeForRaw(raw: Int, currentLevel: String? = null): String? {
        if (raw <= 0) return null
        val mode = report[Integer.numberOfTrailingZeros(raw)] ?: return null
        if (mode != NC) return mode
        return currentLevel?.takeIf { it in levels } ?: levels.firstOrNull()
    }

    companion object {
        const val OFF = "Off"
        const val TRANSPARENCY = "Transparency"
        const val ADAPTIVE = "Adaptive"
        const val SMART = "ANC-Smart"
        /** Low to high, then Smart (the firmware picks the level). */
        val LEVELS = listOf("ANC-Light", "ANC-Medium", "ANC-Deep", SMART)
        private const val NC = "ANC"

        /** HeyMelody's `modeType` -> our name. */
        private fun nameOf(modeType: Int): String? = when (modeType) {
            1 -> OFF
            2 -> TRANSPARENCY
            3 -> "ANC-Light"     // "weak"
            8 -> "ANC-Medium"    // "middle"
            4 -> "ANC-Deep"      // "strong"
            5 -> NC              // "noise reduction", a parent of levels or a mode alone
            10 -> ADAPTIVE       // "auto"
            7 -> SMART           // "intelligent": the firmware picks the level
            6 -> "Voice"         // "transparency (voice)": not offered, reported as Transparency
            else -> null
        }

        fun parse(modes: JSONArray): AncModes {
            val set = LinkedHashMap<String, Int>()
            val report = HashMap<Int, String>()
            for (i in 0 until modes.length()) {
                val top = modes.getJSONObject(i)
                val name = nameOf(top.getInt("modeType")) ?: continue
                val shown = if (name == "Voice") TRANSPARENCY else name
                // decideByEarDevice: HeyMelody shows it only if a per-bud support read says so,
                // which we do not make. Only "Voice" carries it today.
                if (!top.optBoolean("decideByEarDevice")) set.putIfAbsent(name, top.getInt("protocolIndex"))
                report[top.getInt("protocolIndex")] = shown
                val children = top.optJSONArray("childrenMode") ?: JSONArray()
                for (j in 0 until children.length()) {
                    val child = children.getJSONObject(j)
                    val childName = nameOf(child.getInt("modeType")) ?: continue
                    // A child that repeats its parent (Off's bit 3) is a report value only.
                    if (childName != name) set.putIfAbsent(childName, child.getInt("protocolIndex"))
                    report[child.getInt("protocolIndex")] = if (childName == "Voice") shown else childName
                }
            }
            // Plain noise cancelling with no levels is offered as the one level the UI has for it.
            // ponytail: shows as "Medium" in the widget; a level-less ANC name if that confuses.
            set.remove(NC)?.let { if (LEVELS.none { l -> l in set }) set["ANC-Medium"] = it }
            set.remove("Voice")
            return AncModes(set, report)
        }

        /** OnePlus Buds 4 (`065414`): the table used before the product id is read. */
        private val BUDS4 = parse(JSONArray(
            """[{"modeType":5,"protocolIndex":1,"childrenMode":[{"modeType":4,"protocolIndex":4},
            {"modeType":8,"protocolIndex":5},{"modeType":3,"protocolIndex":6},{"modeType":7,"protocolIndex":7}]},
            {"modeType":10,"protocolIndex":11},{"modeType":2,"protocolIndex":2,"childrenMode":[{"modeType":2,"protocolIndex":8}]},
            {"modeType":1,"protocolIndex":0,"childrenMode":[{"modeType":1,"protocolIndex":3}]}]"""))
        private val NONE = AncModes(emptyMap(), emptyMap())

        @Volatile private var cached: Pair<String, AncModes>? = null

        /**
         * The connected (or last connected) model's modes ([ModelCatalog.current]). Nothing
         * detected yet = Buds 4, as before detection. A model HeyMelody lists without modes, or a
         * product id it does not list, gets none: its bits are unknown and a wrong bit sets another
         * mode silently.
         */
        fun of(context: Context): AncModes {
            val model = ModelCatalog.current(context) ?: return if (context.getSharedPreferences(
                    ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getString(Capabilities.KEY_PRODUCT_ID, null) == null) BUDS4 else NONE
            cached?.let { if (it.first == model.id) return it.second }
            val modes = runCatching { model.json.optJSONArray("noiseReductionMode")?.let { parse(it) } }.getOrNull() ?: NONE
            cached = model.id to modes
            return modes
        }
    }
}
