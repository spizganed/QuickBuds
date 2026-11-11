package com.spizganed.quickbuds.protocol

import org.json.JSONObject

/**
 * Which rows the app shows. The earbuds' own answers decide:
 *
 *  - the `0x810D` status reply lists the feature ids the buds have. An id in the reply means
 *    "has the feature", whatever its value.
 *  - the `0x8100` bitmap lists the commands the firmware accepts.
 *  - `models.json` decides only WHAT TO ASK. A model flag adds its id to the query list. It never
 *    shows a row on its own.
 *
 * No Android imports: this object is plain Kotlin, so a unit test can call it with real logs.
 */
object RowRules {

    // Home screen rows, in the app's order.
    const val WIND = "wind"
    const val GAME = "game"
    const val HIRES = "hires"
    const val SPATIAL = "spatial"
    const val GOLDEN = "golden"
    const val EQ = "eq"
    const val DUAL = "dual"
    const val ANC = "anc"
    const val EARBUDS = "earbuds"

    // Earbud settings rows.
    const val CONTROLS = "controls"
    const val WEAR = "wear"
    const val FIND = "find"
    const val FIT = "fit"
    const val ALERT_VOLUME = "alertVolume"
    const val FIRMWARE = "firmware"
    const val TAP_LEVEL = "tapLevel"
    const val PERSONAL_ANC = "personalAnc"
    const val VOICE_BOOST = "voiceBoost"
    const val GAME_SOUND = "gameSound"
    const val GAME_SOUND_TYPE = "gameSoundType"
    const val GAME_EQ = "gameEq"
    const val AUTO_VOLUME = "autoVolume"
    const val ADAPTIVE_VOLUME = "adaptiveVolume"
    const val ADAPTIVE_EAR = "adaptiveEar"
    const val SLEEP_PAUSE = "sleepPause"
    const val LOST_REMINDER = "lostReminder"
    const val SPEECH_PERCEPTION = "speechPerception"
    const val HEARING_OPTIMIZE = "hearingOptimize"
    const val HOLD_VOLUME = "holdVolume"
    const val HEAD_GESTURE = "headGesture"
    const val HEAD_GESTURE_TYPE = "headGestureType"
    const val FAST_PAIR = "fastPair"
    const val POWER_SAVING = "powerSaving"
    const val FIND_PHONE = "findPhone"
    const val VIRTUAL_BASS = "virtualBass"
    // Write-only: the buds do not report them, so the model flag alone shows the row.
    const val VOLUME_ENHANCER = "volumeEnhancer"
    const val AUTO_ANSWER = "autoAnswer"

    // Equalizer screen parts.
    const val EQ_PRESETS = "eqPresets"
    const val EQ_CUSTOM = "eqCustom"
    const val BASS_WAVE = "bassWave"
    const val BASS_BANDS = "bassBands"

    // Dual connection screen parts.
    const val DUAL_PRIORITY = "dualPriority"
    const val DUAL_DEVICES = "dualDevices"

    /** The two game-mode ids: the buds use one of them as their switch. Both are asked. */
    private val GAME_IDS = intArrayOf(Feature.GAME_MODE, Feature.GAME_MODE_MAIN)

    /**
     * The rows to show.
     *
     * [model] is the model's `models.json` object, or null when no model is known.
     * [reply] is the last `0x810D` reply as id -> value. Null means "nothing read yet": then no
     * conditional row shows, because a row needs the buds' answer. Empty means the buds answered
     * nothing, which also shows no conditional row.
     * [bitmap] is the `0x8100` command set. Null means "nothing read yet".
     * [productAnc] names the noise modes the buds reported, when `models.json` has none.
     * [manual]: the person picked the model. Then the model's data passes where the bitmap would decide
     * (Personalized ANC, game sound type, tap level, the priority device).
     * [gestures]: the model has a gesture row this app can write. Null: the model's `gestures` list decides.
     */
    fun rows(
        model: JSONObject?,
        reply: Map<Int, Int>?,
        bitmap: Set<Int>?,
        productAnc: Map<String, Int>? = null,
        phoneSpatialOk: Boolean = false,
        manual: Boolean = false,
        gestures: Boolean? = null
    ): Set<String> {
        val out = linkedSetOf(EARBUDS)
        if (reply == null) return out
        val caps = bitmap ?: emptySet()
        val has = { id: Int -> reply.containsKey(id) }
        val accepts = { cmd: Int -> cmd in caps || manual }
        val p = ModelProfile(model)

        if (has(Feature.WIND_NOISE)) out += WIND
        if (GAME_IDS.any { has(it) }) out += GAME
        if (has(Feature.HIRES_CODEC) || codecPicker(model, bitmap)) out += HIRES
        // Phone-made 3D audio shows only where this phone can make it ([phoneSpatialOk]).
        if ((has(Feature.SPATIAL_SOUND) && (!phoneSpatial(model, bitmap) || phoneSpatialOk)) || spatialByType(model, bitmap)) out += SPATIAL
        if (has(Feature.GOLDEN_SOUND)) out += GOLDEN
        if (eqVisible(model, bitmap)) out += EQ
        if (has(Feature.DUAL_DEVICE)) out += DUAL
        if (OpoProtocol.CMD_SET_ANC in caps && hasAncTable(model, productAnc)) out += ANC

        if (OpoProtocol.CMD_SET_KEY_FUNCTION in caps && (gestures ?: ((p.gestures?.length() ?: 0) > 0))) out += CONTROLS
        if (has(Feature.AUTO_PLAY_PAUSE)) out += WEAR
        if (OpoProtocol.CMD_FIND_BUDS in caps && !p.noFind) out += FIND
        if (OpoProtocol.CMD_FIT_TEST in caps) out += FIT
        if (OpoProtocol.CMD_SET_ALERT_VOLUME in caps) out += ALERT_VOLUME
        if (OpoProtocol.CMD_QUERY_FIRMWARE in caps) out += FIRMWARE
        if (p.tapLevel && accepts(OpoProtocol.CMD_SET_TAP_LEVEL)) out += TAP_LEVEL
        // The flag too: HeyMelody gates on it, and Buds 4 list 0x0C without having the feature.
        // No models.json entry counts as flag on: the buds' reply and bitmap decide.
        if ((!p.known || p.personalAnc) && has(Feature.PERSONAL_NOISE) && accepts(OpoProtocol.CMD_PERSONAL_NOISE)) out += PERSONAL_ANC
        if (has(Feature.VOCAL_ENHANCE)) out += VOICE_BOOST
        if (has(Feature.GAME_SOUND)) {
            out += GAME_SOUND
            if (accepts(OpoProtocol.CMD_GAME_SOUND)) out += GAME_SOUND_TYPE
        }
        if (has(Feature.GAME_EQ) && OpoProtocol.CMD_GAME_EQ_STATE in caps) out += GAME_EQ
        if (has(Feature.SMART_VOLUME)) out += AUTO_VOLUME
        if (has(Feature.ADAPTIVE_VOLUME)) out += ADAPTIVE_VOLUME
        if (has(Feature.ADAPTIVE_EAR)) out += ADAPTIVE_EAR
        if (has(Feature.SLEEP_PAUSE)) out += SLEEP_PAUSE
        if (has(Feature.LOST_REMIND)) out += LOST_REMINDER
        if (has(Feature.SPEECH_PERCEPTION)) out += SPEECH_PERCEPTION
        if (has(Feature.HEARING_OPTIMIZE)) out += HEARING_OPTIMIZE
        if (has(Feature.LONG_PRESS_VOLUME)) out += HOLD_VOLUME
        if (has(Feature.HEAD_MOTION)) {
            out += HEAD_GESTURE
            if (OpoProtocol.CMD_SET_HEAD_MOTION_TYPE in caps) out += HEAD_GESTURE_TYPE
        }
        if (has(Feature.SWIFT_PAIR)) out += FAST_PAIR
        if (has(Feature.POWER_SAVING)) out += POWER_SAVING
        if (has(Feature.FIND_PHONE)) out += FIND_PHONE
        if (has(Feature.VIRTUAL_BASS)) out += VIRTUAL_BASS
        if (p.volumeEnhancer) out += VOLUME_ENHANCER
        if (p.autoAnswer) out += AUTO_ANSWER

        if (eqPresets(model, bitmap)) out += EQ_PRESETS
        if (eqCustom(model, bitmap)) out += EQ_CUSTOM
        if (p.bassEngine && OpoProtocol.CMD_SET_BASSWAVE_LEVEL in caps) out += BASS_WAVE
        // realme dynamic sound: three sliders with a band byte.
        if (p.bassBands == 3) out += BASS_BANDS
        if (has(Feature.DUAL_DEVICE)) {
            out += DUAL_DEVICES
            if (dualPriority(model) && accepts(OpoProtocol.CMD_MULTI_CONNECT)) out += DUAL_PRIORITY
        }
        return out
    }

    /**
     * The ids the app asks in `0x010D`: the model's own list, the ids a model flag names, and both
     * game-mode ids. Null when the model has no list: the caller uses the default list. A model without a list
     * but with the `findPhone` flag starts from the default list, because the default list lacks that id.
     */
    fun askQuery(model: JSONObject?, bitmap: Set<Int>?): String? {
        val p = ModelProfile(model)
        val base = p.statusQuery ?: if (p.findPhone) defaultList(model) else return null
        val ids = base.split(' ').mapNotNull { it.toIntOrNull(16) }.toMutableSet()
        askExtras(model).forEach { ids += it }
        return ids.sorted().joinToString(" ") { "%02X".format(it) }
    }

    /** True when the default id list must add the wind id: only a model with the `windMode` flag. */
    fun asksWind(model: JSONObject?): Boolean = ModelProfile(model).windMode

    /**
     * The ids a model without `statusQuery` is asked, as `Commands.queryStatus` asks them.
     * [asksWind] adds the wind id on top.
     */
    val DEFAULT_IDS: Set<Int> = setOf(
        0x05, 0x04, 0x0B, 0x11, 0x13, 0x18, 0x06, 0x1B, 0x1C, 0x27, 0x28, 0x1D,
        0x09, 0x17, 0x30, 0x31, 0x3A, 0x32, 0x35, 0x37, 0x38, 0x3B, 0x0C
    )

    private fun defaultList(model: JSONObject?): String =
        (DEFAULT_IDS + if (asksWind(model)) setOf(Feature.WIND_NOISE) else emptySet())
            .joinToString(" ") { "%02X".format(it) }

    /** The live ask list: [askQuery] when the model has one, else [DEFAULT_IDS] plus wind. */
    fun liveAskIds(model: JSONObject?, bitmap: Set<Int>?): Set<Int> =
        askQuery(model, bitmap)?.split(' ')?.map { it.toInt(16) }?.toSet()
            ?: (DEFAULT_IDS + if (asksWind(model)) setOf(Feature.WIND_NOISE) else emptySet())

    /**
     * The game-mode id the buds write and read. One place, for the ask list and the row.
     * A model's `gameId` wins, for a model whose capture proves another id (PROTOCOL.md section 16).
     */
    fun gameModeId(bitmap: Set<Int>?, model: JSONObject? = null): Int =
        ModelProfile(model).gameId
            ?: if (bitmap != null && OpoProtocol.CMD_GAME_SOUND in bitmap) Feature.GAME_MODE_MAIN else Feature.GAME_MODE

    /**
     * A OnePlus or OPPO model whose bitmap lacks `0xEF03`: the phone makes the 3D effect, and only OPPO, OnePlus and
     * realme phones can (PROTOCOL.md section 18). The `1B` switch does nothing audible: the row drives the phone's
     * spatializer ([com.spizganed.quickbuds.bluetooth.PhoneSpatial]) where it exists, else it is hidden.
     */
    fun phoneSpatial(model: JSONObject?, bitmap: Set<Int>?): Boolean {
        val p = ModelProfile(model)
        return p.heyMelody && !p.spatialToggle && !bitmap.isNullOrEmpty() && BUDS_SPATIAL !in bitmap
    }

    /** Bitmap bit 43: the buds make the 3D effect themselves. */
    private const val BUDS_SPATIAL = 0xEF03

    /** `0x012A` on the buds, or `spatialModes` with `2`: the 3D row takes a type through `0x0422`. */
    fun spatialByType(model: JSONObject?, bitmap: Set<Int>?): Boolean =
        !ModelProfile(model).spatialToggle &&
            ((bitmap != null && OpoProtocol.CMD_QUERY_SPATIAL_TYPE in bitmap) || spatialHeadTracking(model))

    /** True when `spatialModes` holds the number 2. The value is tested, not the JSON text (R6). */
    fun spatialHeadTracking(model: JSONObject?): Boolean {
        return ModelProfile(model).spatialHeadTracking
    }

    /** `hiRes` model with the codec list command: the row picks a codec instead of a switch. */
    fun codecPicker(model: JSONObject?, bitmap: Set<Int>?): Boolean =
        ModelProfile(model).hiRes && bitmap != null && OpoProtocol.CMD_QUERY_CODEC_LIST in bitmap

    /** The Equalizer row needs EQ data in the model AND its command in the bitmap. No `eqKind` is no EQ. */
    fun eqVisible(model: JSONObject?, bitmap: Set<Int>?): Boolean =
        eqCustom(model, bitmap) || eqPresets(model, bitmap)

    fun eqPresets(model: JSONObject?, bitmap: Set<Int>?): Boolean =
        ModelProfile(model).eqKind in 1..4 && bitmap != null && OpoProtocol.CMD_SET_EQ in bitmap

    fun eqCustom(model: JSONObject?, bitmap: Set<Int>?): Boolean =
        ModelProfile(model).customEq && bitmap != null && OpoProtocol.CMD_SAVE_CUSTOM_EQ in bitmap

    private fun hasAncTable(model: JSONObject?, productAnc: Map<String, Int>?): Boolean =
        !productAnc.isNullOrEmpty() || (ModelProfile(model).ancModes?.length() ?: 0) > 0

    private fun dualPriority(model: JSONObject?): Boolean = "setPriorityDevice" in ModelProfile(model).dualDevice

    /** The ids that a model flag or the game-mode rule adds to the model's own list. */
    private fun askExtras(model: JSONObject?): Set<Int> {
        val out = linkedSetOf<Int>()
        GAME_IDS.forEach { out += it }
        val p = ModelProfile(model)
        if (p.windMode) out += Feature.WIND_NOISE
        if (p.voiceBoost) out += Feature.VOCAL_ENHANCE
        if (p.gameSoundFx) out += Feature.GAME_SOUND
        if (p.autoVolume) out += Feature.SMART_VOLUME
        if (p.holdVolume) out += Feature.LONG_PRESS_VOLUME
        if (p.fastPair) out += Feature.SWIFT_PAIR
        if (p.personalAnc) out += Feature.PERSONAL_NOISE
        if (p.findPhone) out += Feature.FIND_PHONE
        if (p.virtualBass) out += Feature.VIRTUAL_BASS
        return out
    }
}
