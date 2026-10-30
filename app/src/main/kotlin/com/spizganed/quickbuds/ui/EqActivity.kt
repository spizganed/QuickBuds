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
import com.spizganed.quickbuds.protocol.EqSnapshot
import com.spizganed.quickbuds.protocol.EqState
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.protocol.RowRules
import com.spizganed.quickbuds.protocol.Feature
import com.spizganed.quickbuds.protocol.FeatureSync
import org.json.JSONArray
import org.json.JSONObject

/**
 * Equalizer — HeyMelody's layout: built-in presets, BassWave, custom presets with a band editor (6 bands, 10 on some models).
 * Every command is `[CAPTURE]` 2026-09-23 (PROTOCOL.md §17).
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
            flag(context, "customEq") && Capabilities.supports(context, OpoProtocol.CMD_SAVE_CUSTOM_EQ)

        /** `[VENDOR]` HeyMelody: the model's `bassEngineSupport` flag and `0x041B` in the command bitmap. */
        fun hasBassWave(context: Context) =
            flag(context, "bassEngine") && Capabilities.supports(context, OpoProtocol.CMD_SET_BASSWAVE_LEVEL)

        /** `[VENDOR]` realme Link's dynamic sound: three sliders with a band byte (`bassBands` 3). */
        fun hasBassBands(context: Context) = ModelCatalog.current(context)?.json?.optInt("bassBands") == 3

        /** `[VENDOR]` HeyMelody's EQ row: the model has EQ data and the buds accept its command ([RowRules]). */
        fun hasEq(context: Context) = RowRules.EQ in RowRules.rows(
            ModelCatalog.current(context)?.json,
            Capabilities.reply(context),
            Capabilities.commands(context)
        )

        /** `[VENDOR]` `customEqMax`, else HeyMelody's default of 3. */
        fun maxCustom(context: Context) =
            ModelCatalog.current(context)?.json?.optInt("customEqLimit")?.takeIf { it > 0 } ?: 3

        /** `[VENDOR]` `customEqFrequency` (10 bands on 8 models), else HeyMelody's six. */
        fun modelFreqs(context: Context): List<Int> =
            ModelCatalog.current(context)?.json?.optJSONArray("customEqBands")
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
        return listOf("eqPresetsCompat", "eqPresetsByFirmware", "eqPresets")
            .flatMap { key -> json.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty() }
            .filter { it.optInt("minFirmware") <= version }
            .sortedBy { it.optInt("order") }
    }

    /** The bands a new preset gets: the buds' own presets' if they have any. */
    private fun freqs() = manager?.eqCustom?.firstOrNull()?.freqs ?: modelFreqs(this)

    /**
     * The model's built-in presets as (`0x0406` id, name), in HeyMelody's order. `[VENDOR]` its per-model
     * `eqPresets` (`assets/models.json`): each `modeType` names a preset and its `protocolIndex` is
     * the id, so the same id is a different preset on another model (Nord Buds 2R: 1 = Bold). Names
     * are `DisplayContentUtils.d()`. Nothing detected yet = Buds 4; a model without the list gets none.
     */
    private fun builtInPresets(): List<Pair<Int, Int>> {
        val model = ModelCatalog.current(this)
        val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)
        val modes = when {
            model != null -> modelModes(model.json, prefs.getString(BudsConnectionManager.KEY_FIRMWARE, null))
            prefs.getString(Capabilities.KEY_PRODUCT_ID, null) != null -> return emptyList()
            else -> JSONArray("""[{"mode":11,"slot":0},{"mode":14,"slot":1},{"mode":12,"slot":2}]""")
                .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        }
        // Types 1-4 have other names on these models (`f(name) == 2` is the `equalizer` field).
        val alt = model != null && (model.name == "OPPO Enco R" || model.name == "OPPO Enco Air2" || model.json.optInt("eqKind") == 2)
        return modes.mapNotNull { mode ->
            val name = when (mode.getInt("mode")) {
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
                // realme Link's four presets; HeyMelody has none, so the types are ours (PROTOCOL.md §17).
                101 -> R.string.eq_pure_raw
                102 -> R.string.eq_deep_bass
                103 -> R.string.eq_pure_vocals
                104 -> R.string.eq_pure_bass
                // realme Buds T01 (report 25): 0x0406 ids 0, 1, 2 are Bass Boost+, Balanced, Bright.
                105 -> R.string.eq_bass_boost_plus
                106 -> R.string.eq_bright
                else -> null  // unnamed in HeyMelody too
            } ?: return@mapNotNull null
            mode.getInt("slot") to name
        }
    }

    private var manager: BudsConnectionManager? = null
    private var bound = false

    private lateinit var notConnected: TextView
    private lateinit var builtInLabel: TextView
    private lateinit var builtInCard: LinearLayout
    private lateinit var bassSwitch: Switch
    private lateinit var bassRow: LinearLayout
    private lateinit var bassSlider: LevelSliderView
    /** realme dynamic sound: low, medium and high, each with a label. Shown in place of [bassSlider]. */
    private val bandSliders = mutableListOf<LevelSliderView>()
    /** What opens under the switch: [bassSlider], or the three band sliders. */
    private lateinit var bassPanel: View
    private lateinit var customHeader: TextView
    private lateinit var customCard: LinearLayout
    private lateinit var customActions: LinearLayout

    /** False until the BassWave slider was placed once, so the first paint does not animate. */
    private var bassPainted = false

    /** What the preset lists last showed; see [render]. */
    private var shownSignature: String? = null

    /** The saved EQ state, painted in [onCreate] before the service binds. */
    private var snapshot: EqSnapshot? = null

    /** Which renders animate: only a change the user makes on this screen. */
    private val motion = EqRenderMotion()

    /** The rows, kept between renders and updated in place: recommended by buds id, custom by buds id, and the just-created ones by name. */
    private val builtInChoices = LinkedHashMap<Int, Choice>()
    private val customChoices = LinkedHashMap<Int, Choice>()
    private val waitingChoices = LinkedHashMap<String, Choice>()
    private var shownNew: Boolean? = null
    private lateinit var newButton: View
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
        motion.onUserAction()
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
        snapshot = EqSnapshot.decode(
            getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getString(EqSnapshot.KEY, null)
        )

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
        // A tap asks for the opposite value; the switch moves only when the buds report one.
        (bassSwitch as RequestSwitch).onRequest = { wantOn ->
            motion.onUserAction()
            manager?.requestSetting(
                Feature.BASSWAVE,
                listOf(Feature.BASSWAVE to (if (wantOn) 1 else 0)),
                FeatureSync.Confirm.READ
            )
        }
        bassRow = SettingRowFactory.build(
            this, R.drawable.ic_equalizer, R.string.eq_basswave, R.string.eq_basswave_sub, bassSwitch
        ) { bassSwitch.performClick() }
        bassCard.addView(bassRow)
        bassSlider = LevelSliderView(this, -5, 5).apply {
            onRelease = { motion.onUserAction(); manager?.setBassWaveLevel(it) }
            visibility = View.GONE
            val pad = ThemeRes.dp(this@EqActivity, 8f)
            setPadding(pad, 0, pad, pad)
        }
        bassCard.addView(bassSlider)
        val bands = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val pad = ThemeRes.dp(this@EqActivity, 8f)
            setPadding(pad, 0, pad, pad)
        }
        for ((i, label) in listOf(R.string.eq_bass_low, R.string.eq_bass_mid, R.string.eq_bass_high).withIndex()) {
            bands.addView(TextView(this).apply {
                setText(label)
                setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorTextSecondary))
                textSize = 13f
                setPadding(dp(10f), dp(6f), 0, 0)
            })
            bands.addView(LevelSliderView(this, -5, 5).apply {
                onRelease = { motion.onUserAction(); manager?.setBassBand(i + 1, it) }
            }.also { bandSliders += it })
        }
        bassCard.addView(bands)
        bassPanel = if (hasBassBands(this)) bands else bassSlider
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
        customActions.visibility = View.GONE
        newButton = actionButton(R.drawable.ic_plus, R.string.eq_add) {
            val used = customChoices.values.mapNotNull { it.preset?.name }.toSet() + createdNames()
            val name = (1..9).map { "Custom$it" }.first { it !in used }
            createCustom(name, freqs())
        }
        customActions.addView(newButton)
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
        if (bassSlider.dragging || bandSliders.any { it.dragging }) return
        val m = manager
        val connected = m?.isConnected() == true
        val animate = motion.animate()
        // Before the bind the screen does not know the link state, so it hides the label.
        notConnected.visibility = when {
            m == null -> View.GONE
            connected -> View.GONE
            else -> View.VISIBLE
        }
        // The manager holds the latest state after the bind, also while the link is down.
        // Before the bind the screen paints the onCreate snapshot.
        val state = EqState.paint(m?.eq, snapshot)
        val current = state.current

        val bassOn = (m?.featureStates ?: Capabilities.reply(this).orEmpty())[Feature.BASSWAVE] == 1
        bassSwitch.setFromBuds(bassOn)
        state.bassWave?.let { bassSlider.value = it }
        state.bassBands.forEachIndexed { i, v -> v?.let { bandSliders[i].value = it } }
        // The buds move the switch; the slider follows it, animated only after the first paint.
        if (bassOn != (bassPanel.visibility == View.VISIBLE)) {
            if (bassPainted && animate) slide(bassPanel, bassOn, if (bassOn) 450 else 320)
            else bassPanel.visibility = if (bassOn) View.VISIBLE else View.GONE
        }
        bassPainted = true

        // A row folded shut by a delete stays hidden until the re-read drops it; without this,
        // a read landing first (the selection one) flashed it back for a frame.
        val all = state.custom
        deleting.retainAll(all.map { key(it) }.toSet())
        val custom = all.filter { key(it) !in deleting }

        // Each EQ write triggers several re-reads; the lists only update when something changed.
        val waiting = createdNames().filter { n -> custom.none { it.name == n } }
        val signature = "$connected|$current|$waiting|" + custom.joinToString { "${it.id}:${it.name}:${it.gains}" }
        if (signature == shownSignature) return
        shownSignature = signature

        val builtIns = builtInPresets()
        builtInLabel.visibility = if (builtIns.isEmpty()) View.GONE else View.VISIBLE
        builtInCard.visibility = builtInLabel.visibility
        builtInChoices.keys.filter { id -> builtIns.none { it.first == id } }
            .forEach { id -> builtInChoices.remove(id)?.let { builtInCard.removeView(it.card) } }
        var selectedRow: View? = null
        for ((id, label) in builtIns) {
            val c = builtInChoices.getOrPut(id) {
                Choice(getString(label), null) { motion.onUserAction(); manager?.selectBuiltInEq(id); render() }.also {
                    SettingRowFactory.addSplit(builtInCard, it.row); it.card = it.row.parent as View
                }
            }
            c.setSelected(current == id, animate)
            if (current == id) selectedRow = c.card
        }

        // Custom: rows no longer listed fold away, listed ones update in place, new ones grow in before any
        // placeholders (a placeholder for the same name is adopted, so its row does not blink).
        val wanted = custom.map { it.id }.toSet()
        customChoices.keys.filter { it !in wanted }.forEach { id -> customChoices.remove(id)?.let { fold(it, animate) } }
        for (p in custom) {
            val c = customChoices.getOrPut(p.id) {
                waitingChoices.remove(p.name)?.also { it.row.alpha = 1f }
                    ?: Choice(p.name, { c -> c.preset?.let { showEditor(it) } }) {}.also { n ->
                        SettingRowFactory.addSplit(customCard, n.row); n.card = n.row.parent as View
                        customCard.removeView(n.card)
                        customCard.addView(n.card, customCard.childCount - waitingChoices.size)
                        if (animate) slide(n.card, open = true) { selection.snap() }
                    }
            }
            c.preset = p
            c.onClick = { motion.onUserAction(); manager?.selectCustomEq(p); render() }
            c.setName(p.name)
            c.setSelected(current == p.id, animate)
            if (current == p.id) selectedRow = c.card
        }
        // A preset just sent to create shows at once, dimmed, until the buds' re-read brings the real one (about a second).
        waitingChoices.keys.filter { it !in waiting }.forEach { n -> waitingChoices.remove(n)?.let { fold(it, animate) } }
        for (name in waiting) waitingChoices.getOrPut(name) {
            Choice(name, { c -> c.preset?.let { showEditor(it) } }) {}.also { n ->
                n.row.alpha = 0.5f
                SettingRowFactory.addSplit(customCard, n.row); n.card = n.row.parent as View
                if (animate) slide(n.card, open = true)
            }
        }
        val listed = customChoices.size + waitingChoices.size
        customCard.visibility = if (listed == 0 || !hasCustom(this)) View.GONE else View.VISIBLE

        // New lives under the card, apart from the presets themselves; it folds away when the last slot is taken.
        val showNew = connected && hasCustom(this) && listed < maxCustom(this)
        when {
            shownNew == null || !animate -> customActions.visibility = if (showNew) View.VISIBLE else View.GONE
            showNew != shownNew -> slide(customActions, showNew)
        }
        shownNew = showNew
        customHeader.visibility = if (listed == 0 && !showNew) View.GONE else View.VISIBLE
        selection.moveTo(selectedRow, ThemeRes.color(this, R.attr.appColorAccent))
    }

    /** Folds [c]'s card shut and removes it. Without [animate] it goes at once: the first fill does not slide. */
    private fun fold(c: Choice, animate: Boolean = true) {
        if (!animate) {
            (c.card.parent as? ViewGroup)?.removeView(c.card)
            selection.snap()
            return
        }
        if (c.folding) return
        c.folding = true
        slide(c.card, open = false) { (c.card.parent as? ViewGroup)?.removeView(c.card); selection.snap() }
    }

    private fun slide(v: View, open: Boolean, ms: Long = if (open) 280 else 220, onEnd: (() -> Unit)? = null) =
        Motion.slide(v, open, ms, onEnd)

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
            background = if (ThemeRes.glass(context) && !ThemeRes.nothing(context)) ThemeRes.floatingSheet(context) else ThemeRes.card(context, 28f, solid = true, base = ThemeRes.palette(context).background)
            setPadding(dp(8f), dp(18f), dp(8f), dp(18f))
        }

        val nameView = TextView(this).apply {
            text = p.name
            setTextColor(ThemeRes.color(this@EqActivity, R.attr.appColorTextPrimary))
            textSize = 17f
            typeface = ThemeRes.bold(context)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Header: Rename left, name centred, Done (check) right. Every release already
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
                motion.onUserAction()
                manager?.saveCustomEq(p)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        })

        // Duplicate and Delete, 48dp, bottom right.
        val fortyEight = { v: android.view.View -> v.layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f)); v.setPadding(dp(13f), dp(13f), dp(13f), dp(13f)) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10f), dp(6f), 0)
            addView(iconButton(R.drawable.ic_delete, R.string.eq_delete) {
                ConfirmDialog.show(
                    this@EqActivity, getString(R.string.eq_delete_confirm, p.name), null,
                    getString(R.string.eq_delete)
                ) {
                    d.dismiss()
                    deleting += key(p)
                    motion.onUserAction()
                    // Both at once: the fold takes 220 ms, the buds' re-read longer.
                    // The buds refuse to delete the preset in use: switch to a recommended one first.
                    val other = builtInPresets().firstOrNull()?.first
                    if (manager?.eqCurrent == p.id && other != null) {
                        manager?.selectBuiltInEq(other)
                        customCard.postDelayed({ manager?.deleteCustomEq(p) }, 300)
                    } else manager?.deleteCustomEq(p)
                    customChoices.remove(p.id)?.let { fold(it) }
                }
            }.apply {
                fortyEight(this)
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10f)
            })
        })

        BottomSheetDialog.present(this, d, root)
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
                    motion.onUserAction()
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

    /** Icon button (44dp, accent icon). */
    private fun iconButton(iconRes: Int, descRes: Int, onClick: () -> Unit) =
        SettingRowFactory.iconButton(this, iconRes, descRes, onClick)

    // ---------------------------------------------------------------- listener

    override fun onEqState() = render()
    override fun onFeatureStates(states: Map<Int, Int>) = render()

    /** The engine applies a tap: the row says "Applying…" and its switch stays still. */
    override fun onSettingBusy(key: Int, on: Boolean) {
        if (key != Feature.BASSWAVE) return
        bassSwitch.isEnabled = !on
        val sub = SettingRowFactory.subtitle(this, bassRow)
        sub.setText(if (on) R.string.feature_applying else R.string.eq_basswave_sub)
    }
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

    /**
     * A preset row that lives across renders and is updated in place: [setSelected] fades the label between the
     * text and accent colours, [setName] retitles it. Its [card] is set once the row is in a list ([SettingRowFactory.addSplit]).
     */
    private inner class Choice(name: String, onEdit: ((Choice) -> Unit)?, var onClick: () -> Unit) {
        /** The buds' preset behind a custom row; the pencil shows once it is set (a placeholder has none yet). */
        var preset: EqCodec.Preset? = null
            set(v) { field = v; pencil?.visibility = if (v != null) View.VISIBLE else View.INVISIBLE }
        private var pencil: View? = null
        var folding = false
        lateinit var card: View
        private var selected = false
        private val dp = { v: Float -> ThemeRes.dp(this@EqActivity, v) }
        // Glass: the selected tile is lit; its label turns bright white, not the accent (low contrast on glass).
        private val accent = if (ThemeRes.glass(this@EqActivity)) ThemeRes.palette(this@EqActivity).text else ThemeRes.color(this@EqActivity, R.attr.appColorAccent)
        private val primary = ThemeRes.color(this@EqActivity, R.attr.appColorTextPrimary)
        private val label = TextView(this@EqActivity).apply {
            text = name
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(primary)
        }
        val row = LinearLayout(this@EqActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f))
            setPadding(dp(14f), 0, dp(14f), 0)
            setOnClickListener { Haptics.commit(it); onClick() }
            addView(label)
            if (onEdit != null) addView(iconButton(R.drawable.ic_pencil, R.string.eq_edit) { onEdit(this@Choice) }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { marginStart = dp(12f) }
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                pencil = this
                if (preset == null) visibility = View.INVISIBLE
            })
        }

        fun setName(n: String) { if (label.text.toString() != n) label.text = n }

        fun setSelected(sel: Boolean, animate: Boolean) {
            if (sel == selected) return
            selected = sel
            val to = if (sel) accent else primary
            if (!animate) { label.setTextColor(to); return }
            ValueAnimator.ofArgb(if (sel) primary else accent, to).apply {
                duration = 260
                addUpdateListener { label.setTextColor(it.animatedValue as Int) }
                start()
            }
        }
    }
}
