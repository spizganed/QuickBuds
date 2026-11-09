package com.spizganed.quickbuds.tile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.Feature
import com.spizganed.quickbuds.ui.Palette
import com.spizganed.quickbuds.ui.ThemeRes
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * Android routes a long-press on a Quick Settings tile through ACTION_QS_TILE_PREFERENCES.
 * Always show this compact themed control panel; never launch MainActivity from the background.
 */
class AncTileLongPressActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var palette: Palette
    private var useGlassBlur: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        palette = ThemeRes.palette(this)
        configureWindow()
        buildPanel()
    }

    private fun configureWindow() {
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        useGlassBlur = ThemeRes.glass(this) && ThemeRes.windowBlur(this)
        window.attributes = window.attributes.apply {
            width = dp(340)
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
            dimAmount = 0f
            flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = if (useGlassBlur) dp(40) else 0
                flags = if (useGlassBlur) flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                    else flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            }
        }
    }

    private fun buildPanel() {
        val mode = WidgetStateStore.read(this).ancMode
        val isAnc = mode != AncModes.OFF && mode != AncModes.TRANSPARENCY
        val isTransparency = mode == AncModes.TRANSPARENCY
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(palette.card)
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), palette.outline)
            }
        }

        root.addView(TextView(this).apply {
            text = when {
                isAnc -> "Noise Cancellation"
                isTransparency -> "Transparency"
                else -> "Normal"
            }
            textSize = 19f
            setTextColor(palette.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(-1, dp(32)))

        if (isAnc) {
            root.addView(TextView(this).apply {
                text = "Choose noise cancellation strength"
                textSize = 13f
                setTextColor(palette.textSecondary)
                setPadding(0, dp(2), 0, dp(8))
            })
            addAncChoices(mode)
        }

        // LHDC and Spatial Sound are available in every mode. In Transparency and Normal
        // these are the only controls shown; ANC also shows its four strength choices above.
        if (isAnc) {
            root.addView(TextView(this).apply {
                text = "Audio features"
                textSize = 14f
                setTextColor(palette.textSecondary)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(12), 0, dp(4))
            })
        }
        addFeatureSwitch("LHDC", Feature.HIRES_CODEC)
        addFeatureSwitch("Spatial Sound", Feature.SPATIAL_SOUND)
        addLowLatencySwitch()

        setContentView(root)
    }

    private fun addAncChoices(currentMode: String) {
        val modes = AncModes.of(this)
        val choices = listOf(
            "Smart" to AncModes.SMART,
            "High" to "ANC-Deep",
            "Low" to "ANC-Light",
            "Medium" to "ANC-Medium"
        )
        choices.forEach { (label, value) ->
            val supported = modes.supports(value)
            val selected = currentMode == value
            val row = TextView(this).apply {
                text = label + if (selected) "  ✓" else ""
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(if (supported) palette.text else palette.textSecondary)
                alpha = if (supported) 1f else 0.38f
                background = GradientDrawable().apply {
                    setColor(if (selected) palette.tonal else Color.TRANSPARENT)
                    cornerRadius = dp(16).toFloat()
                    if (selected) setStroke(dp(1), palette.accent)
                }
                isEnabled = supported
                isClickable = supported
                setOnClickListener {
                    val serviceIntent = Intent(this@AncTileLongPressActivity, BudsService::class.java).apply {
                        action = BudsService.ACTION_TILE_SET_ANC
                        putExtra(BudsService.EXTRA_TILE_ANC_MODE, value)
                    }
                    startBudsService(serviceIntent)
                    finish()
                }
            }
            val lp = LinearLayout.LayoutParams(-1, dp(46))
            lp.topMargin = dp(4)
            root.addView(row, lp)
        }
    }

    private fun addLowLatencySwitch() {
        val states = Capabilities.reply(this)
        val gameModeId = Capabilities.gameModeId(this)
        val supported = states == null || states.containsKey(gameModeId)
        val toggle = Switch(this).apply {
            text = "Low Latency"
            textSize = 15f
            setTextColor(palette.text)
            isChecked = WidgetStateStore.read(this@AncTileLongPressActivity).gameMode
            isEnabled = supported
            alpha = if (supported) 1f else 0.45f
        }
        toggle.setOnCheckedChangeListener { _, requestedOn ->
            val intent = Intent(this@AncTileLongPressActivity, BudsService::class.java).apply {
                action = BudsService.ACTION_TILE_SET_GAME_MODE
                putExtra(BudsService.EXTRA_TILE_GAME_MODE_ON, requestedOn)
            }
            startBudsService(intent)
        }
        val row = LinearLayout.LayoutParams(-1, dp(52))
        row.topMargin = dp(2)
        root.addView(toggle, row)
    }

    private fun addFeatureSwitch(label: String, feature: Int) {
        val savedStates = Capabilities.reply(this)
        val supported = savedStates == null || savedStates.containsKey(feature)
        val enabled = (savedStates?.get(feature) ?: 0) == 1
        val toggle = Switch(this).apply {
            text = label
            textSize = 15f
            setTextColor(palette.text)
            isChecked = enabled
            isEnabled = supported
            alpha = if (supported) 1f else 0.45f
            tag = feature
        }
        attachFeatureListener(toggle, feature)
        val row = LinearLayout.LayoutParams(-1, dp(52))
        row.topMargin = dp(2)
        root.addView(toggle, row)
    }

    private fun attachFeatureListener(toggle: Switch, feature: Int) {
        toggle.setOnCheckedChangeListener { _, requestedOn ->
            // Keep LHDC and Spatial Sound independent so the user can test both together.
            val intent = Intent(this@AncTileLongPressActivity, BudsService::class.java).apply {
                action = BudsService.ACTION_TILE_SET_FEATURE
                putExtra(BudsService.EXTRA_TILE_FEATURE, feature)
                putExtra(BudsService.EXTRA_TILE_FEATURE_ON, requestedOn)
            }
            startBudsService(intent)
        }
    }

    private fun startBudsService(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (_: Exception) {
            // Keep the floating panel responsive even if Android temporarily blocks the service.
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
