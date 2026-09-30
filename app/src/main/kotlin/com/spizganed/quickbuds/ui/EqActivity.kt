package com.spizganed.quickbuds.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.EqCodec
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol
import org.json.JSONArray
import org.json.JSONObject

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

    companion object {
        /** The model's flag in HeyMelody's list; nothing detected counts as Buds 4, which has them all. */
        private fun flag(context: Context, key: String) =
            ModelCatalog.current(context)?.json?.let { it.optInt(key) == 1 } ?: true

        /** `[VENDOR]` HeyMelody: the model's `customEqualizer` flag and `0x0418` in the command bitmap. */
        fun hasCustom(context: Context) =
            flag(context, "customEqualizer") && Capabilities.supports(context, OpoProtocol.CMD_SAVE_CUSTOM_EQ)

        /** `[VENDOR]` HeyMelody: the model's `bassEngineSupport` flag and `0x041B` in the command bitmap. */
        fun hasBassWave(context: Context) =
            flag(context, "bassEngineSupport") && Capabilities.supports(context, OpoProtocol.CMD_SET_BASSWAVE_LEVEL)

        /** `[VENDOR]` HeyMelody's EQ row: built-in presets (`equalizer` 1-4) or custom presets. */
        fun hasEq(context: Context) = hasCustom(context) ||
            ((ModelCatalog.current(context)?.json?.optInt("equalizer") ?: 1) in 1..4 &&
                Capabilities.supports(context, OpoProtocol.CMD_SET_EQ))

        /** `[VENDOR]` `customEqMax`, else HeyMelody's default of 3. */
        fun maxCustom(context: Context) =
            ModelCatalog.current(context)?.json?.optInt("customEqMax")?.takeIf { it > 0 } ?: 3

        /** `[VENDOR]` `customEqFrequency` (10 bands on 8 models), else HeyMelody's six. */
        fun modelFreqs(context: Context): List<Int> =
            ModelCatalog.current(context)?.json?.optJSONArray("customEqFrequency")
                ?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: EqCodec.DEFAULT_FREQS
    }

    /**
     * `[VENDOR]` HeyMelody's list: `equalizerModeCompat`, then `equalizerModeByVersion` (its app-version
     * gate is always met here), then `equalizerMode`, less any entry whose `minFirmVersion` is above the
     * buds' firmware, stably sorted by `order` (0 when absent). The firmware number is the lower of the
     * two buds' versions (the first two parts of "138.138.105"); unknown firmware counts as 0.
     */
    private fun modelModes(json: JSONObject, firmware: String?): List<JSONObject> {
        val parts = firmware?.split('.')?.map { it.toIntOrNull() ?: 0 }.orEmpty()
        val version = if (parts.size == 3) parts.take(2).filter { it != 0 }.minOrNull() ?: 0 else parts.firstOrNull() ?: 0
        return listOf("equalizerModeCompat", "equalizerModeByVersion", "equalizerMode")
            .flatMap { key -> json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
            .filter { it.optInt("minFirmVersion") <= version }
            .sortedBy { it.optInt("order") }
    }

    /** The bands a new preset gets: the buds' own presets' if they have any. */
    private fun freqs() = manager?.eqCustom?.firstOrNull()?.freqs ?: modelFreqs(this)

    /**
     * The model's built-in presets as (`0x0406` id, name), in HeyMelody's order. `[VENDOR]` its per-model
     * `equalizerMode` (`assets/models.json`): each `modeType` names a preset and its `protocolIndex` is
     * the id, so the same id is a different preset on another model (Nord Buds 2R: 1 = Bold). Names
     * are `DisplayContentUtils.d()`. Nothing detected yet = Buds 4; a model without the list gets none.
     */
    private fun builtInPresets(): List<Pair<Int, Int>> {
        val model = ModelCatalog.current(this)
        val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)
        val modes = when {
            model != null -> modelModes(model.json, prefs.getString(BudsConnectionManager.KEY_FIRMWARE, null))
            prefs.getString(Capabilities.KEY_PRODUCT_ID, null) != null -> return emptyList()
            else -> JSONArray("""[{"modeType":11,"protocolIndex":0},{"modeType":14,"protocolIndex":1},{"modeType":12,"protocolIndex":2}]""")
                .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        }
        // Types 1-4 have other names on these models (`f(name) == 2` is the `equalizer` field).
        val alt = model != null && (model.name == "OPPO Enco R" || model.name == "OPPO Enco Air2" || model.json.optInt("equalizer") == 2)
        return modes.mapNotNull { mode ->
            val name = when (mode.getInt("modeType")) {
                1 -> if (alt) R.string.eq_nature_balance else R.string.eq_classic
                2 -> if (alt) R.string.eq_bass_boost else R.string.eq_dynamic_bass
                3, 14, 32 -> R.string.eq_clear_vocals
                4 -> if (model?.name == "OPPO Enco R") R.string.eq_gentle else R.string.eq_clear
                5, 35 -> R.string.eq_default
                6, 36 -> R.string.eq_dyn_simple
                7, 37 -> R.string.eq_dyn_warm
                8, 38 -> R.string.eq_dyn_punchy
                9, 39 -> R.string.eq_dyn_real
                10 -> R.string.eq_hisaishi
                11, 17 -> R.string.eq_balanced
                12 -> R.string.eq_bass
                13 -> R.string.eq_bold
                15 -> R.string.eq_gentle
                16 -> R.string.eq_enco_x_classic
                18 -> R.string.eq_reno_dawn
                19 -> R.string.eq_hans_zimmer
                20 -> R.string.eq_natural_inspiration
                21 -> R.string.eq_reno_sunrise
                22 -> R.string.eq_nature_balance
                23 -> R.string.eq_punchy
                24 -> R.string.eq_spacious
                25 -> R.string.eq_reno_galaxy
                26 -> R.string.eq_ultimate
                27 -> R.string.eq_hd_clarity
                28 -> R.string.eq_pure_vocals
                29 -> R.string.eq_thundering_bass
                30 -> R.string.eq_dyn_featured
                31 -> R.string.eq_bass_boost
                33 -> R.string.eq_galactic
                34 -> R.string.eq_vibrant
                40 -> R.string.eq_dyn_vocal
                41 -> R.string.eq_clear_crisp
                else -> null  // unnamed in HeyMelody too
            } ?: return@mapNotNull null
            mode.getInt("protocolIndex") to name
        }
    }

    private var manager: BudsConnectionManager? = null
    private var bound = false

    private lateinit var notConnected: TextView
    private lateinit var builtInLabel: TextView
    private lateinit var builtInCard: LinearLayout
    private lateinit var bassSwitch: Switch
    private lateinit var bassSlider: LevelSliderView
    private lateinit var customHeader: TextView
    private lateinit var customCard: LinearLayout
    private lateinit var customActions: LinearLayout

    private var syncing = false

    /**
     * A duplicate waiting for its preset to exist. It is created with zero gains (the only create
     * frame captured) and the gains go in as a normal save once the re-read shows the buds' id.
     */
    private var pendingImport: Pair<String, List<Int>>? = null

    /** What the preset lists last showed; see [render]. */
    private var shownSignature: String? = null
    private var shownSelection: Int? = null
    private var shownNames: Set<String> = emptySet()
    private var shownWaiting: Set<String> = emptySet()
    private val customRows = HashMap<Int, View>()
    private lateinit var selection: SelectionSlider
    /** Rows folding away after a delete, as "id:name": names alone repeat (two presets called Custom1) and hid them all. */
    private val deleting = HashSet<String>()
    private fun key(p: EqCodec.Preset) = "${p.id}:${p.name}"

    /** Names sent to create a moment ago and not read back yet, so a second tap does not reuse one. */
    private val justCreated = HashMap<String, Long>()
    private fun createdNames(): Set<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        justCreated.values.removeAll { now - it > 4000 }
        return justCreated.keys
    }
    private fun createCustom(name: String, freqs: List<Int>) {
        justCreated[name] = android.os.SystemClock.elapsedRealtime()
        manager?.createCustomEq(name, freqs)
        render()
        // If the create never comes back, the placeholder goes with its 4 s.
        customCard.postDelayed({ render() }, 4100)
    }

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
        val root = SettingRowFactory.screen(this)

        root.addView(SettingRowFactory.title(this, R.string.eq_title))

        notConnected = sectionLabel(R.string.eq_not_connected)
        root.addView(notConnected)

        builtInLabel = sectionLabel(R.string.eq_recommended)
        root.addView(builtInLabel)
        builtInCard = SettingRowFactory.splitList(this)
        root.addView(builtInCard)

        // BassWave: a switch row, and the -5..+5 level slider under it while it is on.
        val bassCard = card()
        bassSwitch = SettingRowFactory.buildSwitch(this, false)
        bassSwitch.setOnCheckedChangeListener { _, on ->
            if (!syncing) manager?.setFeatures(OpoProtocol.FEATURE_BASSWAVE to on)
            if (on != (bassSlider.visibility == View.VISIBLE)) {
                // A user toggle unfolds the slider; a re-read syncing the state just places it.
                if (syncing) bassSlider.visibility = if (on) View.VISIBLE else View.GONE
                else slide(bassSlider, on, if (on) 450 else 320)
            }
        }
        bassCard.addView(
            SettingRowFactory.build(
                this, R.drawable.ic_equalizer, R.string.eq_basswave, R.string.eq_basswave_sub, bassSwitch
            ) { bassSwitch.performClick() }
        )
        bassSlider = LevelSliderView(this, -5, 5).apply {
            onRelease = { manager?.setBassWaveLevel(it) }
            visibility = View.GONE
            val pad = ThemeRes.dp(this@EqActivity, 8f)
            setPadding(pad, 0, pad, pad)
        }
        bassCard.addView(bassSlider)
        root.addView(bassCard.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(14f)
            if (!hasBassWave(this@EqActivity)) visibility = View.GONE
        })

        customHeader = sectionLabel(R.string.eq_custom)
        root.addView(customHeader)
        customCard = SettingRowFactory.splitList(this)
        root.addView(customCard)
        customActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }
        root.addView(customActions)

        selection = SelectionSlider(root)
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

        syncing = true
        val bassOn = m?.featureStates?.get(OpoProtocol.FEATURE_BASSWAVE) == 1
        bassSwitch.isChecked = bassOn
        m?.bassWaveLevel?.let { bassSlider.value = it }
        syncing = false

        // A row folded shut by a delete stays hidden until the re-read drops it; without this,
        // a read landing first (the selection one) flashed it back for a frame.
        val all = m?.eqCustom.orEmpty()
        deleting.retainAll(all.map { key(it) }.toSet())
        val custom = all.filter { key(it) !in deleting }
        pendingImport?.let { (name, gains) ->
            custom.firstOrNull { it.name == name }?.let { p ->
                pendingImport = null
                m?.saveCustomEq(EqCodec.Preset(p.id, p.name, p.freqs, gains, p.selected, p.tag))
            }
        }

        // Each EQ write triggers several re-reads; rebuilding on every one would cut the
        // selection and expand animations short, so the lists only rebuild when they change.
        val waiting = createdNames().filter { n -> custom.none { it.name == n } }
        val signature = "$connected|$current|$waiting|" + custom.joinToString { "${it.id}:${it.name}:${it.gains}" }
        if (signature == shownSignature) return
        // Animate only a real change on screen, never the first fill.
        val prevSelection = if (shownSignature == null) current else shownSelection
        val prevNames = if (shownSignature == null) null else shownNames
        shownSignature = signature
        shownSelection = current
        shownNames = custom.map { it.name }.toSet()
        val prevWaiting = shownWaiting
        shownWaiting = waiting.toSet()

        builtInCard.removeAllViews()
        val builtIns = builtInPresets()
        builtInLabel.visibility = if (builtIns.isEmpty()) View.GONE else View.VISIBLE
        builtInCard.visibility = builtInLabel.visibility
        var selectedRow: View? = null
        builtIns.forEachIndexed { i, (id, label) ->
            val row = choiceRow(getString(label), current == id, prevSelection == id) {
                manager?.selectBuiltInEq(id)
            }
            if (current == id) selectedRow = row
            SettingRowFactory.addSplit(builtInCard, row)
        }

        customCard.removeAllViews()
        customRows.clear()
        custom.forEachIndexed { i, p ->
            // Selecting a custom preset IS a save of it as it stands — same frame HeyMelody sends —
            // so opening its editor selects it too, exactly as in HeyMelody.
            // A tap only selects; the pencil at the row's end opens the editor.
            val row = choiceRow(p.name, current == p.id, prevSelection == p.id, onEdit = { showEditor(p) }) {
                manager?.saveCustomEq(p)
            }
            customRows[p.id] = row
            SettingRowFactory.addSplit(customCard, row)
            if (current == p.id) selectedRow = row
            if (prevNames != null && custom.size > prevNames.size && p.name !in prevNames && p.name !in prevWaiting) slide(row.parent as View, open = true) { selection.snap() }
        }
        // A preset just sent to create shows at once, dimmed, until the buds' re-read brings the real one (about a second).
        waiting.forEach { name ->
            val row = choiceRow(name, false, false) {}
            row.alpha = 0.5f
            SettingRowFactory.addSplit(customCard, row)
            if (prevNames != null && name !in prevWaiting) slide(row.parent as View, open = true)
        }
        customCard.visibility = if ((custom.isEmpty() && waiting.isEmpty()) || !hasCustom(this)) View.GONE else View.VISIBLE

        // New lives under the card, apart from the presets themselves.
        customActions.removeAllViews()
        if (connected && hasCustom(this) && custom.size < maxCustom(this)) {
            customActions.addView(actionButton(R.drawable.ic_plus, R.string.eq_add) {
                val used = custom.map { it.name }.toSet() + createdNames()
                val name = (1..9).map { "Custom$it" }.first { it !in used }
                createCustom(name, freqs())
            })
        }
        customActions.visibility = if (customActions.childCount == 0) View.GONE else View.VISIBLE
        customHeader.visibility =
            if (customCard.visibility == View.GONE && customActions.visibility == View.GONE) View.GONE else View.VISIBLE
        selection.moveTo(selectedRow, ThemeRes.color(this, R.attr.appColorAccent))
    }

    /**
     * Grows [v] open, or shuts it, by animating its height with a fade — used for a new preset
     * row, a deleted one, and the Bass boost slider. A shut view ends GONE; [onEnd] runs after.
     */
    private fun slide(v: View, open: Boolean, ms: Long = if (open) 280 else 220, onEnd: (() -> Unit)? = null) {
        val lp = v.layoutParams
        // A split row's card has a gap above it: it grows and shrinks with the card, or it snaps at the end.
        val mlp = lp as? ViewGroup.MarginLayoutParams
        val gap = mlp?.topMargin ?: 0
        val natural = lp.height
        val full = if (natural > 0) natural else {
            val w = (v.parent as? View)?.width ?: 0
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            v.measuredHeight
        }
        v.visibility = View.VISIBLE
        ValueAnimator.ofInt(if (open) 0 else full, if (open) full else 0).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                val shown = if (open) it.animatedFraction else 1f - it.animatedFraction
                v.alpha = shown
                mlp?.topMargin = (gap * shown).toInt()
                v.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    lp.height = natural
                    mlp?.topMargin = gap
                    v.alpha = 1f
                    if (!open) v.visibility = View.GONE
                    v.requestLayout()
                    onEnd?.invoke()
                }
            })
            start()
        }
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
            background = ThemeRes.sheet(context)
            setPadding(dp(8f), dp(14f), dp(8f), dp(18f))
        }

        val nameView = TextView(this).apply {
            text = p.name
            setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorTextPrimary))
            textSize = 17f
            typeface = ThemeRes.bold(context)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // SPEC 3.5 header: Rename left, name centred, Done (check) right. Every release already
        // saved the preset, so Done only closes; there is no "saved" feedback by design.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(6f), 0)
            addView(iconButton(R.drawable.ic_pencil, R.string.eq_rename) {
                rename(p) { renamed -> p = renamed; nameView.text = renamed.name }
            })
            addView(nameView)
            addView(iconButton(R.drawable.ic_check, R.string.eq_done) { d.dismiss() })
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

        // Duplicate and Delete, 48dp, bottom right (SPEC 3.5).
        val fortyEight = { v: android.view.View -> v.layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f)); v.setPadding(dp(13f), dp(13f), dp(13f), dp(13f)) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10f), dp(6f), 0)
            addView(iconButton(R.drawable.ic_copy, R.string.eq_duplicate) {
                if (shownNames.size >= maxCustom(this@EqActivity)) return@iconButton
                d.dismiss()
                val used = shownNames + createdNames()
                val name = (2..9).map { "${p.name.take(18)} $it" }.firstOrNull { it !in used } ?: return@iconButton
                pendingImport = name to p.gains
                createCustom(name, p.freqs)
            }.apply {
                fortyEight(this)
                isEnabled = shownNames.size < maxCustom(this@EqActivity)
                alpha = if (isEnabled) 1f else 0.35f
            })
            addView(iconButton(R.drawable.ic_delete, R.string.eq_delete) {
                ConfirmDialog.show(
                    this@EqActivity, getString(R.string.eq_delete_confirm, p.name), null,
                    getString(R.string.eq_delete)
                ) {
                    d.dismiss()
                                        deleting += key(p)
                    val row = customRows[p.id]
                    // Both at once: the fold takes 220 ms, the buds' re-read longer.
                    // The buds refuse to delete the preset in use: switch to a recommended one first.
                    val other = builtInPresets().firstOrNull()?.first
                    if (manager?.eqCurrent == p.id && other != null) {
                        manager?.selectBuiltInEq(other)
                        customCard.postDelayed({ manager?.deleteCustomEq(p) }, 300)
                    } else manager?.deleteCustomEq(p)
                    (row?.parent as? View)?.let { slide(it, open = false) }
                }
            }.apply {
                fortyEight(this)
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10f)
            })
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
            w.setWindowAnimations(R.style.SheetAnimation)
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
    private fun actionButton(iconRes: Int, labelRes: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        val dp = { v: Float -> ThemeRes.dp(this@EqActivity, v) }
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = ThemeRes.iconButton(context)
        layoutParams = LinearLayout.LayoutParams(0, dp(46f), 1f)
        addView(ImageView(this@EqActivity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f))
            setImageDrawable(ThemeRes.tint(this@EqActivity, iconRes, ThemeRes.color(this@EqActivity, R.attr.appColorAccent)))
        })
        addView(TextView(this@EqActivity).apply {
            setText(labelRes)
            setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorTextPrimary))
            textSize = 14f
            setPadding(dp(8f), 0, 0, 0)
        })
        ThemeRes.sinkOnPress(this)
        setOnClickListener { Haptics.commit(it); onClick() }
    }

    /** SPEC icon button (44dp, accent icon). */
    private fun iconButton(iconRes: Int, descRes: Int, onClick: () -> Unit) =
        SettingRowFactory.iconButton(this, iconRes, descRes, onClick)

    // ---------------------------------------------------------------- listener

    override fun onEqState() = render()
    override fun onFeatureStates(states: Map<Int, Int>) = render()
    override fun onConnected(connected: Boolean) {
        if (connected) manager?.refreshEq()
        render()
    }
    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {}

    // ---------------------------------------------------------------- small builders

    private fun card() = SettingRowFactory.card(this)

    private fun sectionLabel(res: Int) = SettingRowFactory.sectionLabel(this, res)

    /** A selectable row: accent label and a check when selected — the BottomSheetDialog look. */
    /**
     * A preset row. When the selection moves, the old row's red label fades back to white and its
     * tick shrinks away while the new row's label warms to red and its tick pops in.
     */
    private fun choiceRow(
        label: String, selected: Boolean, wasSelected: Boolean,
        onEdit: (() -> Unit)? = null, onClick: () -> Unit
    ): View {
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val accent = ThemeRes.color(this, R.attr.appColorAccent)
        val primary = ThemeRes.color(this, R.attr.appColorTextPrimary)
        val changed = selected != wasSelected
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f))
            setPadding(dp(14f), 0, dp(14f), 0)
            setOnClickListener { Haptics.commit(it); onClick() }
            addView(TextView(this@EqActivity).apply {
                text = label
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val to = if (selected) accent else primary
                if (changed) {
                    ValueAnimator.ofArgb(if (selected) primary else accent, to).apply {
                        duration = 260
                        addUpdateListener { setTextColor(it.animatedValue as Int) }
                        start()
                    }
                } else setTextColor(to)
            })
            if (onEdit != null) addView(iconButton(R.drawable.ic_pencil, R.string.eq_edit, onEdit).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { marginStart = dp(12f) }
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            })
        }
    }
}
