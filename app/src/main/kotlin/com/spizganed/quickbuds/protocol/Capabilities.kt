package com.spizganed.quickbuds.protocol

import android.content.Context
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * What the connected buds can do, from their own replies (PROTOCOL.md §4):
 *
 *  - the `0x8100` handshake reply is a bitmap of the commands the firmware accepts. HeyMelody
 *    refuses to send a command outside it (apart from [ALWAYS]), so a row whose command
 *    is missing is hidden here the same way;
 *  - the `0x810D` status reply lists only the `0x0403` feature switches the firmware has, so a
 *    feature id missing from it is a switch these buds do not have.
 *
 * Both are persisted, so a screen can ask before the service binds. Nothing read yet means
 * everything is allowed: the behaviour before detection existed, and what a Buds 4 gets anyway.
 */
object Capabilities {

    /** Bit n of the `0x8100` bitmap (LSB first) enables these commands. `[VENDOR]` HeyMelody. */
    private val BIT_COMMANDS: Array<IntArray> = arrayOf(
        intArrayOf(0x0105), intArrayOf(0x0106), intArrayOf(0x0107), intArrayOf(0x0108, 0x0401, 0x0416),
        intArrayOf(0x0109), intArrayOf(0x0400), intArrayOf(0x0402), intArrayOf(0x0403),
        intArrayOf(0x010C, 0x0404), intArrayOf(0x0405), intArrayOf(0x0406, 0x010F), intArrayOf(0x0407),
        intArrayOf(), intArrayOf(0x0408), intArrayOf(0x0409), intArrayOf(), intArrayOf(),
        intArrayOf(0x0114), intArrayOf(), intArrayOf(0x040E, 0x040D, 0x0115, 0x0116),
        intArrayOf(0x040F), intArrayOf(0x0410, 0x0119), intArrayOf(0x0205), intArrayOf(0x0F00),
        intArrayOf(), intArrayOf(0x0118, 0x0411), intArrayOf(0x011A, 0x0412),
        intArrayOf(0x011C, 0x0413), intArrayOf(), intArrayOf(0x0112, 0x040B),
        intArrayOf(0x011E, 0x011F, 0x0415), intArrayOf(0x040D), intArrayOf(),
        intArrayOf(0x0121, 0x0417), intArrayOf(0x0122, 0x0418), intArrayOf(),
        intArrayOf(0x011D, 0x0414), intArrayOf(0x0123, 0x041A), intArrayOf(0x0124, 0x041B),
        intArrayOf(0x0125, 0x041C, 0x0127, 0x041D, 0x041F),
        intArrayOf(0x0421, 0x0023, 0x0024, 0x0022, 0x0126, 0x0129), intArrayOf(0xEF01),
        intArrayOf(0xEF02), intArrayOf(0xEF03, 0x041E), intArrayOf(0x0420), intArrayOf(0x001C),
        intArrayOf(), intArrayOf(0x0422, 0x012A), intArrayOf(0xEF04), intArrayOf(0x0423, 0x012B),
        intArrayOf(), intArrayOf(0x0424), intArrayOf(0xEF06), intArrayOf(), intArrayOf(),
        intArrayOf(0x0425, 0x012E, 0x0426), intArrayOf(0x012F), intArrayOf(0x0427, 0x0130),
        intArrayOf(0x0131, 0x0428), intArrayOf(0x0429, 0x0132), intArrayOf(0x0014),
        intArrayOf(0x042D, 0x0133), intArrayOf(0x042E), intArrayOf(0xEF07), intArrayOf(0xEF08),
        intArrayOf(0xEF09), intArrayOf(0x0431, 0x0134)
    )

    /** Sent without a bit. `[VENDOR]` same file. */
    private val ALWAYS = setOf(
        0x0100, 0x0101, 0x0102, 0x0103, 0x0104, 0x0106, 0x010B, 0x010D, 0x0F00, 0x0F03, 0x0F04
    )

    const val KEY_COMMANDS = "budsCommands"
    const val KEY_PRODUCT_ID = "budsProductId"
    /** Written by BudsConnectionManager from every `0x810D` reply, as `id=value,...`. */
    const val KEY_FEATURES = "lastFeatureStates"

    /** `0x8100` payload `00 <bitmap>` -> the commands it enables, or null if malformed. */
    fun parse(payload: ByteArray): Set<Int>? {
        if (payload.size < 2 || payload[0].toInt() != 0) return null
        val out = HashSet(ALWAYS)
        for (bit in 0 until minOf((payload.size - 1) * 8, BIT_COMMANDS.size)) {
            if ((payload[1 + bit / 8].toInt() shr (bit % 8)) and 1 == 1) BIT_COMMANDS[bit].forEach { out += it }
        }
        return out
    }

    /**
     * `0x8103` payload `00 <id, 3 bytes LE>` -> "065414". `[CAPTURE]` Buds 4 answers `00 14 54 06`
     * (2026-09-27); `[OSS]` OppoPods `ProductIdParser` reads it the same way. Colour variants are
     * folded into one id ([ModelCatalog.normalise]).
     */
    fun productId(payload: ByteArray): String? {
        if (payload.size != 4 || payload[0].toInt() != 0) return null
        val id = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8) or
            ((payload[3].toInt() and 0xFF) shl 16)
        return "%06X".format(ModelCatalog.normalise(id))
    }

    fun supports(context: Context, cmd: Int): Boolean {
        val saved = prefs(context).getString(KEY_COMMANDS, null) ?: return true
        return saved.split(',').any { it.toIntOrNull() == cmd }
    }

    fun hasFeature(context: Context, id: Int): Boolean {
        val saved = prefs(context).getString(KEY_FEATURES, null).orEmpty()
        if (saved.isEmpty()) return true
        return saved.split(',').any { it.substringBefore('=').toIntOrNull() == id }
    }

    fun save(context: Context, commands: Set<Int>) {
        prefs(context).edit().putString(KEY_COMMANDS, commands.sorted().joinToString(",")).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
}
