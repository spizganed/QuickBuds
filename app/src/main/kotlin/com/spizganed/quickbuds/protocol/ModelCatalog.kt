package com.spizganed.quickbuds.protocol

import android.content.Context
import com.spizganed.quickbuds.ui.ThemeRes
import org.json.JSONObject

/**
 * Which model the connected buds are, from HeyMelody's own list (`assets/models.json`,
 * PROTOCOL.md §4). `[VENDOR]` `WhitelistUtils.findWhitelistConfig` (): the entries whose
 * name equals the Bluetooth name and those whose id equals the product id; one in both wins, then
 * the first name match, then the first id match. A model picked by hand overrides all of it.
 */
object ModelCatalog {

    class Model(val id: String, val name: String, val json: JSONObject)

    /** The Bluetooth name of the buds last connected, saved at connect time. */
    const val KEY_DEVICE_NAME = "budsDeviceName"
    /** A model id picked by hand in the model list; unset = detected. */
    const val KEY_MANUAL = "budsModelManual"

    @Volatile private var models: List<Model>? = null

    fun all(context: Context): List<Model> = models ?: runCatching {
        val list = JSONObject(context.assets.open("models.json").bufferedReader().use { it.readText() })
            .getJSONArray("whiteList")
        (0 until list.length()).map { list.getJSONObject(it) }
            .map { Model(it.getString("id"), it.getString("name"), it) }
    }.getOrDefault(emptyList()).also { models = it }

    /**
     * `[VENDOR]` HeyMelody folds four colour ranges into one id (, on the BLE scan id;
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
        val byName = models.filter { name != null && it.name == name }
        val byId = models.filter { id != null && it.id.equals(id, ignoreCase = true) }
        return byName.firstOrNull { it in byId } ?: byName.firstOrNull() ?: byId.firstOrNull()
    }

    /** The detected model, ignoring a manual pick. Null = nothing read yet, or not in the list. */
    fun detected(context: Context): Model? {
        val p = prefs(context)
        return find(all(context), p.getString(Capabilities.KEY_PRODUCT_ID, null), p.getString(KEY_DEVICE_NAME, null))
    }

    /** The manual pick if there is one, else [detected]. */
    fun current(context: Context): Model? =
        manual(context)?.let { id -> all(context).firstOrNull { it.id == id } } ?: detected(context)

    fun manual(context: Context): String? = prefs(context).getString(KEY_MANUAL, null)

    fun setManual(context: Context, id: String?) {
        prefs(context).edit().apply { if (id == null) remove(KEY_MANUAL) else putString(KEY_MANUAL, id) }.apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
}
