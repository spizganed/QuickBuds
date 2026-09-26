package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R

/**
 * Home layout: move and hide each row of the home screen's sound settings card one by one
 * ([USER] 2026-09-26). Battery and noise control stay fixed at the top. Writes the prefs
 * MainActivity.layoutFeatureRows() reads; every change saves at once.
 */
class HomeLayoutActivity : Activity() {

    private lateinit var card: LinearLayout
    private val prefs get() = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.settings_layout_title))
        root.addView(SettingRowFactory.sectionLabel(this, R.string.layout_rows))
        card = SettingRowFactory.card(this)
        root.addView(card)
        root.addView(TextView(this).apply {
            setText(R.string.layout_footer)
            setTextColor(ThemeRes.color(this@HomeLayoutActivity, R.attr.appColorTextSecondary))
            textSize = 13f
            setPadding(ThemeRes.dp(this@HomeLayoutActivity, 4f), ThemeRes.dp(this@HomeLayoutActivity, 14f), 0, 0)
        })
        build()
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ThemeRes.color(this@HomeLayoutActivity, R.attr.appColorBg))
            addView(root)
        })
    }

    private fun build() {
        card.removeAllViews()
        val order = MainActivity.rowOrder(prefs, ROWS.keys.toList()).toMutableList()
        val hidden = prefs.getStringSet(MainActivity.KEY_ROW_HIDDEN, emptySet()).orEmpty()
        order.forEachIndexed { i, key ->
            val arrows = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(arrow(up = true, enabled = i > 0) { move(order, i, i - 1) })
                addView(arrow(up = false, enabled = i < order.size - 1) { move(order, i, i + 1) })
            }
            val shown = SettingRowFactory.buildSwitch(this, key !in hidden).apply {
                setOnCheckedChangeListener { _, on ->
                    val set = prefs.getStringSet(MainActivity.KEY_ROW_HIDDEN, emptySet()).orEmpty().toMutableSet()
                    if (on) set.remove(key) else set.add(key)
                    prefs.edit().putStringSet(MainActivity.KEY_ROW_HIDDEN, set).apply()
                }
            }
            val row = SettingRowFactory.build(this, 0, ROWS.getValue(key), 0, shown, leading = arrows) {
                shown.performClick()
            }
            SettingRowFactory.addRow(card, row)
        }
    }

    private fun arrow(up: Boolean, enabled: Boolean, onClick: () -> Unit) = ImageView(this).apply {
        val dp = { v: Float -> ThemeRes.dp(this@HomeLayoutActivity, v) }
        layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
        setPadding(dp(11f), dp(11f), dp(11f), dp(11f))
        setImageDrawable(ThemeRes.tint(this@HomeLayoutActivity, R.drawable.ic_chevron_down, ThemeRes.palette(context).accent))
        rotation = if (up) 180f else 0f
        contentDescription = getString(if (up) R.string.layout_move_up else R.string.layout_move_down)
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.3f
        if (enabled) {
            background = ThemeRes.ripple(this@HomeLayoutActivity)
            setOnClickListener { Haptics.commit(it); onClick() }
        }
    }

    private fun move(order: MutableList<String>, from: Int, to: Int) {
        order.add(to, order.removeAt(from))
        prefs.edit().putString(MainActivity.KEY_ROW_ORDER, order.joinToString(",")).apply()
        build()
    }

    companion object {
        /** Row key -> label, in MainActivity.buildFeatureRows() (default) order. */
        private val ROWS = linkedMapOf(
            "game" to R.string.row_game_title,
            "hires" to R.string.row_hires_title,
            "spatial" to R.string.row_spatial_title,
            "eq" to R.string.row_eq_title,
            "dual" to R.string.row_dual_title,
            "earbuds" to R.string.row_earbuds_title
        )
    }
}
