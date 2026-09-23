package com.spizganed.quickbuds.protocol

/**
 * Custom EQ presets — the `0x8122` list and the `0x0418` select/save frame.
 * `[CAPTURE]` 2026-09-23, confirmed against HeyMelody screenshots; see PROTOCOL.md §9.
 *
 * List entry: `<flag> <tag 2B> <id> <nameLen> <name> <bandCount> [freq u16 LE, gain s8]...`
 * `flag` 01 = selected. `tag` is `FA 06` in every frame seen; it is kept verbatim and echoed
 * back rather than hardcoded, since its meaning is unknown.
 */
object EqCodec {

    /** HeyMelody's built-in presets, selected by id with `0x0406`. Ids 04+ are custom. */
    const val BALANCED = 0
    const val CLEAR_VOCALS = 1
    const val BASS = 2

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
                freqs += (payload[o].toInt() and 0xFF) or ((payload[o + 1].toInt() and 0xFF) shl 8)
                gains += payload[o + 2].toInt() // signed byte
                o += 3
            }
            out += Preset(id, name, freqs, gains, flag == 1, tag)
        }
        return out
    }

    /** `0x0418` payload — select AND save in one: `02 <tag> <id> <nameLen> <name> <bands...>`. */
    fun encodeSave(p: Preset): ByteArray {
        val name = p.name.toByteArray(Charsets.UTF_8)
        val out = ArrayList<Byte>()
        out += 0x02
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
