package com.spizganed.quickbuds.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsDevice
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.widget.AncWidgetProvider
import com.spizganed.quickbuds.widget.BatteryWidgetProvider

/**
 * Runs one activity start. Returns the refusal, or null when Android accepted the start.
 * Android 17 refuses a start on a secondary display (report 67: launchDisplayId=6).
 */
internal inline fun attemptStart(start: () -> Unit): SecurityException? =
    try { start(); null } catch (e: SecurityException) { e }

/**
 * First-run setup, once: welcome, look, permissions, the earbuds, the background service, the notification, widgets,
 * Quick Settings tiles, feedback, known issues, privacy, done.
 * Every choice stays in Settings. The crash report and the model test keep their own prompts (they depend on what just happened).
 */
class SetupActivity : Activity() {

    private val total = 12
    private var page = 0
    private lateinit var body: LinearLayout
    private lateinit var back: TextView
    private lateinit var next: TextView
    private lateinit var dots: LinearLayout
    private val prefs by lazy { getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE) }
    private val p by lazy { ThemeRes.palette(this) }
    private fun dp(v: Float) = ThemeRes.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        page = savedInstanceState?.getInt("page") ?: 0

        val root = SettingRowFactory.screen(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), 0, dp(16f), 0) }
        root.addView(ScrollView(this).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        back = TextView(this).apply {
            textSize = 15f
            typeface = ThemeRes.medium(context)
            setTextColor(p.textSecondary)
            gravity = Gravity.CENTER
            setPadding(dp(12f), 0, dp(12f), 0)
            // The first page has no way back: the same button skips the guide.
            setOnClickListener { if (page == 0) finishSetup() else show(page - 1, animate = true) }
        }
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        next = TextView(this).apply {
            textSize = 15f
            typeface = ThemeRes.bold(context)
            gravity = Gravity.CENTER
            setTextColor(ThemeRes.onFill(this@SetupActivity))
            background = ThemeRes.ripple(this@SetupActivity, ThemeRes.pill(this@SetupActivity, p.accent, 22f))
            setPadding(dp(24f), 0, dp(24f), 0)
            setOnClickListener { if (page == total - 1) finishSetup() else show(page + 1, animate = true) }
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, dp(16f), 0)
            addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))
            addView(dots, LinearLayout.LayoutParams(0, dp(44f), 1f))
            addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))
        })
        setContentView(root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        show(page)
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("page", page) }

    /** The addresses of the earbuds that are connected for audio now; null until the first answer. */
    private var connected: Set<String>? = null

    /** A link change while the earbuds page shows: ask again once Android has settled. */
    private val linkReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (page == EARBUDS) body.postDelayed({ queryConnected() }, 800) }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(linkReceiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(linkReceiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(linkReceiver) }
        super.onStop()
    }

    /** A permission dialog, the Bluetooth settings or the battery dialog may have changed what a page shows. */
    override fun onResume() {
        super.onResume()
        if (::body.isInitialized && page in listOf(PERMISSIONS, EARBUDS, BACKGROUND)) refresh()
    }

    /** Back goes to the previous page, then leaves the app from the first one. */
    private fun goBack() { if (page > 0) show(page - 1, animate = true) else finish() }

    @Suppress("DEPRECATION", "GestureBackNavigation") // Android 12 and older call this; 13 and newer use the callback in onCreate.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    /** What the page on screen shows, so a return to the screen repaints only when something changed (no flash of the buttons). */
    private var shownSig = ""

    private fun signature(): String = when (page) {
        PERMISSIONS -> "${granted(bluetoothPermissions())}${granted(notificationPermissions())}"
        EARBUDS -> "${granted(bluetoothPermissions())}|$connected|${BudsDevice.savedAddress(this)}|" +
            BudsDevice.candidates(this).joinToString { it.address }
        BACKGROUND -> "${batteryExempt()}"
        else -> ""
    }

    /** Repaints the page on screen only when what it shows changed; no transition. */
    private fun refresh() { if (signature() != shownSig) show(page) }

    private fun show(i: Int, animate: Boolean = false) {
        val forward = i >= page
        page = i.coerceIn(0, total - 1)
        body.removeAllViews()
        when (page) {
            WELCOME -> welcome()
            LOOK -> look()
            PERMISSIONS -> permissions()
            EARBUDS -> earbuds()
            BACKGROUND -> background()
            NOTIFICATION -> notification()
            WIDGETS -> widgets()
            TILES -> tiles()
            FEEDBACK -> feedback()
            ISSUES -> issues()
            PRIVACY -> privacy()
            else -> { icon(); heading(R.string.setup_ready_title, R.string.setup_ready_body) }
        }
        shownSig = signature()
        if (animate) Motion.page(body, forward)
        back.setText(if (page == 0) R.string.setup_skip else R.string.setup_back)
        next.setText(if (page == total - 1) R.string.welcome_done else R.string.setup_next)
        dots.removeAllViews()
        for (n in 0 until total) dots.addView(View(this).apply {
            background = ThemeRes.pill(this@SetupActivity, if (n == page) p.accent else p.outline, 4f)
            layoutParams = LinearLayout.LayoutParams(dp(if (n == page) 18f else 8f), dp(8f)).apply { marginStart = dp(3f); marginEnd = dp(3f) }
        })
    }

    private fun icon() = body.addView(ImageView(this).apply {
        setImageDrawable(ThemeRes.tint(this@SetupActivity, R.drawable.ic_launcher_foreground, p.accent))
        background = ThemeRes.pill(this@SetupActivity, p.card, 66f)
        layoutParams = LinearLayout.LayoutParams(dp(132f), dp(132f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(40f) }
    })

    private fun heading(title: Int, text: Int, centered: Boolean = false) {
        body.addView(TextView(this).apply {
            setText(title)
            textSize = 26f
            typeface = ThemeRes.headline(context)
            setTextColor(p.text)
            if (centered) gravity = Gravity.CENTER
            setPadding(dp(4f), dp(24f), dp(4f), dp(8f))
        })
        if (text != 0) body.addView(TextView(this).apply {
            setText(text)
            textSize = 15f
            setTextColor(p.textSecondary)
            if (centered) gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(dp(4f), 0, dp(4f), dp(16f))
        })
    }

    private fun label(res: Int) = body.addView(SettingRowFactory.sectionLabel(this, res))

    private fun switchRow(icon: Int, title: Int, sub: Int, key: String, default: Boolean, onChange: (() -> Unit)? = null): View {
        val sw = SettingRowFactory.buildSwitch(this, prefs.getBoolean(key, default))
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(key, on).apply()
            onChange?.invoke()
        }
        return SettingRowFactory.build(this, icon, title, sub, sw) { sw.performClick() }
    }

    /** A pill button for a row's trailing slot. [action] null draws the quiet "done" label. */
    private fun pillButton(text: Int, action: (() -> Unit)?) = SettingRowFactory.pillButton(this, text, action)

    private fun welcome() {
        icon()
        heading(R.string.welcome_title, 0, centered = true)
        body.addView(TextView(this).apply {
            setText(R.string.about_tagline)
            textSize = 16f
            setTextColor(p.text)
            gravity = Gravity.CENTER
            setPadding(dp(4f), 0, dp(4f), dp(8f))
        })
        body.addView(TextView(this).apply {
            setText(R.string.welcome_body)
            textSize = 14f
            setTextColor(p.textSecondary)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
            setPadding(dp(4f), 0, dp(4f), dp(16f))
        })
    }

    /** Style, theme and wallpaper colours: the same controls as Settings > Theme, the first choices shown in one place. */
    private fun look() {
        heading(R.string.setup_look_title, R.string.setup_look_body)
        label(R.string.widget_style_title)
        // Listed Material, QuickBuds, Pixel art; ORDER maps a segment to the stored style index (as Settings > Theme).
        val order = intArrayOf(2, 0, 1, 3)
        body.addView(AncSegmentedView(this, listOf(getString(R.string.widget_style_material), getString(R.string.widget_style_classic),
            getString(R.string.widget_style_nothing), getString(R.string.widget_style_glass))).apply {
            selected = order.indexOf(ThemeRes.style(this@SetupActivity))
            contentDescription = getString(R.string.widget_style_title)
            onSegmentTapped = { i ->
                if (i != selected) {
                    selected = i
                    ThemeRes.setStyle(this@SetupActivity, order[i])
                    postDelayed({ ThemeRes.recreateFaded(this@SetupActivity) }, 260)
                }
            }
        })

        label(R.string.theme_builtin)
        // The same text picker as Settings > Themes; a theme picked by hand ends Match system.
        val ids = PaletteStore.builtInIds()
        body.addView(AncSegmentedView(this, ids.map { PaletteStore.builtIn(this, it).name }).apply {
            selected = ids.indexOf(PaletteStore.activeId(this@SetupActivity))
            contentDescription = getString(R.string.theme_builtin)
            onSegmentTapped = { i ->
                if (i != selected) {
                    selected = i
                    if (PaletteStore.auto(this@SetupActivity)) PaletteStore.setAuto(this@SetupActivity, false)
                    PaletteStore.setActive(this@SetupActivity, ids[i])
                    postDelayed({ ThemeRes.recreateFaded(this@SetupActivity) }, 260)
                }
            }
        })

        body.addView(SettingRowFactory.card(this).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(18f)
            // Wallpaper colours (Android 12+): the accent follows the phone's wallpaper.
            if (PaletteStore.wallpaperSupported()) {
                val sw = SettingRowFactory.buildSwitch(this@SetupActivity, PaletteStore.wallpaper(this@SetupActivity))
                sw.setOnCheckedChangeListener { v, on -> PaletteStore.setWallpaper(this@SetupActivity, on); v.postDelayed({ ThemeRes.recreateFaded(this@SetupActivity) }, 260) }
                val row = SettingRowFactory.build(this@SetupActivity, 0, R.string.theme_wallpaper, 0, sw) { sw.performClick() }
                SettingRowFactory.subtitle(this@SetupActivity, row).setText(R.string.theme_wallpaper_sub)
                SettingRowFactory.addRow(this, row)
            }
            // The full accent picker and the custom presets stay in Settings > Theme.
            SettingRowFactory.addRow(this, SettingRowFactory.build(this@SetupActivity, R.drawable.ic_palette, R.string.theme_title, 0,
                SettingRowFactory.buildChevron(this@SetupActivity)) { startActivity(Intent(this@SetupActivity, ThemeActivity::class.java)) })
        })
    }

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun notificationPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    private fun granted(list: Array<String>) = list.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun permissions() {
        heading(R.string.setup_perm_title, R.string.setup_perm_body)
        fun row(title: Int, list: Array<String>, code: Int): View {
            val ok = granted(list)
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                addView(TextView(this@SetupActivity).apply {
                    setText(title)
                    textSize = 16f
                    setTextColor(p.text)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pillButton(if (ok) R.string.setup_allowed else R.string.setup_allow, if (ok) null else ({ requestPermissions(list, code) })))
            }
        }
        body.addView(SettingRowFactory.card(this).apply {
            addView(row(R.string.setup_perm_bt, bluetoothPermissions(), 1))
            if (notificationPermissions().isNotEmpty()) addView(row(R.string.setup_perm_notif, notificationPermissions(), 2))
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (page == PERMISSIONS) refresh()
    }

    /** The paired earbuds the app can control: pick one, or pair them in Android first. Connecting happens when the guide ends. */
    @SuppressLint("MissingPermission")
    private fun earbuds() {
        heading(R.string.setup_earbuds_title, R.string.setup_earbuds_body)
        if (!granted(bluetoothPermissions())) {
            body.addView(TextView(this).apply {
                setText(R.string.setup_earbuds_need_bt)
                textSize = 15f
                setTextColor(p.textSecondary)
                setPadding(dp(4f), 0, dp(4f), dp(16f))
            })
            return
        }
        queryConnected()
        val found = BudsDevice.candidates(this)
        val picked = BudsDevice.savedAddress(this)?.takeIf { a -> found.any { it.address == a } } ?: found.firstOrNull()?.address
        if (found.isEmpty()) {
            body.addView(TextView(this).apply {
                setText(R.string.setup_earbuds_none)
                textSize = 15f
                setTextColor(p.text)
                setPadding(dp(4f), 0, dp(4f), dp(12f))
            })
        } else {
            val models = ModelCatalog.all(this)
            val list = SettingRowFactory.splitList(this)
            for (d in found) {
                val on = d.address == picked
                val model = ModelCatalog.find(models, null, d.name)
                val mark = ImageView(this).apply {
                    setImageDrawable(ThemeRes.tint(this@SetupActivity, R.drawable.ic_check, p.accent))
                    visibility = if (on) View.VISIBLE else View.INVISIBLE
                    layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f))
                }
                val row = SettingRowFactory.build(this, R.drawable.ic_earbud, 0, 0, mark) {
                    BudsDevice.remember(this, d)
                    refresh()
                }
                row.findViewWithTag<TextView>(SettingRowFactory.TITLE_TAG).text = d.name
                val state = connected?.let { getString(if (d.address in it) R.string.setup_earbuds_connected else R.string.setup_earbuds_disconnected) }
                SettingRowFactory.subtitle(this, row).text =
                    getString(if (model != null) R.string.setup_earbuds_known else R.string.setup_earbuds_unknown) + (state?.let { "\n$it" } ?: "")
                SettingRowFactory.addSplit(list, row)
            }
            body.addView(list)
        }
        body.addView(SettingRowFactory.card(this).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12f)
            addView(SettingRowFactory.build(this@SetupActivity, R.drawable.ic_earbud, R.string.setup_earbuds_pair, 0,
                SettingRowFactory.buildChevron(this@SetupActivity)) {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            })
        })
    }

    /** Which earbuds are connected for audio. A change repaints the earbuds page; the same answer does nothing. */
    @SuppressLint("MissingPermission")
    private fun queryConnected() {
        if (!granted(bluetoothPermissions())) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val now = proxy.connectedDevices.map { it.address }.toSet()
                adapter.closeProfileProxy(profile, proxy)
                runOnUiThread { if (now != connected) { connected = now; if (page == EARBUDS) refresh() } }
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    private fun batteryExempt() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun background() {
        heading(R.string.setup_bg_title, R.string.setup_bg_body)
        body.addView(SettingRowFactory.card(this).apply {
            addView(switchRow(R.drawable.ic_power, R.string.settings_background_title, R.string.settings_background_sub, BudsService.PREF_BACKGROUND, true))
            val ok = batteryExempt()
            addView(SettingRowFactory.build(this@SetupActivity, R.drawable.ic_battery, R.string.setup_battery_title, R.string.setup_battery_sub, null,
                value = pillButton(if (ok) R.string.setup_allowed else R.string.setup_allow, if (ok) null else ({ requestBatteryExemption() }))))
            addView(switchRow(R.drawable.ic_update, R.string.update_auto_title, R.string.update_auto_sub, UpdateChecker.KEY_AUTO, true) {
                UpdateChecker.syncBackground(this@SetupActivity)
            })
        })
    }

    /** The system dialog for this app only; the full list is the fallback when a phone has none. */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun widgets() {
        heading(R.string.setup_widgets_title, R.string.setup_widgets_body)
        val manager = getSystemService(AppWidgetManager::class.java)
        val canPin = manager.isRequestPinAppWidgetSupported
        fun row(name: Int, provider: Class<*>) = SettingRowFactory.build(this, R.drawable.ic_battery, name, 0, null,
            value = if (canPin) pillButton(R.string.setup_widgets_add) { manager.requestPinAppWidget(ComponentName(this, provider), null, null) } else null)
        body.addView(SettingRowFactory.card(this).apply {
            addView(row(R.string.widget_name, AncWidgetProvider::class.java))
            addView(row(R.string.widget_pages_name, BatteryWidgetProvider::class.java))
            addView(row(R.string.widget_battery_name, com.spizganed.quickbuds.widget.BatteryOnlyWidgetProvider::class.java))
            addView(row(R.string.widget_strip_name, com.spizganed.quickbuds.widget.BatteryStripWidgetProvider::class.java))
            addView(row(R.string.widget_controls_name, com.spizganed.quickbuds.widget.ControlsWidgetProvider::class.java))
            addView(row(R.string.widget_cycle_name, com.spizganed.quickbuds.widget.NoiseCycleWidgetProvider::class.java))
        })
    }

    /** The service notification: the battery, the Android 16 status chip, the lock and up to 3 buttons. Same rows as Settings › Notifications. */
    private fun notification() {
        heading(R.string.setup_notif_title, R.string.setup_notif_body)
        body.addView(SettingRowFactory.card(this).apply {
            addView(switchRow(R.drawable.ic_battery, R.string.settings_full_notif_title, R.string.settings_full_notif_sub, BudsService.PREF_FULL_NOTIFICATION, true))
            if (Build.VERSION.SDK_INT >= 36)
                addView(switchRow(R.drawable.ic_battery, R.string.settings_live_chip_title, R.string.settings_live_chip_sub, BudsService.PREF_LIVE_CHIP, false))
            addView(switchRow(R.drawable.ic_battery, R.string.settings_lock_notif_title, R.string.settings_lock_notif_sub, BudsService.PREF_LOCK_NOTIFICATION, false))
            addView(SettingRowFactory.build(this@SetupActivity, R.drawable.ic_mode_transparency, R.string.notif_buttons_title, R.string.notif_buttons_sub,
                SettingRowFactory.buildChevron(this@SetupActivity)) {
                startActivity(Intent(this@SetupActivity, com.spizganed.quickbuds.widget.WidgetSetupActivity::class.java)
                    .putExtra(com.spizganed.quickbuds.widget.WidgetSetupActivity.EXTRA_NOTIFICATION, true))
            })
        })
    }

    /**
     * The Quick Settings tiles. Android 13+ adds one with a system prompt ("Add"); older versions only through
     * the panel's edit mode, which the text explains.
     */
    private fun tiles() {
        heading(R.string.setup_tiles_title, R.string.setup_tiles_body)
        val canAdd = Build.VERSION.SDK_INT >= 33
        if (!canAdd) body.addView(TextView(this).apply {
            setText(R.string.setup_tiles_old)
            textSize = 14f
            setTextColor(p.textSecondary)
            setPadding(dp(4f), 0, dp(4f), dp(12f))
        })
        fun row(icon: Int, name: Int, service: Class<*>) = SettingRowFactory.build(this, icon, name, 0, null,
            value = if (canAdd) pillButton(R.string.setup_widgets_add) { addTile(service, name, icon) } else null)
        body.addView(SettingRowFactory.card(this).apply {
            addView(row(R.drawable.ic_mode_anc_medium, R.string.anc_section, com.spizganed.quickbuds.widget.NoiseTileService::class.java))
            addView(row(R.drawable.ic_bolt, R.string.row_game_title, com.spizganed.quickbuds.widget.LowLatencyTileService::class.java))
            addView(row(R.drawable.ic_anc, R.string.row_wind_noise_title, com.spizganed.quickbuds.widget.WindTileService::class.java))
            addView(row(R.drawable.ic_devices, R.string.row_dual_title, com.spizganed.quickbuds.widget.DualTileService::class.java))
            addView(row(R.drawable.ic_stat_buds, R.string.widget_open_app, com.spizganed.quickbuds.widget.OpenAppTileService::class.java))
        })
    }

    @android.annotation.TargetApi(33)
    private fun addTile(service: Class<*>, name: Int, icon: Int) {
        getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, service), getString(name), android.graphics.drawable.Icon.createWithResource(this, icon),
            mainExecutor) { }
    }

    /** What the two feedback tools in Settings do. Nothing is sent from here. */
    private fun feedback() {
        heading(R.string.setup_feedback_title, R.string.setup_feedback_body)
        body.addView(SettingRowFactory.card(this).apply {
            addView(SettingRowFactory.build(this@SetupActivity, R.drawable.ic_dev_tools, R.string.problem_title, R.string.problem_sub, null))
            addView(SettingRowFactory.build(this@SetupActivity, R.drawable.ic_earbud, R.string.test_title, R.string.test_sub, null))
        })
    }

    /** What Settings › Known issues holds. Nothing is sent from here. */
    private fun issues() {
        heading(R.string.settings_issues_title, R.string.setup_issues_body)
        body.addView(SettingRowFactory.card(this).apply {
            addView(SettingRowFactory.build(this@SetupActivity, R.drawable.ic_info, R.string.settings_issues_title, R.string.settings_issues_sub, null))
        })
    }

    private fun privacy() {
        heading(R.string.about_privacy_title, R.string.about_privacy_body)
        body.addView(TextView(this).apply {
            setText(R.string.about_privacy_reply)
            textSize = 14f
            setTextColor(p.textSecondary)
            setPadding(dp(4f), 0, dp(4f), dp(12f))
        })
        body.addView(SettingRowFactory.card(this).apply {
            addView(switchRow(R.drawable.ic_info, R.string.settings_ping_title, R.string.settings_ping_sub, UsagePing.KEY_ENABLED, true))
        })
    }

    private fun finishSetup() {
        prefs.edit().putBoolean(KEY_DONE, true).apply()
        // Opened from Settings: the screens below are still there.
        if (!intent.getBooleanExtra(EXTRA_AGAIN, false)) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val KEY_DONE = "setupDone"
        const val EXTRA_AGAIN = "again"
        private const val WELCOME = 0
        private const val LOOK = 1
        private const val PERMISSIONS = 2
        private const val EARBUDS = 3
        private const val BACKGROUND = 4
        private const val NOTIFICATION = 5
        private const val WIDGETS = 6
        private const val TILES = 7
        private const val FEEDBACK = 8
        private const val ISSUES = 9
        private const val PRIVACY = 10
        fun done(a: Activity) = a.getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_DONE, false)

        /**
         * Opens the guide on the default display. Android 17 refuses a start on a secondary
         * display (report 67). A refused start returns false, so the caller keeps its screen.
         */
        fun start(a: Activity): Boolean {
            val refused = attemptStart {
                a.startActivity(
                    Intent(a, SetupActivity::class.java),
                    ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
                )
            }
            if (refused != null) android.util.Log.w("QuickBuds", "Setup start refused: ${refused.javaClass.simpleName}")
            return refused == null
        }
    }
}
