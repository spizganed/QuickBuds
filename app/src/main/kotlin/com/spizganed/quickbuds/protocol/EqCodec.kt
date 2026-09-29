package com.spizganed.quickbuds.protocol

/**
 * Custom EQ presets — the `0x8122` list and the `0x0418` select/save frame.
 * `[CAPTURE]` 2026-09-23, confirmed against HeyMelody screenshots; see PROTOCOL.md §9.
 *
 * List entry: `<flag> <tag 2B> <id> <nameLen> <name> <bandCount> [freq u16 LE, gain s8]...`
 * `flag` 01 = selected. `tag` is `FA 06` in every frame seen: `[VENDOR]` the gain range, min -6 then
 * max +6, which HeyMelody sets on a new preset. It is kept verbatim and echoed back.
 */
object EqCodec {

    const val GAIN_MIN = -6
    const val GAIN_MAX = 6

    class Preset(
        val id: Int,
        val name: String,
        val freqs: List<Int>,
        val gains: List<Int>,
        val selected: Boolean,
        val tag: ByteArray
    ) {
        fun withGain(band: Int, gain: Int) =
            Preset(id, name, freqs, gains.toMutableList().also { it[band] = gain }, selected, tag)

        fun withName(newName: String) = Preset(id, newName, freqs, gains, selected, tag)
    }

    /** `0x8122` payload: `<status> <count> <entries...>`. Returns null on any inconsistency. */
    fun parseList(payload: ByteArray): List<Preset>? {
        if (payload.size < 2 || payload[0].toInt() != 0) return null
        val count = payload[1].toInt() and 0xFF
        var o = 2
        val out = ArrayList<Preset>(count)
        repeat(count) {
            if (o + 5 > payload.size) return null
            val flag = payload[o].toInt() and 0xFF
            val tag = payload.copyOfRange(o + 1, o + 3)
            val id = payload[o + 3].toInt() and 0xFF
            val nameLen = payload[o + 4].toInt() and 0xFF
            o += 5
            if (o + nameLen + 1 > payload.size) return null
            val name = String(payload, o, nameLen, Charsets.UTF_8)
            o += nameLen
            val bands = payload[o].toInt() and 0xFF
            o += 1
            if (o + bands * 3 > payload.size) return null
            val freqs = ArrayList<Int>(bands)
            val gains = ArrayList<Int>(bands)
            repeat(bands) {
                freqs += OpoProtocol.u16(payload, o)
                gains += payload[o + 2].toInt() // signed byte
                o += 3
            }
            out += Preset(id, name, freqs, gains, flag == 1, tag)
        }
        return out
    }

    /** `0x0418` first byte. Create sends id `00` and the buds assign one; delete renumbers the rest. */
    const val ACTION_CREATE = 0x01
    const val ACTION_SAVE = 0x02   // select AND save in one
    const val ACTION_DELETE = 0x03

    /** The tag every captured preset carries; used for a new preset, which has none of its own yet. */
    val DEFAULT_TAG = byteArrayOf(GAIN_MIN.toByte(), GAIN_MAX.toByte())
    /** HeyMelody's bands where the model list gives none (`customEqFrequency`: 10 bands on 8 models). */
    val DEFAULT_FREQS = listOf(62, 250, 1000, 4000, 8000, 16000)

    fun newPreset(name: String, freqs: List<Int>) = Preset(0, name, freqs, List(freqs.size) { 0 }, false, DEFAULT_TAG)

    /** Share text for a preset: `QB-EQ:<gain,...>:<name>`. Gains only; the bands are the model's. */
    private const val TEXT_PREFIX = "QB-EQ:"

    fun toText(p: Preset) = TEXT_PREFIX + p.gains.joinToString(",") + ":" + p.name

    /** Name and gains from [toText]'s format with [bands] gains, or null if it is not one. The name may contain ':'. */
    fun fromText(text: String, bands: Int): Pair<String, List<Int>>? {
        val parts = text.trim().takeIf { it.startsWith(TEXT_PREFIX) }
            ?.removePrefix(TEXT_PREFIX)?.split(":", limit = 2) ?: return null
        val gains = parts[0].split(",").map { it.trim().toIntOrNull() ?: return null }
        val name = parts.getOrNull(1)?.trim().orEmpty()
        if (gains.size != bands || gains.any { it !in GAIN_MIN..GAIN_MAX }) return null
        if (name.isEmpty() || name.length > 20) return null
        return name to gains
    }

    /** `0x0418` payload: `<action> <tag> <id> <nameLen> <name> <bandCount> <bands...>`. */
    fun encode(action: Int, p: Preset): ByteArray {
        val name = p.name.toByteArray(Charsets.UTF_8)
        val out = ArrayList<Byte>()
        out += action.toByte()
        out += p.tag.toList()
        out += p.id.toByte()
        out += name.size.toByte()
        out += name.toList()
        out += p.freqs.size.toByte()
        for (i in p.freqs.indices) {
            out += (p.freqs[i] and 0xFF).toByte()
            out += ((p.freqs[i] shr 8) and 0xFF).toByte()
            out += p.gains[i].toByte()
        }
        return out.toByteArray()
    }
}
