package com.spizganed.quickbuds.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.spizganed.quickbuds.CrashLogger
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.devtool.LayoutReport
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.widget.AncWidgetProvider
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * Main screen — rebuilt from scratch against the reference design.
 *
 * WHAT CHANGED AND WHY, in order:
 *   1. The old "status panel" (a capped mirror of the home-screen widget, sitting
 *      in a row with a dead column beside it) is gone. The battery block is now a
 *      full-width card at the top of the scroll, with the L/C/R bars beside the
 *      icons instead of underneath them, and the letters moved into circles.
 *   2. ANC is four circular buttons. Tapping the ACTIVE one opens a chooser with
 *      the full mode list, because four buttons cannot represent five modes and
 *      the request was for a pop-out rather than a fifth circle.
 *   3. The settings rows are a real card with themed switches and chevrons.
 *
 * THEMING
 * Colours come from resources (values/app_colors.xml + the two override folders)
 * and are swapped by ThemeRes.apply() before super.onCreate(). Nothing here sets a
 * raw colour on a view; the only colour work done in code is tinting vector
 * drawables, which cannot be themed from XML.
 */
class MainActivity : Activity(), BudsConnectionManager.Listener {

    private lateinit var manager: BudsConnectionManager
    private lateinit var mainLayout: LinearLayout
    private lateinit var featureList: LinearLayout

    private lateinit var btnSettings: ImageButton
    private lateinit var btnDevTools: ImageButton
    private lateinit var deviceNameText: TextView

    // Header connection pill.
    private lateinit var connPill: LinearLayout
    private lateinit var connDot: ImageView
    private lateinit var connText: TextView

    /** Last connection state shown in the pill, so a redundant notify does nothing. */
    private var lastConnShown: Boolean? = null

    // Status card + noise control, both custom-drawn (redesign 2026-09-23): see BudsStatusView and
    // AncSegmentedView. They replaced two cards of ImageViews/ProgressBars and four ANC buttons.
    private lateinit var statusView: BudsStatusView
    private lateinit var ancView: AncSegmentedView
    private lateinit var ancLevels: LinearLayout
    private lateinit var tiles: LinearLayout

    // Settings rows that hold live state
    private var gameSwitch: Switch? = null
    private var hiresSwitch: Switch? = null
    private var hiresSubtitle: TextView? = null
    private var spatialSwitch: Switch? = null

    /** Last state rendered, so a redundant notify does not rebuild the UI. */
    private var lastRendered: WidgetStateStore.State? = null

    private val TARGET_MAC = "A8:E6:E8:92:C1:25"
    private val REQUEST_PERMISSIONS = 1001

    /** Card collapse/expand when the app connects or disconnects. */
    private val CARD_ANIM_MS = 240L

    /** Alpha for controls that cannot act without a connection. */
    private val DISABLED_ALPHA = 0.35f

    // The battery tile: never dimmed, so it is the one tile setConnectedUi() skips.
    private lateinit var batteryCard: LinearLayout

    /** The noise control tile. */
    private lateinit var ancRow: LinearLayout

    /**
     * Renders the header connection pill: dot icon, dot colour, label text.
     *
     * THE TRANSITION IS A CROSS-FADE, and it is done by fading OUT, swapping, then
     * fading IN — not by swapping and fading in. Text and a vector cannot tween into
     * each other, so the only honest option is a dip: the pill dims, the new state is
     * set at the bottom of the dip, and it comes back up. A straight "fade to new"
     * would show the new word at full opacity for one frame before the fade started,
     * which is a flicker, not a transition.
     *
     * Colours are the STATE colours (app_conn_on / app_conn_off), NOT theme
     * attributes. Green means connected in every theme; a themed connection colour
     * would change meaning with the theme. The pill's own background IS themed, so
     * the container still matches the app.
     */
    private fun renderConnectionPill(connected: Boolean) {
        if (lastConnShown == connected) return
        val first = lastConnShown == null
        lastConnShown = connected

        fun apply() {
            connDot.setImageResource(
                if (connected) R.drawable.ic_status_dot_filled
                else R.drawable.ic_status_dot_empty
            )
            // SPEC 3.1 / 3.2: accent dot + text label when connected, grey ring + grey label when not.
            val p = ThemeRes.palette(this)
            connDot.setColorFilter(if (connected) p.accent else p.textSecondary)
            // The word is the ACTION (the pill is a button); the dot and colour carry the state.
            connText.setText(if (connected) R.string.conn_action_disconnect else R.string.conn_action_connect)
            connText.setTextColor(if (connected) p.text else p.textSecondary)
            connPill.contentDescription = getString(if (connected) R.string.conn_on else R.string.conn_off) +
                ". " + connText.text
        }

        // First render: no animation. Opening the screen should not play a transition
        // for a state nobody changed.
        if (first || animatorScale() == 0f) {
            apply()
            connPill.alpha = 1f
            return
        }

        val half = CARD_ANIM_MS / 2
        connPill.animate()
            .alpha(0f)
            .setDuration(half)
            .withEndAction {
                apply()
                connPill.animate().alpha(1f).setDuration(half).start()
            }
            .start()
    }

    /**
     * The system animation scale (Developer options -> Animation). 0 means the user
     * has switched animations off, in which case every animation here becomes an
     * instant state change. Checked per call rather than cached: the setting can
     * change while the app is running.
     */
    private fun animatorScale(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            android.provider.Settings.Global.getFloat(
                contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        } else 1f

    /** Connection state the screen was last drawn for; null before the first render. */
    private var connectedUi: Boolean? = null

    /**
     * SPEC 3.2: disconnected, the battery tile keeps its exact size (track-only rings, "—") and
     * every other tile is dimmed to 0.35 and made inert, recursively. Icons keep their accent
     * (dimmed, not greyed); toggles and segments show a neutral state. Nothing collapses, so
     * nothing jumps on connect.
     */
    private fun setConnectedUi(connected: Boolean) {
        if (connectedUi == connected) return
        val first = connectedUi == null
        connectedUi = connected
        statusView.connected = connected
        // INVISIBLE, not GONE: the empty name keeps its space as the header's spacer.
        deviceNameText.visibility = if (connected) View.VISIBLE else View.INVISIBLE

        val alpha = if (connected) 1f else DISABLED_ALPHA
        for (i in 0 until tiles.childCount) {
            val tile = tiles.getChildAt(i)
            if (tile === batteryCard) continue
            setEnabledDeep(tile, connected)
            if (first || animatorScale() == 0f) tile.alpha = alpha
            else tile.animate().alpha(alpha).setDuration(CARD_ANIM_MS).start()
        }

        // Neutral switches while disconnected, set quietly so no listener sends a write.
        // The buds' real state is repainted on connect.
        syncingFeatures = true
        gameSwitch?.isChecked = connected && gameModeOn
        if (!connected) { hiresSwitch?.isChecked = false; spatialSwitch?.isChecked = false }
        syncingFeatures = false
        if (connected && ::manager.isInitialized) onFeatureStates(manager.featureStates)
        renderAnc(activeAncMode)
    }

    private fun setEnabledDeep(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), enabled)
    }

    private var activeAncMode: String = "Off"
    private var gameModeOn = false

    private var isBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BudsService.LocalBinder
            manager = binder.getService().manager!!
            manager.addListener(this@MainActivity)
            onFeatureStates(manager.featureStates)
            isBound = true
            connectDirectly()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    /**
     * Single source of truth for ANC + game mode, fed by the widget store.
     *
     * The store is what the buds' own push events write into (see
     * BudsConnectionManager.onGameModeState), so updating from it — rather than
     * from what we last sent — is what makes the buttons follow a gesture made on
     * the earbuds. That was already true before this redesign and must stay true:
     * do not "simplify" this into updating state at the call site of a command.
     */
    private val storeListener: (WidgetStateStore.State) -> Unit = { state ->
        activeAncMode = state.ancMode
        gameModeOn = state.gameMode
        lastRendered = state
        renderBattery(state)
        renderAnc(state.ancMode)
        renderWear(state)
        gameSwitch?.let { sw ->
            if (state.connected && sw.isChecked != state.gameMode) {
                sw.isChecked = state.gameMode
            }
        }
    }

    /**
     * Renders the three icons using THE WIDGET'S OWN LOGIC, ported verbatim.
     *
     * WHY A PORT RATHER THAN ANOTHER REDESIGN: the app and the widget had drifted
     * into two different icon treatments — different colours, different shapes,
     * different in-case behaviour — and the widget's version is the one that looks
     * right. Reimplementing it here means one source of truth for the rule, and the
     * two surfaces cannot disagree again.
     *
     * The rule, copied from AncWidgetProvider.budStyle():
     *
     *     status 4 or 0   ->  INVISIBLE   (in case)
     *     status 3 or 7   ->  VISIBLE, colourActive  (#FFFFFF, white)
     *     anything else   ->  VISIBLE, colourIdle    (#8A8A8A, grey)
     *
     * COLOURS ARE THE WIDGET'S PALETTE, deliberately, not the app's theme
     * attributes. The user's stated goal is that the panel matches the widget, and
     * the widget is always dark — so white/grey are correct here even on the Light
     * app theme. That is the same reasoning the widget panel used before this
     * redesign, and it is why these two colours are hardcoded rather than themed.
     *
     * The case icon is drawn in the widget's idle/secondary colour and is always
     * visible: the widget has no case icon at all (it labels the bars instead), so
     * there is no widget behaviour to copy for it. Grey is used so it reads as a
     * static reference object rather than competing with the two bud icons.
     */
    private fun renderWear(state: WidgetStateStore.State) {
        setConnectedUi(state.connected)
        // The header pill shows the same state, with its own transition.
        renderConnectionPill(state.connected)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Theme selection FIRST, before super.onCreate. setTheme() is a style-id
        // lookup and cannot fail; this replaced a runtime configuration override
        // that crashed on launch five times (see ThemeRes).
        ThemeRes.select(this)

        super.onCreate(savedInstanceState)

        // Crash report FIRST, before anything that could throw. If the layout or
        // the service binding below is what is crashing on this build, the user
        // must still be able to see the previous crash's report — otherwise the
        // report is unreachable forever. This is the only reason it is not shown
        // after the UI is built.
        maybeShowCrashReport()

        setContentView(R.layout.activity_main)

        mainLayout = findViewById<LinearLayout>(R.id.mainLayout)
        featureList = findViewById<LinearLayout>(R.id.featureList)
        deviceNameText = findViewById<TextView>(R.id.deviceNameText)
        btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        btnDevTools = findViewById<ImageButton>(R.id.btnDevTools)

        statusView = BudsStatusView(this)
        findViewById<android.widget.FrameLayout>(R.id.statusSlot).addView(statusView)
        ancView = AncSegmentedView(
            this, ANC_SEGMENTS.map { getString(it.second) }, ANC_SEGMENTS.map { it.third }
        ).apply {
            onSegmentTapped = { onAncCircleTapped(ANC_SEGMENTS[it].first) }
        }
        findViewById<android.widget.FrameLayout>(R.id.ancSlot).addView(ancView)
        ancLevels = findViewById<LinearLayout>(R.id.ancLevels)
        buildLevelPills()

        tiles = findViewById<LinearLayout>(R.id.tiles)
        batteryCard = findViewById<LinearLayout>(R.id.batteryCard)
        ancRow = findViewById<LinearLayout>(R.id.ancRow)
        applyTileLayout()

        connPill = findViewById<LinearLayout>(R.id.connPill)
        // No ripple: the pill sinks a little under the finger and springs back, like a real button.
        connPill.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f)
                    .setInterpolator(DecelerateInterpolator()).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(OvershootInterpolator(3f)).setDuration(260).start()
            }
            false
        }
        connDot = findViewById<ImageView>(R.id.connDot)
        connText = findViewById<TextView>(R.id.connText)

        // The pill is also THE connect/disconnect button (`[USER]` 2026-09-23, as HeyMelody has
        // one): the dot shows the state, the word is the action. Same service actions as Dev Tools.
        connPill.setOnClickListener {
            val connected = lastConnShown == true
            connText.setText(if (connected) R.string.conn_disconnecting else R.string.conn_connecting)
            startService(
                Intent(this, BudsService::class.java).setAction(
                    if (connected) BudsService.ACTION_FORCE_DISCONNECT else BudsService.ACTION_FORCE_CONNECT
                ).putExtra(BudsService.EXTRA_WITH_AUDIO, true)
            )
            // A failed connect may not change the stored state, so nothing would repaint the
            // pill. ponytail: fixed timeout; a real "connecting" state in the store if it matters.
            lastConnShown = null
            connPill.postDelayed({
                if (lastConnShown == null) renderConnectionPill(WidgetStateStore.read(this).connected)
            }, 25_000)
        }

        applyThemeTints()
        buildFeatureRows()

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnDevTools.setOnClickListener {
            startActivity(Intent(this, DevToolsActivity::class.java))
        }

        val initial = WidgetStateStore.read(this)
        activeAncMode = initial.ancMode
        gameModeOn = initial.gameMode
        renderBattery(initial)
        renderAnc(initial.ancMode)
        renderWear(initial)
        WidgetStateStore.addListener(storeListener)

        checkPermissions()
    }

    /**
     * Starts and binds the foreground service.
     *
     * ONLY call this once the Bluetooth permissions are granted. A foreground
     * service of type connectedDevice must hold FOREGROUND_SERVICE_CONNECTED_DEVICE
     * plus at least one of BLUETOOTH_CONNECT / SCAN / ADVERTISE, and Android
     * enforces that at startForeground() time, not at install time. Starting the
     * service before the user grants them threw
     *
     *   SecurityException: Starting FGS with type connectedDevice ... requires
     *   permissions: allOf=[FOREGROUND_SERVICE_CONNECTED_DEVICE] anyOf=[...]
     *
     * which killed the app on first launch, and then stopped happening after the
     * permission was granted — so it looked intermittent. It was not: it was
     * purely an ordering bug, and the fix is this gate.
     *
     * Binding is gated the same way for a simpler reason: the service's whole job
     * is the RFCOMM link, so binding without permission only produces a manager
     * that cannot connect.
     */
    private fun startAndBindServiceIfPermitted() {
        if (!hasBluetoothPermissions()) return
        if (isBound) return
        val serviceIntent = Intent(this, BudsService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /** True when every runtime permission the RFCOMM link needs is granted. */
    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Pre-12: the FGS type only needs the manifest permissions.
            return true
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Shows the previous crash report, but only ONCE per crash.
     *
     * The earlier version showed the dialog whenever a report file existed and
     * only deleted it on a button press. So if the user swiped the dialog away, or
     * the activity was recreated before they answered (which the theme-switch
     * recreate loop did repeatedly), the same old report reappeared forever — it
     * looked like the app "crashing" on a build that was actually fine.
     *
     * The fix is to key the report by its timestamp and remember the last one
     * shown, so a report is announced exactly once no matter how many times the
     * activity starts. The file is left on disk (the user may still want it) and
     * only the "already seen" marker is stored.
     *
     * This is also why the dialog no longer needs to delete anything: not showing
     * it again is enough, and nothing is destroyed as a side effect of a button.
     */
    private fun maybeShowCrashReport() {
        val report = CrashLogger.last(this) ?: return

        // First line after the header is "time   : ..."; that is a stable identity
        // for a report without needing a hash of the whole trace.
        val stamp = report.lineSequence()
            .firstOrNull { it.startsWith("time") }
            ?: report.take(64)

        val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(ThemeRes.KEY_LAST_SHOWN_CRASH, null) == stamp) return

        val dp = { v: Float -> ThemeRes.dp(this, v) }

        // The path line matters more than it looks: the point of this dialog is
        // that the report is reachable WITHOUT the app working, so it has to say
        // where the file is, not just what it contains.
        val header = TextView(this).apply {
            text = "Saved to:\nDownload/QuickBudsCrash/\n\nThis message appears once. Copy or share it if you want to keep it."
            setTextColor(ThemeRes.color(this@MainActivity, R.attr.appColorTextSecondary))
            textSize = 11f
            setPadding(dp(24f), dp(12f), dp(24f), dp(6f))
        }

        // Scrollable + copyable: stack traces are long and the point is that the
        // user can get the text out. A plain AlertDialog message is not
        // selectable, so the TextView is built explicitly to be.
        val body = TextView(this).apply {
            text = report
            setTextIsSelectable(true)
            setTextColor(ThemeRes.color(this@MainActivity, R.attr.appColorTextPrimary))
            textSize = 11f
            setPadding(dp(24f), dp(0f), dp(24f), dp(16f))
        }

        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(header)
            addView(body)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(column) }

        // Marked as shown BEFORE showing: if the dialog is dismissed by a config
        // change or a swipe, it must not come back on the next onCreate.
        prefs.edit().putString(ThemeRes.KEY_LAST_SHOWN_CRASH, stamp).apply()

        AlertDialog.Builder(this)
            .setTitle("QuickBuds crashed last time")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val clip = getSystemService(ClipboardManager::class.java)
                clip?.setPrimaryClip(ClipData.newPlainText("QuickBuds crash", report))
                Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Share") { _, _ ->
                startActivity(
                    Intent.createChooser(CrashLogger.shareIntent(this, report), "Share crash report")
                )
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    override fun onDestroy() {
        WidgetStateStore.removeListener(storeListener)
        if (isBound) {
            try {
                manager.removeListener(this)
                unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
                // Already unbound (double onDestroy); nothing to do.
            }
            isBound = false
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        instance = this
        // Capture our own geometry while we are actually laid out, and park the
        // TEXT for Dev Tools. Doing it here (not on demand from Dev Tools) is
        // deliberate: once the user navigates to Dev Tools this activity is paused
        // and the tree is no longer the one on screen, which is how the first
        // version of the report ended up always describing Dev Tools itself.
        LayoutReport.capture(this) { text -> Companion.cacheReport(text) }
    }

    override fun onPause() {
        // Clear before onResume of the next activity can run, so the report button
        // can never point at a stopped activity.
        if (instance === this) instance = null
        super.onPause()
    }

    // ==================== Th  eme ====================

    /**
     * Tints every vector glyph and restyles the header icon frames.
     *
     * Vectors are the only thing that cannot read a theme from XML: the drawable
     * carries fillColor="#FFFFFF" from its widget usage, so on the light theme it
     * would render white-on-white without an explicit tint. Read through the theme
     * attribute so the tint follows the in-app theme choice.
     *
     * ICONS: uses the WIDGET's bud drawables (ic_bud_left / ic_bud_right), not the
     * _hq variants. The user preferred the widget artwork — its proportions read
     * better — and the _hq re-trace is now unused by the app. It is kept in the
     * tree only because removing it would be an unrelated change.
     *
     * WHY THE EDGES LOOKED PIXELATED, and what fixes it: the widget's drawables
     * declare width/height of 48dp, and drawing one at 52dp (battery card) or
     * 120dp (find my earbuds) makes the platform rasterise the vector at its
     * INTRINSIC size and then BITMAP-SCALE the result up — which is where the
     * blocky edges came from. It is not a fault in the artwork. Setting the bounds
     * explicitly at the target pixel size forces a vector re-rasterisation at that
     * exact size, so the result is anti-aliased and sharp at any scale.
     *
     * That is what setVectorBounds does below. Do not replace it with a plain
     * setImageDrawable().
     */
    /**
     * Tints the ICON BUTTONS in the header, and nothing else.
     *
     * IT MUST NOT TOUCH THE BATTERY-CARD ICONS. It used to, and that was a real bug:
     * this method runs before renderWear(), so the two fought over the same three
     * ImageViews — this one setting a themed tint, renderWear overwriting with the
     * widget's white/grey. Whichever ran last won, so the case appeared to change
     * size and colour depending on when a packet happened to arrive, and the buds
     * sometimes showed a stale drawable. All three card icons are now owned solely
     * by renderWear(); do not set them here.
     *
     * The header frames keep the widget's dark chip in EVERY theme (see
     * header_icon_bg), so their glyphs take the accent, like the EQ and row-chevron buttons —
     * never appColorIconTint, which would turn them black on a dark chip.
     */
    private fun applyThemeTints() {
        ThemeRes.screenPadding(mainLayout)
        connPill.background = ThemeRes.ripple(this, ThemeRes.iconButton(this, 22f))
        btnDevTools.background = ThemeRes.ripple(this, ThemeRes.iconButton(this))
        btnSettings.background = ThemeRes.ripple(this, ThemeRes.iconButton(this))
        batteryCard.background = ThemeRes.card(this)
        featureList.background = ThemeRes.card(this)
        featureList.clipToOutline = true
        btnDevTools.setImageDrawable(ThemeRes.tint(this, R.drawable.ic_dev_tools, ThemeRes.color(this, R.attr.appColorAccent)))
        btnSettings.setImageDrawable(ThemeRes.tint(this, R.drawable.ic_settings_cog, ThemeRes.color(this, R.attr.appColorAccent)))
    }

    // ==================== Battery block ====================

    private fun renderBattery(state: WidgetStateStore.State) {
        statusView.setState(
            state.leftBattery, state.caseBattery, state.rightBattery,
            state.leftStatus, state.rightStatus
        )
    }

    // ==================== ANC ====================

    /**
     * Moves the noise-control highlight to the mode the BUDS report, and spells the mode out in the
     * caption under it — including the ANC strength, which the old circle showed as "ANC-M".
     */
    private fun renderAnc(mode: String) {
        if (connectedUi == false) {
            ancView.selected = -1
            ancLevels.visibility = View.GONE
            return
        }
        ancView.selected = when (circleFor(mode)) {
            "ANC" -> 1
            "Adapt" -> 2
            "Trans" -> 3
            else -> 0
        }
        // The level row shows only while ANC is on (SPEC 3.1), with the active strength outlined.
        val level = ANC_LEVELS.indexOf(mode)
        ancLevels.visibility = if (level >= 0) View.VISIBLE else View.GONE
        if (level >= 0) {
            lastAncLevel = mode
            for (k in 0 until ancLevels.childCount) paintLevelPill(ancLevels.getChildAt(k) as TextView, k == level)
        }
    }

    /** The ANC strength the ANC segment applies; the last one seen, Medium on a fresh install. */
    private var lastAncLevel: String
        get() = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getString(KEY_ANC_LEVEL, null)
            ?.takeIf { it in ANC_LEVELS } ?: ANC_LEVELS[1]
        set(v) { getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_ANC_LEVEL, v).apply() }

    /** Low / Medium / High pills under the segments, replacing the old strength bottom sheet. */
    private fun buildLevelPills() {
        val names = intArrayOf(R.string.anc_mode_low, R.string.anc_mode_medium, R.string.anc_mode_high)
        ANC_LEVELS.forEachIndexed { i, mode ->
            ancLevels.addView(TextView(this).apply {
                setText(names[i])
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                minWidth = ThemeRes.dp(this@MainActivity, 72f)
                setPadding(ThemeRes.dp(this@MainActivity, 20f), 0, ThemeRes.dp(this@MainActivity, 20f), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, ThemeRes.dp(this@MainActivity, 40f)
                ).apply { if (i > 0) marginStart = ThemeRes.dp(this@MainActivity, 8f) }
                paintLevelPill(this, false)
                setOnClickListener { selectAnc(mode) }
            })
        }
    }

    private fun paintLevelPill(pill: TextView, selected: Boolean) {
        val pal = ThemeRes.palette(this)
        pill.background = ThemeRes.ripple(
            this, ThemeRes.shape(
                this, pal.background, if (selected) pal.accent else pal.outline, 20f,
                if (selected) 1.5f else 1f
            )
        )
        pill.setTextColor(if (selected) pal.text else pal.textSecondary)
        pill.typeface = if (selected) android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        else android.graphics.Typeface.DEFAULT
    }

    /**
     * Orders the home tiles and hides the hidden ones (SPEC section 4), from prefs written by the
     * future Home layout screen. Tiles are identified by their root id's name; the battery and
     * noise control tiles can never be hidden. Unknown or missing names fall back to XML order.
     */
    private fun applyTileLayout() {
        val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)
        val byName = (0 until tiles.childCount).map { tiles.getChildAt(it) }
            .associateBy { resources.getResourceEntryName(it.id) }
        val order = prefs.getString(KEY_TILE_ORDER, null)?.split(',').orEmpty().filter { it in byName }.distinct()
        val hidden = prefs.getStringSet(KEY_TILE_HIDDEN, emptySet()).orEmpty() - LOCKED_TILES
        tiles.removeAllViews()
        var firstShown = true
        (order + (byName.keys - order.toSet())).forEach { name ->
            val tile = byName.getValue(name)
            tile.visibility = if (name in hidden) View.GONE else View.VISIBLE
            (tile.layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                if (firstShown || name in hidden) 0 else ThemeRes.dp(this, 16f)
            if (name !in hidden) firstShown = false
            tiles.addView(tile)
        }
    }

    /**
     * Which circle a stored mode lights.
     *
     * "Adaptive" gets its own arm. Before the circle existed this fell through to
     * the `else` and lit OFF — so a bud-side Adaptive switch showed "no noise
     * control is on" AND "cancelling is off" at the same time, both wrong. The
     * fall-through to "Off" is kept for a genuinely unknown string, but Adaptive
     * must never reach it.
     */
    private fun circleFor(mode: String): String = when (mode) {
        "Transparency" -> "Trans"
        "Adaptive" -> "Adapt"
        "ANC-Light", "ANC-Medium", "ANC-Deep" -> "ANC"
        else -> "Off"
    }

    private fun onAncCircleTapped(circle: String) {
        // Logged unconditionally: the Transparency button was reported as doing
        // nothing and not reacting. There are two possible causes and the log
        // separates them — either the tap never reaches this handler (layout /
        // touch problem), or it does and the buds reject the frame (protocol
        // problem). Without this line those look identical from the outside.
        PacketLogger.log("ANC TAP: circle=$circle current=$activeAncMode")

        when (circle) {
            // ANC opens the chooser UNCONDITIONALLY — it no longer applies a level
            // directly or requires the circle to already be active. Choosing which
            // strength to use is the whole point of the button, and requiring a
            // second tap on an already-lit circle was an unnecessary step.
            "ANC" -> selectAnc(lastAncLevel)
            "Off" -> selectAnc("Off")
            // Adaptive is a plain state, not a chooser: unlike ANC it has no
            // strengths to pick between, so the tap applies it directly.
            "Adapt", "Adaptive" -> selectAnc("Adaptive")
            // The BUTTON passes "Transparency"; the display label and circleFor()
            // use "Trans". Accepting only "Trans" here meant every Transparency tap
            // fell through the when with NO else, so no command was sent and nothing
            // was rendered — which is exactly the "Trans does nothing" report, and
            // the log line reads circle=Transparency. Accept both spellings; do not
            // "tidy" this back to one of them.
            "Trans", "Transparency" -> selectAnc("Transparency")
            else -> PacketLogger.log("ANC TAP: unhandled circle='$circle' (no command sent)")
        }
    }

    /**
     * Applies an ANC mode: sends the command, updates state, repaints everything.
     *
     * Smart is deliberately NOT handled. The user does not want it, so there is no UI
     * path that can select it and no command is sent for it. The command builder
     * (OpoProtocol.ancSmart) and the manager's sendAncSmart remain, because the tile
     * can still reach it and removing a protocol capability is a bigger change than
     * this request.
     *
     * ADAPTIVE, BY CONTRAST, IS HANDLED — and it is a different mode from Smart, not
     * another name for it: the buds set them with different bits (0x0800 vs 0x0080)
     * and report them differently too. "The adaptive mode" in the note above meant the
     * one the vendor app calls Adaptive, which he now wants on the main screen.
     */
    private fun selectAnc(mode: String) {
        when (mode) {
            "Off" -> manager.sendAncOff()
            "Transparency" -> manager.sendAncTransparency()
            "ANC-Light" -> manager.sendAncLight()
            "ANC-Medium" -> manager.sendAncMedium()
            "ANC-Deep" -> manager.sendAncDeep()
            "Adaptive" -> manager.sendAncAdaptive()
        }
        activeAncMode = mode
        syncWidgetState()
        renderAnc(mode)
    }

    private fun toggleGameMode(next: Boolean) {
        manager.setGameMode(next)
        gameModeOn = next
        syncWidgetState()
    }

    /**
     * Pushes our optimistic state into the store and REPAINTS THE WIDGET.
     *
     * The refreshAll() call is the whole reason this method exists. Writing the
     * store is not enough on its own: the widget renders from it only when
     * something asks it to, and MainActivity's own storeListener posts a callback
     * rather than raising one. Without the explicit refresh, a tap in the APP
     * updated the store but left the widget showing stale icons until some other
     * event happened to refresh it — which is exactly the one-way sync that was
     * reported (widget -> app worked, app -> widget did not).
     *
     * BudsService does the same thing after a buds-side event; both directions
     * must refresh, so keep these two calls in step.
     */
    private fun syncWidgetState() {
        val state = WidgetStateStore.read(this)
        state.ancMode = activeAncMode
        state.gameMode = gameModeOn
        WidgetStateStore.write(this, state)
        AncWidgetProvider.refreshAll(this)
    }

    // ==================== Settings rows ====================

    /**
     * Builds the settings card.
     *
     * Row order is the order specified: game mode and the two switches first, so
     * the block above the fold is the one that gets used most, then the three rows
     * that open another screen.
     *
     * Every row through here must be honest about what it does.
     */
    private fun buildFeatureRows() {
        featureList.removeAllViews()
        gameSwitch = null
        hiresSwitch = null
        hiresSubtitle = null
        spatialSwitch = null

        // --- 1. Game mode / low latency ---
        val game = SettingRowFactory.buildSwitch(this, gameModeOn)
        gameSwitch = game
        game.setOnCheckedChangeListener { _, isChecked ->
            // setOnCheckedChangeListener fires when we programmatically set
            // isChecked too, which would echo a bud-side gesture back as a
            // command. The store listener guards against that by only writing
            // when the value actually differs.
            if (syncingFeatures) return@setOnCheckedChangeListener
            if (isChecked != gameModeOn) toggleGameMode(isChecked)
        }
        addRow(
            SettingRowFactory.build(
                this, R.drawable.ic_bolt, R.string.row_game_title, R.string.row_game_sub, game
            ) { game.performClick() }
        )

        // --- 2. Hi-Res codec + 3. Spatial / 3D audio ---
        // Both are 0x0403 feature switches (0x18 / 0x1B), [CAPTURE] 2026-09-23, PROTOCOL.md §9.
        // They are mutually exclusive: turning one on while the other is on also turns the
        // other off, in the order HeyMelody sends them. Any codec change makes the buds drop
        // and reconnect, so those writes go through a warning first — as HeyMelody does.
        // The switches show the BUDS' state: onFeatureStates() repaints them from 0x810D.
        val hires = SettingRowFactory.buildSwitch(this, false)
        hiresSwitch = hires
        hires.setOnCheckedChangeListener { _, isChecked ->
            if (syncingFeatures) return@setOnCheckedChangeListener
            setSwitchQuiet(hires, !isChecked)
            val dropSpatial = isChecked && featureOn(OpoProtocol.FEATURE_SPATIAL_SOUND)
            confirmReconnect(
                if (dropSpatial) R.string.codec_msg_hires_drops_spatial else R.string.codec_msg_reconnect
            ) {
                setSwitchQuiet(hires, isChecked)
                if (dropSpatial) manager.setFeatures(
                    OpoProtocol.FEATURE_SPATIAL_SOUND to false, OpoProtocol.FEATURE_HIRES_CODEC to true
                )
                else manager.setFeatures(OpoProtocol.FEATURE_HIRES_CODEC to isChecked)
            }
        }
        val hiresRow = SettingRowFactory.build(
            this, R.drawable.ic_hires, R.string.row_hires_title, R.string.row_hires_sub_off, hires
        ) { hires.performClick() }
        hiresSubtitle = hiresRow.findViewWithTag<TextView>(SettingRowFactory.SUBTITLE_TAG)
        addRow(hiresRow)

        val spatial = SettingRowFactory.buildSwitch(this, false)
        spatialSwitch = spatial
        spatial.setOnCheckedChangeListener { _, isChecked ->
            if (syncingFeatures) return@setOnCheckedChangeListener
            if (isChecked && featureOn(OpoProtocol.FEATURE_HIRES_CODEC)) {
                setSwitchQuiet(spatial, false)
                confirmReconnect(R.string.codec_msg_spatial_drops_hires) {
                    setSwitchQuiet(spatial, true)
                    manager.setFeatures(
                        OpoProtocol.FEATURE_SPATIAL_SOUND to true, OpoProtocol.FEATURE_HIRES_CODEC to false
                    )
                }
            } else {
                manager.setFeatures(OpoProtocol.FEATURE_SPATIAL_SOUND to isChecked)
            }
        }
        addRow(
            SettingRowFactory.build(
                this, R.drawable.ic_spatial, R.string.row_spatial_title, R.string.row_spatial_sub, spatial
            ) { spatial.performClick() }
        )

        // --- 4. Equalizer ---
        addRow(
            SettingRowFactory.build(
                this, R.drawable.ic_equalizer, R.string.row_eq_title, R.string.row_eq_sub,
                SettingRowFactory.buildChevron(this)
            ) { startActivity(Intent(this, EqActivity::class.java)) }
        )

        // --- 5. Earbud settings: gestures, wear detection, find, alert volume (option A, [USER] 2026-09-25) ---
        // Keeps this card to what changes the sound. App update moved to the cog.
        addRow(
            SettingRowFactory.build(
                this, R.drawable.ic_earbud, R.string.row_earbuds_title, R.string.row_earbuds_sub,
                SettingRowFactory.buildChevron(this)
            ) { startActivity(Intent(this, EarbudSettingsActivity::class.java)) }
        )

        if (::manager.isInitialized) onFeatureStates(manager.featureStates)
    }

    /** True while a switch is being set from code, so its listener does not send a write. */
    private var syncingFeatures = false

    private fun setSwitchQuiet(s: Switch, on: Boolean) {
        syncingFeatures = true
        s.isChecked = on
        syncingFeatures = false
    }

    private fun featureOn(id: Int) = manager.featureStates[id] == 1

    /** HeyMelody-style warning before any write that makes the buds reconnect. Dismiss = decline. */
    private fun confirmReconnect(messageRes: Int, onAccept: () -> Unit) {
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(R.string.codec_dialog_title))
            .message(getString(messageRes))
            .confirm(getString(R.string.codec_dialog_accept)) { sheet.close(); onAccept() }
            .show()
    }

    override fun onFeatureStates(states: Map<Int, Int>) {
        // Disconnected, the switches stay neutral (SPEC 3.2); setConnectedUi repaints on connect.
        if (connectedUi == false) return
        states[OpoProtocol.FEATURE_HIRES_CODEC]?.let { v ->
            hiresSwitch?.let { setSwitchQuiet(it, v == 1) }
            hiresSubtitle?.setText(if (v == 1) R.string.row_hires_sub else R.string.row_hires_sub_off)
        }
        states[OpoProtocol.FEATURE_SPATIAL_SOUND]?.let { v ->
            spatialSwitch?.let { setSwitchQuiet(it, v == 1) }
        }
    }

    /** Adds a row plus a divider, skipping the divider after the final row. */
    private fun addRow(row: View) {
        if (featureList.childCount > 0) {
            featureList.addView(SettingRowFactory.buildDivider(this))
        }
        featureList.addView(row)
    }

    // ==================== Settings dialog ====================

    /**
     * The settings cog: theme, and App update (moved off the main card 2026-09-25).
     *
     * RECONNECT AND DISCONNECT USED TO LIVE HERE and have moved to Dev Tools at his
     * request: they are connection plumbing, not a setting, and sitting next to
     * "Theme" made the cog look like it might do something destructive. Dev Tools
     * already owns the connection diagnostics, so that is where they belong.
     */
    private fun showSettingsDialog() {
        BottomSheetDialog(this)
            .title(getString(R.string.settings_title))
            .items(listOf(
                BottomSheetDialog.Item(getString(R.string.theme_title), false) { showThemeDialog() },
                BottomSheetDialog.Item(getString(R.string.row_update_title), false) {
                    startActivity(Intent(this, UpdateActivity::class.java))
                }
            ))
            .show()
    }

    /**
     * Theme picker, as a bottom sheet.
     *
     * Replaces an AlertDialog: that rendered as a centred platform box with stock
     * accents, which is the mismatch this whole pass is fixing. The theme is
     * applied by recreate(), so the sheet is dismissed first — leaving it up while
     * the activity rebuilds leaves an orphaned window on some devices.
     */
    private fun showThemeDialog() {
        val active = PaletteStore.activeId(this)
        val presets = PaletteStore.builtInIds().map { PaletteStore.builtIn(this, it) } + PaletteStore.custom(this)
        BottomSheetDialog(this)
            .title(getString(R.string.theme_title))
            .items(presets.map { p ->
                BottomSheetDialog.Item(label = p.name, selected = p.id == active) {
                    // The stale check in QuickBudsApp rebuilds every open activity on resume.
                    PaletteStore.setActive(this, p.id)
                    recreate()
                }
            })
            .show()
    }

    // ==================== Permissions / connection ====================

    /**
     * Requests anything missing, and starts the service only once it is granted.
     *
     * The service start MUST NOT happen in onCreate unconditionally: the
     * connectedDevice foreground type requires BLUETOOTH_CONNECT at
     * startForeground() time and throws SecurityException without it, killing the
     * app on first launch. So the startup order is now:
     *
     *   onCreate -> request permissions (nothing started)
     *        |
     *   onRequestPermissionsResult -> if granted, start+bind the service
     *
     * and the already-granted case short-circuits straight to the service, so a
     * normal launch reconnects as before.
     *
     * POST_NOTIFICATIONS is requested but NOT required to start the service: it
     * only affects whether the (IMPORTANCE_MIN, swipeable) notification is shown.
     * A user who denies it should still get a working connection, so it is
     * deliberately excluded from hasBluetoothPermissions().
     */
    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isEmpty()) {
            // Nothing to ask for — normal launch path.
            startAndBindServiceIfPermitted()
        } else {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    /**
     * Permission dialog result.
     *
     * Starts the service as soon as the Bluetooth permissions are available, so
     * the app becomes usable immediately after the user accepts, rather than
     * needing a restart. If they were denied, the service stays down and the
     * feature rows will simply report no connection — the app no longer dies.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return

        if (hasBluetoothPermissions()) {
            startAndBindServiceIfPermitted()
        } else {
            // Denied. Say so once, in plain language, and point at the fix rather
            // than leaving a screen where every control silently does nothing.
            AlertDialog.Builder(this)
                .setTitle("Bluetooth permission needed")
                .setMessage(
                    "QuickBuds needs the Nearby devices permission to talk to your earbuds. " +
                        "Grant it in Settings, then reopen the app."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.dialog_close, null)
                .show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectDirectly() {
        if (!isBound) {
            toast("Service not bound yet.")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Bluetooth is off.")
            return
        }
        val device = adapter.getRemoteDevice(TARGET_MAC)
        manager.connect(device, withAudio = true)
    }

    /**
     * Toast helper, kept for genuinely exceptional events only.
     *
     * The connect/disconnect toasts are GONE at his request — routine events should
     * not interrupt. This remains so a future fatal case has somewhere to go that is
     * not a view on a packet path; the 800ms debounce is the guard that stopped an
     * earlier version producing a toast storm when onStatus was wired straight to it.
     */
    private var lastToastAt = 0L

    private fun toast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 800) return
        lastToastAt = now
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ==================== BudsConnectionManager.Listener ====================

    override fun onStatus(msg: String) {
        // NO TOASTS. He asked for them off: they are noise for routine connect and
        // disconnect events, and the connection state is now shown properly in the
        // header (the status pill).
        //
        // Kept silent rather than deleted so the ONE case that is genuinely worth
        // interrupting for can be added later. Nothing here should become a general
        // channel again: onStatus fires per packet on some paths, and an earlier
        // version wired toasts straight to it and produced a toast storm.
        //
        // A failure that the user MUST know about goes through the header pill and
        // the log, not a toast. If a genuinely fatal case appears (a crash), that is
        // CrashLogger's job, not this method's.
    }

    // ==================== BudsConnectionManager.Listener ====================

    override fun onConnected(connected: Boolean) {
        // The connection state is rendered from WidgetStateStore in renderWear
        // (header pill + setConnectedUi), so there is one source for it.
    }

    override fun onPacketReceived(bytes: ByteArray) {}

    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {
        // The store is the single source of truth for the panel; letting battery
        // callbacks paint the bars directly would race with it on every packet.
        // Nothing to do here beyond letting the store listener above handle it.
    }

    override fun onBudState(state: String) {}
    override fun onWearState(left: Int, right: Int, caseSt: Int) {}

    override fun onGameModeState(on: Boolean) {
        // Routed through the store, not painted here, for the same reason ANC is.
    }

    override fun onAncModeState(mode: String) {
        // Routed through the store, not painted here, for the same reason game mode is.
    }

    companion object {
        /** The three real noise-cancelling levels, weakest first. */
        private val ANC_LEVELS = listOf("ANC-Light", "ANC-Medium", "ANC-Deep")

        /** Noise-control segments, left to right: the tap name onAncCircleTapped() takes, label, icon. */
        private val ANC_SEGMENTS = listOf(
            Triple("Off", R.string.anc_seg_off, R.drawable.ic_noise_off),
            Triple("ANC", R.string.anc_seg_anc, R.drawable.ic_anc),
            Triple("Adaptive", R.string.anc_seg_adapt, R.drawable.ic_adaptive),
            Triple("Transparency", R.string.anc_seg_trans, R.drawable.ic_transparency)
        )

        private const val KEY_ANC_LEVEL = "homeAncLevel"
        /** Home tile order / hidden set, by tile root id name (SPEC section 4). */
        const val KEY_TILE_ORDER = "homeTileOrder"
        const val KEY_TILE_HIDDEN = "homeTileHidden"
        val LOCKED_TILES = setOf("batteryCard", "ancRow")

        /**
         * The last report MainActivity took of ITSELF, while resumed.
         *
         * WHY THIS EXISTS: the Layout button lives on Dev Tools, so fetching a
         * report on demand can never describe the main screen — by the time the
         * button is reachable, MainActivity is paused. The first version held a
         * reference to the Activity instead and therefore always fell back to
         * dumping Dev Tools (visible as the "layout_devtools_" filename).
         *
         * So the main screen captures its own tree in onResume and parks the TEXT
         * here. Text, not the Activity: keeping a paused Activity alive to ask it
         * questions later is the leak this avoids.
         */
        @JvmStatic
        var cachedReport: String? = null
            private set

        /** Called by MainActivity once its tree has been laid out. */
        @JvmStatic
        fun cacheReport(text: String) {
            cachedReport = text
        }

        /**
         * The currently resumed MainActivity, or null.
         *
         * Only used to decide whether a CACHED report is still meaningful, and as
         * a fallback target. Cleared in onPause.
         */
        @JvmStatic
        var instance: MainActivity? = null
            private set
    }
}
