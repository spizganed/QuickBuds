package com.spizganed.quickbuds.protocol

import android.content.Context
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * What the connected buds can do, from their own replies (PROTOCOL.md §5 and §16):
 *
 *  - the `0x8100` handshake reply is a bitmap of the commands the firmware accepts. HeyMelody
 *    refuses to send a command outside it (apart from [ALWAYS]), so a row whose command
 *    is missing is hidden here the same way;
 *  - the `0x810D` status reply lists only the `0x0403` feature switches the firmware has, so a
 *    feature id missing from it is a switch these buds do not have.
 *
 * Both are persisted, so a screen can ask before the service binds. A row needs the buds' answer:
 * nothing read yet shows no conditional row ([RowRules]).
 */
object Capabilities {

    // The saved answers of the last buds. Only this object reads or writes them; callers get typed values.
    private const val KEY_COMMANDS = "budsCommands"
    private const val KEY_PRODUCT_ID = "budsProductId"
    /** The last `0x810D` reply, as `id=value,...`. */
    private const val KEY_FEATURES = "lastFeatureStates"
    private const val KEY_FIRMWARE = "firmwareVersion"

    /** `0x8100` payload `00 <bitmap>` -> the commands it enables, or null if malformed. The table is [Bitmap]'s. */
    fun parse(payload: ByteArray): Set<Int>? = Bitmap.parse(payload)?.commands

    /**
     * `0x8103` payload `00 <id, 3 bytes LE>` -> "065414". `[CAPTURE]` Buds 4 answers `00 14 54 06`;
     * `[OSS]` OppoPods `ProductIdParser` reads it the same way. Colour variants are
     * folded into one id ([ModelCatalog.normalise]).
     */
    fun productId(payload: ByteArray): String? {
        if (payload.size != 4 || payload[0].toInt() != 0) return null
        return productId(OpoProtocol.u16(payload, 1) or ((payload[3].toInt() and 0xFF) shl 16))
    }

    /** The 24-bit product id as text, colour variants folded. */
    fun productId(id: Int): String = "%06X".format(ModelCatalog.normalise(id))

    /** The saved product id ("065414"), or null when the buds have not said it. */
    fun productId(context: Context): String? = prefs(context).getString(KEY_PRODUCT_ID, null)

    /** Saves [id]. True when it differs from the saved one: other buds than last time. */
    fun saveProductId(context: Context, id: String): Boolean {
        val changed = productId(context) != id
        prefs(context).edit().putString(KEY_PRODUCT_ID, id).apply()
        return changed
    }

    /** The last firmware version read, as HeyMelody shows it ([Msg.Firmware.version]). */
    fun firmware(context: Context): String? = prefs(context).getString(KEY_FIRMWARE, null)

    fun saveFirmware(context: Context, version: String) = prefs(context).edit().putString(KEY_FIRMWARE, version).apply()

    /** Saves the last `0x810D` reply. */
    fun saveReply(context: Context, reply: Map<Int, Int>) =
        prefs(context).edit().putString(KEY_FEATURES, reply.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()

    /** Other buds: their product id, bitmap and reply go, so no saved row of the old buds shows ([RowRules]). */
    fun forget(context: Context) =
        prefs(context).edit().remove(KEY_PRODUCT_ID).remove(KEY_COMMANDS).remove(KEY_FEATURES).apply()

    /** Switch [id] is on in the last `0x810D` reply. */
    fun featureOn(context: Context, id: Int): Boolean = reply(context)?.get(id) == 1

    fun supports(context: Context, cmd: Int): Boolean {
        val saved = prefs(context).getString(KEY_COMMANDS, null) ?: return true
        return saved.split(',').any { it.toIntOrNull() == cmd }
    }

    /** The saved `0x8100` commands, or null when the bitmap is not read yet. */
    fun commands(context: Context): Set<Int>? {
        val saved = prefs(context).getString(KEY_COMMANDS, null) ?: return null
        return saved.split(',').mapNotNull { it.toIntOrNull() }.toSet()
    }

    /** The saved `0x810D` reply, or null when no reply is saved yet. */
    fun reply(context: Context): Map<Int, Int>? {
        val saved = prefs(context).getString(KEY_FEATURES, null).orEmpty()
        if (saved.isEmpty()) return null
        return saved.split(',').mapNotNull { e ->
            val k = e.substringBefore('=').toIntOrNull()
            val v = e.substringAfter('=', "").toIntOrNull()
            if (k != null && v != null) k to v else null
        }.toMap()
    }

    /** The game mode switch: `0x28` on buds with game sound types, else `0x06`. */
    fun gameModeId(context: Context) = RowRules.gameModeId(commands(context), ModelCatalog.current(context)?.json)

    /** The buds listed [id] in their last `0x810D` reply. Nothing read yet means no. */
    fun hasFeature(context: Context, id: Int): Boolean = reply(context)?.containsKey(id) == true

    fun save(context: Context, commands: Set<Int>) {
        prefs(context).edit().putString(KEY_COMMANDS, commands.sorted().joinToString(",")).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
}
