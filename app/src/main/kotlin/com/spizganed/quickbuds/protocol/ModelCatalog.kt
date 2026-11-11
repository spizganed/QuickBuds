package com.spizganed.quickbuds.protocol

import android.content.Context
import com.spizganed.quickbuds.ui.ThemeRes
import org.json.JSONObject

/**
 * Which model the connected buds are, from HeyMelody's own list (`assets/models.json`,
 * PROTOCOL.md §6). `[VENDOR]`: the entries whose
 * name equals the Bluetooth name and those whose id equals the product id; one in both wins, then
 * the first name match, then the first id match. A model picked by hand overrides all of it.
 */
object ModelCatalog {

    class Model(val id: String, val name: String, val json: JSONObject) {
        /** The entry as typed answers ([ModelProfile]). */
        val profile get() = ModelProfile(json)
    }

    /** The current model's entry as typed answers; an unknown model answers false and empty. */
    fun profile(context: Context) = ModelProfile(current(context)?.json)

    /** The Bluetooth name of the buds last connected, saved at connect time. */
    const val KEY_DEVICE_NAME = "budsDeviceName"
    /** A model id picked by hand in the model list; unset = detected. */
    const val KEY_MANUAL = "budsModelManual"
    private const val KEY_WEAR_SEEN = "wearSensorSeen_"

    @Volatile private var cache: List<Model>? = null

    fun all(context: Context): List<Model> {
        cache?.let { return it }
        val list = runCatching {
            val a = JSONObject(context.assets.open("models.json").bufferedReader().use { it.readText() }).getJSONArray("whiteList")
            (0 until a.length()).map { a.getJSONObject(it) }.map { Model(it.getString("id"), it.getString("name"), it) }
        }.getOrDefault(emptyList())
        cache = list
        return list
    }

    /** The high-quality codecs, best first, with the names the UI shows. */
    private val HIRES_CODECS = listOf(
        "lhdc5" to "LHDC 5.0",
        "lhdc" to "LHDC",
        "ldac" to "LDAC",
        "aptx-adaptive" to "aptX Adaptive",
        "aptx-hd" to "aptX HD",
        "aptx" to "aptX",
    )

    /** The model's own best high-quality codec. Null when the model has no `codecs` key or lists none of them. */
    fun bestHiResCodec(model: JSONObject?): String? {
        val tokens = ModelProfile(model).codecs
        return HIRES_CODECS.firstOrNull { it.first in tokens }?.second
    }

    /**
     * The best codec to name in the Hi-Res hint. A model with a `codecs` key answers from its own list.
     * Without the key, the buds' offered list answers, as before the data arrived.
     */
    fun hiresBestCodec(context: Context, offered: List<Int>): String? {
        val json = current(context)?.json
        if (json != null && json.has("codecs")) return bestHiResCodec(json)
        return when {
            8 in offered -> "LHDC 5.0"
            3 in offered -> "LDAC"
            7 in offered -> "LHDC"
            else -> null
        }
    }

    /**
     * `[VENDOR]` HeyMelody folds four colour ranges into one id (on the BLE scan id;
     * `[OSS]` OppoPodsManager `NormalizeProductId` applies it to `0x8103` too). The low byte is the colour.
     */
    fun normalise(id: Int): Int = when (id) {
        in 0x100100..0x100102 -> 0x060414   // OnePlus Buds
        in 0x100200..0x100202 -> 0x060814   // OnePlus Buds Z
        in 0x108100..0x108102 -> 0x068414   // OnePlus Buds
        in 0x108200..0x108202 -> 0x068814   // OnePlus Buds Z
        else -> id
    }

    fun find(models: List<Model>, id: String?, name: String?): Model? {
        // HeyMelody's order (PROTOCOL.md §6): name and id, then name (some buds reuse another model's id,
        // issue #5), then id (a renamed device).
        val byId = { m: Model -> id != null && m.id.equals(id, ignoreCase = true) }
        val byName = { m: Model -> name != null && m.name == name }
        return models.firstOrNull { byName(it) && byId(it) } ?: models.firstOrNull(byName) ?: models.firstOrNull(byId)
            ?: models.filter { name != null && squash(it.name) == squash(name) }.singleOrNull()
    }

    /** A name without case, spaces and punctuation, so "OnePlus Buds3" and "oneplus buds 3" compare equal. Last resort only. */
    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Which rule of [find] chose the detected model, for the log: "name+id", "name", "id", "squashed name" or "none". */
    fun rule(context: Context): String {
        val p = prefs(context)
        val id = Capabilities.productId(context)
        val name = p.getString(KEY_DEVICE_NAME, null)
        val models = all(context)
        return when {
            models.any { it.name == name && id != null && it.id.equals(id, ignoreCase = true) } -> "name+id"
            models.any { it.name == name } -> "name"
            models.any { id != null && it.id.equals(id, ignoreCase = true) } -> "id"
            find(models, id, name) != null -> "squashed name"
            else -> "none"
        }
    }

    /** The detected model, ignoring a manual pick. Null = nothing read yet, or not in the list. */
    fun detected(context: Context): Model? {
        val p = prefs(context)
        return find(all(context), Capabilities.productId(context), p.getString(KEY_DEVICE_NAME, null))
    }

    /** realme Link's three-device item: the Dual connection rows say "Triple connection". */
    fun tripleDevice(context: Context): Boolean = profile(context).tripleDevice

    /** The manual pick if there is one, else [detected]. */
    fun current(context: Context): Model? =
        manual(context)?.let { id -> all(context).firstOrNull { it.id == id } } ?: detected(context)

    /**
     * True once this model's buds have reported "in ear". Some models have no in-ear sensor and always report "out"
     * (report #46); until the first "in ear" the app shows "out" as in the ear, with no wear label.
     */
    fun wearSensorSeen(context: Context): Boolean {
        val id = current(context)?.id ?: return true
        return prefs(context).getBoolean(KEY_WEAR_SEEN + id, false)
    }

    fun markWearSensorSeen(context: Context) {
        val id = current(context)?.id ?: return
        prefs(context).edit().putBoolean(KEY_WEAR_SEEN + id, true).apply()
    }

    fun manual(context: Context): String? = prefs(context).getString(KEY_MANUAL, null)

    fun setManual(context: Context, id: String?) {
        prefs(context).edit().apply { if (id == null) remove(KEY_MANUAL) else putString(KEY_MANUAL, id) }.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
}
