package com.spizganed.quickbuds.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
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
import com.spizganed.quickbuds.bluetooth.BudsDevice
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.ConnectPolicy
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.bluetooth.VirtualBuds
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.RowRules
import com.spizganed.quickbuds.protocol.Feature
import com.spizganed.quickbuds.protocol.FeatureSync
import com.spizganed.quickbuds.protocol.FeatureSyncConfig
import com.spizganed.quickbuds.widget.AncWidgetProvider
import com.spizganed.quickbuds.widget.WidgetSettings
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * Main screen — rebuilt from scratch against the reference design.
 *
 * WHAT CHANGED AND WHY, in order:
 *   1. The old "status panel" (a capped mirror of the home-screen widget, sitting
 *      in a row with a dead column beside it) is gone. The battery block is now a
 *      full-width card at the top of the scroll, with the L/C/R bars beside the
 *      icons instead of underneath them, and the letters moved into circles.
 *   2. ANC is four circular buttons. A tap on ANC opens the strength picker under them.
 *   3. The settings rows are a real card with themed switches and chevrons.
 *
 * THEMING
 * Colours come from resources (values/colors.xml + the two override folders)
 * and are swapped by ThemeRes.select() before super.onCreate(). Nothing here sets a
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
    /** Whether the pill was drawn after the buds' first data arrived (connected, but still reading, shows Connecting). */
    private var lastReadyShown = true
    /** Set 8 s after the link came up, so buds that never report a battery level do not stay on Connecting. */
    private var readyForced = false

    // Status card + noise control, both custom-drawn: see BudsStatusView and
    // AncSegmentedView.
    private lateinit var statusView: BudsStatusView
    private lateinit var ancView: AncSegmentedView
    /** The ANC level chooser: under the modes while an ANC level is on ([openLevels]). */
    private lateinit var levelView: AncSegmentedView
    private lateinit var tiles: LinearLayout

    // Settings rows that hold live state
    private var gameSwitch: Switch? = null
    private var gameSubtitle: TextView? = null
    private var hiresSwitch: Switch? = null
    private var hiresSubtitle: TextView? = null
    private var spatialSwitch: Switch? = null
    private var windSwitch: Switch? = null
    private var spatialSubtitle: TextView? = null
    private var goldenSwitch: Switch? = null
    private var windSubtitle: TextView? = null
    private var goldenSubtitle: TextView? = null

    /** Last state from the store. */
    private var lastRendered: WidgetStateStore.State? = null

    private val REQUEST_PERMISSIONS = 1001

    /** Fade time for the connection change. */
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
     * The dot takes the accent when connected and the secondary text when not. Glass uses a
     * mint dot. The pill's own background IS themed, so the container still matches the app.
     */
    private fun renderConnectionPill(connected: Boolean, ready: Boolean = true) {
        // The link is up but the buds have told nothing yet: "Connecting" until the first level arrives or 8 s pass.
        val reading = connected && !ready && !readyForced
        if (!connected) readyForced = false
        if (lastConnShown == connected && lastReadyShown == !reading) return
        if (reading) connPill.postDelayed({
            readyForced = true
            renderConnectionPill(WidgetStateStore.read(this).connected, true)
        }, 8_000)
        val first = lastConnShown == null
        lastConnShown = connected
        lastReadyShown = !reading

        fun apply() {
            // Accent dot + text label when connected, grey ring + grey label when not.
            val p = ThemeRes.palette(this)
            val dotRes = if (connected && !reading) R.drawable.ic_status_dot_filled else R.drawable.ic_status_dot_empty
            val dotColor = if (connected && !reading) p.accent else p.textSecondary
            if (ThemeRes.glass(this) && !ThemeRes.nothing(this)) {
                // Glass: a small state dot (mint connected, amber connecting, grey ring off); "Connect" is the lit, inviting pill.
                val on = connected && !reading
                val state = if (on) 0xFF5BE49B.toInt() else if (reading) 0xFFFFC857.toInt() else p.textSecondary
                connDot.colorFilter = null
                connDot.setImageDrawable(ThemeRes.tint(this, dotRes, state))
                connDot.layoutParams = (connDot.layoutParams as LinearLayout.LayoutParams).apply { width = ThemeRes.dp(this@MainActivity, 12f); height = width }
                connPill.minimumWidth = 0
                connPill.setPadding(ThemeRes.dp(this, 16f), 0, ThemeRes.dp(this, 18f), 0)
                connPill.background = ThemeRes.ripple(this, ThemeRes.GlassDrawable(this, ThemeRes.FULL, selected = !connected, flat = true))
            } else if (ThemeRes.nothing(this)) {
                connDot.colorFilter = null
                connDot.setImageDrawable(ThemeRes.tint(this, dotRes, dotColor))
            } else {
                connDot.setImageResource(dotRes)
                connDot.setColorFilter(dotColor)
            }
            // The word is the ACTION (the pill is a button); the dot and colour carry the state.
            connText.setText(if (reading) R.string.conn_connecting else if (connected) R.string.conn_action_disconnect else R.string.conn_action_connect)
            connText.setTextColor(if (connected && !reading) p.text else p.textSecondary)
            connPill.contentDescription = getString(if (reading) R.string.conn_connecting else if (connected) R.string.conn_on else R.string.conn_off) +
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
     * Disconnected, the battery tile keeps its exact size (track-only rings, "—") and
     * every other tile is dimmed to 0.35 and made inert, recursively. Icons keep their accent
     * (dimmed, not greyed); toggles and segments show a neutral state. Nothing collapses, so
     * nothing jumps on connect.
     */
    private fun setConnectedUi(connected: Boolean) {
        if (connectedUi == connected) return
        val first = connectedUi == null
        connectedUi = connected
        statusView.connected = connected
        // INVISIBLE, not GONE: the battery tile keeps its height, so nothing jumps on connect.
        if (first || animatorScale() == 0f) {
            deviceNameText.visibility = if (connected) View.VISIBLE else View.INVISIBLE
            deviceNameText.alpha = 1f
        } else {
            deviceNameText.visibility = View.VISIBLE
            deviceNameText.animate().alpha(if (connected) 1f else 0f).setDuration(CARD_ANIM_MS)
                .withEndAction { if (!connected) deviceNameText.visibility = View.INVISIBLE }.start()
        }

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
        if (connected) gameSwitch?.setFromBuds(gameModeOn) else gameSwitch?.snapFromBuds(false)
        if (!connected) { hiresSwitch?.snapFromBuds(false); spatialSwitch?.snapFromBuds(false); goldenSwitch?.snapFromBuds(false); windSwitch?.snapFromBuds(false) }
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
                sw.setFromBuds(state.gameMode)
            }
        }
    }

    private fun renderWear(state: WidgetStateStore.State) {
        setConnectedUi(state.connected)
        // The header pill shows the same state, with its own transition.
        renderConnectionPill(state.connected, state.leftBattery >= 0 || state.caseBattery >= 0 || state.rightBattery >= 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Theme selection FIRST, before super.onCreate. setTheme() is a style-id
        // lookup and cannot fail; this replaced a runtime configuration override
        // that crashed on launch five times (see ThemeRes).
        ThemeRes.select(this)

        super.onCreate(savedInstanceState)
        UpdateChecker.clearDownload(this)

        // First start: the setup runs first and opens this screen when it ends.
        // A refused start (report 67: display 6) keeps this screen instead of crashing.
        if (!SetupActivity.done(this) && SetupActivity.start(this)) {
            finish()
            return
        }

        // Crash report FIRST, before anything that could throw. If the layout or
        // the service binding below is what is crashing on this build, the user
        // must still be able to see the previous crash's report — otherwise the
        // report is unreachable forever. This is the only reason it is not shown
        // after the UI is built.
        maybeShowCrashReport()
        UsagePing.send(this)

        setContentView(R.layout.activity_main)
        
        mainLayout = findViewById<LinearLayout>(R.id.mainLayout)
        featureList = findViewById<LinearLayout>(R.id.featureList)
        deviceNameText = findViewById<TextView>(R.id.deviceNameText)
        findViewById<ImageButton>(R.id.btnModel).apply {
            background = ThemeRes.ripple(this@MainActivity, ThemeRes.iconButton(this@MainActivity))
            setImageDrawable(ThemeRes.tint(this@MainActivity, R.drawable.ic_model_list, ThemeRes.color(this@MainActivity,
                if (ThemeRes.material(this@MainActivity)) R.attr.appColorTextSecondary else R.attr.appColorAccent)))
            setOnClickListener { startActivity(Intent(this@MainActivity, ModelActivity::class.java)) }
        }
        showModelName()
        btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        btnDevTools = findViewById<ImageButton>(R.id.btnDevTools)

        statusView = BudsStatusView(this)
        findViewById<android.widget.FrameLayout>(R.id.statusSlot).addView(statusView)
        buildAnc()

        tiles = findViewById<LinearLayout>(R.id.tiles)
        batteryCard = findViewById<LinearLayout>(R.id.batteryCard)
        ancRow = findViewById<LinearLayout>(R.id.ancRow)

        connPill = findViewById<LinearLayout>(R.id.connPill)
        // No ripple: the pill sinks a little under the finger and springs back, like a real button.
        ThemeRes.sinkOnPress(connPill)
        connDot = findViewById<ImageView>(R.id.connDot)
        connText = findViewById<TextView>(R.id.connText)

        // The pill is also THE connect/disconnect button (as HeyMelody has
        // one): the dot shows the state, the word is the action. Same service actions as Dev Tools.
        connPill.setOnClickListener {
            val connected = lastConnShown == true
            // Disconnecting asks first; connecting does not.
            if (connected) {
                ConfirmDialog.show(
                    this, getString(R.string.disconnect_title, deviceNameText.text),
                    getString(R.string.disconnect_body), getString(R.string.conn_action_disconnect)
                ) { toggleConnection(true) }
            } else toggleConnection(false)
        }

        applyThemeTints()
        buildFeatureRows()

        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
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
     * At most one launch prompt per screen start, in this order: crash report, permissions, update, model test, known issues, the phone-link hint.
     * A prompt shows only when no sheet is open and this screen has the focus (no permission request on top).
     */
    private var promptShown = false

    /** True, and taken, when a launch prompt may show now. */
    private fun claimPrompt(): Boolean {
        if (promptShown || BottomSheetDialog.open > 0 || !hasWindowFocus() || isFinishing) return false
        promptShown = true
        return true
    }

    private var askTestPending = false

    /** Asks [maybeAskTest] a little later, once the connect's reads (model, `0x810D`) are in. */
    private fun scheduleAskTest() {
        if (askTestPending || promptShown) return
        askTestPending = true
        featureList.postDelayed(askTest, 3000)
    }

    private val askTest = Runnable { askTestPending = false; maybeShowReply(); maybeAskTest(); maybeAnnounceIssues(); maybeAnnounceLink() }

    /** The developer's reply to a report of this install ([UsagePing.takeReply]); shown once, before the other prompts. */
    private fun maybeShowReply() {
        if (!claimPrompt()) return
        val text = UsagePing.takeReply(this) ?: run { promptShown = false; return }
        ConfirmDialog.show(this, getString(R.string.reply_title), text, getString(R.string.welcome_done), cancelRes = null)
    }

    /** Once per model: connected buds whose model has known issues ([KnownIssues]). */
    private fun maybeAnnounceIssues() {
        if (!WidgetStateStore.read(this).connected || !KnownIssues.shouldAnnounce(this) || !claimPrompt()) return
        KnownIssues.markAnnounced(this)
        ConfirmDialog.show(
            this, getString(R.string.settings_issues_title), getString(R.string.issues_dialog_body),
            getString(R.string.issues_dialog_action), R.string.test_ask_later
        ) { startActivity(Intent(this, KnownIssuesActivity::class.java)) }
    }

    /** Once per install: the phone's earbud settings can hold the link ([KnownIssues.shouldAnnounceLink]). */
    private fun maybeAnnounceLink() {
        if (!KnownIssues.shouldAnnounceLink(Build.MANUFACTURER, KnownIssues.linkAnnounced(this)) || !claimPrompt()) return
        KnownIssues.markLinkAnnounced(this)
        ConfirmDialog.show(
            this, getString(R.string.issue_oneplus_title), getString(R.string.oneplus_hint),
            getString(R.string.welcome_done), cancelRes = null
        )
    }

    /** Once per model: connected buds with a feature shown that no owner confirmed ([ModelTest]). */
    private fun maybeAskTest() {
        if (!WidgetStateStore.read(this).connected || !ModelTest.shouldAsk(this) || !claimPrompt()) return
        ModelTest.markAsked(this)
        ConfirmDialog.show(
            this, getString(R.string.report_title), getString(R.string.test_ask_body),
            getString(R.string.test_title), R.string.test_ask_later
        ) { startActivity(Intent(this, ModelTestActivity::class.java)) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A sheet or the permission request closed: the model test may ask now.
        if (hasFocus) scheduleAskTest()
    }

    /** Sends the Connect / Disconnect service action for the header chip. */
    private fun toggleConnection(connected: Boolean) {
        connText.setText(if (connected) R.string.conn_disconnecting else R.string.conn_connecting)
        startService(
            Intent(this, BudsService::class.java).setAction(
                if (connected) BudsService.ACTION_FORCE_DISCONNECT else BudsService.ACTION_FORCE_CONNECT
            ).putExtra(BudsService.EXTRA_WITH_AUDIO, true)
                .putExtra(BudsService.EXTRA_USER_CONNECT, true)
        )
        // A failed connect may not change the stored state, so nothing would repaint the
        // pill. ponytail: fixed timeout; a real "connecting" state in the store if it matters.
        lastConnShown = null
        connPill.postDelayed({
            if (lastConnShown == null) renderConnectionPill(WidgetStateStore.read(this).connected)
        }, 25_000)
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
        if (!hasBluetoothPermissions() && !VirtualBuds.on(this)) return
        if (isBound) return
        val serviceIntent = Intent(this, BudsService::class.java)
        // Virtual buds needs no Bluetooth permission, so the connectedDevice foreground type (which
        // needs BLUETOOTH_CONNECT) is not asked for: a plain start keeps the service alive while bound.
        if (VirtualBuds.on(this)) {
            startService(serviceIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        promptShown = true

        val dp = { v: Float -> ThemeRes.dp(this, v) }

        // Selectable, in a scroll box: stack traces are long and the point is that the user can get the text out.
        val body = TextView(this).apply {
            text = report
            setTextIsSelectable(true)
            setTextColor(ThemeRes.color(this@MainActivity, R.attr.appColorTextPrimary))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
        }
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(240f)
            ).apply { marginStart = dp(10f); marginEnd = dp(10f) }
            addView(body)
        }

        // Marked as shown BEFORE showing: if the sheet is dismissed by a config change or a swipe, it must not
        // come back on the next onCreate.
        prefs.edit().putString(ThemeRes.KEY_LAST_SHOWN_CRASH, stamp).apply()

        // The saved-to line matters: the report has to be reachable WITHOUT the app working. Tapping outside dismisses.
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(R.string.crash_title))
            .message(getString(R.string.crash_saved_to))
            .content(scroll)
            .confirm(getString(R.string.problem_send)) {
                sheet.close()
                startActivity(Intent(this, ReportActivity::class.java).putExtra(ReportActivity.EXTRA_CRASH, report))
            }
            .cancel(getString(R.string.crash_share)) {
                startActivity(Intent.createChooser(CrashLogger.shareIntent(this, report), getString(R.string.crash_share_title)))
            }
            .show()
    }

    override fun onDestroy() {
        // Left in onCreate for the first-run setup: nothing was built.
        if (!::featureList.isInitialized) return super.onDestroy()
        WidgetStateStore.removeListener(storeListener)
        featureList.removeCallbacks(askTest)
        unbind()
        super.onDestroy()
    }

    /** Set when onStop let go of the service, so onStart binds again (not on the first start). */
    private var unboundOnStop = false

    override fun onStart() {
        super.onStart()
        if (unboundOnStop) {
            unboundOnStop = false
            startAndBindServiceIfPermitted()
        }
    }

    override fun onStop() {
        // Background service off: a bound main screen kept the service (and the link) alive after
        // Home, and on Android 12+ Back does not destroy it either. Let go here; QuickBudsApp stops
        // the service once no screen is visible, and onStart binds (and connects) again.
        if (isBound && !BudsService.backgroundAllowed(this)) {
            unbind()
            unboundOnStop = true
        }
        super.onStop()
    }

    private fun unbind() {
        if (!isBound) return
        try {
            manager.removeListener(this)
            unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
            // Already unbound (double onDestroy); nothing to do.
        }
        isBound = false
    }

    override fun onResume() {
        super.onResume()
        // Settings › Home layout may have changed the row order.
        if (featureRows.isNotEmpty()) layoutFeatureRows()
        // The model list may have changed the model, and with it the noise modes.
        showModelName()
        relayoutIfSupportChanged()
        // ...and the Hi-Res row between the plain switch and the codec picker.
        if (::manager.isInitialized) onFeatureStates(manager.featureStates)
        // Settings › Developer › Dev tools button (default on).
        btnDevTools.visibility = if (getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_DEV_TOOLS_BUTTON, true)) View.VISIBLE else View.GONE
        // The phone may have changed its codec while the app was away, or does so when a stream starts.
        refreshCodecRow()
        val filter = IntentFilter(android.bluetooth.BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
        // The Bluetooth stack sends ACTION_PLAYING_STATE_CHANGED from its own process, so a not-exported receiver never gets it. The action is protected.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(a2dpReceiver, filter, RECEIVER_EXPORTED) else registerReceiver(a2dpReceiver, filter)
        // The process stays alive in the background service, so onCreate runs only on a cold start.
        // A check on each resume is what the user gets after 6 h, or when an earlier prompt postponed it.
        // Its own gate: the shared once-per-start flag would hold an update back for the life of this screen.
        UpdateChecker.maybeAutoCheck(this) {
            (BottomSheetDialog.open == 0 && hasWindowFocus() && !isFinishing).also { if (it) promptShown = true }
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(a2dpReceiver) } catch (_: IllegalArgumentException) {}
    }

    /** A stream starting or stopping is when the phone settles on a codec: read it again, 0.5 s later. */
    private val a2dpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            window.decorView.postDelayed({ refreshCodecRow() }, 500)
        }
    }

    /** Reads the phone link's codec again, then repaints the Hi-Res row from it. The reply sets the codec within 0.4 s. */
    private fun refreshCodecRow() {
        if (!::manager.isInitialized || connectedUi != true || !rowSupported("hires")) return
        manager.refreshCodec()
        window.decorView.postDelayed({ if (connectedUi == true) onFeatureStates(manager.featureStates) }, 400)
    }

    // ==================== Theme ====================

    /**
     * Tints the ICON BUTTONS in the header, and nothing else.
     *
     * IT MUST NOT TOUCH THE BATTERY-CARD ICONS. It used to, and that was a real bug:
     * this method runs before renderWear(), so the two fought over the same three
     * ImageViews — this one setting a themed tint, renderWear overwriting with the
     * widget's white/grey. Whichever ran last won, so the case appeared to change
     * size and colour depending on when a packet happened to arrive, and the buds
     * sometimes showed a stale drawable. All three card icons are owned solely by
     * BudsStatusView.
     *
     * The header frames use ThemeRes.iconButton in EVERY theme, so their glyphs take
     * the accent, like the EQ and row-chevron buttons.
     */
    private fun applyThemeTints() {
        ThemeRes.screenPadding(mainLayout)
        // The bottom padding belongs to the scrolling content, not to the screen: a card scrolls under the edge instead of being
        // cut off above a gap, and the last card clears the system bar.
        val scroll = findViewById<android.widget.ScrollView>(R.id.mainScroll)
        scroll.clipToPadding = false
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val side = ThemeRes.dp(this, 16f)
            v.setPadding(side + bars.left, ThemeRes.dp(this, 16f) + bars.top, side + bars.right, 0)
            scroll.setPadding(0, 0, 0, ThemeRes.dp(this, 20f) + bars.bottom)
            insets
        }
        connPill.background = ThemeRes.ripple(this, ThemeRes.iconButton(this, 16f))
        btnDevTools.background = ThemeRes.ripple(this, ThemeRes.iconButton(this))
        btnSettings.background = ThemeRes.ripple(this, ThemeRes.iconButton(this))
        batteryCard.background = ThemeRes.group(this)
        featureList.background = ThemeRes.group(this)
        featureList.clipToOutline = true
        SettingRowFactory.noiseCard(ancRow)
        connText.typeface = ThemeRes.medium(this)
        deviceNameText.typeface = ThemeRes.medium(this)
        // Material: header icons in on-surface-variant, as Google's icon buttons.
        val headerIcon = ThemeRes.color(this, if (ThemeRes.material(this)) R.attr.appColorTextSecondary else R.attr.appColorAccent)
        btnDevTools.setImageDrawable(ThemeRes.tint(this, R.drawable.ic_dev_tools, headerIcon))
        btnSettings.setImageDrawable(ThemeRes.tint(this, R.drawable.ic_settings_cog, headerIcon))
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
            closeLevels(animate = false)
            return
        }
        val level = ancModes.levels.indexOf(mode)
        // ANC tapped, no strength picked yet: ANC stays lit and the chooser stays up with nothing lit; nothing was sent.
        val waiting = ancAwaitingPick && level < 0
        if (!waiting) ancAwaitingPick = false
        ancView.selected = if (waiting) ancSegments.indexOfFirst { it.first == "ANC" } else ancSegments.indexOfFirst { it.first == circleFor(mode) }
        levelView.selected = level
        // The chooser is up while ANC is lit, and gone for Off, Transparency and Adaptive.
        if ((level >= 0 || waiting) && ancModes.levels.size > 1) openLevels(animate = ancView.width > 0) else closeLevels(animate = ancView.width > 0)
        if (level >= 0) lastAncLevel = mode
    }

    override fun onSmartLevel() = renderAnc(activeAncMode)

    /** The ANC strength the ANC segment applies; the last one seen, else the buds' middle one. */
    private var lastAncLevel: String
        get() = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getString(KEY_ANC_LEVEL, null)
            ?.takeIf { it in ancModes.levels } ?: ancModes.levels.filter { it != AncModes.SMART }.let { it.getOrElse(it.size / 2) { AncModes.LEVELS[1] } }
        set(v) { getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_ANC_LEVEL, v).apply() }

    /** The connected model's noise modes ([AncModes]); the segments and pills show only these. */
    private lateinit var ancModes: AncModes
    private var ancSegments = ANC_SEGMENTS

    /**
     * The segments and the level picker, for the modes these buds have. Rebuilt when the model changes.
     * The picker is the widget's (it replaced the Low / Medium / High pills): the ANC
     * segment slides it in, a pick applies that level and slides back, the lit level turns ANC off.
     */
    private fun buildAnc() {
        ancModes = AncModes.of(this)
        ancSegments = ANC_SEGMENTS.filter { if (it.first == "ANC") ancModes.levels.isNotEmpty() else ancModes.supports(it.first) }
        ancView = AncSegmentedView(this, ancSegments.map { getString(it.second) }, ancSegments.map { it.third }).apply {
            onSegmentTapped = { onAncCircleTapped(ancSegments[it].first) }
        }
        val levels = ancModes.levels.map { m -> WidgetSettings.MODES.first { it.store == m } }
        levelView = AncSegmentedView(this, levels.map { getString(it.short) }, levels.map { it.icon }).apply {
            visibility = View.GONE
            onSegmentTapped = { i -> selectAnc(ancModes.levels[i]) }
        }
        // The mode control with the level chooser under it: the chooser drops down when ANC is tapped, and folds away when not needed.
        findViewById<android.widget.FrameLayout>(R.id.ancSlot).apply {
            removeAllViews()
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(ancView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(levelView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ThemeRes.dp(this@MainActivity, 8f) })
            })
        }
    }

    private var levelsOpen = false

    /** ANC was tapped and no strength is picked yet. */
    private var ancAwaitingPick = false

    /** The level chooser drops down under the modes. */
    private fun openLevels(animate: Boolean) {
        if (levelsOpen) return
        levelsOpen = true
        if (animate) Motion.slide(levelView, true) else levelView.visibility = View.VISIBLE
    }

    /** The chooser folds up. */
    private fun closeLevels(animate: Boolean) {
        if (!::levelView.isInitialized || !levelsOpen) return
        levelsOpen = false
        if (animate) Motion.slide(levelView, false) else levelView.visibility = View.GONE
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
        "Transparency" -> "Transparency"
        "Adaptive" -> "Adaptive"
        "ANC-Light", "ANC-Medium", "ANC-Deep", AncModes.SMART -> "ANC"
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
            // ANC opens the level picker unconditionally, lit or not, as the widget's button does.
            // More than one level: the widget's picker. One: apply it.
            // ANC lights up and the strength chooser drops down; nothing is sent until a strength is picked.
            "ANC" -> if (ancModes.levels.size > 1) {
                if (activeAncMode !in ancModes.levels) { ancAwaitingPick = true; renderAnc(activeAncMode) }
            } else selectAnc(lastAncLevel)
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
     * Smart is an ANC level (its pill after High: near-full HeyMelody parity).
     * Adaptive is a different mode, not another name for it: the buds set them with
     * different bits (0x0800 vs 0x0080) and report them differently too.
     */
    private fun selectAnc(mode: String) {
        if (!::manager.isInitialized) return
        ancAwaitingPick = false
        manager.sendAnc(mode)
        activeAncMode = mode
        syncWidgetState()
        renderAnc(mode)
    }

    private fun toggleGameMode(next: Boolean) {
        if (!::manager.isInitialized) return
        val gameId = Capabilities.gameModeId(this)
        manager.requestSetting(
            gameId,
            listOf(gameId to (if (next) 1 else 0)),
            FeatureSyncConfig.gameConfirm(ModelCatalog.current(this)?.json)
        )
    }

    /** A tap on the Golden Sound switch asks for [wantOn]; the buds' own report moves it. */
    private fun requestGolden(wantOn: Boolean) {
        if (!::manager.isInitialized) return
        manager.requestSetting(
            Feature.GOLDEN_SOUND,
            listOf(Feature.GOLDEN_SOUND to (if (wantOn) 1 else 0)),
            FeatureSync.Confirm.READ
        )
    }

    /** A tap on the wind noise switch asks for [wantOn]; the buds' own report moves it. */
    private fun requestWind(wantOn: Boolean) {
        if (!::manager.isInitialized) return
        manager.requestSetting(
            Feature.WIND_NOISE,
            listOf(Feature.WIND_NOISE to (if (wantOn) 1 else 0)),
            FeatureSync.Confirm.READ
        )
    }

    /**
     * A tap on the plain 3D audio switch. On with Hi-Res on is ONE operation: spatial first, then
     * Hi-Res off, and the link restarts (HeyMelody's order). Models with a spatial type stay old.
     */
    private fun requestSpatial(wantOn: Boolean) {
        if (!::manager.isInitialized) return
        if (spatialByType()) return setSpatial(if (wantOn) 1 else 0)
        if (wantOn == spatialOn()) return
        val dropHires = wantOn && featureOn(Feature.HIRES_CODEC) && !spatialSwitchOnly()
        val write = {
            manager.requestSetting(
                Feature.SPATIAL_SOUND,
                if (dropHires) listOf(Feature.SPATIAL_SOUND to 1, Feature.HIRES_CODEC to 0)
                else listOf(Feature.SPATIAL_SOUND to (if (wantOn) 1 else 0)),
                FeatureSync.Confirm.READ,
                restarts = dropHires
            )
        }
        if (dropHires) confirmReconnect(R.string.codec_msg_spatial_drops_hires, write) else write()
    }

    /**
     * A tap on the Hi-Res switch asks for [wantOn]. On codec-picker models the switch moves at
     * once. On the other models the buds' report moves it. Every dialog, the codec guard and the
     * codec refresh are as before.
     */
    private fun requestHires(wantOn: Boolean) {
        if (!::manager.isInitialized) return
        val hires = hiresSwitch ?: return
        if (codecPicker()) {
            val codec = manager.codec ?: return
            confirmReconnect(R.string.codec_msg_reconnect) {
                setSwitchQuiet(hires, wantOn)
                manager.setCodec(codec, wantOn)
            }
            return
        }
        val apply = fun() {
            // Buds that offer a codec list refuse this switch unless the phone plays LDAC or LHDC (status 2, reports #33 #42 #44).
            // A model with no high-quality codec in `codecs` gets no guard: the known issue explains why.
            val played = manager.codec
            val best = hiresBestCodec()
            if (wantOn && best != null && manager.codecs.isNotEmpty() && played != null && played != 3 && played != 7 && played != 8) {
                Toast.makeText(this, getString(R.string.hires_needs_codec, codecName(played), best), Toast.LENGTH_LONG).show()
                return
            }
            val dropSpatial = wantOn && spatialOn() && !spatialSwitchOnly()
            confirmReconnect(
                if (dropSpatial) R.string.codec_msg_hires_drops_spatial else R.string.codec_msg_reconnect
            ) {
                if (dropSpatial && spatialByType()) {
                    manager.setSpatialType(0)
                    paintSpatial()
                    hires.postDelayed({
                        manager.requestSetting(
                            Feature.HIRES_CODEC,
                            listOf(Feature.HIRES_CODEC to 1),
                            FeatureSync.Confirm.READ,
                            restarts = true
                        )
                    }, 300)
                } else {
                    manager.requestSetting(
                        Feature.HIRES_CODEC,
                        if (dropSpatial) listOf(Feature.SPATIAL_SOUND to 0, Feature.HIRES_CODEC to 1)
                        else listOf(Feature.HIRES_CODEC to (if (wantOn) 1 else 0)),
                        FeatureSync.Confirm.READ,
                        restarts = true
                    )
                }
            }
        }
        // The phone can switch codec after the connect: read it again before the guard looks at it.
        if (wantOn && manager.codecs.isNotEmpty()) {
            manager.refreshCodec()
            hires.postDelayed(apply, 400)
        } else apply()
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
     * Rows build in this order: wind, game, Hi-Res, spatial, Golden Sound, equalizer,
     * dual connection, earbud settings.
     *
     * Every row through here must be honest about what it does.
     */
    private fun buildFeatureRows() {
        featureRows.clear()
        gameSwitch = null
        gameSubtitle = null
        hiresSwitch = null
        hiresSubtitle = null
        spatialSwitch = null
        goldenSwitch = null
        windSwitch = null
        windSubtitle = null
        goldenSubtitle = null

        // --- Wind noise reduction, feature 0x1A (realme Link data, PROTOCOL.md §16), under noise control ---
        val wind = SettingRowFactory.buildSwitch(this, false)
        windSwitch = wind
        // A tap asks for the opposite value; the switch moves only when the buds report one.
        (wind as RequestSwitch).onRequest = { wantOn -> requestWind(wantOn) }
        addRow("wind",
            SettingRowFactory.build(
                this, R.drawable.ic_anc, R.string.row_wind_noise_title, R.string.row_wind_noise_sub, wind
            ) { wind.performClick() }.also { windSubtitle = it.findViewWithTag(SettingRowFactory.SUBTITLE_TAG) }
        )

        // --- 1. Game mode / low latency ---
        val game = SettingRowFactory.buildSwitch(this, gameModeOn)
        gameSwitch = game
        // A tap asks for the opposite value; the switch moves only when the buds report one.
        (game as RequestSwitch).onRequest = { wantOn ->
            if (wantOn != gameModeOn) toggleGameMode(wantOn)
        }
        addRow("game", 
            SettingRowFactory.build(
                this, R.drawable.ic_bolt, R.string.row_game_title, R.string.row_game_sub, game
            ) { game.performClick() }.also { gameSubtitle = it.findViewWithTag(SettingRowFactory.SUBTITLE_TAG) }
        )

        // --- 2. Hi-Res codec + 3. Spatial / 3D audio ---
        // Both are 0x0403 feature switches (0x18 / 0x1B), [CAPTURE] 2026-09-23, PROTOCOL.md §16.
        // They are mutually exclusive: turning one on while the other is on also turns the
        // other off, in the order HeyMelody sends them. Any codec change makes the buds drop
        // and reconnect, so those writes go through a warning first — as HeyMelody does.
        // The switches show the BUDS' state: onFeatureStates() repaints them from 0x810D.
        val hires = SettingRowFactory.buildSwitch(this, false)
        hiresSwitch = hires
        // A tap asks for the opposite value; the switch moves only when the buds report one.
        (hires as RequestSwitch).onRequest = { wantOn -> requestHires(wantOn) }
        val hiresRow = SettingRowFactory.build(
            this, R.drawable.ic_hires, R.string.row_hires_title, R.string.row_hires_sub_off, hires
        ) { if (codecPicker()) codecSheet() else hires.performClick() }
        hiresSubtitle = hiresRow.findViewWithTag<TextView>(SettingRowFactory.SUBTITLE_TAG)
        addRow("hires", hiresRow)

        val spatial = SettingRowFactory.buildSwitch(this, false)
        spatialSwitch = spatial
        // A tap asks for the opposite value; the switch moves only when the buds report one.
        (spatial as RequestSwitch).onRequest = { wantOn -> requestSpatial(wantOn) }
        val spatialRow = SettingRowFactory.build(
            this, R.drawable.ic_spatial, R.string.row_spatial_title, R.string.row_spatial_sub, spatial
        ) { if (spatialHeadTracking()) spatialSheet() else spatial.performClick() }
        spatialSubtitle = spatialRow.findViewWithTag<TextView>(SettingRowFactory.SUBTITLE_TAG)
        addRow("spatial", spatialRow)

        // --- Golden Sound: the hearing profile on the buds, feature 0x0B [VENDOR] (PROTOCOL.md §20) ---
        // The row opens the Golden Sound screen (profiles, hearing test); the switch toggles it.
        val golden = SettingRowFactory.buildSwitch(this, false)
        goldenSwitch = golden
        // A tap asks for the opposite value; the switch moves only when the buds report one.
        (golden as RequestSwitch).onRequest = { wantOn -> requestGolden(wantOn) }
        addRow("golden",
            SettingRowFactory.build(
                this, R.drawable.ic_hearing, R.string.row_golden_title, R.string.row_golden_sub, golden
            ) { startActivity(Intent(this, GoldenSoundActivity::class.java)) }
                .also { goldenSubtitle = it.findViewWithTag(SettingRowFactory.SUBTITLE_TAG) }
        )

        // --- 4. Equalizer ---
        addRow("eq", 
            SettingRowFactory.build(
                this, R.drawable.ic_equalizer, R.string.row_eq_title, R.string.row_eq_sub,
                SettingRowFactory.buildChevron(this)
            ) { startActivity(Intent(this, EqActivity::class.java)) }
        )

        // --- 5. Dual connection, on the home screen too ---
        addRow(
            "dual",
            SettingRowFactory.build(
                this, R.drawable.ic_devices, R.string.row_dual_title, R.string.row_dual_sub,
                SettingRowFactory.buildChevron(this)
            ) { startActivity(Intent(this, DualDeviceActivity::class.java)) }
        )

        // --- 6. Earbud settings: gestures, wear detection, find, alert volume (option A) ---
        // Keeps this card to what changes the sound. App update moved to the cog.
        addRow("earbuds", 
            SettingRowFactory.build(
                this, R.drawable.ic_settings_cog, R.string.row_earbuds_title, R.string.row_earbuds_sub,
                SettingRowFactory.buildChevron(this)
            ) { startActivity(Intent(this, EarbudSettingsActivity::class.java)) }
        )

        if (::manager.isInitialized) onFeatureStates(manager.featureStates)
        layoutFeatureRows()
    }

    /** True while a switch is being set from code, so its listener does not send a write. */
    private var syncingFeatures = false

    private fun setSwitchQuiet(s: Switch, on: Boolean) {
        syncingFeatures = true
        s.isChecked = on
        syncingFeatures = false
    }

    /** A state from the earbuds: the first paint snaps, a later real change slides, an equal value does nothing. */
    private fun setSwitchFromBuds(s: Switch, on: Boolean) {
        syncingFeatures = true
        s.setFromBuds(on)
        syncingFeatures = false
    }

    private fun featureOn(id: Int) = manager.featureStates[id] == 1

    private fun spatialByType() = MainActivity.spatialByType(this)
    private fun spatialSwitchOnly() = MainActivity.spatialSwitchOnly(this)
    private fun spatialHeadTracking() = MainActivity.spatialHeadTracking(this)

    private fun spatialOn() = if (spatialByType()) (manager.spatialType ?: 0) != 0
        else featureOn(Feature.SPATIAL_SOUND)

    /** Spatial and Hi-Res never coexist: turning spatial on with Hi-Res on asks first, then drops Hi-Res. */
    private fun setSpatial(type: Int) {
        val dropHires = type != 0 && featureOn(Feature.HIRES_CODEC) && !spatialSwitchOnly()
        val write = {
            spatialSwitch?.let { setSwitchQuiet(it, type != 0) }
            val hiresOff = Feature.HIRES_CODEC to false
            if (spatialByType()) {
                manager.setSpatialType(type)
                paintSpatial()
                // Spatial first, then the codec, as HeyMelody sends them.
                if (dropHires) spatialSwitch?.postDelayed({ manager.setFeatures(hiresOff) }, 300)
            } else if (dropHires) manager.setFeatures(Feature.SPATIAL_SOUND to true, hiresOff)
            else manager.setFeatures(Feature.SPATIAL_SOUND to (type != 0))
        }
        if (!dropHires) return write()
        spatialSwitch?.let { setSwitchQuiet(it, spatialOn()) }
        confirmReconnect(R.string.codec_msg_spatial_drops_hires, write)
    }

    private fun codecPicker() = MainActivity.codecPicker(this)

    private fun codecName(codec: Int) =
        listOf("SBC", "AAC", "LDAC", "aptX", "aptX HD", "aptX Adaptive", "LHDC", "LHDC").getOrNull(codec - 1) ?: "#$codec"

    /** The best codec of the model or of the buds' offered list; null when neither names one. */
    private fun hiresBestCodec() = ModelCatalog.hiresBestCodec(this, manager.codecs)

    private fun codecSheet() {
        val current = manager.codec
        // HeyMelody's order: LHDC V5, LHDC, LDAC, aptX Adaptive, aptX HD, aptX, AAC, SBC.
        val codecs = listOf(8, 7, 3, 6, 5, 4, 2, 1).filter { it in manager.codecs }
        BottomSheetDialog(this)
            .title(getString(R.string.row_hires_title))
            .items(codecs.map { codec ->
                BottomSheetDialog.Item(codecName(codec), codec == current) {
                    if (codec != current) confirmReconnect(R.string.codec_msg_reconnect) {
                        manager.setCodec(codec, featureOn(Feature.HIRES_CODEC))
                    }
                }
            })
            .show()
    }

    private fun spatialLabel(type: Int) = getString(when (type) {
        1 -> R.string.spatial_fixed
        2 -> R.string.spatial_head_tracking
        else -> R.string.anc_seg_off
    })

    private fun spatialSheet() {
        val current = manager.spatialType ?: 0
        BottomSheetDialog(this)
            .title(getString(R.string.row_spatial_title))
            .items(listOf(0, 1, 2).map { type ->
                BottomSheetDialog.Item(spatialLabel(type), type == current) { setSpatial(type) }
            })
            .show()
    }

    /** Switch and (head-tracking models) subtitle from the buds' last state. */
    private fun paintSpatial() {
        if (connectedUi == false) return
        spatialSwitch?.let { setSwitchFromBuds(it, spatialOn()) }
        if (spatialHeadTracking()) spatialSubtitle?.text = spatialLabel(manager.spatialType ?: 0)
        else spatialSubtitle?.setText(R.string.row_spatial_sub)
    }

    /** HeyMelody-style warning before any write that makes the buds reconnect. Dismiss = decline. */
    private fun confirmReconnect(messageRes: Int, onAccept: () -> Unit) {
        val sheet = BottomSheetDialog(this)
        sheet.title(getString(R.string.codec_dialog_title))
            .message(getString(messageRes))
            .confirm(getString(R.string.codec_dialog_accept)) { sheet.close(); onAccept() }
            .cancel(getString(R.string.codec_dialog_deny)) { sheet.close() }
            .show()
    }

    override fun onFeatureStates(states: Map<Int, Int>) {
        relayoutIfSupportChanged()
        scheduleAskTest()
        // Disconnected, the switches stay neutral; setConnectedUi repaints on connect.
        if (connectedUi == false) return
        states[Feature.HIRES_CODEC]?.let { v ->
            hiresSwitch?.let { setSwitchFromBuds(it, v == 1) }
            hiresSubtitle?.setText(if (v == 1) R.string.row_hires_sub else R.string.row_hires_sub_off)
        }
        val codec = manager.codec.takeIf { codecPicker() }
        if (codec != null) hiresSubtitle?.text = codecName(codec)
        // Quality switch on, but the phone plays another codec than LHDC: name the model's best codec
        // (report #17). Without a known best codec the hint stays off.
        val played = manager.codec
        val best = hiresBestCodec()
        if (codec == null && best != null && played != null && played != 7 && played != 8 && states[Feature.HIRES_CODEC] == 1)
            hiresSubtitle?.text = getString(R.string.row_hires_sub_codec, codecName(played), best)
        hiresSwitch?.isEnabled = codec == null || codec == 3 || codec == 8
        paintSpatial()
        states[Feature.GOLDEN_SOUND]?.let { v ->
            goldenSwitch?.let { setSwitchFromBuds(it, v == 1) }
        }
        states[Feature.WIND_NOISE]?.let { v ->
            windSwitch?.let { setSwitchFromBuds(it, v == 1) }
        }
    }

    /**
     * The engine says a tapped setting is being applied: the row shows "Applying…" and its switch
     * cannot be tapped again. On the way back, [onFeatureStates] repaints from the buds' own values.
     */
    override fun onSettingBusy(key: Int, on: Boolean) {
        val gameId = Capabilities.gameModeId(this)
        val sw: Switch?
        val sub: TextView?
        when (key) {
            Feature.HIRES_CODEC -> { sw = hiresSwitch; sub = hiresSubtitle }
            gameId -> { sw = gameSwitch; sub = gameSubtitle }
            Feature.SPATIAL_SOUND -> { sw = spatialSwitch; sub = spatialSubtitle }
            Feature.GOLDEN_SOUND -> { sw = goldenSwitch; sub = goldenSubtitle }
            Feature.WIND_NOISE -> { sw = windSwitch; sub = windSubtitle }
            else -> return
        }
        if (on) {
            sw?.isEnabled = false
            sub?.setText(R.string.feature_applying)
            return
        }
        onFeatureStates(manager.featureStates)
        // onFeatureStates returns early while disconnected: take "Applying…" away by hand.
        when (key) {
            Feature.HIRES_CODEC -> if (connectedUi == false) sub?.setText(R.string.row_hires_sub_off)
            gameId -> sub?.setText(R.string.row_game_sub)
            Feature.SPATIAL_SOUND -> if (connectedUi == false) sub?.setText(R.string.row_spatial_sub)
            Feature.GOLDEN_SOUND -> sub?.setText(R.string.row_golden_sub)
            Feature.WIND_NOISE -> sub?.setText(R.string.row_wind_noise_sub)
        }
        // onFeatureStates leaves the Hi-Res switch disabled for a codec it cannot run with.
        if (key != Feature.HIRES_CODEC) sw?.isEnabled = connectedUi != false
    }

    override fun onCapabilities() { showModelName(); relayoutIfSupportChanged(); scheduleAskTest() }

    /** The model's name from the model list; its Bluetooth name if the list has no match. */
    private fun showModelName() {
        deviceNameText.text = ModelCatalog.current(this)?.name
            ?: getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getString(ModelCatalog.KEY_DEVICE_NAME, null)
            ?: getString(R.string.default_device_name)
    }

    private fun rowSupported(key: String) = MainActivity.rowSupported(this, key)

    private var supportSignature: String? = null

    /** Relays the rows only when what the buds support changed, not on every status poll. */
    private fun relayoutIfSupportChanged() {
        val rows = rowsOf(this)
        val sig = rows.joinToString()
        if (sig == supportSignature) return
        supportSignature = sig
        if (AncModes.of(this) !== ancModes) { buildAnc(); renderAnc(activeAncMode) }
        layoutFeatureRows(rows)
    }

    /** The sound-settings rows by key, in build (default) order; laid out by [layoutFeatureRows]. */
    private val featureRows = LinkedHashMap<String, View>()

    private fun addRow(key: String, row: View) {
        featureRows[key] = row
    }

    /**
     * Places the rows in the order and visibility set in Settings › Home layout
     * ([KEY_ROW_ORDER] / [KEY_ROW_HIDDEN]); unknown or new keys fall back to build order.
     * The tile disappears when every row is hidden.
     */
    private fun layoutFeatureRows(rows: Set<String> = rowsOf(this)) {
        val prefs = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)
        val hidden = prefs.getStringSet(KEY_ROW_HIDDEN, emptySet()).orEmpty()
        featureList.removeAllViews()
        for (key in rowOrder(prefs, featureRows.keys.toList())) {
            val row = featureRows[key] ?: continue
            (row.parent as? android.view.ViewGroup)?.removeView(row)
            if (key !in hidden && key in rows) SettingRowFactory.addRow(featureList, row)
        }
        featureList.visibility = if (featureList.childCount == 0) View.GONE else View.VISIBLE
        ancRow.visibility = if (RowRules.ANC in rows) View.VISIBLE else View.GONE
        setEnabledDeep(featureList, connectedUi != false)
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
        // Dev tools › Virtual buds: a socket needs no Bluetooth permission, so nothing is asked.
        if (VirtualBuds.on(this)) {
            startAndBindServiceIfPermitted()
            return
        }
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
            promptShown = true
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
            ConfirmDialog.show(
                this, getString(R.string.perm_title), getString(R.string.perm_body),
                getString(R.string.perm_action), cancelRes = R.string.dialog_close
            ) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectDirectly() {
        if (!isBound) {
            toast("Service not bound yet.")
            return
        }
        // Dev tools › Virtual buds: skip the adapter and the paired-device list. The emulator has no adapter.
        if (VirtualBuds.on(this)) {
            val target = VirtualBuds.parseAddress(VirtualBuds.address(this))
            if (target == null) {
                toast("Virtual buds: set a host and port.")
                return
            }
            manager.connectVirtual(target.first, target.second, VirtualBuds.name(this))
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast("Bluetooth is off.")
            return
        }
        val device = BudsDevice.find(this)
        if (device == null) {
            toast(getString(R.string.no_buds_paired))
            return
        }
        // App open: an automatic connect starts only with the live audio link. With the buds in
        // the closed case Android reports disconnected, and the tries would only fail.
        if (!ConnectPolicy.autoConnectAllowed(manager.audioLinkState(device), virtual = false)) return
        manager.connect(device, withAudio = true)
    }

    /**
     * Toast helper, kept for genuinely exceptional events only.
     *
     * The connect/disconnect toasts are GONE at his request — routine events should
     * not interrupt. This remains so a future fatal case has somewhere to go that is
     * not a view on a packet path; the 800ms debounce is the guard that stopped an
     * earlier version producing a toast storm when it was wired to a per-packet callback.
     */
    private var lastToastAt = 0L

    private fun toast(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 800) return
        lastToastAt = now
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ==================== BudsConnectionManager.Listener ====================

    override fun onConnected(connected: Boolean) {
        // The connection state is rendered from WidgetStateStore in renderWear
        // (header pill + setConnectedUi), so there is one source for it.
    }

    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {
        // The store is the single source of truth for the panel; letting battery
        // callbacks paint the bars directly would race with it on every packet.
        // Nothing to do here beyond letting the store listener above handle it.
    }

    override fun onWearState(left: Int, right: Int, caseSt: Int) {}

    override fun onGameModeState(on: Boolean) {
        // Routed through the store, not painted here, for the same reason ANC is.
    }

    override fun onAncModeState(mode: String) {
        // Routed through the store, not painted here, for the same reason game mode is.
    }

    companion object {
        /** Noise-control segments, left to right: the tap name onAncCircleTapped() takes, label, icon. */
        val ANC_SEGMENTS = listOf(
            Triple("Off", R.string.anc_seg_off, R.drawable.ic_noise_off),
            Triple("ANC", R.string.anc_seg_anc, R.drawable.ic_mode_anc_medium),
            Triple("Adaptive", R.string.anc_seg_adapt, R.drawable.ic_adaptive),
            Triple("Transparency", R.string.anc_seg_trans, R.drawable.ic_transparency)
        )

        private const val KEY_ANC_LEVEL = "homeAncLevel"
        /** Home screen sound-settings rows: order and hidden set, by row key (Settings › Home layout). */
        const val KEY_ROW_ORDER = "homeRowOrder"
        const val KEY_ROW_HIDDEN = "homeRowHidden"

        /**
         * Whether the connected buds have a row's feature at all ([RowRules]): a `0x0403`
         * switch must be in their `0x810D` reply, a command in their `0x8100` bitmap. Nothing read
         * yet = no conditional row. Earbud settings gates its own rows. [ModelTest] lists the same rows.
         */
        fun rowSupported(c: Context, key: String): Boolean = key in rowsOf(c)

        /**
         * The rows the buds' own answers support ([RowRules]). Nothing read yet = only the
         * unconditional rows; the last saved reply shows its rows until the fresh one arrives.
         */
        fun rowsOf(c: Context): Set<String> = RowRules.rows(
            ModelCatalog.current(c)?.json,
            Capabilities.reply(c),
            Capabilities.commands(c),
            AncModes.of(c).slots().takeIf { it.isNotEmpty() }
        )

        /**
         * Spatial audio as HeyMelody picks it (PROTOCOL.md §18): buds whose bitmap has `0x012A` (or a
         * model with head tracking) take a type through `0x0422`, the rest feature `0x1B`.
         */
        fun spatialByType(c: Context) =
            RowRules.spatialByType(ModelCatalog.current(c)?.json, Capabilities.commands(c))

        /**
         * Our own key `spatialToggle` (realme Link data, PROTOCOL.md §18): spatial is feature `1B` alone and
         * Hi-Res `18` alone, never written together, whatever the bitmap says.
         */
        fun spatialSwitchOnly(c: Context) = ModelCatalog.current(c)?.json?.optInt("spatialToggle") == 1

        /** `spatialModes` holds `2`: Off / Fixed / Head tracking instead of a plain switch. */
        fun spatialHeadTracking(c: Context) =
            RowRules.spatialHeadTracking(ModelCatalog.current(c)?.json)

        /**
         * `[VENDOR]` HeyMelody's "High-quality audio" screen on `hiRes` models (PROTOCOL.md §18): the row
         * picks the codec, and the switch (Hi-Res) is live only with LDAC or LHDC V5, as there.
         */
        fun codecPicker(c: Context) =
            RowRules.codecPicker(ModelCatalog.current(c)?.json, Capabilities.commands(c))

        /** Saved row order, completed with any key it does not name, in [defaults] order. */
        fun rowOrder(prefs: android.content.SharedPreferences, defaults: List<String>): List<String> {
            val saved = prefs.getString(KEY_ROW_ORDER, null)?.split(',').orEmpty().filter { it in defaults }
            return (saved + defaults.filter { it !in saved }).distinct()
        }
    }
}
