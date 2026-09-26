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
 * Home layout (SPEC section 4): reorder the home tiles and hide the ones that may be hidden.
 * Writes the prefs MainActivity.applyTileLayout() reads; every change saves at once.
 */
class HomeLayoutActivity : Activity() {

    private lateinit var card: LinearLayout
    private val prefs get() = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.settings_layout_title))
        root.addView(SettingRowFactory.sectionLabel(this, R.string.layout_tiles))
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

    /** Saved order, completed with any tile it does not name, in the XML order. */
    private fun order(): MutableList<String> {
        val saved = prefs.getString(MainActivity.KEY_TILE_ORDER, null)?.split(',').orEmpty().filter { it in TILES }
        return (saved + TILES.keys.filter { it !in saved }).distinct().toMutableList()
    }

    private fun build() {
        card.removeAllViews()
        val order = order()
        val hidden = prefs.getStringSet(MainActivity.KEY_TILE_HIDDEN, emptySet()).orEmpty()
        order.forEachIndexed { i, name ->
            val locked = name in MainActivity.LOCKED_TILES
            val arrows = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(arrow(up = true, enabled = i > 0) { move(order, i, i - 1) })
                addView(arrow(up = false, enabled = i < order.size - 1) { move(order, i, i + 1) })
            }
            val trailing = if (locked) null else SettingRowFactory.buildSwitch(this, name !in hidden).apply {
                setOnCheckedChangeListener { _, on ->
                    val set = prefs.getStringSet(MainActivity.KEY_TILE_HIDDEN, emptySet()).orEmpty().toMutableSet()
                    if (on) set.remove(name) else set.add(name)
                    prefs.edit().putStringSet(MainActivity.KEY_TILE_HIDDEN, set).apply()
                }
            }
            val row = SettingRowFactory.build(
                this, 0, TILES.getValue(name), if (locked) R.string.layout_locked else 0,
                trailing, leading = arrows
            ) { trailing?.performClick() }
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
        prefs.edit().putString(MainActivity.KEY_TILE_ORDER, order.joinToString(",")).apply()
        build()
    }

    companion object {
        /** Tile root id name -> label, in the XML (default) order. */
        private val TILES = linkedMapOf(
            "batteryCard" to R.string.layout_tile_battery,
            "ancRow" to R.string.anc_section,
            "featureList" to R.string.layout_tile_settings
        )
    }
}
