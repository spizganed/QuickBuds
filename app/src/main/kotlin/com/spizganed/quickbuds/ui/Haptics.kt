package com.spizganed.quickbuds.ui

import android.content.Context
import android.os.Build
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * SPEC section 4: a CONFIRM tick when a setting is committed (finger lift, toggle, selection),
 * VIRTUAL_KEY below API 30. Gated on Settings › Haptic feedback (default on).
 */
object Haptics {
    private fun on(c: Context) =
        c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(SettingsActivity.KEY_HAPTICS, true)

    fun commit(view: View) {
        if (!on(view.context)) return
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    /**
     * A widget tap: no view of ours is on screen, so the vibrator directly (needs VIBRATE, a normal
     * permission). EFFECT_CLICK is the platform's key tick; a plain 20 ms pulse below API 29.
     */
    fun tick(c: Context) {
        if (!on(c)) return
        val vib = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(Vibrator::class.java)
        val effect = if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        else VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
        // The default TOUCH usage is dropped for a background app ("ignored_background" in
        // dumpsys vibrator_manager); HARDWARE_FEEDBACK and NOTIFICATION_EVENT are allowed.
        if (Build.VERSION.SDK_INT >= 33) vib?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
        else @Suppress("DEPRECATION") vib?.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
    }
}
