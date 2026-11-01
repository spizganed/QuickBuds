package com.spizganed.quickbuds.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.WidgetActions
import com.spizganed.quickbuds.protocol.Feature
import com.spizganed.quickbuds.ui.MainActivity
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * A QuickBuds Quick Settings tile. While the shade shows it, it repaints on every change of the widget state
 * or the buds' switches, so a change from a gesture, the app or the widget shows at once.
 */
abstract class LiveTileService : TileService() {
    abstract fun paint(tile: Tile)

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> repaint() }

    override fun onStartListening() {
        WidgetStateStore.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        settings(this).registerOnSharedPreferenceChangeListener(listener)
        repaint()
    }

    override fun onStopListening() {
        WidgetStateStore.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        settings(this).unregisterOnSharedPreferenceChangeListener(listener)
    }

    protected fun repaint() {
        val tile = qsTile ?: return
        paint(tile)
        tile.updateTile()
    }

    protected fun connected() = WidgetStateStore.read(this).connected

    protected fun send(intent: Intent) = sendBroadcast(intent.setClass(this, WidgetActionReceiver::class.java))

    companion object {
        private fun settings(c: Context) = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

        /** Asks the system to repaint the tiles that are not listening now. */
        fun refresh(c: Context) {
            for (cls in listOf(NoiseTileService::class.java, LowLatencyTileService::class.java, WindTileService::class.java)) {
                runCatching { requestListeningState(c, ComponentName(c, cls)) }
            }
        }
    }
}

/**
 * Noise control: a tap moves to the next mode of the Noise cycle setting ([WidgetSettings.cycleModes]).
 * Lit in every mode except Off. The second line names the mode.
 */
class NoiseTileService : LiveTileService() {

    override fun paint(tile: Tile) {
        val current = WidgetSettings.modeOf(WidgetStateStore.read(this).ancMode)
        val usable = connected() && WidgetSettings.available(this, "cycle")
        tile.label = getString(R.string.anc_section)
        tile.icon = Icon.createWithResource(this, if (usable) current.icon else R.drawable.ic_mode_anc_medium)
        tile.state = when {
            !usable -> Tile.STATE_UNAVAILABLE
            current.key != "off" -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (usable) getString(current.name) else null
    }

    override fun onClick() {
        if (!connected() || !WidgetSettings.available(this, "cycle")) return
        val current = WidgetSettings.modeOf(WidgetStateStore.read(this).ancMode)
        send(Intent(WidgetActions.ACTION_ANC_SELECT)
            .putExtra(WidgetActions.EXTRA_ANC_TARGET, WidgetSettings.cycleNext(this, current))
            .putExtra(WidgetActions.EXTRA_CYCLE, true))
    }
}

/** An on/off switch of the buds. Unavailable while the buds are not connected or lack the switch. */
abstract class SwitchTileService(private val label: Int, private val icon: Int) : LiveTileService() {
    abstract fun available(): Boolean
    abstract fun isOn(): Boolean
    abstract fun tap(): Intent

    override fun paint(tile: Tile) {
        tile.label = getString(label)
        tile.icon = Icon.createWithResource(this, icon)
        tile.state = when {
            !connected() || !available() -> Tile.STATE_UNAVAILABLE
            isOn() -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (tile.state == Tile.STATE_UNAVAILABLE) null
                else getString(if (tile.state == Tile.STATE_ACTIVE) R.string.tile_on else R.string.tile_off)
        }
    }

    override fun onClick() {
        if (connected() && available()) send(tap())
    }
}

/** Low latency (game mode), as the widget's "ll" button. */
class LowLatencyTileService : SwitchTileService(R.string.row_game_title, R.drawable.ic_bolt) {
    override fun available() = WidgetSettings.available(this, "ll")
    override fun isOn() = WidgetStateStore.read(this).gameMode
    override fun tap() = Intent(WidgetActions.ACTION_GAME_TOGGLE)
}

/** Wind noise reduction (`0x1A`), as the widget's "wind" button. */
class WindTileService : SwitchTileService(R.string.row_wind_noise_title, R.drawable.ic_anc) {
    override fun available() = WidgetSettings.available(this, "wind")
    override fun isOn() = WidgetSettings.featureOn(this, Feature.WIND_NOISE)
    override fun tap() = Intent(WidgetActions.ACTION_FEATURE_TOGGLE).putExtra(WidgetActions.EXTRA_FEATURE, Feature.WIND_NOISE)
}

/** Opens QuickBuds. */
class OpenAppTileService : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.widget_open_app)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_buds)
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
