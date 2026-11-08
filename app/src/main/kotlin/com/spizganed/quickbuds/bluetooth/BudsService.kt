package com.spizganed.quickbuds.bluetooth

import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.tile.AncQuickSettingsTileService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.protocol.RowRules
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.Feature
import com.spizganed.quickbuds.protocol.FeatureSync
import com.spizganed.quickbuds.protocol.FeatureSyncConfig
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.ui.OnCallGesture
import com.spizganed.quickbuds.ui.ThemeRes
import com.spizganed.quickbuds.widget.AncWidgetProvider
import com.spizganed.quickbuds.widget.WidgetSettings
import com.spizganed.quickbuds.widget.WidgetStateStore

class BudsService : Service(), BudsConnectionManager.Listener {

    private val binder = LocalBinder()
    var manager: BudsConnectionManager? = null
        private set
    private val gameEq = GameEqWatcher(this, { manager }, { asleep })

    private val handler = Handler(Looper.getMainLooper())

    /** ANC mode requested from the Quick Settings tile while the RFCOMM link is being opened. */
    private var pendingTileAncMode: String? = null
    private val pendingTileFeatures = mutableListOf<Pair<Int, Boolean>>()
    private var pendingTileGameMode: Boolean? = null

    /** The delayed ACL connect (KeepAliveReceiver), so a deliberate disconnect cancels it. */
    private var aclConnect: Runnable? = null

    private fun statusLog(msg: String) {
        Log.d("BudsConn", msg)
    }

    private val widgetCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_NOTIF_DISMISSED) {
                // Android 14+ lets the user swipe away an ongoing service notification. With the lock on, post it again (issue 36).
                if (lockNotification()) { shownLevels = null; updateNotification() }
                return
            }
            if (intent.action != ACTION_WIDGET_COMMAND) return
            statusLog("<< WIDGET broadcast")
            executeWidgetCommand(intent)
        }
    }

    /**
     * The service sleeps while the phone is locked: the RFCOMM link closes and nothing connects until
     * the user unlocks. Smart auto-pause keeps it awake, because that needs the wear events.
     */
    @Volatile private var asleep = false

    /** Activities bound to this service. While one is open the service stays. */
    @Volatile private var bindCount = 0

    private fun bluetoothOff() =
        getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled != true

    /**
     * The notification belongs to a connection. With nothing connected, nothing connecting and no screen open, the
     * service stops and its notification goes: when Bluetooth is off at once, else after [IDLE_MS]. The buds' audio
     * link and the widgets start it again (KeepAliveReceiver, WidgetActionReceiver).
     */
    private val idleCheck = Runnable {
        val m = manager
        when {
            bindCount > 0 || m == null -> Unit
            m.isConnected() -> Unit
            !m.isIdle() -> scheduleIdleCheck(IDLE_MS)
            // A locked phone sleeps the link on purpose; the unlock wakes it. Only a Bluetooth that is off ends that.
            asleep && !bluetoothOff() -> Unit
            else -> park()
        }
    }

    private fun scheduleIdleCheck(delayMs: Long) {
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, delayMs)
    }

    private fun park() {
        statusLog("[SVC] Park: nothing connected (bluetooth off=${bluetoothOff()})")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)
            if (state == android.bluetooth.BluetoothAdapter.STATE_OFF || state == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF) scheduleIdleCheck(BT_OFF_MS)
        }
    }

    private fun shouldSleep(): Boolean {
        val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked ||
            !getSystemService(android.os.PowerManager::class.java).isInteractive
        return locked && !smartPauseOn()
    }

    private fun smartPauseOn() = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_SMART_PAUSE, false)

    private fun sleep() {
        if (asleep) return
        asleep = true
        statusLog("[SVC] Sleep (screen locked)")
        handler.removeCallbacksAndMessages(null)
        // The game-EQ poll must not wake the phone every 2 s.
        gameEq.stop()
        manager?.disconnect()
    }

    private fun wake() {
        if (!asleep) return
        asleep = false
        statusLog("[SVC] Wake (unlocked)")
        // Start the poll again only when game EQ is on; the link comes up after this call.
        gameEq.update()
        if (backgroundAllowed(this)) forceConnect(false)
        scheduleIdleCheck(IDLE_MS)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (!smartPauseOn()) sleep()
                Intent.ACTION_USER_PRESENT -> wake()
                Intent.ACTION_SCREEN_ON -> if (!getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) wake()
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BudsService = this@BudsService
    }

    override fun onCreate() {
        super.onCreate()
        statusLog("[SVC] onCreate")
        // First: Android kills the app when startForeground() comes later than 5 s after startForegroundService() (report 115).
        startForegroundService()

        manager = BudsConnectionManager(this).also { it.addListener(this) }

        val filter = IntentFilter(ACTION_WIDGET_COMMAND).apply { addAction(ACTION_NOTIF_DISMISSED) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(widgetCommandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(widgetCommandReceiver, filter)
        }

        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, screen)
        registerReceiver(bluetoothStateReceiver, IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        scheduleIdleCheck(if (bluetoothOff()) BT_OFF_MS else IDLE_MS)
        getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
        // Started by a Bluetooth event while locked: stay asleep until the unlock.
        if (backgroundAllowed(this) && shouldSleep()) asleep = true
        // A new service has no link. A connected state from an earlier process is stale (report 158); the sleep keeps it on purpose.
        else if (WidgetStateStore.read(this).connected) onConnected(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        statusLog("[SVC] onStartCommand action=${intent?.action}")
        // Android 12+ kills the app when a startForegroundService() call gets no startForeground() within 5 s. onCreate does
        // it once; a start of a running service needs it again (report 165). A repeat only reposts the notification.
        if (intent != null) startForegroundService()

        when (intent?.action) {
            ACTION_FORCE_CONNECT -> {
                val withAudio = intent.getBooleanExtra(EXTRA_WITH_AUDIO, false)
                val user = intent.getBooleanExtra(EXTRA_USER_CONNECT, false)
                // KeepAliveReceiver: Android just reported the buds' link, so the link check is not needed.
                val fromLink = intent.getBooleanExtra(EXTRA_FROM_LINK, false)
                val delayMs = intent.getLongExtra(EXTRA_DELAY_MS, 0L)
                // A delayed connect is the ACL fallback (KeepAliveReceiver): the audio-profile
                // broadcast normally connects first, and then this finds us connected and does nothing.
                if (delayMs > 0) {
                    aclConnect?.let { handler.removeCallbacks(it) }
                    val run = Runnable { aclConnect = null; forceConnect(withAudio, user, fromLink) }
                    aclConnect = run
                    handler.postDelayed(run, delayMs)
                } else {
                    forceConnect(withAudio, user, fromLink)
                }
            }
            ACTION_FORCE_DISCONNECT -> {
                statusLog("[SVC] FORCE_DISCONNECT")
                // A queued ACL connect must not undo the tap.
                aclConnect?.let { handler.removeCallbacks(it) }
                aclConnect = null
                manager?.disconnect()
                // Only the deliberate disconnect drops phone audio — the manager's own
                // disconnect() also runs after a failed connect attempt.
                BudsDevice.find(this)?.let { manager?.setPhoneAudio(it, on = false) }
            }
            ACTION_TILE_SET_ANC -> {
                val mode = intent.getStringExtra(EXTRA_TILE_ANC_MODE)
                if (mode == null) {
                    statusLog("[TILE] Missing ANC mode; ignored")
                } else if (manager?.isConnected() == true) {
                    sendTileAncMode(mode)
                } else {
                    statusLog("[TILE] Not connected; queue ANC $mode and connect")
                    pendingTileAncMode = mode
                    forceConnect(withAudio = false, user = true)
                }
            }
            ACTION_TILE_SET_FEATURE -> {
                val feature = intent.getIntExtra(EXTRA_TILE_FEATURE, -1)
                val enabled = intent.getBooleanExtra(EXTRA_TILE_FEATURE_ON, false)
                if (feature != Feature.HIRES_CODEC && feature != Feature.SPATIAL_SOUND) {
                    statusLog("[TILE] Unsupported feature id=$feature")
                } else if (manager?.isConnected() == true) {
                    sendTileFeature(feature, enabled)
                } else {
                    statusLog("[TILE] Queue feature id=$feature enabled=$enabled and connect")
                    pendingTileFeatures += feature to enabled
                    forceConnect(withAudio = false, user = true)
                }
            }
            ACTION_TILE_SET_GAME_MODE -> {
                val enabled = intent.getBooleanExtra(EXTRA_TILE_GAME_MODE_ON, false)
                if (manager?.isConnected() == true) {
                    sendTileGameMode(enabled)
                } else {
                    statusLog("[TILE] Queue low latency=$enabled and connect")
                    pendingTileGameMode = enabled
                    forceConnect(withAudio = false, user = true)
                }
            }
            ACTION_WIDGET_COMMAND -> {
                statusLog("<< startService WIDGET_COMMAND")
                if (manager?.isConnected() == true) {
                    executeWidgetCommand(intent)
                } else {
                    statusLog("<< not connected yet — will retry in 800ms")
                    handler.postDelayed({ executeWidgetCommand(intent) }, 800)
                }
            }
            ACTION_SET_GESTURE -> {
                val device = intent.getIntExtra(EXTRA_GESTURE_DEVICE, -1)
                val gestureAction = intent.getIntExtra(EXTRA_GESTURE_ACTION, -1)
                val function = intent.getIntExtra(EXTRA_GESTURE_FUNCTION, -1)
                statusLog("<< SET_GESTURE: dev=0x${
                    "%02X".format(device)} act=0x${
                    "%02X".format(gestureAction)} fn=0x${"%02X".format(function)}")
                if (device < 0 || gestureAction < 0 || function < 0) {
                    statusLog("<< SET_GESTURE: malformed extras, ignored")
                } else if (manager?.isConnected() != true) {
                    statusLog("<< SET_GESTURE: not connected, not sent")
                } else {
                    // WHICH SLOTS GET THE VALUE is the manager's business, not this caller's:
                    // it reads them from the buds' own table (see writeGestureBinding), because
                    // a gesture's button group is not fixed — slide has appeared in btn 0x01
                    // AND split across 0x02/0x03, on different buds and in different sessions.
                    // The manager returns false when no slot exists for the gesture or when it
                    // has no 0x8108 reading to rebuild the full table from. Both refusals are
                    // the SAFE outcome, so they are logged and NOT retried: a write aimed at a
                    // slot the device does not have does nothing, and a made-up table could
                    // wipe bindings we don't model.
                    val sent = manager?.writeGestureBinding(device, gestureAction, function) ?: false
                    if (!sent) statusLog("<< SET_GESTURE: refused (see KEYFN WRITE line above)")
                }
            }
            ACTION_SET_HOLD_MODES -> {
                val mask = intent.getIntExtra(EXTRA_HOLD_MASK, -1)
                statusLog("<< SET_HOLD_MODES: mask=0x${"%04X".format(mask)}")
                if (mask < 0) {
                    statusLog("<< SET_HOLD_MODES: malformed extras, ignored")
                } else if (manager?.isConnected() != true) {
                    statusLog("<< SET_HOLD_MODES: not connected, not sent")
                } else {
                    manager?.sendHoldAncModes(mask, intent.getIntExtra(EXTRA_HOLD_TYPE, OpoProtocol.HOLD_TYPE_SHARED))
                }
            }
            ACTION_GAME_EQ_UPDATE -> gameEq.update()
            ACTION_MODEL_PICKED -> manager?.modelPicked()
            ACTION_FIND_BUDS -> {
                val on = intent.getBooleanExtra(EXTRA_FIND_ON, false)
                statusLog("<< FIND_BUDS: $on")
                manager?.setFindTone(on)
            }
            ACTION_SET_ON_CALL -> {
                val row = intent.getStringExtra(EXTRA_ON_CALL_ROW)
                val enabled = intent.getBooleanExtra(EXTRA_ON_CALL_ENABLED, false)
                statusLog("<< SET_ON_CALL: row=$row enabled=$enabled")
                if (manager?.isConnected() != true) {
                    statusLog("<< SET_ON_CALL: not connected, not sent")
                } else {
                    val g = OnCallGesture.values().firstOrNull { it.serviceRow == row }
                    if (g == null) statusLog("<< SET_ON_CALL: unknown row '$row', ignored")
                    else manager?.sendOnCall(g.act, if (enabled) g.enabledFn else 0x00, g.button)
                }
            }
            else -> statusLog("[SVC] onStartCommand (no action)")
        }
        // Whatever started us (a connect, a widget), the link gets [IDLE_MS] to come up.
        scheduleIdleCheck(IDLE_MS)
        return START_STICKY
    }

    private fun forceConnect(withAudio: Boolean, user: Boolean = false, fromLink: Boolean = false) {
        // A missed unlock broadcast must never leave the service asleep: check the real state.
        if (asleep && !shouldSleep()) { asleep = false; statusLog("[SVC] Wake (state check)") }
        if (asleep) {
            statusLog("[SVC] FORCE_CONNECT ignored (asleep, screen locked)")
            return
        }
        if (manager?.isConnected() == true) {
            statusLog("[SVC] FORCE_CONNECT ignored (already connected)")
            return
        }
        // An automatic connect needs the live audio link. With the buds in the closed case the
        // A2DP / HEADSET state is disconnected, and 5 socket tries would only fail. An unknown state connects as before.
        if (!user && !fromLink) {
            val device = try { BudsDevice.find(this) } catch (_: Exception) { null }
            val link = device?.let { manager?.audioLinkState(it) } ?: LinkState.UNKNOWN
            if (!ConnectPolicy.autoConnectAllowed(link)) {
                statusLog("[SVC] FORCE_CONNECT skipped (the buds have no audio link)")
                // The sleep kept the connected state and the last levels. The buds left during the lock: clear them (reports 134, 135).
                if (WidgetStateStore.read(this).connected) onConnected(false)
                return
            }
        }
        statusLog("[SVC] FORCE_CONNECT: connecting...")
        AncWidgetProvider.setConnecting(this, true)
        try {
            val device = BudsDevice.find(this)
            if (device != null) manager?.connect(device, withAudio, user)
            else { statusLog("[SVC] No paired OPPO / OnePlus / realme buds found"); AncWidgetProvider.setConnecting(this, false) }
        } catch (e: Exception) {
            statusLog("[SVC] Force connect failed: ${e.message}")
        }
    }

    private fun executeWidgetCommand(intent: Intent) {
        val widgetAction = intent.getStringExtra(EXTRA_WIDGET_ACTION)
        val ancMode = intent.getStringExtra(EXTRA_WIDGET_ANC_MODE)
        val gameMode = intent.getBooleanExtra(EXTRA_WIDGET_GAME_MODE, false)
        val feature = intent.getIntExtra(EXTRA_WIDGET_FEATURE, -1)
        val featureOn = intent.getBooleanExtra(EXTRA_WIDGET_FEATURE_ON, false)
        val connected = manager?.isConnected() == true
        statusLog("<< executeWidgetCommand: action=$widgetAction (anc=$ancMode, game=$gameMode, feature=$feature $featureOn, connected=$connected)")

        if (!connected) {
            statusLog("<< SKIPPED — not connected")
            return
        }

        when (widgetAction) {
            // ANC_CYCLE carries a mode name (levels and Adaptive); the manager picks the model's bit.
            "ANC_CYCLE" -> ancMode?.let { statusLog("<< sending ANC $it"); manager?.sendAnc(it) }
            "TRANS" -> { statusLog("<< sending Transparency"); manager?.sendAnc(AncModes.TRANSPARENCY) }
            "OFF" -> { statusLog("<< sending ANC Off"); manager?.sendAnc(AncModes.OFF) }
            "GAME_TOGGLE" -> {
                statusLog("<< sending Game $gameMode")
                val gameId = Capabilities.gameModeId(this)
                manager?.requestSetting(
                    gameId,
                    listOf(gameId to (if (gameMode) 1 else 0)),
                    FeatureSyncConfig.gameConfirm(ModelCatalog.current(this)?.json)
                )
            }
            "FEATURE" -> if (feature >= 0) manager?.setFeatures(feature to featureOn)
            else -> statusLog("<< UNKNOWN widget action: '$widgetAction'")
        }
    }

    /** True for 1 s after a disconnect: the chip shows "– –" before it goes. */
    private var goodbye = false

    /** True when the last posted notification has the levels chip. */
    private var chipShown = false

    /** The RFCOMM link is open. The sleep keeps the connected state with no link: the chip must not show those old levels. */
    private fun linkUp() = manager?.isConnected() == true

    /** The last levels the notification shows, so an unchanged battery event does not repost it. */
    private var shownLevels: String? = null

    /**
     * The service notification. A tap opens the app (issue 26). Connected with a level: the battery rings,
     * drawn like the widget's. Else the plain text.
     */
    private fun buildNotification(): Notification {
        val open = android.app.PendingIntent.getActivity(this, 0,
            Intent(this, com.spizganed.quickbuds.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val full = fullNotification()
        val b = Notification.Builder(this, if (full) CHANNEL_FULL_ID else CHANNEL_ID)
            .setContentTitle("QuickBuds")
            .setContentText("Maintaining connection to earbuds")
            .setSmallIcon(R.drawable.ic_stat_buds)
            .setContentIntent(open)
            .setOngoing(lockNotification())
            .setDeleteIntent(android.app.PendingIntent.getBroadcast(this, 1,
                Intent(ACTION_NOTIF_DISMISSED).setPackage(packageName), android.app.PendingIntent.FLAG_IMMUTABLE))
            .setShowWhen(false)
        val st = WidgetStateStore.read(this)
        val levels = intArrayOf(st.leftBattery, st.caseBattery, st.rightBattery)
        val known = levels.any { it >= 0 }
        chipShown = false
        if (android.os.Build.VERSION.SDK_INT >= 36 && liveChip() && (goodbye || (st.connected && linkUp()))) {
            // Live update (Android 16+): a promoted notification needs a standard style, so no rings. The chip shows chipText.
            // "– –" while the levels are not read yet (connect) and for 1 s after a disconnect.
            if (known && !goodbye) {
                val names = intArrayOf(R.string.status_left, R.string.status_case, R.string.status_right)
                val text = (0..2).filter { levels[it] >= 0 }.joinToString("  ") { "${getString(names[it])} ${levels[it]}%" }
                b.setStyle(Notification.BigTextStyle().bigText(text)).setContentText(text)
            }
            b.setOngoing(true)
            val chip = if (known && !goodbye) chipText(st) else "– –"
            if (chip != null) {
                chipShown = !goodbye
                b.setShortCriticalText(chip)
                // Notification.Builder.setRequestPromotedOngoing is missing on Android 16.0; the extra is the same flag.
                b.extras.putBoolean("android.requestPromotedOngoing", true)
            }
        } else if (full && st.connected && levels.any { it >= 0 }) {
            val v = android.widget.RemoteViews(packageName, R.layout.notification_battery)
            val rings = intArrayOf(R.id.n_ring_l, R.id.n_ring_c, R.id.n_ring_r)
            val pcts = intArrayOf(R.id.n_pct_l, R.id.n_pct_c, R.id.n_pct_r)
            val names = intArrayOf(R.string.status_left, R.string.status_case, R.string.status_right)
            val statuses = intArrayOf(st.leftStatus, -1, st.rightStatus)
            for (slot in 0..2) {
                v.setImageViewBitmap(rings[slot], com.spizganed.quickbuds.widget.QuickBudsWidget.notificationRing(this, levels[slot], slot, statuses[slot]))
                v.setTextViewText(pcts[slot], if (levels[slot] >= 0) "${levels[slot]}%" else "–")
                v.setContentDescription(rings[slot], getString(names[slot]))
            }
            b.setCustomContentView(v).setStyle(Notification.DecoratedCustomViewStyle())
        }
        return b.build()
    }

    /**
     * The status bar chip text, 7 characters at most: the levels of the buds outside the case, left then right
     * ("82 80", "100 100" at most, one number with one bud out). Null when no bud is out: no chip.
     */
    private fun chipText(st: WidgetStateStore.State): String? {
        // Status 4 = in the case, 0 = in the case and asleep (a closed lid).
        fun shown(level: Int, status: Int) = level.takeIf { it >= 0 && status != 4 && status != 0 }
        return listOfNotNull(shown(st.leftBattery, st.leftStatus), shown(st.rightBattery, st.rightStatus))
            .takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** Reposts the notification when the shown levels or the connection changed. */
    private fun updateNotification() {
        val st = WidgetStateStore.read(this)
        val key = "$goodbye ${linkUp()} ${st.connected} ${st.leftBattery} ${st.caseBattery} ${st.rightBattery} ${st.leftStatus} ${st.rightStatus}"
        if (key == shownLevels) return
        shownLevels = key
        try {
            getSystemService(NotificationManager::class.java).notify(1, buildNotification())
        } catch (e: Exception) {
            statusLog("[SVC] notification update failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Two channels: the small one (minimum importance, folded, no status bar icon) and the full one
     * (low importance, open, with the battery rings). [PREF_FULL_NOTIFICATION] picks one.
     */
    private fun createChannels() {
        fun channel(id: String, name: String, importance: Int) = NotificationChannel(id, name, importance).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            description = "Keeps the connection to your earbuds alive"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            channel(CHANNEL_ID, "Buds Connection", NotificationManager.IMPORTANCE_MIN),
            channel(CHANNEL_FULL_ID, "Battery", NotificationManager.IMPORTANCE_LOW)))
    }

    /** The status bar chip (Android 16+ live update): the setting is on and the system lets the app post promoted notifications. */
    private fun liveChip(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 36 || !fullNotification()) return false
        if (!getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_LIVE_CHIP, false)) return false
        return getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
    }

    private fun fullNotification() =
        getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_FULL_NOTIFICATION, true)

    /** Settings › Full notification: repost on the other channel at once. */
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_FULL_NOTIFICATION || key == PREF_LIVE_CHIP || key == PREF_LOCK_NOTIFICATION) { shownLevels = null; updateNotification() }
    }

    /** Settings › Lock the notification: the user cannot swipe the service notification away (issue 36). */
    private fun lockNotification() =
        getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_LOCK_NOTIFICATION, false)

    private fun startForegroundService() {
        createChannels()
        val notification = buildNotification()
        // Android 12+ refuses this when the app is in the background (report: realme RMX3890, Android 15). Stop instead of crashing.
        try {
            startForeground(1, notification)
        } catch (e: IllegalStateException) {
            statusLog("[SVC] startForeground refused: ${e.javaClass.simpleName}")
            stopSelf()
        } catch (e: SecurityException) {
            // A phone without the permission stops here.
            statusLog("[SVC] startForeground refused: ${e.javaClass.simpleName}")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        statusLog("[SVC] onBind")
        bindCount++
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        bindCount = (bindCount - 1).coerceAtLeast(0)
        if (bindCount == 0) scheduleIdleCheck(IDLE_MS)
        return false
    }

    override fun onDestroy() {
        statusLog("[SVC] onDestroy")
        handler.removeCallbacksAndMessages(null)
        gameEq.stop()
        // Close the RFCOMM link with the service: with the background service switched off,
        // leaving the app stops the service, and a live socket must not outlive it.
        try { manager?.disconnect() } catch (e: Exception) { PacketLogger.error("service stop disconnect", e) }
        try { manager?.removeListener(this) } catch (_: Exception) {}
        try { unregisterReceiver(widgetCommandReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(bluetoothStateReceiver) } catch (_: Exception) {}
        getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onConnected(connected: Boolean) {
        // Our own sleep, not a lost link: the widget keeps showing the connected state and the last levels until the unlock reconnects.
        if (!connected && asleep) { statusLog("[SVC] Sleep: widget state kept"); updateNotification(); return }
        val st = WidgetStateStore.read(this)
        val before = st.copy()
        st.connected = connected
        if (connected) AncWidgetProvider.setConnecting(this, false)
        if (connected) {
            statusLog("[SVC] Connected")
            st.caseLidClosed = false
            st.leftDocked = false
            st.rightDocked = false
        } else {
            statusLog("[SVC] Disconnected")
            st.leftBattery = -1
            st.caseBattery = -1
            st.rightBattery = -1
            // Socket died with a bud in the case (4 = docked, 0 = docked & asleep)
            // => lid closed. Freeze the docked set so those icons stay hidden.
            if (st.leftStatus == 4 || st.rightStatus == 4 ||
                st.leftStatus == 0 || st.rightStatus == 0) {
                st.caseLidClosed = true
                st.leftDocked = (st.leftStatus == 4 || st.leftStatus == 0)
                st.rightDocked = (st.rightStatus == 4 || st.rightStatus == 0)
            }
        }
        WidgetStateStore.write(this, st)
        goodbye = false
        if (!connected && before.connected && chipShown && liveChip()) {
            goodbye = true
            handler.postDelayed({ goodbye = false; updateNotification() }, 1000)
        }
        if (before.connected != connected) AncWidgetProvider.animate(this, connected, before.takeIf { it.connected })
        else AncWidgetProvider.refreshAll(this)
        if (connected) {
            pendingTileAncMode?.let { mode ->
                pendingTileAncMode = null
                sendTileAncMode(mode)
            }
            if (pendingTileFeatures.isNotEmpty()) {
                val queued = pendingTileFeatures.toList()
                pendingTileFeatures.clear()
                queued.forEach { (feature, enabled) -> sendTileFeature(feature, enabled) }
            }
            pendingTileGameMode?.let { enabled ->
                pendingTileGameMode = null
                sendTileGameMode(enabled)
            }
        }
        gameEq.update()
        updateNotification()
        if (connected) handler.removeCallbacks(idleCheck) else scheduleIdleCheck(IDLE_MS)
    }

    override fun onBattery(
        left: Int?, case: Int?, right: Int?,
        chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean
    ) {
        statusLog("[SVC] Battery: L=$left C=$case R=$right " +
                "(chg: $chargingLeft/$chargingCase/$chargingRight)")
        val st = WidgetStateStore.read(this)
        val clear = clearMissingBattery(this)
        if (left != null || clear) st.leftBattery = left ?: -1
        if (case != null || clear) st.caseBattery = case ?: -1
        if (case != null) {
            // heartbeat: a closed lid stops these, so freshness == lid open
            st.caseBatteryAt = System.currentTimeMillis()
        }
        if (right != null || clear) st.rightBattery = right ?: -1
        WidgetStateStore.write(this, st)
        AncWidgetProvider.refreshAll(this)
        updateNotification()
    }

    override fun onWearState(leftRaw: Int, rightRaw: Int, caseSt: Int) {
        val firstWorn = (isWorn(leftRaw) || isWorn(rightRaw)) && !ModelCatalog.wearSensorSeen(this)
        if (firstWorn) ModelCatalog.markWearSensorSeen(this)
        // No "in ear" seen yet: "out" may only mean the model has no sensor, so it counts as in the ear (the fit test and the like need that).
        val blind = !ModelCatalog.wearSensorSeen(this)
        val left = if (blind && (leftRaw == 1 || leftRaw == 5)) 3 else leftRaw
        val right = if (blind && (rightRaw == 1 || rightRaw == 5)) 3 else rightRaw
        val st = WidgetStateStore.read(this)
        val wasWorn = isWorn(st.leftStatus) || isWorn(st.rightStatus)
        var changed = false
        if (left >= 0 && st.leftStatus != left) {
            st.leftStatus = left
            st.leftInBox = (left == 4)
            changed = true
        }
        if (right >= 0 && st.rightStatus != right) {
            st.rightStatus = right
            st.rightInBox = (right == 4)
            changed = true
        }
        if (changed || firstWorn) {
            statusLog("[SVC] Wear state: L=$left R=$right case=$caseSt")
            WidgetStateStore.write(this, st)
            AncWidgetProvider.refreshAll(this)
            updateNotification()
        }
        if (wasWorn && !isWorn(st.leftStatus) && !isWorn(st.rightStatus)) smartPause()
    }

    private fun isWorn(st: Int) = st == 3 || st == 7

    /**
     * Smart auto-pause (our own, not the firmware's): pause when the LAST bud leaves an ear.
     * One bud out keeps playing, and nothing ever auto-plays. Off unless enabled on the wear screen.
     */
    private fun smartPause() {
        if (!getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_SMART_PAUSE, false)) return
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        if (!audio.isMusicActive) return
        statusLog("[SVC] Smart pause: both buds out")
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    /**
     * Game mode changed ON THE BUDS (0x0204 subType 0x05).
     *
     * This fires for our own game-mode write AND for the user's buds gesture, so it is
     * the authoritative state. Persisting it here is all that is needed to refresh
     * BOTH UIs: the widget renders from this store, and MainActivity re-renders via
     * its WidgetStateStore listener — no view code has to know about this event.
     */
    override fun onGameModeState(on: Boolean) {
        val st = WidgetStateStore.read(this)
        if (st.gameMode == on) return
        st.gameMode = on
        statusLog("[SVC] Game mode (from buds): ${if (on) "ON" else "OFF"}")
        WidgetStateStore.write(this, st)
        AncWidgetProvider.refreshAll(this)
    }

    /**
     * ANC mode changed ON THE BUDS (0x0204 subType 0x03).
     *
     * Same contract as onGameModeState above: persist, then let the store listener
     * repaint both surfaces. Nothing here touches views directly.
     *
     * The equality check is what makes OUR OWN command's echo a no-op, so a gesture
     * and a tap cannot fight each other over the last write.
     */
    /** The widget buttons' feature values last drawn; a `0x810D` reply repaints only when one changed. */
    private var widgetFeatures: List<Int?>? = null

    override fun onFeatureStates(states: Map<Int, Int>) {
        gameEq.update()
        // The Quick Settings tiles show more switches than the widget, so they repaint on every answer.
        com.spizganed.quickbuds.widget.LiveTileService.refresh(this)
        val now = WidgetSettings.FEATURE_BUTTONS.values.map { states[it] }
        if (now == widgetFeatures) return
        widgetFeatures = now
        AncWidgetProvider.refreshAll(this)
    }

    override fun onAncModeState(mode: String) {
        val st = WidgetStateStore.read(this)
        if (st.ancMode == mode) return
        st.ancMode = mode
        statusLog("[SVC] ANC mode (from buds): $mode")
        WidgetStateStore.write(this, st)
        AncWidgetProvider.refreshAll(this)
        AncQuickSettingsTileService.requestRefresh(this)
    }

    /** Send the floating panel's low-latency toggle through the same model-aware path as MainActivity. */
    private fun sendTileGameMode(enabled: Boolean) {
        val m = manager
        if (m?.isConnected() != true) {
            statusLog("[TILE] Low latency=$enabled not sent: disconnected")
            return
        }
        val gameId = Capabilities.gameModeId(this)
        statusLog("[TILE] Low latency=$enabled (feature id=$gameId)")
        m.requestSetting(
            gameId,
            listOf(gameId to if (enabled) 1 else 0),
            FeatureSyncConfig.gameConfirm(ModelCatalog.current(this)?.json)
        )
    }

    /**
     * Quick Settings floating-panel feature changes. LHDC (the app's Hi-Res feature) and Spatial
     * Sound are mutually exclusive here. Spatial is written first because that is the order used
     * by the app/protocol when a codec restart is needed.
     */
    private fun sendTileFeature(feature: Int, enabled: Boolean) {
        val m = manager
        if (m?.isConnected() != true) {
            statusLog("[TILE] Feature id=$feature not sent: disconnected")
            return
        }
        val spatialByType = RowRules.spatialByType(ModelCatalog.current(this)?.json, Capabilities.commands(this))
        val currentStates = m.featureStates
        val writes: List<Pair<Int, Int>>
        val key: Int
        val restarts: Boolean

        if (feature == Feature.SPATIAL_SOUND && spatialByType) {
            m.setSpatialType(if (enabled) 1 else 0)
            if (enabled && currentStates[Feature.HIRES_CODEC] == 1) {
                handler.postDelayed({
                    m.requestSetting(
                        Feature.HIRES_CODEC,
                        listOf(Feature.HIRES_CODEC to 0),
                        FeatureSync.Confirm.READ,
                        restarts = true
                    )
                }, 300)
            }
            return
        }

        if (feature == Feature.HIRES_CODEC && enabled && spatialByType) {
            m.setSpatialType(0)
            handler.postDelayed({
                m.requestSetting(
                    Feature.HIRES_CODEC,
                    listOf(Feature.HIRES_CODEC to 1),
                    FeatureSync.Confirm.READ,
                    restarts = true
                )
            }, 300)
            return
        }

        key = feature
        writes = when {
            feature == Feature.HIRES_CODEC && enabled -> listOf(
                Feature.SPATIAL_SOUND to 0,
                Feature.HIRES_CODEC to 1
            )
            feature == Feature.SPATIAL_SOUND && enabled -> listOf(
                Feature.SPATIAL_SOUND to 1,
                Feature.HIRES_CODEC to 0
            )
            else -> listOf(feature to if (enabled) 1 else 0)
        }
        restarts = writes.any { it.first == Feature.HIRES_CODEC }
        statusLog("[TILE] Feature id=$feature enabled=$enabled writes=$writes")
        m.requestSetting(key, writes, FeatureSync.Confirm.READ, restarts = restarts)
    }

    /** Send a Quick Settings tile request through the same model-aware ANC controller as the UI. */
    private fun sendTileAncMode(mode: String) {
        if (manager?.isConnected() != true) {
            statusLog("[TILE] ANC $mode not sent: still disconnected")
            return
        }
        val modes = AncModes.of(this)
        val target = when {
            mode == AncModes.OFF -> AncModes.OFF
            modes.supports(mode) -> mode
            else -> modes.levels.firstOrNull()
        }
        if (target == null) {
            statusLog("[TILE] ANC $mode not sent: this model has no known ANC mode table")
            return
        }
        statusLog("[TILE] Sending ANC mode $target")
        manager?.sendAnc(target)
        val st = WidgetStateStore.read(this)
        st.ancMode = target
        WidgetStateStore.write(this, st)
        AncWidgetProvider.refreshAll(this)
        AncQuickSettingsTileService.requestRefresh(this)
    }

    companion object {
        /** The service notification's channel. */
        private const val CHANNEL_ID = "QuickBuds_Service"
        /** The full notification's channel: open in the shade, with the battery rings. */
        private const val CHANNEL_FULL_ID = "QuickBuds_Battery"
        /** Settings › Full notification. On by default. */
        const val PREF_FULL_NOTIFICATION = "fullNotification"
        const val PREF_LIVE_CHIP = "liveChip"
        const val PREF_LOCK_NOTIFICATION = "lockNotification"
        const val ACTION_FORCE_CONNECT = "com.spizganed.quickbuds.FORCE_CONNECT"
        /** On FORCE_CONNECT: true when the user asked (pill), so phone audio is brought up too. */
        const val EXTRA_WITH_AUDIO = "with_audio"
        /**
         * On FORCE_CONNECT: true when the user asked (Connect button, widget tap, Dev tools). Only
         * that connect shows the try toasts; an automatic connect is silent.
         */
        const val EXTRA_USER_CONNECT = "user_connect"
        /** On FORCE_CONNECT from [KeepAliveReceiver]: Android reported the buds' ACL or audio link. */
        const val EXTRA_FROM_LINK = "from_link"
        const val ACTION_FORCE_DISCONNECT = "com.spizganed.quickbuds.FORCE_DISCONNECT"
        const val ACTION_TILE_SET_ANC = "com.spizganed.quickbuds.TILE_SET_ANC"
        const val EXTRA_TILE_ANC_MODE = "tile_anc_mode"
        const val ACTION_TILE_SET_FEATURE = "com.spizganed.quickbuds.TILE_SET_FEATURE"
        const val EXTRA_TILE_FEATURE = "tile_feature"
        const val EXTRA_TILE_FEATURE_ON = "tile_feature_on"
        const val ACTION_TILE_SET_GAME_MODE = "com.spizganed.quickbuds.TILE_SET_GAME_MODE"
        const val EXTRA_TILE_GAME_MODE_ON = "tile_game_mode_on"
        const val ACTION_WIDGET_COMMAND = "com.spizganed.quickbuds.WIDGET_COMMAND"
        const val ACTION_NOTIF_DISMISSED = "com.spizganed.quickbuds.NOTIF_DISMISSED"
        const val EXTRA_WIDGET_ACTION = "widget_action"
        const val EXTRA_WIDGET_ANC_MODE = "widget_anc_mode"
        const val EXTRA_WIDGET_GAME_MODE = "widget_game_mode"
        /** FEATURE: the `0x0403` switch id and the value to write. */
        const val EXTRA_WIDGET_FEATURE = "widget_feature"
        const val EXTRA_WIDGET_FEATURE_ON = "widget_feature_on"

        /** Earbud controls: change one gesture binding. See GestureActivity.writeToBuds(). */
        const val ACTION_SET_GESTURE = "com.spizganed.quickbuds.SET_GESTURE"
        const val EXTRA_GESTURE_DEVICE = "gesture_device"
        const val EXTRA_GESTURE_ACTION = "gesture_action"
        const val EXTRA_GESTURE_FUNCTION = "gesture_function"

        /** The hold's ANC-cycle membership. See BudsConnectionManager.sendHoldAncModes(). */
        const val ACTION_SET_HOLD_MODES = "com.spizganed.quickbuds.SET_HOLD_MODES"
        const val EXTRA_HOLD_MASK = "hold_mask"
        const val EXTRA_HOLD_TYPE = "hold_type"

        /** On-call gestures (`btn 0x06`). See BudsConnectionManager.sendOnCall(). */
        const val ACTION_SET_ON_CALL = "com.spizganed.quickbuds.SET_ON_CALL"
        const val ACTION_FIND_BUDS = "com.spizganed.quickbuds.FIND_BUDS"
        /** The model list changed the model ([BudsConnectionManager.modelPicked]). */
        const val ACTION_MODEL_PICKED = "com.spizganed.quickbuds.MODEL_PICKED"
        const val ACTION_GAME_EQ_UPDATE = "com.spizganed.quickbuds.GAME_EQ_UPDATE"
        const val PREF_SMART_PAUSE = "smart_auto_pause"

        /** Delay for a FORCE_CONNECT, in ms (the ACL fallback in KeepAliveReceiver). */
        const val EXTRA_DELAY_MS = "delay_ms"

        /**
         * Settings › Background service (default on). Off: the service runs only while the app is
         * open, nothing starts it from Bluetooth events, and the widget only works with the app open.
         */
        const val PREF_BACKGROUND = "backgroundService"

        /** The link gets this long to come up before an idle service stops. */
        private const val IDLE_MS = 60_000L
        /** Bluetooth off: nothing can connect, so the service stops soon. */
        private const val BT_OFF_MS = 3_000L

        fun backgroundAllowed(c: Context) =
            c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_BACKGROUND, true)

        /**
         * Settings › Hide old battery levels (default off). On: each battery report replaces all three
         * levels, so a part it leaves out (the case after the lid closes) shows blank instead of its last level.
         */
        const val PREF_CLEAR_BATTERY = "clearMissingBattery"

        fun clearMissingBattery(c: Context) =
            c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_CLEAR_BATTERY, false)
        const val EXTRA_FIND_ON = "find_on"
        const val EXTRA_ON_CALL_ROW = "on_call_row"   // OnCallGesture.serviceRow
        const val EXTRA_ON_CALL_ENABLED = "on_call_enabled"
    }
}
