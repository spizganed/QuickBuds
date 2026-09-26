package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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
 * Theme & colors (design/SPEC.md 3.7): the three built-in presets as preview tiles, then the
 * custom presets (at most 3) as rows, then "New preset". Tapping a tile or a row applies that
 * preset at once; the pencil opens [PresetEditActivity].
 */
class ThemeActivity : Activity() {

    private lateinit var root: LinearLayout

    /** The built-in accent picker is open; kept across the recreate() a commit triggers. */
    private var accentOpen = false

    /** The active built-in tile, repainted live while the accent picker drags. */
    private var activeTile: PalettePreviewView? = null

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
        recreate()
    }

    private fun build() {
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val p = ThemeRes.palette(this)
        val activeId = PaletteStore.activeId(this)
        root.removeAllViews()
        activeTile = null
        root.addView(SettingRowFactory.title(this, R.string.theme_title))

        // --- Built-in: 3-column grid of preview tiles ---
        root.addView(SettingRowFactory.sectionLabel(this, R.string.theme_builtin))
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        PaletteStore.builtInIds().forEachIndexed { i, id ->
            val preset = PaletteStore.builtIn(this, id)
            val isActive = id == activeId
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i > 0) marginStart = dp(10f) }
                setOnClickListener { apply(id, it) }
                contentDescription = preset.name
            }
            val frame = FrameLayout(this).apply { clipChildren = false }
            frame.addView(PalettePreviewView(this, detailed = false).apply {
                palette = preset
                active = isActive
                if (isActive) activeTile = this
            })
            // Active: a 24dp accent check badge overlapping the top-right corner.
            if (isActive) frame.addView(ImageView(this).apply {
                setImageDrawable(ThemeRes.tint(this@ThemeActivity, R.drawable.ic_check, p.onAccent))
                background = ThemeRes.shape(this@ThemeActivity, p.accent, null, 12f)
                setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
                layoutParams = FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.TOP or Gravity.END)
                translationX = dp(6f).toFloat()
                translationY = -dp(6f).toFloat()
            })
            column.addView(frame)
            column.addView(TextView(this).apply {
                text = preset.name
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(8f), 0, 0)
                setTextColor(if (isActive) p.text else p.textSecondary)
                if (isActive) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            grid.addView(column)
        }
        root.addView(grid)

        // --- Accent of the applied built-in preset ([USER] 2026-09-26) ---
        val active = ThemeRes.palette(this)
        if (active.builtIn) {
            val accentCard = SettingRowFactory.card(this).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f)
            }
            val swatch = View(this).apply {
                background = ThemeRes.shape(this@ThemeActivity, active.accent, p.outline, 15f)
                layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f))
            }
            val chevron = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
                setImageDrawable(ThemeRes.tint(
                    this@ThemeActivity, if (accentOpen) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, p.accent
                ))
            }
            val row = SettingRowFactory.build(
                this, 0, R.string.theme_accent, 0, chevron, leading = swatch, minHeightDp = 62f
            ) { accentOpen = !accentOpen; build() }
            val sub = SettingRowFactory.subtitle(this, row)
            sub.text = getString(R.string.theme_accent_sub, active.name, ColorPickerView.hex(active.accent))
            accentCard.addView(row)
            if (accentOpen) accentCard.addView(ColorPickerView(
                this, active.accent,
                onChange = { c ->
                    swatch.background = ThemeRes.shape(this, c, p.outline, 15f)
                    sub.text = getString(R.string.theme_accent_sub, active.name, ColorPickerView.hex(c))
                    activeTile?.palette = active.copy(accent = c)
                },
                onCommit = { c, v ->
                    Haptics.commit(v)
                    PaletteStore.setAccentOverride(this, active.id, c)
                    // The whole app takes the new accent; this screen rebuilds with the picker open.
                    v.post { recreate() }
                }
            ).apply {
                setPadding(dp(16f), dp(4f), dp(16f), dp(16f))
                accentCard.background = ThemeRes.card(this@ThemeActivity).apply { setColor(p.expanded) }
            })
            root.addView(accentCard)
        }

        // --- Custom · N of 3 ---
        val custom = PaletteStore.custom(this)
        root.addView(TextView(this).apply {
            text = getString(R.string.theme_custom, custom.size, PaletteStore.MAX_CUSTOM)
            setTextColor(p.textSecondary)
            textSize = 14f
            setPadding(dp(4f), dp(18f), 0, dp(9f))
        })
        val card = SettingRowFactory.card(this)
        custom.forEach { preset ->
            val pencil = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
                setPadding(dp(11f), dp(11f), 0, dp(11f))
                setImageDrawable(ThemeRes.tint(this@ThemeActivity, R.drawable.ic_pencil, p.accent))
                background = ThemeRes.ripple(this@ThemeActivity)
                contentDescription = getString(R.string.theme_edit)
                setOnClickListener { edit(preset.id) }
            }
            val row = SettingRowFactory.build(
                this, 0, 0, 0, pencil, leading = SwatchGrid(this, preset), minHeightDp = 72f
            ) { apply(preset.id, card) }
            row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).text = preset.name
            SettingRowFactory.addRow(card, row)
        }
        val left = PaletteStore.MAX_CUSTOM - custom.size
        val newRow = SettingRowFactory.build(this, R.drawable.ic_plus, R.string.theme_new, 0, null) { createPreset() }
        SettingRowFactory.subtitle(this, newRow).text = when (left) {
            0 -> getString(R.string.theme_new_full)
            1 -> getString(R.string.theme_new_sub_one)
            else -> getString(R.string.theme_new_sub, left)
        }
        if (left == 0) { newRow.isEnabled = false; newRow.alpha = 0.35f }
        SettingRowFactory.addRow(card, newRow)
        root.addView(card)

        root.addView(TextView(this).apply {
            setText(R.string.theme_footer)
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(dp(4f), dp(14f), dp(4f), 0)
        })
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

    /** 3x2 grid of a preset's six colours, 42dp wide, each dot outlined so dark dots stay visible. */
    private class SwatchGrid(context: Context, private val preset: Palette) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = ThemeRes.dp(context, 1f).toFloat()
            color = ThemeRes.palette(context).outline
        }

        override fun onMeasure(w: Int, h: Int) =
            setMeasuredDimension(ThemeRes.dp(context, 42f), ThemeRes.dp(context, 28f))

        override fun onDraw(c: Canvas) {
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
    }
}
