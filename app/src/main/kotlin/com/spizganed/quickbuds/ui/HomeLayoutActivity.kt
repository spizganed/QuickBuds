package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Bundle
import android.view.DragEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * Home layout ([USER] 2026-09-26): the main screen itself, in an edit mode. The battery and
 * noise control tiles are shown as they are (fixed, not interactive); each sound settings row can
 * be held and dragged to a new place, and has an eye button that shows or hides it (hidden rows
 * are greyed). Nothing is saved until the check; the X or Back discards the changes.
 *
 * Writes the prefs MainActivity.layoutFeatureRows() reads.
 */
class HomeLayoutActivity : Activity() {

    private lateinit var list: LinearLayout
    private val prefs get() = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)

    private lateinit var order: MutableList<String>
    private lateinit var hidden: MutableSet<String>
    private val rows = HashMap<String, View>()
    private val eyes = HashMap<String, ImageView>()

    /** Row being dragged, drawn faint in its current slot; null when no drag is running. */
    private var dragging: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val p = ThemeRes.palette(this)
        order = MainActivity.rowOrder(prefs, ROWS.keys.toList()).toMutableList()
        hidden = prefs.getStringSet(MainActivity.KEY_ROW_HIDDEN, emptySet()).orEmpty().toMutableSet()

        // Header: title, then Cancel and Apply in place of the chip / dev tools / cog.
        ThemeRes.screenPadding(findViewById(R.id.mainLayout))
        findViewById<TextView>(R.id.deviceNameText).setText(R.string.layout_title)
        findViewById<View>(R.id.connPill).visibility = View.GONE
        fun headerButton(id: Int, icon: Int, desc: Int, onClick: () -> Unit) = findViewById<ImageButton>(id).apply {
            background = ThemeRes.ripple(this@HomeLayoutActivity, ThemeRes.iconButton(this@HomeLayoutActivity))
            setImageDrawable(ThemeRes.tint(this@HomeLayoutActivity, icon, p.accent))
            contentDescription = getString(desc)
            setOnClickListener { onClick() }
        }
        headerButton(R.id.btnDevTools, R.drawable.ic_close, R.string.layout_cancel) { finish() }
        headerButton(R.id.btnSettings, R.drawable.ic_check, R.string.layout_done) { apply(findViewById(R.id.btnSettings)) }

        // The fixed tiles, as the main screen shows them now, but inert.
        val state = WidgetStateStore.read(this)
        findViewById<View>(R.id.batteryCard).background = ThemeRes.card(this)
        findViewById<FrameLayout>(R.id.statusSlot).addView(BudsStatusView(this).apply {
            connected = state.connected
            setState(state.leftBattery, state.caseBattery, state.rightBattery, state.leftStatus, state.rightStatus)
        })
        findViewById<FrameLayout>(R.id.ancSlot).addView(AncSegmentedView(
            this, MainActivity.ANC_SEGMENTS.map { getString(it.second) }, MainActivity.ANC_SEGMENTS.map { it.third }
        ).apply { isEnabled = false })

        // Hint above the tiles.
        findViewById<LinearLayout>(R.id.tiles).addView(TextView(this).apply {
            setText(R.string.layout_hint)
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(ThemeRes.dp(this@HomeLayoutActivity, 4f), 0, 0, ThemeRes.dp(this@HomeLayoutActivity, 12f))
        }, 0)

        list = findViewById(R.id.featureList)
        list.background = ThemeRes.card(this)
        list.clipToOutline = true
        ROWS.forEach { (key, def) -> rows[key] = previewRow(key, def) }
        list.setOnDragListener { _, e -> onDrag(e) }
        render()
    }

    /** A row as the main screen draws it, with the eye button in the trailing slot. */
    private fun previewRow(key: String, def: Triple<Int, Int, Int>): View {
        val eye = ImageView(this).apply {
            val pad = ThemeRes.dp(this@HomeLayoutActivity, 11f)
            layoutParams = LinearLayout.LayoutParams(ThemeRes.dp(this@HomeLayoutActivity, 44f), ThemeRes.dp(this@HomeLayoutActivity, 44f))
            setPadding(pad, pad, 0, pad)
            background = ThemeRes.ripple(this@HomeLayoutActivity)
            setOnClickListener {
                if (!hidden.remove(key)) hidden.add(key)
                Haptics.commit(it)
                render()
            }
        }
        eyes[key] = eye
        return SettingRowFactory.build(this, def.first, def.second, def.third, eye).apply {
            setOnLongClickListener { v ->
                dragging = key
                v.startDragAndDrop(null, View.DragShadowBuilder(v), key, 0)
                render()
                true
            }
        }
    }

    private fun render() {
        val p = ThemeRes.palette(this)
        list.removeAllViews()
        for (key in order) {
            val row = rows.getValue(key)
            val isHidden = key in hidden
            SettingRowFactory.addRow(list, row)
            row.alpha = when {
                key == dragging -> 0.15f
                isHidden -> 0.35f
                else -> 1f
            }
            eyes.getValue(key).apply {
                setImageDrawable(ThemeRes.tint(this@HomeLayoutActivity, if (isHidden) R.drawable.ic_eye_off else R.drawable.ic_eye, p.accent))
                contentDescription = getString(if (isHidden) R.string.layout_show else R.string.layout_hide)
            }
        }
    }

    /** Moves the dragged row to the slot under the finger, live, so the preview shows the result. */
    private fun onDrag(e: DragEvent): Boolean {
        val key = dragging ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                val target = order.indexOfFirst { k -> rows.getValue(k).let { e.y < it.top + it.height / 2f } }
                    .let { if (it < 0) order.size else it }
                val from = order.indexOf(key)
                val to = if (target > from) target - 1 else target
                if (to != from && to in order.indices) {
                    order.removeAt(from)
                    order.add(to, key)
                    render()
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                dragging = null
                Haptics.commit(list)
                render()
            }
        }
        return true
    }

    private fun apply(v: View) {
        prefs.edit()
            .putString(MainActivity.KEY_ROW_ORDER, order.joinToString(","))
            .putStringSet(MainActivity.KEY_ROW_HIDDEN, hidden.toSet())
            .apply()
        Haptics.commit(v)
        finish()
    }

    companion object {
        /** Row key -> (icon, title, subtitle), matching MainActivity.buildFeatureRows(), in default order. */
        private val ROWS = linkedMapOf(
            "game" to Triple(R.drawable.ic_bolt, R.string.row_game_title, R.string.row_game_sub),
            "hires" to Triple(R.drawable.ic_hires, R.string.row_hires_title, R.string.row_hires_sub),
            "spatial" to Triple(R.drawable.ic_spatial, R.string.row_spatial_title, R.string.row_spatial_sub),
            "eq" to Triple(R.drawable.ic_equalizer, R.string.row_eq_title, R.string.row_eq_sub),
            "dual" to Triple(R.drawable.ic_devices, R.string.row_dual_title, R.string.row_dual_sub),
            "earbuds" to Triple(R.drawable.ic_bud_left, R.string.row_earbuds_title, R.string.row_earbuds_sub)
        )
    }
}
