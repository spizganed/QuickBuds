package com.spizganed.quickbuds.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * SPEC section 4: a CONFIRM tick when a setting is committed (finger lift, toggle, selection),
 * VIRTUAL_KEY below API 30. Gated on Settings › Haptic feedback (default on).
 */
object Haptics {
    fun commit(view: View) {
        val on = view.context.getSharedPreferences(ThemeRes.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getBoolean(SettingsActivity.KEY_HAPTICS, true)
        if (!on) return
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }
}
