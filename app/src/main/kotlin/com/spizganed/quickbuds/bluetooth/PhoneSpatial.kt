package com.spizganed.quickbuds.bluetooth

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.provider.Settings

/**
 * 3D audio that the phone makes, for buds whose bitmap lacks `0xEF03` (PROTOCOL.md section 18).
 * The state is the standard Android `Spatializer`. On OPPO, OnePlus and realme phones the switch is an
 * OPLUS audio parameter; a write that the phone ignores falls back to the phone's Holo Audio page.
 * Other phones let only their settings turn it on, so the switch opens the Bluetooth settings.
 */
object PhoneSpatial {
    private val MAKERS = setOf("oneplus", "oppo", "realme")
    private const val SET_ENABLED = "OPLUS_AUDIO_SET_SPATIALIZER_AUDIO;setEnabled;"
    private const val SETTINGS_ACTION = "oplus.intent.action.settings.HOLO_AUDIO"

    private fun audio(c: Context) = c.getSystemService(AudioManager::class.java)

    /** The phone can make the 3D effect for the audio device in use now. */
    fun supported(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching { audio(c)?.spatializer?.isAvailable == true }.getOrDefault(false)
    }

    /** QuickBuds can write the switch: an OPPO, OnePlus or realme phone. */
    fun writable(): Boolean = Build.MANUFACTURER.lowercase() in MAKERS

    fun isOn(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && runCatching { audio(c)?.spatializer?.isEnabled == true }.getOrDefault(false)

    /** Asks the phone to turn the effect on or off. The caller reads [isOn] back. */
    fun set(c: Context, on: Boolean) {
        if (writable()) runCatching { audio(c)?.setParameters(SET_ENABLED + on) }
    }

    /** Opens the Holo Audio settings, or on other phones the Bluetooth settings. False when the page is missing. */
    fun openSettings(c: Context): Boolean {
        val intent = (if (writable()) Intent(SETTINGS_ACTION).addCategory(Intent.CATEGORY_DEFAULT).setPackage("com.android.settings")
            else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { c.startActivity(intent); true }.getOrDefault(false)
    }
}
