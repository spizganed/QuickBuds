package com.spizganed.quickbuds.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.spizganed.quickbuds.R
import java.util.Locale

/**
 * Inline colour picker (design/SPEC.md 3.8, plus saturation and brightness so white, black and
 * greys are reachable, [USER] 2026-09-26): hue, saturation and brightness sliders, a hex field and
 * the five quick swatches. [onChange] fires while a slider drags; [onCommit] on finger lift, hex
 * done / focus loss, or a swatch tap. Used by Edit preset and by the built-in accent picker.
 *
 * Under the swatches, the last [MAX_RECENT] committed colours from any picker ([remember]).
 * [target] names what this picker edits: a new commit to the same target replaces its last recent
 * colour instead of adding one, so dragging three sliders leaves one entry, not three.
 */
class ColorPickerView(
    context: Context,
    initial: Int,
    private val target: String,
    private val onChange: (Int) -> Unit,
    private val onCommit: (Int, View) -> Unit
) : LinearLayout(context) {

    private val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
    private val sliders: List<ColorSliderView>
    private fun dp(v: Float) = ThemeRes.dp(context, v)

    init {
        orientation = VERTICAL
        val p = ThemeRes.palette(context)
        val labels = intArrayOf(R.string.preset_hue, R.string.preset_saturation, R.string.preset_brightness)
        sliders = (0..2).map { ch ->
            ColorSliderView(context, ch).apply {
                hsv = this@ColorPickerView.hsv
                contentDescription = context.getString(labels[ch])
                onChange = { v -> this@ColorPickerView.hsv[ch] = v; sync(); onChange(color()) }
                onRelease = { v -> this@ColorPickerView.hsv[ch] = v; sync(); commit(color(), this) }
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { if (ch > 0) topMargin = dp(6f) }
            }
        }
        sliders.forEach { addView(it) }

        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, 0)
        }
        bottom.addView(EditText(context).apply {
            setText(hex(initial))
            setTextColor(p.text)
            textSize = 15f
            fontFeatureSettings = "tnum"
            typeface = ThemeRes.medium(context)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(7))
            imeOptions = EditorInfo.IME_ACTION_DONE
            contentDescription = context.getString(R.string.preset_hex)
            background = ThemeRes.card(context, 14f).apply { setColor(p.background) }
            setPadding(dp(14f), 0, dp(14f), 0)
            layoutParams = LayoutParams(dp(112f), dp(44f))
            fun apply(v: TextView) {
                val parsed = parseHex(v.text.toString())
                if (parsed == null) v.text = hex(color()) else if (parsed != color()) commit(parsed, v)
            }
            setOnEditorActionListener { v, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(v.windowToken, 0)
                    apply(v)
                }
                false
            }
            setOnFocusChangeListener { v, focused -> if (!focused) apply(v as TextView) }
        })
        bottom.addView(View(context), LayoutParams(0, 1, 1f))
        swatches(bottom, listOf(R.color.swatch_red, R.color.swatch_orange, R.color.swatch_green,
            R.color.swatch_blue, R.color.swatch_purple).map { context.getColor(it) }, initial)
        addView(bottom)

        val recent = recent(context)
        if (recent.isNotEmpty()) addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
            addView(TextView(context).apply {
                setText(R.string.preset_recent)
                setTextColor(p.textSecondary)
                textSize = 13f
            })
            addView(View(context), LayoutParams(0, 1, 1f))
            swatches(this, recent, initial)
        })
    }

    private fun swatches(row: LinearLayout, colors: List<Int>, initial: Int) {
        val p = ThemeRes.palette(context)
        colors.forEachIndexed { k, c ->
            row.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    // The outline keeps a swatch in the card's own colour visible.
                    setStroke(dp(if (c == initial) 2f else 1f), if (c == initial) p.text else p.outline)
                }
                contentDescription = hex(c)
                layoutParams = LayoutParams(dp(30f), dp(30f)).apply { if (k > 0) marginStart = dp(8f) }
                setOnClickListener { commit(c, it) }
            })
        }
    }

    private fun commit(c: Int, v: View) {
        remember(context, target, c)
        onCommit(c, v)
    }

    private fun color() = Color.HSVToColor(hsv)

    /** Every track depends on the other two channels, so all three repaint on any change. */
    private fun sync() = sliders.forEach { it.hsv = hsv }

    companion object {
        private const val KEY_RECENT = "recentColors"
        private const val KEY_RECENT_TARGET = "recentColorsTarget"
        const val MAX_RECENT = 5

        private fun prefs(context: Context) =
            context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

        fun recent(context: Context): List<Int> =
            prefs(context).getString(KEY_RECENT, "")!!.split(',').mapNotNull { parseHex(it) }

        /** Most recent first, no duplicates; a repeat commit to [target] replaces its own entry. */
        fun remember(context: Context, target: String, color: Int) {
            val prefs = prefs(context)
            val list = recent(context).toMutableList()
            if (prefs.getString(KEY_RECENT_TARGET, null) == target && list.isNotEmpty()) list.removeAt(0)
            list.remove(color)
            list.add(0, color)
            prefs.edit()
                .putString(KEY_RECENT, list.take(MAX_RECENT).joinToString(",") { hex(it) })
                .putString(KEY_RECENT_TARGET, target)
                .apply()
        }

        fun hex(color: Int) = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

        fun parseHex(s: String): Int? {
            val t = s.trim().removePrefix("#")
            if (t.length != 6) return null
            return t.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
        }
    }
}
