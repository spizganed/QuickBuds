package com.spizganed.quickbuds.bluetooth

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.spizganed.quickbuds.protocol.Feature

/**
 * Game equalizer (PROTOCOL.md §18): while the buds' switch [Feature.GAME_EQ] is on, tells them when a game
 * comes to the foreground and when it leaves. Needs "Usage access"; without it, nothing is sent.
 * A game is an app Android marks as one (`ApplicationInfo.CATEGORY_GAME`).
 */
class GameEqWatcher(
    private val context: Context,
    private val manager: () -> BudsConnectionManager?,
    private val asleep: () -> Boolean = { false },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var inGame: Boolean? = null   // what the buds were last told
    private var foreground: String? = null

    private val tick = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, POLL_MS)
        }
    }

    /** Call when the link or the switch changes. Starts or stops the polling. */
    fun update() {
        val m = manager()
        val active = GameEqPoll.active(
            connected = m != null && m.isConnected(),
            gameEqOn = m?.featureStates?.get(Feature.GAME_EQ) == 1,
            usageAccess = hasUsageAccess(context),
            asleep = asleep(),
        )
        handler.removeCallbacks(tick)
        if (active) {
            handler.post(tick)
        } else {
            // Leave the buds out of game mode when the feature stops (the switch off, the permission gone).
            if (inGame == true && m != null && m.isConnected()) m.setGameEqState(false)
            inGame = null
            foreground = null
        }
    }

    fun stop() = handler.removeCallbacks(tick)

    @Suppress("DEPRECATION")   // MOVE_TO_* is the pair that exists on API 26; ACTIVITY_RESUMED needs 29
    private fun poll() {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - if (inGame == null) FIRST_WINDOW_MS else WINDOW_MS, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> foreground = e.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (foreground == e.packageName) foreground = null
            }
        }
        val game = foreground?.let(::isGame) ?: false
        if (game != inGame) {
            inGame = game
            manager()?.setGameEqState(game)
        }
    }

    private fun isGame(pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_GAME
    } catch (_: Exception) { false }

    companion object {
        private const val POLL_MS = 2000L
        private const val WINDOW_MS = 60_000L
        private const val FIRST_WINDOW_MS = 600_000L

        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")   // unsafeCheckOpNoThrow needs API 29
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        }
    }
}
