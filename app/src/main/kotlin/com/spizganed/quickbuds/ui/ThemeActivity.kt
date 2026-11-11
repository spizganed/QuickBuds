package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R

/**
 * Theme & colors: the style (Material / Classic / Nothing / Glass), the three built-in presets as preview tiles, then the
 * custom presets (at most 3) as rows, then "New preset". Tapping a tile or a row applies that
 * preset at once; the pencil opens [PresetEditActivity].
 */
class ThemeActivity : Activity() {

    private lateinit var root: LinearLayout

    /** The built-in accent picker is open; kept across the recreate() a commit triggers. */
    private var accentOpen = false

    /** The active built-in tile, repainted live while the accent picker drags. */
    private var activeTile: PalettePreviewView? = null

    /** The selected tile or preset row, outlined by [selection] (slides, also across the recreate() a pick triggers). */
    private lateinit var selection: SelectionSlider
    private var selTarget: View? = null
    private var shownCustom: List<Palette>? = null
    private var selRadius = SettingRowFactory.SPLIT_RADIUS

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        accentOpen = savedInstanceState?.getBoolean(KEY_ACCENT_OPEN) ?: false
        root = SettingRowFactory.screen(this)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@ThemeActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean(KEY_ACCENT_OPEN, accentOpen)
    }

    /** Rebuilt on every resume: the editor may have renamed, added or deleted a preset. */
    override fun onResume() {
        super.onResume()
        if (!isFinishing) build()
    }

    private fun apply(id: String, v: View) {
        if (id == PaletteStore.activeId(this)) return
        Haptics.commit(v)
        PaletteStore.setActive(this, id)
        ThemeRes.recreateFaded(this)
    }

    private fun build() {
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val p = ThemeRes.palette(this)
        val activeId = PaletteStore.activeId(this)
        root.removeAllViews()
        activeTile = null
        selTarget = null
        if (!::selection.isInitialized) selection = SelectionSlider(root, "theme")
        root.addView(SettingRowFactory.title(this, R.string.theme_title))

        // --- Style: Material, QuickBuds (Classic), Pixel art (dot matrix) or Glass, for the app and the widgets ---
        // Listed in that order; ORDER maps a segment to the stored style index.
        val order = intArrayOf(2, 0, 1, 3)
        root.addView(SettingRowFactory.sectionLabel(this, R.string.widget_style_title))
        root.addView(AncSegmentedView(this, listOf(getString(R.string.widget_style_material), getString(R.string.widget_style_classic),
            getString(R.string.widget_style_nothing), getString(R.string.widget_style_glass))).apply {
            selected = order.indexOf(ThemeRes.style(this@ThemeActivity))
            // Its name, for accessibility and for adb tests that look the control up.
            contentDescription = getString(R.string.widget_style_title)
            onSegmentTapped = { i ->
                if (i != selected) {
                    // The pill slides first, then the screen is rebuilt in the new style.
                    selected = i
                    ThemeRes.setStyle(this@ThemeActivity, order[i])
                    postDelayed({ ThemeRes.recreateFaded(this@ThemeActivity) }, 260)
                }
            }
        })

        // --- Where the colours come from: the wallpaper, one accent on a built-in set, or a palette of six colours ---
        // Every style takes all three. Glass takes from a preset its accent and whether it is light or dark; its surfaces, its text and its
        // background are its own.
        val wallpaperOk = PaletteStore.wallpaperSupported()
        val sources = if (wallpaperOk) intArrayOf(0, 1, 2) else intArrayOf(1, 2)
        val mode = if (PaletteStore.wallpaper(this)) 0 else if (!ThemeRes.palette(this).builtIn) 2 else 1
        root.addView(SettingRowFactory.sectionLabel(this, R.string.theme_colors_title))
        root.addView(AncSegmentedView(this, sources.map { getString(when (it) { 0 -> R.string.colors_wallpaper; 1 -> R.string.colors_accent; else -> R.string.colors_palette }) }).apply {
            selected = sources.indexOf(mode)
            contentDescription = getString(R.string.theme_colors_title)
            onSegmentTapped = tapped@{ i ->
                if (i != selected) {
                    selected = i
                    Haptics.commit(this)
                    when (sources[i]) {
                        0 -> PaletteStore.setWallpaper(this@ThemeActivity, true)
                        1 -> {
                            PaletteStore.setWallpaper(this@ThemeActivity, false)
                            if (!ThemeRes.palette(this@ThemeActivity).builtIn) PaletteStore.setActive(this@ThemeActivity, PaletteStore.OLED)
                        }
                        else -> {
                            PaletteStore.setWallpaper(this@ThemeActivity, false)
                            val first = PaletteStore.custom(this@ThemeActivity).firstOrNull()
                            if (first != null) PaletteStore.setActive(this@ThemeActivity, first.id)
                            else { createPreset(); selected = sources.indexOf(mode); return@tapped }
                        }
                    }
                    postDelayed({ ThemeRes.recreateFaded(this@ThemeActivity) }, 260)
                }
            }
        })
        val coloured = mode != 2
        // A text picker: the built-in presets as one segmented control, the way the Style picker is.
        val ids = PaletteStore.builtInIds()
        if (coloured) root.addView(SettingRowFactory.sectionLabel(this, R.string.theme_builtin))
        if (coloured) root.addView(AncSegmentedView(this, ids.map { PaletteStore.builtIn(this, it).name }).apply {
            selected = ids.indexOf(activeId)
            contentDescription = getString(R.string.theme_builtin)
            onSegmentTapped = { i ->
                if (i != selected) {
                    selected = i
                    val id = ids[i]
                    Haptics.commit(this)
                    // A theme picked by hand ends Match system.
                    if (PaletteStore.auto(this@ThemeActivity)) PaletteStore.setAuto(this@ThemeActivity, false)
                    PaletteStore.setActive(this@ThemeActivity, id)
                    postDelayed({ ThemeRes.recreateFaded(this@ThemeActivity) }, 260)
                }
            }
        })

        // --- Match system: White in light mode, a dark built-in in dark mode ---
        val autoSwitch = SettingRowFactory.buildSwitch(this, PaletteStore.auto(this))
        autoSwitch.setOnCheckedChangeListener { v, on -> PaletteStore.setAuto(this, on); v.postDelayed({ ThemeRes.recreateFaded(this@ThemeActivity) }, 260) }
        val autoRow = SettingRowFactory.build(this, 0, R.string.theme_auto, 0, autoSwitch) { autoSwitch.performClick() }
        SettingRowFactory.subtitle(this, autoRow).text =
            getString(R.string.theme_auto_sub, ThemeRes.builtInName(this, PaletteStore.WHITE),
                ThemeRes.builtInName(this, PaletteStore.autoDark(this)))
        root.addView(SettingRowFactory.card(this).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f)
            if (coloured) addView(autoRow)
            // Glass: the background behind the glass.
            if (ThemeRes.glass(this@ThemeActivity)) {
                val current = ThemeRes.glassBg(this@ThemeActivity)
                val bgRow = SettingRowFactory.build(this@ThemeActivity, 0, R.string.theme_glass_gradient, 0, SettingRowFactory.buildChevron(this@ThemeActivity)) {
                    var pick = ThemeRes.glassBg(this@ThemeActivity)
                    val sheet = BottomSheetDialog(this@ThemeActivity)
                    fun refresh() { sheet.items(ThemeRes.GlassBg.values().map { bg ->
                        BottomSheetDialog.Item(getString(bg.nameRes), selected = bg == pick, onClick = { pick = bg; refresh() })
                    }) }
                    sheet.title(getString(R.string.theme_glass_gradient)).dismissOnSelect(false)
                        .confirm(getString(R.string.gesture_done)) {
                            sheet.close()
                            if (pick != ThemeRes.glassBg(this@ThemeActivity)) { ThemeRes.setGlassBg(this@ThemeActivity, pick); ThemeRes.recreateFaded(this@ThemeActivity) }
                        }
                        .cancel(getString(R.string.dialog_cancel)) { sheet.close() }
                    refresh()
                    sheet.show()
                }
                SettingRowFactory.subtitle(this@ThemeActivity, bgRow).text = getString(current.nameRes)
                if (childCount == 0) addView(bgRow) else SettingRowFactory.addRow(this, bgRow)
            }
            if (childCount == 0) visibility = View.GONE
        })

        // --- Accent of the applied built-in preset ---
        val active = ThemeRes.palette(this)
        // Hidden under wallpaper colours: the wallpaper sets the accent.
        if (mode == 1 && active.builtIn) {
            val accentCard = SettingRowFactory.card(this).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f)
            }
            val swatch = ColorPickerView.Swatch(this, active.accent, p.outline)
            val chevron = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
                setImageDrawable(ThemeRes.tint(
                    this@ThemeActivity, if (accentOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, p.accent
                ))
            }
            lateinit var sub: TextView
            var picker: ColorPickerView? = null
            fun newPicker() = ColorPickerView(
                this, active.accent, "accent:${active.id}",
                onChange = { c ->
                    swatch.color = c
                    sub.text = getString(R.string.theme_accent_sub, active.name, ColorPickerView.hex(c))
                    activeTile?.palette = active.copy(accent = c)
                },
                onCommit = { c, v ->
                    Haptics.commit(v)
                    PaletteStore.setAccentOverride(this, active.id, c)
                    // The whole app takes the new accent; this screen rebuilds with the picker open.
                    v.post { ThemeRes.recreateFaded(this) }
                }
            ).apply { setPadding(dp(16f), dp(4f), dp(16f), dp(16f)) }
            val row = SettingRowFactory.build(
                this, 0, R.string.theme_accent, 0, chevron, leading = swatch, minHeightDp = 54f
            ) {
                // Unfolds or folds in place; the rest of the screen is not rebuilt.
                accentOpen = !accentOpen
                chevron.setImageDrawable(ThemeRes.tint(
                    this@ThemeActivity, if (accentOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, p.accent
                ))
                if (accentOpen) {
                    val pk = newPicker().also { picker = it }
                    accentCard.background = ThemeRes.card(this@ThemeActivity, fill = p.expanded)
                    accentCard.addView(pk)
                    Motion.slide(pk, open = true)
                } else picker?.let { pk ->
                    picker = null
                    Motion.slide(pk, open = false) {
                        accentCard.removeView(pk)
                        accentCard.background = ThemeRes.card(this@ThemeActivity)
                    }
                }
            }
            sub = SettingRowFactory.subtitle(this, row)
            sub.text = getString(R.string.theme_accent_sub, active.name, ColorPickerView.hex(active.accent))
            accentCard.addView(row)
            if (accentOpen) {
                picker = newPicker()
                accentCard.background = ThemeRes.card(this, fill = p.expanded)
                accentCard.addView(picker)
            }
            root.addView(accentCard)
        }

        if (mode == 2) {
            // --- Custom · N of 3 ---
            val custom = PaletteStore.custom(this)
            root.addView(SettingRowFactory.sectionLabel(this, R.string.theme_custom).apply {
                text = getString(R.string.theme_custom, custom.size, PaletteStore.MAX_CUSTOM)
                setPadding(dp(4f), dp(18f), 0, dp(9f))
            })
            val card = SettingRowFactory.splitList(this)
            // Rows that came or went while the editor was open grow in or fold away (never on the first build).
            val before = shownCustom
            shownCustom = custom
            fun presetRow(preset: Palette): View {
                val pencil = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
                    setPadding(dp(11f), dp(11f), 0, dp(11f))
                    setImageDrawable(ThemeRes.tint(this@ThemeActivity, R.drawable.ic_pencil, p.accent))
                    background = ThemeRes.ripple(this@ThemeActivity)
                    contentDescription = getString(R.string.theme_edit)
                    setOnClickListener { edit(preset.id) }
                }
                val row = SettingRowFactory.build(
                    this, 0, 0, 0, pencil, leading = SwatchGrid(this, preset), minHeightDp = 62f
                ) { apply(preset.id, card) }
                row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).text = preset.name
                return row
            }
            custom.forEach { preset ->
                val row = presetRow(preset)
                // Active: the selection border, like the built-in tiles.
                if (preset.id == activeId) { selTarget = row; selRadius = SettingRowFactory.SPLIT_RADIUS }
                SettingRowFactory.addSplit(card, row)
                if (before != null && before.none { it.id == preset.id }) Motion.slide(row.parent as View, open = true)
            }
            before?.forEachIndexed { i, old ->
                if (custom.none { it.id == old.id }) {
                    val ghost = presetRow(old).apply { isEnabled = false; isClickable = false }
                    SettingRowFactory.addSplit(card, ghost)
                    val gc = ghost.parent as View
                    card.removeView(gc)
                    card.addView(gc, minOf(i, card.childCount))
                    Motion.slide(gc, open = false) { card.removeView(gc) }
                }
            }
            val left = PaletteStore.MAX_CUSTOM - custom.size
            val newRow = SettingRowFactory.build(this, R.drawable.ic_plus, R.string.theme_new, 0, null) { createPreset() }
            SettingRowFactory.subtitle(this, newRow).text = when (left) {
                0 -> getString(R.string.theme_new_full)
                1 -> getString(R.string.theme_new_sub_one)
                else -> getString(R.string.theme_new_sub, left)
            }
            if (left == 0) { newRow.isEnabled = false; newRow.alpha = 0.35f }
            SettingRowFactory.addSplit(card, newRow)
            root.addView(card)

        }

        if (mode == 2) root.addView(TextView(this).apply {
            setText(R.string.theme_footer)
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(dp(4f), dp(14f), dp(4f), 0)
        })
        selection.moveTo(selTarget, p.accent, selRadius)
    }

    /** "Starts from the current theme": a copy of the active preset's colours, then its editor. */
    private fun createPreset() {
        val used = PaletteStore.custom(this).map { it.name }.toSet()
        val name = (1..9).map { getString(R.string.theme_new_name, it) }.first { it !in used }
        val preset = ThemeRes.palette(this).copy(id = "c${System.currentTimeMillis()}", name = name, builtIn = false)
        if (PaletteStore.saveCustom(this, preset)) edit(preset.id)
    }

    private fun edit(id: String) {
        startActivity(Intent(this, PresetEditActivity::class.java).putExtra(PresetEditActivity.EXTRA_ID, id))
    }

    private companion object {
        const val KEY_ACCENT_OPEN = "accentOpen"
    }

    /**
     * 3x2 grid of a preset's six colours, 42dp wide, each dot outlined so dark dots stay visible.
     * Dot style: six 7-cell discs a cell apart ([DotArt.disc]).
     */
    private class SwatchGrid(context: Context, private val preset: Palette) : View(context) {
        private val nothing = ThemeRes.nothing(context)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ThemeRes.dp(context, 1f).toFloat()
            color = ThemeRes.palette(context).outline
        }

        override fun onMeasure(w: Int, h: Int) = if (nothing) {
            val pitch = DotArt.pitchPx(context).toInt()
            setMeasuredDimension(pitch * (3 * DISC + 2), pitch * (2 * DISC + 1))
        } else setMeasuredDimension(ThemeRes.dp(context, 42f), ThemeRes.dp(context, 28f))

        override fun onDraw(c: Canvas) {
            if (nothing) {
                val bg = ThemeRes.palette(context).background
                DotArt.draw(context, c, width, height) { dc ->
                    preset.tokens.forEachIndexed { i, color ->
                        // No outline, except on a colour that would vanish into the screen.
                        val edge = if (Palette.contrast(color, bg) < 1.5) ring.color else color
                        DotArt.disc(context, dc, (i % 3) * (DISC + 1), (i / 3) * (DISC + 1), DISC, color, edge)
                    }
                }
                return
            }
            val cell = width / 3f
            val r = cell / 2 - ThemeRes.dp(context, 1.5f)
            preset.tokens.forEachIndexed { i, color ->
                val cx = cell * (i % 3) + cell / 2
                val cy = cell * (i / 3) + cell / 2
                fill.color = color
                c.drawCircle(cx, cy, r, fill)
                c.drawCircle(cx, cy, r, ring)
            }
        }

        private companion object { const val DISC = 7 }
    }
}
