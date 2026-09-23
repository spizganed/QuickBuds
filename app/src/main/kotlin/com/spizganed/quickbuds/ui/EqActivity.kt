package com.spizganed.quickbuds.ui

import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * repaints through [onEqState]. Tapping a custom preset selects it AND opens its editor sheet
 * ([showEditor]), because `0x0418` selects and saves in the same frame — a preset that is not
 * selected cannot be edited. Create / delete are the same command, action `01` / `03`.
 */
class EqActivity : Activity(), BudsConnectionManager.Listener {

    private var manager: BudsConnectionManager? = null
    private var bound = false

    private lateinit var notConnected: TextView
    private lateinit var builtInCard: LinearLayout
    private lateinit var bassSwitch: Switch
    private lateinit var bassSlider: LevelSliderView
    private lateinit var customHeader: TextView
    private lateinit var customCard: LinearLayout

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
            bassSlider.visibility = if (on) View.VISIBLE else View.GONE
        }
        bassCard.addView(
            SettingRowFactory.build(
                this, R.drawable.ic_equalizer, R.string.eq_basswave, R.string.eq_basswave_sub, bassSwitch
            ) { bassSwitch.performClick() }
        )
        bassSlider = LevelSliderView(this, -5, 5, getString(R.string.eq_weak), getString(R.string.eq_strong)).apply {
            onRelease = { manager?.setBassWaveLevel(it) }
            visibility = View.GONE
            val pad = ThemeRes.dp(this@EqActivity, 8f)
            setPadding(pad, 0, pad, pad)
        }
        bassCard.addView(bassSlider)
        root.addView(bassCard.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(14f)
        })

        customHeader = sectionLabel(R.string.eq_custom)
        root.addView(customHeader)
        customCard = card()
        root.addView(customCard)

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
        // Never rebuild under a finger: a re-read landing mid-drag would yank the knob back.
        if (bassSlider.dragging) return
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
        bassSlider.visibility = if (bassOn) View.VISIBLE else View.GONE
        m?.bassWaveLevel?.let { bassSlider.value = it }
        syncing = false

        val custom = m?.eqCustom.orEmpty()
        customCard.removeAllViews()
        custom.forEachIndexed { i, p ->
            if (i > 0) customCard.addView(SettingRowFactory.buildDivider(this))
            // Selecting a custom preset IS a save of it as it stands — same frame HeyMelody sends —
            // so opening its editor selects it too, exactly as in HeyMelody.
            customCard.addView(choiceRow(p.name, current == p.id) {
                m?.saveCustomEq(p)
                showEditor(p)
            })
        }
        if (connected && custom.size < EqCodec.MAX_CUSTOM) {
            if (custom.isNotEmpty()) customCard.addView(SettingRowFactory.buildDivider(this))
            customCard.addView(actionRow(getString(R.string.eq_add)) {
                val used = custom.map { it.name }.toSet()
                val name = (1..9).map { "Custom$it" }.first { it !in used }
                m?.createCustomEq(name)
            })
        }
        customHeader.visibility = if (customCard.childCount == 0) View.GONE else View.VISIBLE
        customCard.visibility = customHeader.visibility
    }

    /**
     * The band editor, as a bottom sheet (HeyMelody's layout: Close / name / Rename, the curve, then
     * Delete). Kept out of the page so the curve gets the full width and the list stays short.
     * The sheet owns its working copy [p]; each release saves the whole preset, like HeyMelody.
     */
    private fun showEditor(start: EqCodec.Preset) {
        var p = start
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val accent = ThemeRes.color(this, R.attr.appColorAccent)
        val d = Dialog(this).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.dialog_sheet_bg)
            setPadding(dp(8f), dp(14f), dp(8f), dp(18f))
        }

        val nameView = TextView(this).apply {
            text = p.name
            setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorTextPrimary))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun link(res: Int, onClick: () -> Unit) = TextView(this).apply {
            setText(res)
            setTextColor(accent)
            textSize = 15f
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            setOnClickListener { onClick() }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(link(R.string.eq_close) { d.dismiss() })
            addView(nameView)
            addView(link(R.string.eq_rename) {
                rename(p) { renamed -> p = renamed; nameView.text = renamed.name }
            })
        })

        root.addView(EqCurveView(this).apply {
            freqs = p.freqs
            gains = p.gains.toIntArray()
            onRelease = { band, gain ->
                p = p.withGain(band, gain)
                manager?.saveCustomEq(p)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        })

        root.addView(TextView(this).apply {
            setText(R.string.eq_delete)
            setTextColor(accent)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(14f), 0, dp(4f))
            setOnClickListener {
                val sheet = BottomSheetDialog(this@EqActivity)
                sheet.title(getString(R.string.eq_delete_confirm, p.name))
                    .confirm(getString(R.string.eq_delete)) {
                        sheet.close()
                        manager?.deleteCustomEq(p)
                        d.dismiss()
                    }
                    .show()
            }
        })

        d.setContentView(root)
        d.setCanceledOnTouchOutside(true)
        // Same window as BottomSheetDialog, so the two sheets look like one family.
        d.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(0x00000000))
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.45f)
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
        }
        d.show()
    }

    private fun rename(p: EqCodec.Preset, onDone: (EqCodec.Preset) -> Unit) {
        // ponytail: 20-char cap is ours, not a measured firmware limit; the name length is one byte.
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(R.string.eq_rename))
            .input(p.name, 20)
            .confirm(getString(R.string.eq_save)) {
                val name = sheet.inputValue()
                sheet.close()
                if (name.isNotEmpty() && name != p.name) {
                    val renamed = p.withName(name)
                    manager?.saveCustomEq(renamed)
                    onDone(renamed)
                }
            }
            .show()
    }

    /** A plain accent-coloured action row inside a card (e.g. "Add preset"). */
    private fun actionRow(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorAccent))
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        val dp = { v: Float -> ThemeRes.dp(this@EqActivity, v) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f))
        setPadding(dp(14f), 0, dp(14f), 0)
        setOnClickListener { onClick() }
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
}
