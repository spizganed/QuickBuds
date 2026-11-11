package com.spizganed.quickbuds.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * One model's entry in `models.json`, as typed answers. It is the only reader of the entry's top-level keys: code asks
 * `profile.noFind`, not `json.optInt("noFind")`. A new key goes here once. Nested tables (gestures, noise modes, presets)
 * are handed to their own parsers. No entry (an unknown model) answers false, 0, null or an empty list.
 * The keys are in docs/ADD-A-MODEL.md and PROTOCOL.md section 21. The Rust twin is `Profile` in `desktop-tauri/src/types.rs`.
 */
class ModelProfile(val json: JSONObject?) {
    private fun flag(key: String) = json?.optInt(key) == 1
    private fun strings(key: String): List<String> =
        json?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    val known get() = json != null
    val id: String get() = json?.optString("id").orEmpty()
    val name: String get() = json?.optString("name").orEmpty()

    /** The first word of the name (OnePlus, OPPO, realme, DIZO). Vendor rules that differ by brand read it here. */
    val brand: String get() = name.substringBefore(' ')
    /** A OnePlus or OPPO model: HeyMelody's list, not realme Link's. */
    val heyMelody get() = brand == "OnePlus" || brand == "OPPO"

    // --- What to ask in 0x010D, and how a write is confirmed ---
    /** The model's own `0x010D` id list, as hex text, or null when it has none. */
    val statusQuery: String? get() = json?.optString("statusQuery")?.takeIf { it.isNotBlank() }
    /** The game-mode id a capture proved, or null. */
    val gameId: Int? get() = json?.optInt("gameId")?.takeIf { it > 0 }
    /** The buds never push game mode: a status read confirms it. */
    val gameConfirmRead get() = json?.optString("gameConfirm") == "read"
    /** Acks come back with another seq than the write. */
    val seqless get() = flag("seqless")

    // --- Switches a flag adds to the ask list (RowRules.askQuery) ---
    val windMode get() = flag("windMode")
    val voiceBoost get() = flag("voiceBoost")
    val gameSoundFx get() = flag("gameSoundFx")
    val autoVolume get() = flag("autoVolume")
    val holdVolume get() = flag("holdVolume")
    val fastPair get() = flag("fastPair")
    val personalAnc get() = flag("personalAnc")
    val findPhone get() = flag("findPhone")
    val virtualBass get() = flag("virtualBass")

    // --- Rows ---
    val noFind get() = flag("noFind")
    val tapLevel get() = flag("tapLevel")
    /** Write-only rows: the buds do not report them. */
    val volumeEnhancer get() = flag("volumeEnhancer")
    val autoAnswer get() = flag("autoAnswer")
    /** Hi-Res picks a codec instead of a switch. */
    val hiRes get() = flag("hiRes")
    /** 3D audio and Hi-Res are one switch each, written alone (realme). */
    val spatialToggle get() = flag("spatialToggle")
    /** `spatialModes` holds the number 2: Off / Fixed / Head tracking. */
    val spatialHeadTracking: Boolean
        get() = json?.optJSONArray("spatialModes")?.let { a -> (0 until a.length()).any { a.optInt(it) == 2 } } == true
    val tripleDevice get() = flag("tripleDevice")
    /** The dual-connection functions the model offers (`setPriorityDevice`, `connectDisconnectDevice`, ...). */
    val dualDevice: List<String> get() = strings("dualDevice")
    val hearingScan get() = flag("hearingScan")
    val codecs: List<String> get() = strings("codecs")
    val knownIssues: List<String> get() = strings("knownIssues")
    /** The features an owner confirmed in a model test. */
    val confirmed: List<String> get() = strings("confirmed")

    // --- Equalizer ---
    /** 1-4: built-in presets; 2 also names types 1-4 differently. 0: none. */
    val eqKind: Int get() = json?.optInt("eqKind") ?: 0
    val customEq get() = flag("customEq")
    /** Custom presets the model takes; 3 when the entry says nothing. */
    val customEqLimit: Int get() = json?.optInt("customEqLimit")?.takeIf { it > 0 } ?: 3
    val customEqBands: JSONArray? get() = json?.optJSONArray("customEqBands")
    val bassEngine get() = flag("bassEngine")
    /** 3: realme dynamic sound, three sliders with a band byte. */
    val bassBands: Int get() = json?.optInt("bassBands") ?: 0
    val eqAltNames get() = flag("eqAltNames")
    val eqGentle4 get() = flag("eqGentle4")

    // --- Gestures and noise modes: tables for their parsers ---
    val gestures: JSONArray? get() = json?.optJSONArray("gestures")
    val callGestures: JSONArray? get() = json?.optJSONArray("callGestures")
    val ancModes: JSONArray? get() = json?.optJSONArray("ancModes")
    /** `longPressType`: each bud has its own hold; the bits are its choices. 0: one shared hold. */
    val holdMask: Int get() = json?.optInt("holdMask") ?: 0
    val sharedHold get() = flag("sharedHold")
    val oneButton get() = flag("oneButton")
    val bothHold get() = flag("bothHold")
    /** OnePlus Buds, Buds Z: previous and next track are `fn` 4 and 5. */
    val oldTrackBytes get() = flag("oldTrackBytes")
    /** Buds Pro: the hold shows its ANC levels as one ANC. */
    val holdAncOne get() = flag("holdAncOne")
}
