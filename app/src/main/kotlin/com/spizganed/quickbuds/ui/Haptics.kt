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
 * A CONFIRM tick when a setting is committed (finger lift, toggle, selection),
 * VIRTUAL_KEY below API 30. Gated on Settings › Haptic feedback (default on).
 */
object Haptics {
    private fun on(c: Context) =
        c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(SettingsActivity.KEY_HAPTICS, true)

    private var last = 0L

    /** One tick per tap: a second call within 80 ms (the view's own and the app-wide one) is dropped. */
    private fun fresh(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - last < 80) return false
        last = now
        return true
    }

    fun commit(view: View) {
        if (!on(view.context) || !fresh()) return
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

    /** A slider crossing a step while dragged: the lightest tick, so a run of them does not buzz. */
    fun step(view: View) {
        if (!on(view.context)) return
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
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

    /**
     * Every tap on a clickable view in the app gets the tick, without each button asking for it: the activity's window callback
     * watches a finger go down and up on the same spot and, when a clickable view is under it, ticks (views that tick themselves are
     * deduped in [commit]).
     */
    fun install(app: android.app.Application) = app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
            watch(a, a.window)
        }
        override fun onActivityStarted(a: android.app.Activity) {}
        override fun onActivityResumed(a: android.app.Activity) {}
        override fun onActivityPaused(a: android.app.Activity) {}
        override fun onActivityStopped(a: android.app.Activity) {}
        override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
        override fun onActivityDestroyed(a: android.app.Activity) {}
    })

    /** Ticks on taps in [window] (an activity's, or a dialog's). */
    fun watch(a: android.app.Activity, window: android.view.Window) {
        val inner = window.callback
        if (inner is TapCallback) return
        window.callback = TapCallback(a, window, inner)
    }

    private class TapCallback(private val a: android.app.Activity, private val window: android.view.Window, private val inner: android.view.Window.Callback) :
        android.view.Window.Callback by inner {
        private var downX = 0f; private var downY = 0f

        override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { downX = e.rawX; downY = e.rawY; ThemeRes.lastTapX = downX; ThemeRes.lastTapY = downY }
            val handled = inner.dispatchTouchEvent(e)
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP && handled) {
                val slop = android.view.ViewConfiguration.get(a).scaledTouchSlop
                if (Math.abs(e.rawX - downX) < slop && Math.abs(e.rawY - downY) < slop) {
                    val hit = clickableAt(window.decorView, e.rawX.toInt(), e.rawY.toInt())
                    if (hit != null) commit(hit)
                }
            }
            return handled
        }

        /** The deepest enabled, visible clickable view that holds the point. */
        private fun clickableAt(v: View, x: Int, y: Int): View? {
            if (v.visibility != View.VISIBLE) return null
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            if (x < loc[0] || y < loc[1] || x >= loc[0] + v.width || y >= loc[1] + v.height) return null
            if (v is android.view.ViewGroup) for (i in v.childCount - 1 downTo 0) clickableAt(v.getChildAt(i), x, y)?.let { return it }
            return if (v.isClickable && v.isEnabled) v else null
        }
    }
}
