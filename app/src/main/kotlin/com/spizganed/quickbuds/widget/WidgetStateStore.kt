package com.spizganed.quickbuds.widget

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object WidgetStateStore {

    private const val PREFS = "BudsWidgetState"
    private const val KEY_ANC = "ancMode"
    private const val KEY_GAME = "gameMode"
    private const val KEY_LEFT = "leftBattery"
    private const val KEY_CASE = "caseBattery"
    private const val KEY_RIGHT = "rightBattery"
    private const val KEY_LEFT_IN_BOX = "leftInBox"
    private const val KEY_RIGHT_IN_BOX = "rightInBox"
    private const val KEY_LEFT_STATUS = "leftStatus"
    private const val KEY_RIGHT_STATUS = "rightStatus"
    private const val KEY_LID_CLOSED = "caseLidClosed"
    private const val KEY_LEFT_DOCKED = "leftDocked"
    private const val KEY_RIGHT_DOCKED = "rightDocked"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_CASE_BATTERY_AT = "caseBatteryAt"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    data class State(
        var ancMode: String = "Off",
        var gameMode: Boolean = false,
        var leftBattery: Int = -1,
        var caseBattery: Int = -1,
        var rightBattery: Int = -1,
        var leftInBox: Boolean = false,
        var rightInBox: Boolean = false,

        // Raw wear status codes from getEarBudsStatus (0x0109):
        //   4 = in case, 1/5 = out of case idle, 3/7 = wearing, 0 = disconnected, -1 = unknown
        var leftStatus: Int = -1,
        var rightStatus: Int = -1,

        // true when the case lid is believed closed
        var caseLidClosed: Boolean = false,

        // buds that were docked when the lid closed — they stay hidden while the
        // lid is closed even though the firmware re-reports them as "off" (1/5)
        var leftDocked: Boolean = false,
        var rightDocked: Boolean = false,

        // true while the RFCOMM link to the buds is up
        var connected: Boolean = false,

        // timestamp of the last valid case battery report — a closed lid stops
        // these, so freshness == lid open
        var caseBatteryAt: Long = 0L
    )

    fun addListener(l: (State) -> Unit) { listeners.add(l) }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }

    private fun notifyListeners(state: State) {
        mainHandler.post {
            for (l in listeners) {
                try { l(state) } catch (_: Exception) {}
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): State {
        val p = prefs(context)
        val s = State()
        s.ancMode = p.getString(KEY_ANC, "Off") ?: "Off"
        s.gameMode = p.getBoolean(KEY_GAME, false)
        s.leftBattery = p.getInt(KEY_LEFT, -1)
        s.caseBattery = p.getInt(KEY_CASE, -1)
        s.rightBattery = p.getInt(KEY_RIGHT, -1)
        s.leftInBox = p.getBoolean(KEY_LEFT_IN_BOX, false)
        s.rightInBox = p.getBoolean(KEY_RIGHT_IN_BOX, false)
        s.leftStatus = p.getInt(KEY_LEFT_STATUS, -1)
        s.rightStatus = p.getInt(KEY_RIGHT_STATUS, -1)
        s.caseLidClosed = p.getBoolean(KEY_LID_CLOSED, false)
        s.leftDocked = p.getBoolean(KEY_LEFT_DOCKED, false)
        s.rightDocked = p.getBoolean(KEY_RIGHT_DOCKED, false)
        s.connected = p.getBoolean(KEY_CONNECTED, false)
        s.caseBatteryAt = p.getLong(KEY_CASE_BATTERY_AT, 0L)
        return s
    }

    fun write(context: Context, state: State) {
        prefs(context).edit()
            .putString(KEY_ANC, state.ancMode)
            .putBoolean(KEY_GAME, state.gameMode)
            .putInt(KEY_LEFT, state.leftBattery)
            .putInt(KEY_CASE, state.caseBattery)
            .putInt(KEY_RIGHT, state.rightBattery)
            .putBoolean(KEY_LEFT_IN_BOX, state.leftInBox)
            .putBoolean(KEY_RIGHT_IN_BOX, state.rightInBox)
            .putInt(KEY_LEFT_STATUS, state.leftStatus)
            .putInt(KEY_RIGHT_STATUS, state.rightStatus)
            .putBoolean(KEY_LID_CLOSED, state.caseLidClosed)
            .putBoolean(KEY_LEFT_DOCKED, state.leftDocked)
            .putBoolean(KEY_RIGHT_DOCKED, state.rightDocked)
            .putBoolean(KEY_CONNECTED, state.connected)
            .putLong(KEY_CASE_BATTERY_AT, state.caseBatteryAt)
            .apply()
        notifyListeners(state)
    }
}
