package com.spizganed.quickbuds.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.WidgetActions

class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == WidgetActions.ACTION_NOOP) return

        Log.d("BudsWidget", "[RX] Action: $action")

        val state = WidgetStateStore.read(context)
        var shortAction: String? = null
        var sendAncMode: String = state.ancMode
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

        // A pick from a widget's mode list closes that list.
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetSettings.setListOpenedAt(context, widgetId, 0L)

        when (action) {
            WidgetActions.ACTION_LIST_CLOSE -> {
                AncWidgetProvider.refreshAll(context)
                return
            }
            WidgetActions.ACTION_MODE_TAP -> {
                if (WidgetSettings.tapOpensList(context)) {
                    openList(context, widgetId)
                    return
                }
                // Next mode: the same send as a list pick, so the service path is unchanged.
                val next = WidgetSettings.next(context, WidgetSettings.modeOf(state.ancMode))
                return onReceive(context, Intent(context, WidgetActionReceiver::class.java)
                    .setAction(WidgetActions.ACTION_ANC_SELECT)
                    .putExtra(WidgetActions.EXTRA_ANC_TARGET, next.key))
            }
            WidgetActions.ACTION_ANC_SELECT -> {
                val target = intent.getStringExtra(WidgetActions.EXTRA_ANC_TARGET) ?: return
                when (target) {
                    "off"   -> { state.ancMode = "Off";          shortAction = "OFF" }
                    "trans" -> { state.ancMode = "Transparency"; shortAction = "TRANS" }
                    "low"   -> { state.ancMode = "ANC-Light";    shortAction = "ANC_CYCLE" }
                    "med"   -> { state.ancMode = "ANC-Medium";   shortAction = "ANC_CYCLE" }
                    "high"  -> { state.ancMode = "ANC-Deep";     shortAction = "ANC_CYCLE" }
                    // Routed through ANC_CYCLE like the three levels above: the
                    // service already resolves an ANC mode NAME to a command there,
                    // so Adaptive needs no new action string and the widget and the
                    // service cannot drift apart on how it is sent.
                    "adapt" -> { state.ancMode = "Adaptive";     shortAction = "ANC_CYCLE" }
                }
                sendAncMode = state.ancMode
            }
            WidgetActions.ACTION_GAME_TOGGLE -> {
                state.gameMode = !state.gameMode
                shortAction = "GAME_TOGGLE"
            }
            WidgetActions.ACTION_ANC_CYCLE -> {
                state.ancMode = when (state.ancMode) {
                    "Off" -> "ANC-Light"
                    "ANC-Light" -> "ANC-Medium"
                    "ANC-Medium" -> "ANC-Deep"
                    "ANC-Deep" -> "ANC-Light"
                    "Transparency" -> "ANC-Light"
                    else -> "ANC-Light"
                }
                shortAction = "ANC_CYCLE"
                sendAncMode = state.ancMode
            }
            WidgetActions.ACTION_TRANS -> {
                state.ancMode = "Transparency"; shortAction = "TRANS"
            }
            WidgetActions.ACTION_OFF -> {
                state.ancMode = "Off"; shortAction = "OFF"
            }
            else -> {
                Log.d("BudsWidget", "[RX] Unknown action, ignoring")
                return
            }
        }

        WidgetStateStore.write(context, state)
        AncWidgetProvider.refreshAll(context)

        val finalShort = shortAction ?: return

        val localIntent = Intent(BudsService.ACTION_WIDGET_COMMAND).apply {
            putExtra(BudsService.EXTRA_WIDGET_ACTION, finalShort)
            putExtra(BudsService.EXTRA_WIDGET_ANC_MODE, sendAncMode)
            putExtra(BudsService.EXTRA_WIDGET_GAME_MODE, state.gameMode)
        }
        context.sendBroadcast(localIntent)

        // Background service off: only a running service (the app is open) takes the command.
        if (!BudsService.backgroundAllowed(context)) return

        try {
            val serviceIntent = Intent(context, BudsService::class.java).apply {
                this.action = BudsService.ACTION_WIDGET_COMMAND
                putExtra(BudsService.EXTRA_WIDGET_ACTION, finalShort)
                putExtra(BudsService.EXTRA_WIDGET_ANC_MODE, sendAncMode)
                putExtra(BudsService.EXTRA_WIDGET_GAME_MODE, state.gameMode)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("BudsWidget", "[RX] startService failed", e)
        }
    }

    /**
     * Shows widget [id]'s mode list and closes it after [WidgetSettings.LIST_TIMEOUT_MS] with no
     * pick. goAsync keeps the process alive for the wait (well inside the broadcast timeout); the
     * stamp check makes a pick or a newer open win over this close.
     */
    private fun openList(context: Context, id: Int) {
        val stamp = System.currentTimeMillis()
        WidgetSettings.setListOpenedAt(context, id, stamp)
        AncWidgetProvider.refreshAll(context)
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (WidgetSettings.listOpenedAt(context, id) == stamp) {
                    WidgetSettings.setListOpenedAt(context, id, 0L)
                    AncWidgetProvider.refreshAll(context)
                }
            } finally {
                pending.finish()
            }
        }, WidgetSettings.LIST_TIMEOUT_MS)
    }
}
