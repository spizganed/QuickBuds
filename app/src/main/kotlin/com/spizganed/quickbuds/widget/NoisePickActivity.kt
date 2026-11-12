package com.spizganed.quickbuds.widget

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.WidgetActions
import com.spizganed.quickbuds.ui.BottomSheetDialog
import com.spizganed.quickbuds.ui.MainActivity
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * A long press on the Noise control tile: a sheet with every noise mode the buds have, in the Noise cycle order.
 * A pick sends the mode, as the widget does. With no buds connected, and for the other tiles, the long press
 * opens QuickBuds.
 */
class NoisePickActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        theme.applyStyle(R.style.ThemeOverlay_App_SheetHost, true)
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // ThemeRes.select paints the system bars in the app colour; over the shade they stay clear.
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        // Android sends the long press of every QuickBuds tile here; only Noise control has a sheet.
        @Suppress("DEPRECATION")
        val tile = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)
        if (tile?.className != NoiseTileService::class.java.name ||
            !WidgetStateStore.read(this).connected || !WidgetSettings.available(this, "cycle")) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val current = WidgetSettings.modeOf(WidgetStateStore.read(this).ancMode)
        val items = WidgetSettings.cycleOffered(this).map { key ->
            val mode = WidgetSettings.MODES.first { it.key == key }
            BottomSheetDialog.Item(getString(mode.name), key == current.key) {
                sendBroadcast(Intent(WidgetActions.ACTION_ANC_SELECT)
                    .setClass(this, WidgetActionReceiver::class.java)
                    .putExtra(WidgetActions.EXTRA_ANC_TARGET, key))
            }
        }
        fitWindowToSheet(items.size)
        BottomSheetDialog(this).title(getString(R.string.anc_section)).items(items).onDismiss { finish() }.show()
    }

    /**
     * The system UI grows a held tile to the window bounds of the screen it opens. A full-screen window gave a gray
     * full-screen box before the sheet. This window takes the place and size of the sheet card, so the tile grows into it.
     * The height follows the card in [BottomSheetDialog]: top and bottom padding, the title, 52 dp for each row.
     */
    private fun fitWindowToSheet(rows: Int) {
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val width = minOf(resources.displayMetrics.widthPixels - dp(48f), dp(420f))
        window.setLayout(width, dp(64f) + rows * dp(52f))
        window.setGravity(Gravity.CENTER)
    }
}
