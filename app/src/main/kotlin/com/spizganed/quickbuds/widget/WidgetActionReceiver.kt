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
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.ui.Haptics
import com.spizganed.quickbuds.ui.MainActivity

class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == null || intent.action == WidgetActions.ACTION_NOOP) return
        // Every finger tap ticks at once, including a double tap's first one, which then waits.
        Haptics.tick(context)
        handle(context, intent)
    }

    private fun handle(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BudsWidget", "[RX] Action: $action")

        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val page = intent.getStringExtra(WidgetActions.EXTRA_PAGE)
        if (page != null) return doubleTap(context, intent, widgetId, page)

        val state = WidgetStateStore.read(context)
        var shortAction: String? = null
        var sendAncMode: String = state.ancMode
        val feature = intent.getIntExtra(WidgetActions.EXTRA_FEATURE, -1)
        // The tap flips what the buds last reported; their 0x810D answer repaints the button.
        val featureOn = !WidgetSettings.featureOn(context, feature)

        // A pick from a widget's mode list closes that list; the controls page stays, as after a T or A tap.
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetSettings.setListOpenedAt(context, widgetId, 0L)

        when (action) {
            WidgetActions.ACTION_OPEN_APP -> return
            WidgetActions.ACTION_QUICK -> {
                val target = intent.getStringExtra(WidgetActions.EXTRA_ANC_TARGET) ?: return
                if (target == "anc") return openList(context, widgetId)
                // The lit mode's button turns noise control off; the send is a list pick's.
                val pick = if (WidgetSettings.modeOf(state.ancMode).key == target) "off" else target
                return handle(context, Intent(context, WidgetActionReceiver::class.java)
                    .setAction(WidgetActions.ACTION_ANC_SELECT)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(WidgetActions.EXTRA_ANC_TARGET, pick))
            }
            WidgetActions.ACTION_ANC_SELECT -> {
                val target = intent.getStringExtra(WidgetActions.EXTRA_ANC_TARGET) ?: return
                when (target) {
                    "off"   -> { state.ancMode = "Off";          shortAction = "OFF" }
                    "trans" -> { state.ancMode = "Transparency"; shortAction = "TRANS" }
                    "low"   -> { state.ancMode = "ANC-Light";    shortAction = "ANC_CYCLE" }
                    "med"   -> { state.ancMode = "ANC-Medium";   shortAction = "ANC_CYCLE" }
                    "high"  -> { state.ancMode = "ANC-Deep";     shortAction = "ANC_CYCLE" }
                    "smart" -> { state.ancMode = AncModes.SMART; shortAction = "ANC_CYCLE" }
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
            WidgetActions.ACTION_FEATURE_TOGGLE -> {
                if (feature < 0) return
                shortAction = "FEATURE"
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
            putExtra(BudsService.EXTRA_WIDGET_FEATURE, feature)
            putExtra(BudsService.EXTRA_WIDGET_FEATURE_ON, featureOn)
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
                putExtra(BudsService.EXTRA_WIDGET_FEATURE, feature)
                putExtra(BudsService.EXTRA_WIDGET_FEATURE_ON, featureOn)
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
     * A second tap within [WidgetSettings.DOUBLE_TAP_MS] switches
     * to [page]; otherwise the tap runs as a plain one when the wait ends. Main thread only, like
     * [openList]; a process death during the wait drops the tap.
     */
    private fun doubleTap(context: Context, intent: Intent, id: Int, page: String) {
        pending.remove(id)?.let {
            handler.removeCallbacks(it)
            WidgetSettings.setPage(context, id, QuickBudsWidget.Kind.valueOf(page))
            AncWidgetProvider.refreshAll(context)
            return
        }
        val app = context.applicationContext
        val single = Intent(intent).apply { removeExtra(WidgetActions.EXTRA_PAGE) }
        val run = Runnable { pending.remove(id); handle(app, single) }
        pending[id] = run
        handler.postDelayed(run, WidgetSettings.DOUBLE_TAP_MS)
    }

    private companion object {
        val handler = Handler(Looper.getMainLooper())
        /** The tap waiting for a second one, per widget id. */
        val pending = HashMap<Int, Runnable>()
    }

    /**
     * Shows widget [id]'s level picker. It stays open until a pick (the lit level turns ANC off).
     */
    private fun openList(context: Context, id: Int) {
        WidgetSettings.setListOpenedAt(context, id, System.currentTimeMillis())
        AncWidgetProvider.refreshAll(context)
    }
}
