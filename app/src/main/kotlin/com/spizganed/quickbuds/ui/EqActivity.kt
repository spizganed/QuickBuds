package com.spizganed.quickbuds.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.protocol.EqCodec
import com.spizganed.quickbuds.protocol.OpoProtocol

/**
 * Equalizer — HeyMelody's layout: built-in presets, BassWave, custom presets with a 6-band editor.
 * Every command is `[CAPTURE]` 2026-09-23 (PROTOCOL.md §9).
 *
 * The screen shows the BUDS' state, never its own guess: it binds the service, reads the EQ on
 * open, and every write is followed by a re-read ([BudsConnectionManager.sendThenRead]) that
 * repaints through [onEqState]. A custom preset's editor is shown while that preset is the
 * selected one, because `0x0418` selects and saves in the same frame — editing a preset that is not
 * selected is not something the protocol can do.
 */
class EqActivity : Activity(), BudsConnectionManager.Listener {

    private var manager: BudsConnectionManager? = null
    private var bound = false

    private lateinit var notConnected: TextView
    private lateinit var builtInCard: LinearLayout
    private lateinit var bassSwitch: Switch
    private lateinit var bassSliderRow: LinearLayout
    private lateinit var bassSlider: SeekBar
    private lateinit var bassValue: TextView
    private lateinit var customHeader: TextView
    private lateinit var customCard: LinearLayout
    private lateinit var editorCard: LinearLayout

    /** True while the user holds a slider, so a re-read cannot rebuild it under their finger. */
    private var dragging = false
    private var syncing = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            manager = (service as BudsService.LocalBinder).getService().manager
            manager?.addListener(this@EqActivity)
            manager?.refreshEq()
            manager?.requestFullStatus()   // BassWave on/off lives in the 0x810D reply
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            manager = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate — see ThemeRes.select.
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)

        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeRes.color(this@EqActivity, R.attr.appColorBg))
            setPadding(dp(16f), dp(44f), dp(16f), dp(24f))
        }

        root.addView(TextView(this).apply {
            setText(R.string.eq_title)
            setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorTextPrimary))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })

        notConnected = sectionLabel(R.string.eq_not_connected)
        root.addView(notConnected)

        root.addView(sectionLabel(R.string.eq_recommended))
        builtInCard = card()
        root.addView(builtInCard)

        // BassWave: a switch row, and the -5..+5 level slider under it while it is on.
        val bassCard = card()
        bassSwitch = SettingRowFactory.buildSwitch(this, false)
        bassSwitch.setOnCheckedChangeListener { _, on ->
            if (!syncing) manager?.setFeatures(OpoProtocol.FEATURE_BASSWAVE to on)
            bassSliderRow.visibility = if (on) View.VISIBLE else View.GONE
        }
        bassCard.addView(
            SettingRowFactory.build(
                this, R.drawable.ic_equalizer, R.string.eq_basswave, R.string.eq_basswave_sub, bassSwitch
            ) { bassSwitch.performClick() }
        )
        bassValue = valueText()
        bassSlider = slider(10)
        bassSlider.setOnSeekBarChangeListener(seekListener(
            onChange = { v -> bassValue.text = signed(v - 5) },
            onRelease = { v -> manager?.setBassWaveLevel(v - 5) }
        ))
        bassSliderRow = sliderRow(getString(R.string.eq_level), bassSlider, bassValue)
        bassSliderRow.visibility = View.GONE
        bassCard.addView(bassSliderRow)
        root.addView(bassCard.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(14f)
        })

        customHeader = sectionLabel(R.string.eq_custom)
        root.addView(customHeader)
        customCard = card()
        root.addView(customCard)

        editorCard = card().apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14f) }
        }
        root.addView(editorCard)

        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, BudsService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        manager?.removeListener(this)
        if (bound) unbindService(connection)
        bound = false
        manager = null
    }

    // ---------------------------------------------------------------- rendering

    private fun render() {
        if (dragging) return
        val m = manager
        val connected = m?.isConnected() == true
        notConnected.visibility = if (connected) View.GONE else View.VISIBLE
        val current = m?.eqCurrent

        builtInCard.removeAllViews()
        listOf(
            EqCodec.BALANCED to R.string.eq_balanced,
            EqCodec.CLEAR_VOCALS to R.string.eq_clear_vocals,
            EqCodec.BASS to R.string.eq_bass
        ).forEachIndexed { i, (id, label) ->
            if (i > 0) builtInCard.addView(SettingRowFactory.buildDivider(this))
            builtInCard.addView(choiceRow(getString(label), current == id) { m?.selectBuiltInEq(id) })
        }

        syncing = true
        val bassOn = m?.featureStates?.get(OpoProtocol.FEATURE_BASSWAVE) == 1
        bassSwitch.isChecked = bassOn
        bassSliderRow.visibility = if (bassOn) View.VISIBLE else View.GONE
        m?.bassWaveLevel?.let {
            bassSlider.progress = it + 5
            bassValue.text = signed(it)
        }
        syncing = false

        val custom = m?.eqCustom.orEmpty()
        customHeader.visibility = if (custom.isEmpty()) View.GONE else View.VISIBLE
        customCard.visibility = customHeader.visibility
        customCard.removeAllViews()
        custom.forEachIndexed { i, p ->
            if (i > 0) customCard.addView(SettingRowFactory.buildDivider(this))
            // Selecting a custom preset IS a save of it as it stands — same frame HeyMelody sends.
            customCard.addView(choiceRow(p.name, current == p.id) { m?.saveCustomEq(p) })
        }

        renderEditor(custom.firstOrNull { it.id == current })
    }

    private fun renderEditor(p: EqCodec.Preset?) {
        editorCard.removeAllViews()
        editorCard.visibility = if (p == null) View.GONE else View.VISIBLE
        if (p == null) return
        val dp = { v: Float -> ThemeRes.dp(this, v) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(4f))
        }
        header.addView(TextView(this).apply {
            text = p.name
            setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorTextPrimary))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            setText(R.string.eq_rename)
            setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorAccent))
            textSize = 14f
            setPadding(dp(8f), dp(8f), 0, dp(8f))
            setOnClickListener { rename(p) }
        })
        editorCard.addView(header)

        p.freqs.forEachIndexed { band, freq ->
            val value = valueText().apply { text = signed(p.gains[band]) }
            val range = EqCodec.GAIN_MAX - EqCodec.GAIN_MIN
            val bar = slider(range)
            bar.progress = p.gains[band] - EqCodec.GAIN_MIN
            bar.setOnSeekBarChangeListener(seekListener(
                onChange = { v -> value.text = signed(v + EqCodec.GAIN_MIN) },
                onRelease = { v -> manager?.saveCustomEq(p.withGain(band, v + EqCodec.GAIN_MIN)) }
            ))
            editorCard.addView(sliderRow(freqLabel(freq), bar, value))
        }
    }

    private fun rename(p: EqCodec.Preset) {
        // ponytail: 20-char cap is ours, not a measured firmware limit; the name length is one byte.
        val input = EditText(this).apply {
            setText(p.name)
            setSelection(p.name.length)
            filters = arrayOf(InputFilter.LengthFilter(20))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.eq_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != p.name) manager?.saveCustomEq(p.withName(name))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- listener

    override fun onEqState() = render()
    override fun onFeatureStates(states: Map<Int, Int>) = render()
    override fun onConnected(connected: Boolean) {
        if (connected) manager?.refreshEq()
        render()
    }
    override fun onStatus(msg: String) {}
    override fun onPacketReceived(bytes: ByteArray) {}
    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {}
    override fun onBudState(state: String) {}

    // ---------------------------------------------------------------- small builders

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun freqLabel(hz: Int) = if (hz >= 1000) "${hz / 1000}k" else "$hz"

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.app_card_bg)
        val p = ThemeRes.dp(this@EqActivity, 4f)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun sectionLabel(res: Int) = TextView(this).apply {
        setText(res)
        setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorTextSecondary))
        textSize = 13f
        val dp = { v: Float -> ThemeRes.dp(this@EqActivity, v) }
        setPadding(dp(4f), dp(22f), 0, dp(8f))
    }

    /** A selectable row: accent label and a check when selected — the BottomSheetDialog look. */
    private fun choiceRow(label: String, selected: Boolean, onClick: () -> Unit): View {
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val accent = ThemeRes.color(this, R.attr.appColorAccent)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f))
            setPadding(dp(14f), 0, dp(14f), 0)
            setOnClickListener { onClick() }
            addView(TextView(this@EqActivity).apply {
                text = label
                setTextColor(if (selected) accent else ThemeRes.color(this@EqActivity, R.attr.appColorTextPrimary))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (selected) addView(ImageView(this@EqActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f))
                setImageDrawable(ThemeRes.tint(this@EqActivity, R.drawable.ic_check, accent))
            })
        }
    }

    private fun valueText() = TextView(this).apply {
        setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorTextPrimary))
        textSize = 14f
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(ThemeRes.dp(this@EqActivity, 36f), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun slider(max: Int) = SeekBar(this).apply {
        this.max = max
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun sliderRow(label: String, bar: SeekBar, value: TextView) = LinearLayout(this).apply {
        val dp = { v: Float -> ThemeRes.dp(this@EqActivity, v) }
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        addView(TextView(this@EqActivity).apply {
            text = label
            setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorTextSecondary))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(dp(44f), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(bar)
        addView(value)
    }

    /** Sends on RELEASE only — one write per gesture, not one per step as HeyMelody does. */
    private fun seekListener(onChange: (Int) -> Unit, onRelease: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) = onChange(v)
            override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                onRelease(s.progress)
            }
        }
}
