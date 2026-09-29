package com.spizganed.quickbuds.protocol

import android.content.Context
import com.spizganed.quickbuds.ui.ThemeRes
import org.json.JSONArray
import org.json.JSONObject

/**
 * Golden Sound records (PROTOCOL.md §9). The buds hold only the active one; the list lives on the
 * phone, as in HeyMelody, which keeps up to 10 per buds.
 */
object GoldenSound {

    /** The hearing test slider's 25 stops. `[VENDOR]`. */
    val STOPS = intArrayOf(-120, -88, -55, -52, -49, -45, -41, -38, -35, -30, -25, -22, -19, -15, -11, -8, -5, -1, 3, 5, 7, 10, 13, 15, 17)
    /** The values a result can hold; a stop is saved as the nearest one. */
    val RESULTS = intArrayOf(-55, -49, -41, -35, -25, -19, -11, -5, 3, 7, 13, 17)
    /** Where each frequency starts: `-30`, as HeyMelody's slider does. */
    const val START_STOP = 9
    /** HeyMelody warns about loudness from this stop (value 10) up. */
    const val LOUD_STOP = 21

    fun snap(value: Int): Int = RESULTS.minByOrNull { Math.abs(it - value) }!!

    /**
     * `[VENDOR]` `generateRandomDescribe`: a description id picked at random from the enhance
     * type's group (0 low 1..6, 1 middle 7..12, else high 13..20). The buds only store it.
     */
    fun descId(enhanceType: Int): Int = when (enhanceType) {
        0 -> (1..6).random()
        1 -> (7..12).random()
        else -> (13..20).random()
    }

    /** One result: 12 values (left 1..6, right 1..6), the ear scan (may be empty) and its id. */
    class Record(val uid: Int, val name: String, val values: IntArray, val scan: ByteArray, val descId: Int) {
        fun toJson() = JSONObject().put("uid", uid).put("name", name)
            .put("values", JSONArray(values.toList())).put("scan", OpoProtocol.bytesToHex(scan).replace(" ", ""))
            .put("desc", descId)

        companion object {
            fun fromJson(o: JSONObject) = Record(
                o.getInt("uid"), o.getString("name"),
                o.getJSONArray("values").let { a -> IntArray(a.length()) { a.getInt(it) } },
                hex(o.optString("scan")), o.optInt("desc")
            )
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private const val KEY = "goldenRecords"
    private fun prefs(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    fun records(c: Context): List<Record> = runCatching {
        val a = JSONArray(prefs(c).getString(KEY, "[]"))
        (0 until a.length()).map { Record.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<Record>) =
        prefs(c).edit().putString(KEY, JSONArray(list.map { it.toJson() }).toString()).apply()

    /** Adds or replaces (same uid) a record, newest first, at most 10. */
    fun put(c: Context, r: Record) = save(c, (listOf(r) + records(c).filter { it.uid != r.uid }).take(10))

    fun delete(c: Context, uid: Int) = save(c, records(c).filter { it.uid != uid })

    /** `0x8115`: `00 03 0c <12 x side freq value> <uid> <name>` -> uid, name, values. Null if no record. */
    fun parseActive(payload: ByteArray): Triple<Int, String, IntArray>? {
        if (payload.size < 3 || payload[0].toInt() != 0) return null
        val count = payload[2].toInt() and 0xFF
        val end = 3 + count * 3
        if (count != 12 || payload.size < end + 4) return null
        val values = IntArray(12)
        for (i in 0 until 12) {
            val side = payload[3 + i * 3].toInt()
            val freq = payload[4 + i * 3].toInt()
            if (side !in 1..2 || freq !in 1..6) return null
            values[(side - 1) * 6 + freq - 1] = payload[5 + i * 3].toInt()
        }
        return Triple(int32(payload, end), String(payload, end + 4, payload.size - end - 4, Charsets.UTF_8), values)
    }

    /** `<action> <length little-endian> <data> <uid>` (event `0x0E` after its id, `0x811E` after its status). */
    fun parseScan(p: ByteArray, from: Int): Pair<Int, ByteArray>? {
        if (p.size < from + 3) return null
        val len = (p[from + 1].toInt() and 0xFF) or ((p[from + 2].toInt() and 0xFF) shl 8)
        val start = from + 3
        if (len == 0 || p.size < start + len + 4) return null
        return int32(p, start + len) to p.copyOfRange(start, start + len)
    }

    /**
     * Filter replies, both ears in one packet (type `04`), floats little-endian:
     * `0x8116` `00 <uid> 04 <count LE> <packet> <enhance type> <floats>` (`[VENDOR]` `FreqDataCache`),
     * `0x811F` `00 <uid> 04 <sample rate LE> <count LE> <packet> <floats>` (`[CAPTURE]`: 44100, 72 floats).
     * First half left, second right. Returns sample rate (44100 when not given, as HeyMelody), left, right.
     */
    fun parseCurves(p: ByteArray, scan: Boolean): Triple<Int, FloatArray, FloatArray>? {
        if (p.size < 11 || p[5].toInt() != 4) return null
        val le = { i: Int -> (p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8) }
        val fs = if (scan) le(6) else 44100
        val count = if (scan) le(8) else le(6)
        val start = if (scan) 11 else 10
        if (count == 0 || count % 12 != 0 || p.size < start + count * 4) return null
        val f = FloatArray(count) { java.lang.Float.intBitsToFloat(
            (p[start + it * 4].toInt() and 0xFF) or ((p[start + it * 4 + 1].toInt() and 0xFF) shl 8) or
            ((p[start + it * 4 + 2].toInt() and 0xFF) shl 16) or ((p[start + it * 4 + 3].toInt() and 0xFF) shl 24)) }
        return Triple(fs, f.copyOfRange(0, count / 2), f.copyOfRange(count / 2, count))
    }

    /** HeyMelody's radar axes (Hz), in its order, and each one's scale in dB. `[VENDOR]`. */
    val AXES = intArrayOf(80, 10000, 4800, 2400, 1200, 250)
    private val AXIS_SCALE = floatArrayOf(7.5f, 15f, 15f, 12.5f, 12.5f, 7.5f)

    /** The summed response of biquads (a0 a1 a2 b0 b1 b2 each) at [freq], in dB. */
    fun responseDb(c: FloatArray, fs: Int, freq: Int): Double {
        val w = 2 * Math.PI * freq / fs
        var db = 0.0
        for (k in 0 until c.size / 6 * 6 step 6) {
            fun mag(x0: Float, x1: Float, x2: Float): Double {
                val re = x0 + x1 * Math.cos(w) + x2 * Math.cos(2 * w)
                val im = -x1 * Math.sin(w) - x2 * Math.sin(2 * w)
                return Math.hypot(re, im)
            }
            val den = mag(c[k], c[k + 1], c[k + 2])
            val num = mag(c[k + 3], c[k + 4], c[k + 5])
            if (den > 0 && num > 0) db += 20 * Math.log10(num / den)
        }
        return db
    }

    /** One ear's radar radii (0..10, 10 = no change, at least 2), from its hearing and ear-scan filters. */
    fun radar(hearing: FloatArray, scan: FloatArray?, scanFs: Int): FloatArray = FloatArray(AXES.size) { i ->
        val db = responseDb(hearing, 44100, AXES[i]) + (scan?.let { responseDb(it, scanFs, AXES[i]) } ?: 0.0)
        maxOf(2f, (-Math.abs(db) * 10 / AXIS_SCALE[i] + 10).toFloat())
    }

    fun uidAt(p: ByteArray, i: Int) = int32(p, i)

    private fun int32(p: ByteArray, i: Int) = ((p[i].toInt() and 0xFF) shl 24) or ((p[i + 1].toInt() and 0xFF) shl 16) or
        ((p[i + 2].toInt() and 0xFF) shl 8) or (p[i + 3].toInt() and 0xFF)
}
