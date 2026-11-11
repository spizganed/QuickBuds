package com.spizganed.quickbuds.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Last EQ state, stored in the ThemeRes prefs next to `lastFeatureStates`.
 * The MAC ties it to one pair of buds: a different device starts with an empty list.
 * The screen paints this snapshot before the service binds.
 */
data class EqSnapshot(
    val mac: String,
    val current: Int?,
    val bassWave: Int?,
    val custom: List<EqCodec.Preset>,
    /** realme dynamic sound: the last level written to each slider (low, medium, high). The buds do not read back. */
    val bassBands: List<Int?> = NO_BANDS
) {
    /** True when [mac] is the device this snapshot belongs to. */
    fun matches(mac: String?) = mac != null && this.mac.equals(mac, ignoreCase = true)

    fun encode(): String {
        val o = JSONObject()
        o.put("mac", mac)
        o.put("current", current ?: JSONObject.NULL)
        o.put("bass", bassWave ?: JSONObject.NULL)
        o.put("bassBands", JSONArray(bassBands.map { it ?: JSONObject.NULL }))
        o.put("custom", JSONArray(custom.map { p ->
            JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("freqs", JSONArray(p.freqs))
                .put("gains", JSONArray(p.gains))
                .put("selected", p.selected)
                .put("tag", JSONArray(p.tag.map { it.toInt() }))
        }))
        return o.toString()
    }

    companion object {
        /** The `ThemeRes` prefs key. */
        const val KEY = "lastEqState"

        /** Null when there is no snapshot, or it is not readable. A broken snapshot must not stop the app. */
        fun decode(text: String?): EqSnapshot? {
            if (text.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(text)
                val mac = o.getString("mac")
                if (mac.isEmpty()) return null
                val current = if (o.isNull("current")) null else o.getInt("current")
                val bass = if (o.isNull("bass")) null else o.getInt("bass")
                val bands = o.optJSONArray("bassBands")
                    ?.let { a -> (0 until 3).map { if (it >= a.length() || a.isNull(it)) null else a.getInt(it) } }
                    ?: NO_BANDS
                val arr = o.getJSONArray("custom")
                val custom = (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    val freqs = p.getJSONArray("freqs").let { a -> (0 until a.length()).map { a.getInt(it) } }
                    val gains = p.getJSONArray("gains").let { a -> (0 until a.length()).map { a.getInt(it) } }
                    val tag = p.getJSONArray("tag").let { a -> ByteArray(a.length()) { a.getInt(it).toByte() } }
                    if (freqs.size != gains.size) return null
                    EqCodec.Preset(p.getInt("id"), p.getString("name"), freqs, gains, p.getBoolean("selected"), tag)
                }
                EqSnapshot(mac, current, bass, custom, bands)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * The EQ values the manager holds. A link starts from the stored snapshot only when the MAC
 * matches; every `0x8122` reply then replaces the list, also with an empty one.
 */
data class EqState(
    val current: Int? = null,
    val bassWave: Int? = null,
    val custom: List<EqCodec.Preset> = emptyList(),
    val bassBands: List<Int?> = NO_BANDS
) {
    /** The `0x8122` reply always wins, also when it is an empty list. */
    fun withList(reply: List<EqCodec.Preset>) = copy(custom = reply)

    companion object {
        /** The state a link starts from: the snapshot only when its MAC matches the device that connects. */
        fun forLink(snapshot: EqSnapshot?, mac: String?): EqState =
            if (snapshot != null && snapshot.matches(mac))
                EqState(snapshot.current, snapshot.bassWave, snapshot.custom, snapshot.bassBands)
            else EqState()

        /** The state before any link: the last snapshot, whatever its MAC. The link up then applies [forLink]. */
        fun beforeLink(snapshot: EqSnapshot?): EqState =
            if (snapshot == null) EqState() else EqState(snapshot.current, snapshot.bassWave, snapshot.custom, snapshot.bassBands)

        /** The state the screen paints: the manager's state after a bind, else the snapshot. */
        fun paint(manager: EqState?, snapshot: EqSnapshot?): EqState =
            manager ?: EqState(snapshot?.current, snapshot?.bassWave, snapshot?.custom.orEmpty(), snapshot?.bassBands ?: NO_BANDS)
    }
}

/** No realme dynamic sound slider written yet: low, medium, high. */
val NO_BANDS: List<Int?> = listOf(null, null, null)
