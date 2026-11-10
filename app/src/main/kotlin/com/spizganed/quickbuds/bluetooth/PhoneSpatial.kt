package com.spizganed.quickbuds.bluetooth

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build

/**
 * 3D audio that the phone makes, for buds whose bitmap lacks `0xEF03` (PROTOCOL.md section 18).
 * Only OPPO, OnePlus and realme phones have this spatializer. The state is the standard Android
 * `Spatializer`; the switch is an OPLUS audio parameter. A write that the phone ignores falls back to
 * the phone's own Holo Audio settings page.
 */
object PhoneSpatial {
    private val MAKERS = setOf("oneplus", "oppo", "realme")
    private const val SET_ENABLED = "OPLUS_AUDIO_SET_SPATIALIZER_AUDIO;setEnabled;"
    private const val SETTINGS_ACTION = "oplus.intent.action.settings.HOLO_AUDIO"

    private fun audio(c: Context) = c.getSystemService(AudioManager::class.java)

    /** The phone can make the 3D effect for the audio device in use now. */
    fun supported(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33 || Build.MANUFACTURER.lowercase() !in MAKERS) return false
        return runCatching { audio(c)?.spatializer?.isAvailable == true }.getOrDefault(false)
    }

    fun isOn(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && runCatching { audio(c)?.spatializer?.isEnabled == true }.getOrDefault(false)

    /** Asks the phone to turn the effect on or off. The caller reads [isOn] back. */
    fun set(c: Context, on: Boolean) {
        runCatching { audio(c)?.setParameters(SET_ENABLED + on) }
    }

    /** Opens the phone's Holo Audio settings. False when the phone has no such page. */
    fun openSettings(c: Context): Boolean {
        val intent = Intent(SETTINGS_ACTION).addCategory(Intent.CATEGORY_DEFAULT)
            .setPackage("com.android.settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { c.startActivity(intent); true }.getOrDefault(false)
    }
}
