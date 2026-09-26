package com.spizganed.quickbuds.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var customActions: LinearLayout

    private var syncing = false

    /**
     * An import waiting for its preset to exist. It is created with zero gains (the only create
     * frame captured) and the gains go in as a normal save once the re-read shows the buds' id.
     */
    private var pendingImport: Pair<String, List<Int>>? = null

    /** What the preset lists last showed; see [render]. */
    private var shownSignature: String? = null
    private var shownSelection: Int? = null
    private var shownNames: Set<String> = emptySet()
    private val customRows = HashMap<Int, View>()
    private val deleting = HashSet<String>()

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
        })

        customHeader = sectionLabel(R.string.eq_custom)
        root.addView(customHeader)
        customCard = card()
        root.addView(customCard)
        customActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }
        root.addView(customActions)

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
        deleting.retainAll(all.map { it.name }.toSet())
        val custom = all.filter { it.name !in deleting }
        pendingImport?.let { (name, gains) ->
            custom.firstOrNull { it.name == name }?.let { p ->
                pendingImport = null
                m?.saveCustomEq(EqCodec.Preset(p.id, p.name, p.freqs, gains, p.selected, p.tag))
            }
        }

        // Each EQ write triggers several re-reads; rebuilding on every one would cut the
        // selection and expand animations short, so the lists only rebuild when they change.
        val signature = "$connected|$current|" + custom.joinToString { "${it.id}:${it.name}:${it.gains}" }
        if (signature == shownSignature) return
        // Animate only a real change on screen, never the first fill.
        val prevSelection = if (shownSignature == null) current else shownSelection
        val prevNames = if (shownSignature == null) null else shownNames
        shownSignature = signature
        shownSelection = current
        shownNames = custom.map { it.name }.toSet()

        builtInCard.removeAllViews()
        listOf(
            EqCodec.BALANCED to R.string.eq_balanced,
            EqCodec.CLEAR_VOCALS to R.string.eq_clear_vocals,
            EqCodec.BASS to R.string.eq_bass
        ).forEachIndexed { i, (id, label) ->
            if (i > 0) builtInCard.addView(SettingRowFactory.buildDivider(this))
            builtInCard.addView(choiceRow(getString(label), current == id, prevSelection == id) {
                manager?.selectBuiltInEq(id)
            })
        }

        customCard.removeAllViews()
        customRows.clear()
        custom.forEachIndexed { i, p ->
            if (i > 0) customCard.addView(SettingRowFactory.buildDivider(this))
            // Selecting a custom preset IS a save of it as it stands — same frame HeyMelody sends —
            // so opening its editor selects it too, exactly as in HeyMelody.
            // A tap only selects; the pencil at the row's end opens the editor.
            val row = choiceRow(p.name, current == p.id, prevSelection == p.id, onEdit = { showEditor(p) }) {
                manager?.saveCustomEq(p)
            }
            customRows[p.id] = row
            customCard.addView(row)
            if (prevNames != null && custom.size > prevNames.size && p.name !in prevNames) slide(row, open = true)
        }
        customCard.visibility = if (custom.isEmpty()) View.GONE else View.VISIBLE

        // New / Import live under the card, apart from the presets themselves.
        customActions.removeAllViews()
        if (connected && custom.size < EqCodec.MAX_CUSTOM) {
            customActions.addView(actionButton(R.drawable.ic_add, R.string.eq_add) {
                val used = custom.map { it.name }.toSet()
                val name = (1..9).map { "Custom$it" }.first { it !in used }
                manager?.createCustomEq(name)
            })
            customActions.addView(actionButton(R.drawable.ic_paste, R.string.eq_import) { showImport(custom) }
                .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = ThemeRes.dp(this@EqActivity, 10f) })
        }
        customActions.visibility = if (customActions.childCount == 0) View.GONE else View.VISIBLE
        customHeader.visibility =
            if (customCard.visibility == View.GONE && customActions.visibility == View.GONE) View.GONE else View.VISIBLE
    }

    /**
     * Grows [v] open, or shuts it, by animating its height with a fade — used for a new preset
     * row, a deleted one, and the Bass boost slider. A shut view ends GONE; [onEnd] runs after.
     */
    private fun slide(v: View, open: Boolean, ms: Long = if (open) 280 else 220, onEnd: (() -> Unit)? = null) {
        val lp = v.layoutParams
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
                v.alpha = if (open) it.animatedFraction else 1f - it.animatedFraction
                v.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    lp.height = natural
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
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(6f), 0)
            addView(iconButton(R.drawable.ic_close, R.string.eq_close) { d.dismiss() })
            addView(nameView)
            addView(iconButton(R.drawable.ic_edit, R.string.eq_rename) {
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

        // Copy and delete sit as icons in the bottom-right corner, out of the curve's way.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10f), dp(6f), 0)
            addView(iconButton(R.drawable.ic_copy, R.string.eq_copy) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("QuickBuds EQ", EqCodec.toText(p)))
                Toast.makeText(this@EqActivity, R.string.eq_copied, Toast.LENGTH_SHORT).show()
            })
            addView(iconButton(R.drawable.ic_delete, R.string.eq_delete) {
                val sheet = BottomSheetDialog(this@EqActivity)
                sheet.title(getString(R.string.eq_delete_confirm, p.name))
                    .confirm(getString(R.string.eq_delete)) {
                        sheet.close()
                        d.dismiss()
                        // The row folds shut first; the delete goes out once it has.
                        deleting += p.name
                        val row = customRows[p.id]
                        if (row == null) manager?.deleteCustomEq(p)
                        else slide(row, open = false) { manager?.deleteCustomEq(p) }
                    }
                    .show()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10f) })
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

    /** Paste sheet, pre-filled when the clipboard already holds a preset. */
    private fun showImport(custom: List<EqCodec.Preset>) {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(R.string.eq_import_title))
            .input(if (EqCodec.fromText(clip) != null) clip.trim() else "", 120)
            .confirm(getString(R.string.eq_import_confirm)) {
                val parsed = EqCodec.fromText(sheet.inputValue())
                if (parsed == null) {
                    Toast.makeText(this, R.string.eq_import_bad, Toast.LENGTH_SHORT).show()
                    return@confirm
                }
                sheet.close()
                // A unique name, so the pending import finds its own preset and never an older one.
                val used = custom.map { it.name }.toSet()
                val name = (listOf(parsed.first) + (2..9).map { "${parsed.first.take(18)} $it" })
                    .first { it !in used }
                pendingImport = name to parsed.second
                manager?.createCustomEq(name)
            }
            .show()
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
        setOnClickListener { onClick() }
    }

    /** Square framed icon button, the header cog's style, accent-tinted. */
    private fun iconButton(iconRes: Int, descRes: Int, onClick: () -> Unit) = ImageView(this).apply {
        val dp = { v: Float -> ThemeRes.dp(this@EqActivity, v) }
        layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        setPadding(dp(9f), dp(9f), dp(9f), dp(9f))
        background = ThemeRes.iconButton(context)
        setImageDrawable(ThemeRes.tint(this@EqActivity, iconRes, ThemeRes.color(this@EqActivity, R.attr.appColorAccent)))
        contentDescription = getString(descRes)
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
        background = ThemeRes.card(context)
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
            setOnClickListener { onClick() }
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
            if (selected || changed) addView(ImageView(this@EqActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f))
                setImageDrawable(ThemeRes.tint(this@EqActivity, R.drawable.ic_check, accent))
                if (changed) {
                    val from = if (selected) 0f else 1f
                    scaleX = from; scaleY = from; alpha = from
                    val to = 1f - from
                    animate().scaleX(to).scaleY(to).alpha(to).setDuration(260)
                        .setInterpolator(if (selected) OvershootInterpolator(2.5f) else DecelerateInterpolator())
                        .start()
                }
            })
            if (onEdit != null) addView(iconButton(R.drawable.ic_edit, R.string.eq_edit, onEdit).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { marginStart = dp(12f) }
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            })
        }
    }
}
