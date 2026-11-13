package com.spizganed.quickbuds.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.ui.Haptics
import com.spizganed.quickbuds.ui.Rows
import com.spizganed.quickbuds.ui.SettingRowFactory
import com.spizganed.quickbuds.ui.ThemeRes

/**
 * A widget's setup screen: which buttons its controls page shows, up to [WidgetSettings.MAX_BUTTONS], in the
 * order of [WidgetSettings.BUTTONS]. Only the buttons the buds have are listed. Each switch saves at once and
 * repaints the widget. Opens when the widget is placed (optional from Android 12) and from its long-press menu.
 * With [EXTRA_NOTIFICATION]: the service notification's buttons (Settings › Notifications), up to
 * [WidgetSettings.MAX_NOTIFICATION_BUTTONS].
 */
class WidgetSetupActivity : Activity() {

    private val switches = LinkedHashMap<String, Switch>()

    /** The most buttons this screen lets the user pick. */
    private var max = WidgetSettings.MAX_BUTTONS

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_NOTIFICATION, false)) return notificationButtons()
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        // OK from the start: Back keeps the widget, with the buttons picked so far.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))

        val p = ThemeRes.palette(this)
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.widget_settings_title))
        // Material: this widget's box on or off.
        if (WidgetSettings.materialStyle(this)) {
            val bgSwitch = SettingRowFactory.buildSwitch(this, WidgetSettings.background(this, id))
            bgSwitch.setOnCheckedChangeListener { v, on -> WidgetSettings.setBackground(this, id, on); Haptics.commit(v) }
            val bgRow = SettingRowFactory.build(this, 0, R.string.widget_background_title, 0, bgSwitch) { bgSwitch.performClick() }
            SettingRowFactory.subtitle(this, bgRow).setText(R.string.widget_background_sub)
            root.addView(SettingRowFactory.card(this).apply { addView(bgRow) })
        }
        // One-page widgets: a tap on the box opens the app. The two-page one swaps pages on a tap; it has the "app" button.
        if (QuickBudsWidget.kindOf(this, id)?.page != null) {
            val tapSwitch = SettingRowFactory.buildSwitch(this, WidgetSettings.tapOpens(this, id))
            tapSwitch.setOnCheckedChangeListener { v, on -> WidgetSettings.setTapOpens(this, id, on); QuickBudsWidget.refreshAll(this); Haptics.commit(v) }
            val tapRow = SettingRowFactory.build(this, 0, R.string.widget_tap_opens_title, 0, tapSwitch) { tapSwitch.performClick() }
            SettingRowFactory.subtitle(this, tapRow).setText(R.string.widget_tap_opens_sub)
            root.addView(SettingRowFactory.card(this).apply { addView(tapRow) })
        }
        // The buttons, for widgets with a controls page.
        if (QuickBudsWidget.kindOf(this, id) == QuickBudsWidget.Kind.BATTERY_ONLY) {
            if (root.childCount == 1) return finish()
            setContentView(ScrollView(this).apply { setBackgroundColor(p.background); addView(root) })
            return
        }
        buttonList(root, R.string.widget_setup_title, R.string.widget_setup_hint, WidgetSettings.BUTTONS,
            WidgetSettings.buttons(this, id), id) { WidgetSettings.setButtons(this, id, it); QuickBudsWidget.refreshAll(this) }
    }

    /** Settings › Notifications › Notification buttons. */
    private fun notificationButtons() {
        max = WidgetSettings.MAX_NOTIFICATION_BUTTONS
        val root = SettingRowFactory.screen(this)
        root.addView(SettingRowFactory.title(this, R.string.notif_buttons_title))
        buttonList(root, 0, R.string.notif_buttons_hint, WidgetSettings.NOTIFICATION_BUTTONS,
            WidgetSettings.notificationButtons(this), WidgetSettings.NOTIFICATION_ID) { WidgetSettings.setNotificationButtons(this, it) }
    }

    /**
     * The button switches of [all] that the buds have, [stored] on, then the Noise cycle row when "cycle" is listed.
     * [id] is the widget (or [WidgetSettings.NOTIFICATION_ID]) whose cycle list the row opens. [save] gets the keys in order.
     */
    private fun buttonList(root: android.widget.LinearLayout, label: Int, hint: Int, all: List<String>, stored: List<String>, id: Int,
                           save: (List<String>) -> Unit) {
        val p = ThemeRes.palette(this)
        if (label != 0) root.addView(SettingRowFactory.sectionLabel(this, label))
        root.addView(TextView(this).apply {
            setText(hint)
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(ThemeRes.dp(this@WidgetSetupActivity, 4f), 0, 0, ThemeRes.dp(this@WidgetSetupActivity, 12f))
        })
        val rows = Rows.of(this)
        val keys = all.filter { WidgetSettings.available(this, it, rows) }
        val picked = stored.filter { it in keys }.toMutableSet()
        val card = SettingRowFactory.card(this)
        for (key in keys) {
            val (icon, title) = ROWS.getValue(key)
            val sw = SettingRowFactory.buildSwitch(this, key in picked)
            switches[key] = sw
            sw.setOnCheckedChangeListener { v, on ->
                if (on) picked.add(key) else picked.remove(key)
                save(keys.filter { it in picked })
                Haptics.commit(v)
                limit(picked.size)
            }
            if (card.childCount > 0) card.addView(SettingRowFactory.buildDivider(this))
            card.addView(SettingRowFactory.build(this, icon, title, 0, sw) { if (sw.isEnabled) sw.performClick() })
        }
        root.addView(card)
        // This widget's (or the notification's) own Noise cycle modes.
        if ("cycle" in keys) {
            root.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(1, ThemeRes.dp(this, 16f)))
            val sub = if (id == WidgetSettings.NOTIFICATION_ID) 0 else R.string.settings_cycle_sub
            val row = SettingRowFactory.build(this, R.drawable.ic_mode_transparency, R.string.widget_cycle, sub,
                SettingRowFactory.buildChevron(this)) {
                startActivity(Intent(this, NoiseCycleActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            }
            root.addView(SettingRowFactory.card(this).apply { addView(row) })
        }
        limit(picked.size)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.background)
            addView(root)
        })
    }

    /** At the limit, only the picked switches can change. */
    private fun limit(count: Int) {
        for (sw in switches.values) sw.isEnabled = sw.isChecked || count < max
    }

    companion object {
        /** Opens the notification's button list instead of a widget's. */
        const val EXTRA_NOTIFICATION = "notification"

        /** Button key -> (icon, title). */
        val ROWS = mapOf(
            "anc" to (R.drawable.ic_mode_anc_medium to R.string.anc_section),
            "trans" to (R.drawable.ic_mode_transparency to R.string.anc_seg_trans),
            "adapt" to (R.drawable.ic_mode_adaptive to R.string.anc_seg_adapt),
            "off" to (R.drawable.ic_mode_off to R.string.anc_seg_off),
            "cycle" to (R.drawable.ic_mode_transparency to R.string.widget_cycle),
            "ll" to (R.drawable.ic_low_latency to R.string.row_game_title),
            "spatial" to (R.drawable.ic_spatial to R.string.row_spatial_title),
            "wind" to (R.drawable.ic_anc to R.string.row_wind_noise_title),
            "dual" to (R.drawable.ic_devices to R.string.row_dual_title),
            "app" to (R.drawable.ic_earbud to R.string.widget_open_app)
        )
    }
}
