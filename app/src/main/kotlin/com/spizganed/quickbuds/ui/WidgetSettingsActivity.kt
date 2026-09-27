package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.widget.WidgetSettings

/**
 * Widget settings (design/widgets/WIDGETS.md 5, w6): what the mode button does, which modes it
 * uses and in what order (dragged by the handle, as in [HomeLayoutActivity]), and two toggles.
 * Every change is saved at once and repaints the placed widgets.
 */
class WidgetSettingsActivity : Activity() {

    private lateinit var modesCard: LinearLayout
    private lateinit var order: MutableList<WidgetSettings.Mode>
    private lateinit var on: MutableSet<String>
    private val rows = HashMap<String, View>()
    private val boxes = HashMap<String, CheckBox>()
    private var dragging: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        val p = ThemeRes.palette(this)
        order = WidgetSettings.order(this).toMutableList()
        on = WidgetSettings.enabledKeys(this).toMutableSet()

        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.widget_settings_title))
        fun hint(res: Int) = TextView(this).apply {
            setText(res)
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(ThemeRes.dp(this@WidgetSettingsActivity, 4f), ThemeRes.dp(this@WidgetSettingsActivity, 8f), 0, 0)
        }

        root.addView(SettingRowFactory.sectionLabel(this, R.string.widget_tap_title))
        root.addView(AncSegmentedView(
            this, listOf(getString(R.string.widget_tap_next), getString(R.string.widget_tap_list)),
            listOf(R.drawable.ic_hint_cycle, R.drawable.ic_grid)
        ).apply {
            selected = if (WidgetSettings.tapOpensList(this@WidgetSettingsActivity)) 1 else 0
            onSegmentTapped = { i ->
                selected = i
                WidgetSettings.setTapOpensList(this@WidgetSettingsActivity, i == 1)
            }
        })
        root.addView(hint(R.string.widget_tap_hint))

        root.addView(SettingRowFactory.sectionLabel(this, R.string.widget_modes_title))
        modesCard = SettingRowFactory.card(this)
        order.forEach { rows[it.key] = modeRow(it, p) }
        modesCard.setOnDragListener { _, e -> onDrag(e) }
        root.addView(modesCard)
        root.addView(hint(R.string.widget_modes_hint))

        root.addView(SettingRowFactory.sectionLabel(this, R.string.widget_pages_title))
        root.addView(AncSegmentedView(
            this, listOf(getString(R.string.widget_pages_button), getString(R.string.widget_pages_double)),
            listOf(R.drawable.ic_swap_page, R.drawable.ic_tap_double)
        ).apply {
            selected = if (WidgetSettings.doubleTapSwaps(this@WidgetSettingsActivity)) 1 else 0
            onSegmentTapped = { i ->
                selected = i
                WidgetSettings.setDoubleTapSwaps(this@WidgetSettingsActivity, i == 1)
            }
        })
        root.addView(hint(R.string.widget_pages_hint))

        root.addView(SettingRowFactory.sectionLabel(this, R.string.widget_section))
        root.addView(SettingRowFactory.card(this).apply {
            SettingRowFactory.addRow(this, toggle(R.drawable.ic_low_latency, R.string.widget_ll_title, R.string.widget_ll_sub,
                WidgetSettings.lowLatencyShown(this@WidgetSettingsActivity)) { WidgetSettings.setLowLatencyShown(this@WidgetSettingsActivity, it) })
            SettingRowFactory.addRow(this, toggle(R.drawable.ic_open_app, R.string.widget_open_title, R.string.widget_open_sub,
                WidgetSettings.openAppOnTap(this@WidgetSettingsActivity)) { WidgetSettings.setOpenAppOnTap(this@WidgetSettingsActivity, it) })
        })

        render()
        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
    }

    private fun toggle(icon: Int, title: Int, sub: Int, checked: Boolean, save: (Boolean) -> Unit): LinearLayout {
        val sw = SettingRowFactory.buildSwitch(this, checked)
        sw.setOnCheckedChangeListener { _, v -> save(v) }
        return SettingRowFactory.build(this, icon, title, sub, sw) { sw.performClick() }
    }

    /** Handle, mode icon, name, checkbox. Touching the handle starts the drag. */
    private fun modeRow(mode: WidgetSettings.Mode, p: Palette): View {
        val box = CheckBox(this).apply {
            buttonTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.textSecondary)
            )
            isClickable = false   // the row toggles it, so the minimum-two rule lives in one place
            contentDescription = getString(mode.name)
        }
        boxes[mode.key] = box
        val handle = ImageView(this).apply {
            val size = ThemeRes.dp(this@WidgetSettingsActivity, 24f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageDrawable(ThemeRes.tint(this@WidgetSettingsActivity, R.drawable.ic_drag_handle, p.textSecondary))
            contentDescription = getString(R.string.widget_drag_desc)
        }
        val row = SettingRowFactory.build(this, mode.icon, mode.name, 0, box, leading = handle) {
            if (!on.remove(mode.key)) { if (on.size >= WidgetSettings.MAX_ON) return@build; on.add(mode.key) }
            else if (on.size < WidgetSettings.MIN_ON) { on.add(mode.key); return@build }
            Haptics.commit(box)
            WidgetSettings.setEnabledKeys(this, on)
            render()
        }
        handle.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                dragging = mode.key
                row.startDragAndDrop(null, View.DragShadowBuilder(row), mode.key, 0)
                render()
            }
            true
        }
        return row
    }

    private fun render() {
        modesCard.removeAllViews()
        for (mode in order) {
            val row = rows.getValue(mode.key)
            val checked = mode.key in on
            SettingRowFactory.addRow(modesCard, row)
            row.alpha = when {
                mode.key == dragging -> 0.15f
                checked -> 1f
                else -> 0.5f
            }
            boxes.getValue(mode.key).apply {
                isChecked = checked
                // The last two checked cannot be unchecked, nor a seventh checked.
                isEnabled = if (checked) on.size > WidgetSettings.MIN_ON else on.size < WidgetSettings.MAX_ON
            }
        }
    }

    private fun onDrag(e: DragEvent): Boolean {
        val key = dragging ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                val target = order.indexOfFirst { m -> rows.getValue(m.key).let { e.y < it.top + it.height / 2f } }
                    .let { if (it < 0) order.size else it }
                val from = order.indexOfFirst { it.key == key }
                val to = if (target > from) target - 1 else target
                if (to != from && to in order.indices) {
                    order.add(to, order.removeAt(from))
                    render()
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                dragging = null
                Haptics.commit(modesCard)
                WidgetSettings.setOrder(this, order)
                render()
            }
        }
        return true
    }
}
