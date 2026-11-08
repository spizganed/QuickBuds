package com.spizganed.quickbuds.tile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.widget.WidgetStateStore

/** Three-state Quick Settings cycle: ANC -> Normal -> Transparency -> ANC. */
class AncQuickSettingsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileFromStoredState()
    }

    override fun onClick() {
        super.onClick()
        val current = WidgetStateStore.read(this).ancMode
        val modes = AncModes.of(this)
        val target = when {
            isAnc(current) -> AncModes.OFF
            current == AncModes.OFF -> if (modes.supports(AncModes.TRANSPARENCY)) AncModes.TRANSPARENCY else firstAncMode(modes)
            current == AncModes.TRANSPARENCY -> firstAncMode(modes)
            else -> firstAncMode(modes)
        }
        requestMode(target)
        updateTileForMode(target)
    }

    private fun firstAncMode(modes: AncModes): String = when {
        modes.supports(AncModes.SMART) -> AncModes.SMART
        modes.supports("ANC-Medium") -> "ANC-Medium"
        modes.levels.isNotEmpty() -> modes.levels.first()
        else -> "ANC-Medium"
    }

    private fun requestMode(mode: String) {
        val serviceIntent = Intent(this, BudsService::class.java).apply {
            action = BudsService.ACTION_TILE_SET_ANC
            putExtra(BudsService.EXTRA_TILE_ANC_MODE, mode)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
        } catch (_: Exception) {
            // System UI must not crash if Android blocks a background service start.
            updateTileFromStoredState()
        }
    }

    private fun updateTileFromStoredState() = updateTileForMode(WidgetStateStore.read(this).ancMode)

    private fun updateTileForMode(mode: String) {
        val tile = qsTile ?: return
        val label: String
        val iconRes: Int
        val description: String
        val state: Int
        when {
            mode == AncModes.OFF -> {
                label = "Normal"
                iconRes = R.drawable.ic_noise_off
                description = "Normal mode. Tap to switch to Transparency."
                state = Tile.STATE_INACTIVE
            }
            mode == AncModes.TRANSPARENCY -> {
                label = "Transparency"
                iconRes = R.drawable.ic_transparency
                description = "Transparency mode. Tap to switch to Noise Cancellation."
                state = Tile.STATE_ACTIVE
            }
            else -> {
                label = "Noise Cancellation"
                iconRes = R.drawable.ic_anc
                description = "Noise Cancellation mode. Tap to switch to Normal."
                state = Tile.STATE_ACTIVE
            }
        }
        tile.label = label
        tile.icon = Icon.createWithResource(this, iconRes)
        tile.state = state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = label
        }
        tile.contentDescription = description
        tile.updateTile()
    }

    private fun isAnc(mode: String): Boolean = mode != AncModes.OFF && mode != AncModes.TRANSPARENCY

    companion object {
        /** Ask System UI to reread the last device-reported mode after an ANC state change. */
        fun requestRefresh(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    TileService.requestListeningState(
                        context,
                        ComponentName(context, AncQuickSettingsTileService::class.java)
                    )
                }
            }
        }
    }
}
