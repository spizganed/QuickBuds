package com.spizganed.quickbuds.protocol

import org.json.JSONObject

/**
 * Which settings go through the feature-sync engine ([FeatureSync]) on Android.
 * Remove one key from [ENABLED] to put that setting back on the old `setFeatures` path.
 */
object FeatureSyncConfig {
    /** The engine sets these keys. `BudsConnectionManager.requestSetting` checks this set. */
    val ENABLED: Set<Int> = setOf(        Feature.GAME_MODE,
        Feature.GAME_MODE_MAIN,
        Feature.HIRES_CODEC,
        Feature.SPATIAL_SOUND,
        Feature.GOLDEN_SOUND,
        Feature.WIND_NOISE,
        Feature.BASSWAVE,
        Feature.AUTO_PLAY_PAUSE,
        Feature.VOCAL_ENHANCE,
        Feature.GAME_SOUND,
        Feature.GAME_EQ,
        Feature.SMART_VOLUME,
        Feature.ADAPTIVE_VOLUME,
        Feature.ADAPTIVE_EAR,
        Feature.SLEEP_PAUSE,
        Feature.LOST_REMIND,
        Feature.SPEECH_PERCEPTION,
        Feature.HEARING_OPTIMIZE,
        Feature.LONG_PRESS_VOLUME,
        Feature.HEAD_MOTION,
        Feature.SWIFT_PAIR,
        Feature.FIND_PHONE,
    )

    /**
     * How the game-mode write is confirmed. `push` by default: the buds push `0x0204` subType `05` after the ack.
     * A model that never pushes sets `"gameConfirm":"read"` in `models.json`; the status reply then confirms it
     * (realme Buds T310, reports #16 and #73). The model entry may override only this, never the write itself.
     */
    fun gameConfirm(model: JSONObject?): FeatureSync.Confirm =
        if (model?.optString("gameConfirm") == "read") FeatureSync.Confirm.READ else FeatureSync.Confirm.PUSH
}
