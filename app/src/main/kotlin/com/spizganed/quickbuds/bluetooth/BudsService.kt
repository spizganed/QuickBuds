package com.spizganed.quickbuds.bluetooth

import com.spizganed.quickbuds.protocol.ModelCatalog
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
import com.spizganed.quickbuds.protocol.Capabilities
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

    /** The delayed ACL connect (KeepAliveReceiver), so a deliberate disconnect cancels it. */
    private var aclConnect: Runnable? = null

    private fun statusLog(msg: String) {
        Log.d("BudsConn", msg)
    }

    private val widgetCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
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

        val filter = IntentFilter(ACTION_WIDGET_COMMAND)
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
        // Started by a Bluetooth event while locked: stay asleep until the unlock.
        if (backgroundAllowed(this) && shouldSleep()) asleep = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        statusLog("[SVC] onStartCommand action=${intent?.action}")

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
            ACTION_TOGGLE_BRIDGE -> manager?.setBridge(!RfcommBridge.running)
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
        // A2DP / HEADSET state is disconnected, and 5 socket tries would only fail. Virtual buds
        // keep their own path, and an unknown state connects as before.
        if (!user && !fromLink) {
            val virtual = VirtualBuds.on(this)
            val device = if (virtual) null else try { BudsDevice.find(this) } catch (_: Exception) { null }
            val link = device?.let { manager?.audioLinkState(it) } ?: LinkState.UNKNOWN
            if (!ConnectPolicy.autoConnectAllowed(link, virtual)) {
                statusLog("[SVC] FORCE_CONNECT skipped (the buds have no audio link)")
                return
            }
        }
        statusLog("[SVC] FORCE_CONNECT: connecting...")
        AncWidgetProvider.setConnecting(this, true)
        // Dev tools › Virtual buds: no paired device and no Bluetooth adapter is needed.
        if (VirtualBuds.on(this)) {
            val target = VirtualBuds.parseAddress(VirtualBuds.address(this))
            if (target == null) {
                statusLog("[SVC] Virtual buds: bad host:port '${VirtualBuds.address(this)}'")
                AncWidgetProvider.setConnecting(this, false)
                return
            }
            manager?.connectVirtual(target.first, target.second, VirtualBuds.name(this))
            return
        }
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

    private fun startForegroundService() {
        val channelId = "QuickBuds_Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Buds Connection",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                description = "Keeps the connection to your earbuds alive"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("QuickBuds")
                .setContentText("Maintaining connection to earbuds")
                .setSmallIcon(R.drawable.ic_stat_buds)
                .setOngoing(false)
                .setShowWhen(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("QuickBuds")
                .setContentText("Maintaining connection to earbuds")
                .setSmallIcon(R.drawable.ic_stat_buds)
                .setOngoing(false)
                .setShowWhen(false)
                .build()
        }
        // Android 12+ refuses this when the app is in the background (report: realme RMX3890, Android 15). Stop instead of crashing.
        try {
            startForeground(1, notification)
        } catch (e: IllegalStateException) {
            statusLog("[SVC] startForeground refused: ${e.javaClass.simpleName}")
            stopSelf()
        } catch (e: SecurityException) {
            // A real phone without the permission stops here. Virtual buds only need the bound service.
            statusLog("[SVC] startForeground refused: ${e.javaClass.simpleName}")
            if (!VirtualBuds.on(this)) stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        statusLog("[SVC] onBind")
        return binder
    }

    override fun onDestroy() {
        statusLog("[SVC] onDestroy")
        handler.removeCallbacksAndMessages(null)
        gameEq.stop()
        // Close the RFCOMM link with the service: with the background service switched off,
        // leaving the app stops the service, and a live socket must not outlive it.
        try { manager?.disconnect() } catch (e: Exception) { PacketLogger.error("service stop disconnect", e) }
        try { manager?.setBridge(false) } catch (_: Exception) {}
        try { manager?.removeListener(this) } catch (_: Exception) {}
        try { unregisterReceiver(widgetCommandReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onConnected(connected: Boolean) {
        // Our own sleep, not a lost link: the widget keeps showing the connected state and the last levels until the unlock reconnects.
        if (!connected && asleep) { statusLog("[SVC] Sleep: widget state kept"); return }
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
        if (before.connected != connected) AncWidgetProvider.animate(this, connected, before.takeIf { it.connected })
        else AncWidgetProvider.refreshAll(this)
        gameEq.update()
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
    }

    companion object {
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
        /** Dev tools › Bridge on/off ([RfcommBridge]). */
        const val ACTION_TOGGLE_BRIDGE = "com.spizganed.quickbuds.TOGGLE_BRIDGE"
        const val ACTION_WIDGET_COMMAND = "com.spizganed.quickbuds.WIDGET_COMMAND"
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
