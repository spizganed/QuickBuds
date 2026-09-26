package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import java.util.Locale

/**
 * Edit preset (design/SPEC.md 3.8). The screen is drawn in the ACTIVE theme; only the preview is
 * drawn in the preset's colours. Every change applies to the preview live and saves on commit
 * (finger lift, hex done, swatch tap, name done / focus loss), the same pattern as the EQ.
 */
class PresetEditActivity : Activity() {

    private lateinit var preset: Palette
    private lateinit var preview: PalettePreviewView
    private lateinit var colorsCard: LinearLayout

    /** Index of the colour row whose picker is open, -1 for none. Only one at a time. */
    private var expanded = -1

    // Views of the expanded row, updated in place while the hue slider drags.
    private var liveSwatch: View? = null
    private var liveHex: TextView? = null

    private val p get() = ThemeRes.palette(this)
    private fun dp(v: Float) = ThemeRes.dp(this, v)

    private val tokenNames = intArrayOf(
        R.string.token_background, R.string.token_card, R.string.token_accent,
        R.string.token_text, R.string.token_text_secondary, R.string.token_outline
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_ID)
        preset = PaletteStore.custom(this).firstOrNull { it.id == id } ?: run { finish(); return }

        val root = SettingRowFactory.screen(this)

        // Header: title, then Duplicate and Delete.
        val canDuplicate = PaletteStore.custom(this).size < PaletteStore.MAX_CUSTOM
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(SettingRowFactory.title(this@PresetEditActivity, R.string.preset_title).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(SettingRowFactory.iconButton(this@PresetEditActivity, R.drawable.ic_copy, R.string.preset_duplicate) {
                duplicate()
            }.apply { isEnabled = canDuplicate; alpha = if (canDuplicate) 1f else 0.35f })
            addView(SettingRowFactory.iconButton(this@PresetEditActivity, R.drawable.ic_delete, R.string.preset_delete) {
                confirmDelete()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = dp(10f) })
        })

        // Name: saves on IME done or focus loss.
        root.addView(SettingRowFactory.sectionLabel(this, R.string.preset_name))
        root.addView(EditText(this).apply {
            setText(preset.name)
            setTextColor(p.text)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(24))
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = ThemeRes.card(this@PresetEditActivity, 16f)
            setPadding(dp(16f), 0, dp(16f), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f))
            setOnEditorActionListener { v, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { v.clearFocus(); saveName(v.text.toString()); hideKeyboard(v) }
                false
            }
            setOnFocusChangeListener { v, focused -> if (!focused) saveName((v as EditText).text.toString()) }
        })

        root.addView(SettingRowFactory.sectionLabel(this, R.string.preset_preview))
        preview = PalettePreviewView(this, detailed = true).apply { palette = preset }
        root.addView(preview)

        root.addView(SettingRowFactory.sectionLabel(this, R.string.preset_colors))
        colorsCard = SettingRowFactory.card(this)
        root.addView(colorsCard)
        buildColors()

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
    }

    // ---------------------------------------------------------------- colours

    private fun buildColors() {
        colorsCard.removeAllViews()
        liveSwatch = null
        liveHex = null
        for (i in 0 until 6) {
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            group.addView(colorRow(i))
            if (i == expanded) {
                group.background = ColorDrawable(p.expanded)
                group.addView(picker(i))
            }
            SettingRowFactory.addRow(colorsCard, group)
        }
    }

    private fun swatchDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1f), p.outline)
    }

    private fun hex(color: Int) = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

    private fun colorRow(i: Int): View {
        val color = preset.tokens[i]
        val swatch = View(this).apply {
            background = swatchDrawable(color)
            layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f))
        }
        val hexView = TextView(this).apply {
            text = hex(color)
            setTextColor(p.textSecondary)
            textSize = 14f
            fontFeatureSettings = "tnum"
            setPadding(dp(8f), 0, 0, 0)
        }
        val chevron = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
            setImageDrawable(ThemeRes.tint(
                this@PresetEditActivity,
                if (i == expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, p.accent
            ))
        }
        val row = SettingRowFactory.build(
            this, 0, tokenNames[i], 0, chevron, value = hexView, minHeightDp = 62f, leading = swatch
        ) {
            expanded = if (expanded == i) -1 else i
            buildColors()
        }
        if (i == expanded) { row.background = null; liveSwatch = swatch; liveHex = hexView }

        warning(i)?.let { msg ->
            val column = row.findViewWithTag<LinearLayout>(SettingRowFactory.TEXT_TAG)
            column.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2f), 0, 0)
                addView(ImageView(this@PresetEditActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(14f), dp(14f)).apply { marginEnd = dp(6f) }
                    setImageDrawable(ThemeRes.tint(this@PresetEditActivity, R.drawable.ic_warning, p.accent))
                })
                addView(TextView(this@PresetEditActivity).apply {
                    text = msg
                    setTextColor(p.textSecondary)
                    textSize = 12f
                })
            })
        }
        return row
    }

    /**
     * SPEC section 1 contrast warning (WCAG 2): text and secondary text below 4.5:1 on card or
     * background, accent below 3:1 on background. Reports the worse surface. Warning only.
     */
    private fun warning(i: Int): String? {
        val (ratio, surface) = when (i) {
            3, 4 -> {
                val c = preset.tokens[i]
                val onCard = Palette.contrast(c, preset.card)
                val onBg = Palette.contrast(c, preset.background)
                if (onCard <= onBg) onCard to R.string.token_card else onBg to R.string.token_background
            }
            2 -> Palette.contrast(preset.accent, preset.background) to R.string.token_background
            else -> return null
        }
        val min = if (i == 2) 3.0 else 4.5
        if (ratio >= min) return null
        return getString(R.string.contrast_warning, String.format(Locale.US, "%.1f", ratio), getString(surface))
    }

    private fun picker(i: Int): View {
        val color = preset.tokens[i]
        val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
        // A grey has no hue to move; give the slider enough saturation to be visible.
        val sat = if (hsv[1] < 0.1f) 0.5f else hsv[1]
        val colorAt = { hue: Float -> Color.HSVToColor(floatArrayOf(hue, sat, hsv[2])) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(16f))
        }
        column.addView(HueSliderView(this).apply {
            hue = hsv[0]
            thumbColor = color
            contentDescription = getString(R.string.preset_hue)
            onChange = { h ->
                val c = colorAt(h)
                thumbColor = c
                preview.palette = preset.withToken(i, c)
                liveSwatch?.background = swatchDrawable(c)
                liveHex?.text = hex(c)
            }
            onRelease = { h -> commit(i, colorAt(h), this) }
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, 0)
        }
        bottom.addView(EditText(this).apply {
            setText(hex(color))
            setTextColor(p.text)
            textSize = 15f
            fontFeatureSettings = "tnum"
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(7))
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = getString(R.string.preset_hex)
            background = ThemeRes.card(this@PresetEditActivity, 14f).apply { setColor(p.background) }
            setPadding(dp(14f), 0, dp(14f), 0)
            layoutParams = LinearLayout.LayoutParams(dp(112f), dp(44f))
            fun apply(v: TextView) {
                val parsed = parseHex(v.text.toString())
                if (parsed == null) v.text = hex(preset.tokens[i]) else if (parsed != preset.tokens[i]) commit(i, parsed, v)
            }
            setOnEditorActionListener { v, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { hideKeyboard(v); apply(v) }
                false
            }
            setOnFocusChangeListener { v, focused -> if (!focused) apply(v as TextView) }
        })
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        listOf(R.color.swatch_red, R.color.swatch_orange, R.color.swatch_green, R.color.swatch_blue, R.color.swatch_purple)
            .forEachIndexed { k, res ->
                val c = getColor(res)
                bottom.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        if (c == color) setStroke(dp(2f), p.text)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { if (k > 0) marginStart = dp(8f) }
                    setOnClickListener { commit(i, c, it) }
                })
            }
        column.addView(bottom)
        return column
    }

    private fun parseHex(s: String): Int? {
        val t = s.trim().removePrefix("#")
        if (t.length != 6) return null
        return t.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
    }

    /** One committed colour change: save, repaint the preview and the rows (warnings included). */
    private fun commit(i: Int, color: Int, v: View) {
        preset = preset.withToken(i, color)
        PaletteStore.saveCustom(this, preset)
        preview.palette = preset
        Haptics.commit(v)
        v.post { buildColors() }
    }

    private fun saveName(raw: String) {
        val name = raw.trim()
        if (name.isEmpty() || name == preset.name) return
        preset = preset.copy(name = name)
        PaletteStore.saveCustom(this, preset)
    }

    // ---------------------------------------------------------------- header actions

    private fun duplicate() {
        val copy = preset.copy(
            id = "c${System.currentTimeMillis()}",
            name = getString(R.string.theme_copy_name, preset.name).take(24)
        )
        if (!PaletteStore.saveCustom(this, copy)) return
        startActivity(Intent(this, PresetEditActivity::class.java).putExtra(EXTRA_ID, copy.id))
        finish()
    }

    private fun confirmDelete() {
        val isActive = PaletteStore.activeId(this) == preset.id
        ConfirmDialog.show(
            this, getString(R.string.preset_delete_title, preset.name),
            if (isActive) getString(R.string.preset_delete_active) else null,
            getString(R.string.preset_delete)
        ) {
            PaletteStore.deleteCustom(this, preset.id)
            finish()
        }
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
    }

    companion object {
        const val EXTRA_ID = "presetId"
    }
}
