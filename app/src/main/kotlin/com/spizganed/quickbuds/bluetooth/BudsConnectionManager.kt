package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spizganed.quickbuds.protocol.AncEventParser
import com.spizganed.quickbuds.protocol.BatteryParser
import com.spizganed.quickbuds.protocol.EqCodec
import com.spizganed.quickbuds.protocol.GameModeParser
import com.spizganed.quickbuds.protocol.KeyFunctionParser
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.protocol.OppoPacketFramer
import com.spizganed.quickbuds.protocol.UserInteractionParser
import com.spizganed.quickbuds.protocol.WearingStatusParser
import com.spizganed.quickbuds.ui.GestureConfigStore
import com.spizganed.quickbuds.ui.OnCallConfigStore
import com.spizganed.quickbuds.ui.ThemeRes
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BudsConnectionManager(private val context: Context) {

    init {
        PacketLogger.init(context)
    }

    interface Listener {
        fun onStatus(msg: String)
        fun onConnected(connected: Boolean)
        fun onPacketReceived(bytes: ByteArray)
        fun onBattery(left: Int?, case: Int?, right: Int?, chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean)
        fun onBudState(state: String)
        fun onEarStatus(leftInBox: Boolean, rightInBox: Boolean) {}

        /**
         * Full wear state (raw status codes from 0x0109 / 0x0204):
         * 4 = in case, 1/5 = out idle, 3/7 = wearing, 0 = disconnected, -1 = side not reported.
         * caseSt is the raw code reported by the case component (comp 3), -1 if absent.
         */
        fun onWearState(left: Int, right: Int, caseSt: Int) {}

        /**
         * Game mode changed on the BUDS themselves (0x0204 subType 0x05).
         *
         * This fires both when we set game mode and when the user toggles it with a
         * buds gesture — the buds push the new state either way, so it is the
         * authoritative source. Implementers should persist it to WidgetStateStore
         * so the widget and app buttons reflect reality rather than our last command.
         *
         * NOTE: ANC has its own event, subType 0x03 — see onAncModeState.
         */
        fun onGameModeState(on: Boolean) {}

        /**
         * ANC mode changed on the BUDS themselves (0x0204 subType 0x03).
         *
         * Mirrors onGameModeState: raised for a buds gesture AND for our own command
         * (the buds push real state either way), so implementers should persist it to
         * WidgetStateStore and let the store listener repaint both surfaces. An
         * implementation that ignores an equal value is what keeps an echo a no-op.
         *
         * `mode` is a store ANC name ("Off", "Transparency", "Adaptive",
         * "ANC-Light", "ANC-Medium", "ANC-Deep"). "Adaptive" is included because the
         * buds CAN report it (0x0800) and the app now has a circle for it — before the
         * circle existed it was folded into "ANC-Light". An unnameable raw value is
         * never reported, so an
         * implementer can never be handed a mode the buds did not actually report.
         */
        fun onAncModeState(mode: String) {}

        /** A `0x810D` status reply arrived — feature id -> value, see [featureStates]. */
        fun onFeatureStates(states: Map<Int, Int>) {}

        /** Any EQ reading changed — see [eqCurrent], [eqCustom], [bassWaveLevel]. */
        fun onEqState() {}

        /** The buds reported their alert-sound volume (1..10), see [alertVolume]. */
        fun onAlertVolume(level: Int) {}

        /** The paired-device list changed, see [devices]. */
        fun onDevices(list: List<PairedDevice>) {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    var listener: Listener?
        get() = listeners.firstOrNull()
        set(value) {
            listeners.clear()
            value?.let { listeners.add(it) }
        }

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val handler = Handler(Looper.getMainLooper())
    private val pollExecutor = Executors.newSingleThreadScheduledExecutor()

    private var bluetoothSocket: BluetoothSocket? = null
    private var connectedThread: ConnectedThread? = null
    private var isReady = false
    private var isConnecting = false
    private var reconnectAttempts = 0
    private var pollTask: java.util.concurrent.ScheduledFuture<*>? = null

    // Reconnect-after-loss state — see reconnectAfterLoss().
    private var lastDevice: BluetoothDevice? = null
    private var lossRetries = 0
    private var connectedAt = 0L
    private var pendingReconnect: Runnable? = null

    /**
     * Poll period for the status query (0x010D).
     *
     * Only a keep-alive: wear and battery are pushed (0x0204).
     *
     * Do NOT remove the 0x010D packet outright: the reference sources describe
     * it as a FIXED packet that wakes the earbuds and likely acts as a
     * keep-alive. Widening is safe; deleting risks the buds sleeping and the
     * link going stale.
     */
    private val POLL_INTERVAL_SECONDS = 300L

    private var lastLeft: BatteryParser.Info? = null
    private var lastRight: BatteryParser.Info? = null
    private var lastCase: BatteryParser.Info? = null

    private var lastLeftInCase = false
    private var lastRightInCase = false
    private var lastLeftStatus = -1
    private var lastRightStatus = -1
    private var lastCaseStatus = -1

    fun isConnected(): Boolean = isReady && bluetoothSocket?.isConnected == true

    /**
     * Drops or restores the phone's own audio link, the way HeyMelody's Connect/Disconnect does
     * (bugreport 2026-09-24, ROADMAP-DONE.md "Connection and push"): the hidden
     * `connect()`/`disconnect()` on the A2DP and Headset proxies, by reflection. Headset `connect()`
     * is refused for ordinary apps, so connecting asks A2DP only and the system brings HFP up by
     * itself (~10 s). Connecting an already-connected profile is a no-op.
     */
    fun setPhoneAudio(device: BluetoothDevice, on: Boolean) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val method = if (on) "connect" else "disconnect"
        val profiles = if (on) listOf(BluetoothProfile.A2DP)
            else listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)
        for (p in profiles) {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try {
                        val ok = proxy.javaClass.getMethod(method, BluetoothDevice::class.java)
                            .invoke(proxy, device)
                        log("Audio $method profile=$profile -> $ok")
                    } catch (e: Exception) {
                        log("Audio $method profile=$profile failed: ${e.cause ?: e}")
                    } finally {
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, p)
        }
    }

    fun connect(device: BluetoothDevice, withAudio: Boolean = false) {
        if (isConnecting || isConnected()) {
            log("Already connecting/connected, ignoring.")
            return
        }
        isConnecting = true
        lastDevice = device
        // Only a connect the user asked for (the pill) brings phone audio up. An automatic one —
        // ACL receiver, retries, reconnect after loss — leaves audio to Android: asking for A2DP
        // while the system is auto-connecting raced it and left audio stuck (fixed and confirmed
        // on device 2026-09-25).
        if (withAudio) setPhoneAudio(device, on = true)
        log("Initiating RFCOMM connection to ${device.name}...")
        context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()

        Thread {
            // 079A first: it is the one that works (HeyMelody connects straight to it), while
            // 1107 never connects on the Buds 4 and burns ~5 s. 1107 stays for other models.
            val uuids = listOf(
                OpoProtocol.SPP_UUID_FALLBACK,
                OpoProtocol.SPP_UUID_PRIMARY
            )
            var socket: BluetoothSocket? = null
            var lastError: String? = null

            for (uuidStr in uuids) {
                try {
                    log("Trying UUID: $uuidStr")
                    val uuid = UUID.fromString(uuidStr)
                    socket = device.createRfcommSocketToServiceRecord(uuid)
                    socket?.connect()
                    log("Connected via UUID: $uuidStr")
                    break
                } catch (e: IOException) {
                    log("Failed UUID $uuidStr: ${e.message}")
                    lastError = e.message
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                }
            }

            if (socket == null) {
                try {
                    log("Trying RFCOMM channel 15...")
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = m.invoke(device, 15) as BluetoothSocket
                    socket.connect()
                    log("Connected via raw channel 15")
                } catch (e: Exception) {
                    log("Channel 15 failed: ${e.message}")
                    lastError = e.message
                    socket = null
                }
            }

            if (socket != null) {
                bluetoothSocket = socket
                connectedThread = ConnectedThread(socket)
                connectedThread?.start()
                isReady = true
                reconnectAttempts = 0
                connectedAt = System.currentTimeMillis()
                handler.post { listeners.forEach { it.onConnected(true) } }
                log("Ready for commands. Running init sequence...")
                runInitSequence()
                startBatteryPolling()
            } else {
                log("All connection methods failed: $lastError")
                isConnecting = false
                disconnect()
                if (reconnectAttempts < 3) {
                    reconnectAttempts++
                    log("Auto-retry $reconnectAttempts/3 in 5s...")
                    handler.postDelayed({
                        if (!isConnected() && !isConnecting) connect(device)
                    }, 5000)
                } else {
                    log("Giving up. Tap CONNECT to retry manually.")
                    reconnectAttempts = 0
                }
                return@Thread
            }
            isConnecting = false
        }.start()
    }

    private fun runInitSequence() {
        Thread {
            try {
                delay(300); sendRawBlocking(OpoProtocol.buildHandshake(), "handshake")
                delay(200); sendRawBlocking(OpoProtocol.buildQueryProductId(), "query product id")
                delay(200); sendRawBlocking(OpoProtocol.buildQueryBroadcastCodes(), "query broadcast codes")
                delay(200); sendRawBlocking(OpoProtocol.registerNotifications(), "register notify")
                delay(200); sendRawBlocking(OpoProtocol.queryStatus(), "query status")
                delay(200); sendRawBlocking(OpoProtocol.queryAncMode(), "query anc")
                delay(200); sendRawBlocking(OpoProtocol.queryAlertVolume(), "query alert volume")
                delay(200); sendRawBlocking(OpoProtocol.queryBattery(), "query battery")
                delay(200); sendRawBlocking(OpoProtocol.queryWearingStatus(), "query wearing")
                // Read-only: reports the CURRENT gesture bindings so a capture can
                // reveal the `function` enum. See OpoProtocol.queryKeyFunction().
                delay(200); sendRawBlocking(OpoProtocol.queryKeyFunction(), "query key function")
                // Read-only: the HOLD's ANC mode list, which is the one gesture this app
                // still cannot configure — its key-function byte only reports that the hold
                // cycles ANC, not which modes. See OpoProtocol.queryNoiseSwitchModes().
                delay(200); sendRawBlocking(OpoProtocol.queryNoiseSwitchModes(), "query noise switch")
            } catch (e: Exception) {
                log("Init sequence error: ${e.message}")
            }
        }.start()
    }

    private fun startBatteryPolling() {
        // disconnect() cancels it; cancel here too so a reconnect never stacks a second one.
        pollTask?.cancel(false)
        pollTask = pollExecutor.scheduleWithFixedDelay({
            if (isReady) {
                try {
                    // Status query only. The wear query (0x0109) is intentionally
                    // not polled — the buds push wear changes as 0x0204 subtype 02,
                    // measured at 1-3 ms, so polling it just wastes radio time.
                    sendRaw(OpoProtocol.queryStatus(), "poll status")
                } catch (_: Exception) {}
            }
        }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * A link we did not close ourselves dropped — typically the buds restarting after a codec
     * change, which drops us 2-3 times in a row while they settle (log 2026-09-23). Nothing else
     * would reconnect: KeepAliveReceiver only fires on ACL_CONNECTED, and the buds can drop just
     * our RFCOMM channel while the Bluetooth link itself stays up. Growing delays give them time
     * to settle; a connection that lived 30 s starts the count over.
     */
    private fun reconnectAfterLoss() {
        val device = lastDevice ?: return
        if (System.currentTimeMillis() - connectedAt > 30_000) lossRetries = 0
        if (lossRetries >= 5) {
            log("Reconnect after loss: giving up after 5 tries.")
            return
        }
        lossRetries++
        val wait = 3000L * lossRetries
        log("Reconnect after loss $lossRetries/5 in ${wait / 1000}s...")
        val r = Runnable { if (!isConnected() && !isConnecting) connect(device) }
        pendingReconnect = r
        handler.postDelayed(r, wait)
    }

    fun disconnect() {
        // A deliberate disconnect must not be undone by a queued reconnect.
        pendingReconnect?.let { handler.removeCallbacks(it) }
        pendingReconnect = null
        isReady = false
        isConnecting = false
        connectedThread?.cancel()
        connectedThread = null
        bluetoothSocket = null
        pollTask?.cancel(false)
        pollTask = null
        handler.post { listeners.forEach { it.onConnected(false) } }
        log("Disconnected")
    }

    fun sendAncOff() { lastAncLevelSent = null; sendRaw(OpoProtocol.ancOff(), "ANC Off") }
    fun sendAncOn() { sendRaw(OpoProtocol.ancOn(), "ANC On") }
    fun sendAncTransparency() { sendRaw(OpoProtocol.ancTransparency(), "ANC Trans") }
    fun sendAncSmart() { sendRaw(OpoProtocol.ancSmart(), "ANC Smart") }
    fun sendAncDeep() { lastAncLevelSent = "ANC-Deep"; sendRaw(OpoProtocol.ancDeep(), "ANC Deep") }
    fun sendAncMedium() { lastAncLevelSent = "ANC-Medium"; sendRaw(OpoProtocol.ancMedium(), "ANC Medium") }
    fun sendAncLight() { lastAncLevelSent = "ANC-Light"; sendRaw(OpoProtocol.ancLight(), "ANC Light") }

    /**
     * Adaptive — a state of its own, NOT a fourth level.
     *
     * `lastAncLevelSent` stays null (like Off), because that field exists only to
     * interpret the ambiguous "ANC on" stop, which reports whichever LEVEL was last
     * used. Adaptive reports itself unambiguously as 0x0800, which AncEventParser now
     * names outright, so there is nothing for a hint to disambiguate.
     */
    fun sendAncAdaptive() { lastAncLevelSent = null; sendRaw(OpoProtocol.ancAdaptive(), "ANC Adaptive") }

    fun setGameMode(on: Boolean) =
        sendRaw(if (on) OpoProtocol.gameModeOn() else OpoProtocol.gameModeOff(), "GameMode")

    /**
     * Dual connection, in HeyMelody's exact order (`[CAPTURE]` 2026-09-25): `0x0403 11 xx`, a status
     * re-read, then `0x0413 08 00 xx`. The buds answer with a `0x0204` subType `06` device list.
     */
    fun setDualDevice(on: Boolean) {
        Thread {
            try {
                sendRawBlocking(OpoProtocol.setFeature(OpoProtocol.FEATURE_DUAL_DEVICE, on), "Dual device -> $on")
                sendRawBlocking(OpoProtocol.queryStatus(), "verify features")
                sendRawBlocking(OpoProtocol.dualFollowup(on), "Dual follow-up")
            } catch (e: Exception) {
                log("DUAL WRITE failed: ${e.message}")
            }
        }.start()
    }

    /** One entry of the buds' paired-device list (`0x8112`, `0x0204` subType `06`). */
    data class PairedDevice(val mac: String, val name: String, val connected: Boolean)

    /** Latest device list, empty until read. See [refreshDevices]. */
    @Volatile var devices: List<PairedDevice> = emptyList()
        private set

    fun refreshDevices() = sendRaw(OpoProtocol.queryDevices(), "query devices")

    /**
     * `[count]` then per device `[MAC, 6 bytes reversed][?][state][?][nameLen][name UTF-8]`.
     * State `02` = connected, `00` = not. The two `?` bytes are undecoded (PROTOCOL.md §9).
     */
    private fun parseDevices(p: ByteArray, start: Int): List<PairedDevice>? {
        if (p.size <= start) return null
        val count = p[start].toInt() and 0xFF
        var o = start + 1
        val out = ArrayList<PairedDevice>()
        repeat(count) {
            if (o + 10 > p.size) return null
            val mac = (5 downTo 0).joinToString(":") { "%02X".format(p[o + it]) }
            val connected = p[o + 7].toInt() == 0x02
            val len = p[o + 9].toInt() and 0xFF
            if (o + 10 + len > p.size) return null
            out += PairedDevice(mac, String(p, o + 10, len, Charsets.UTF_8), connected)
            o += 10 + len
        }
        return out
    }

    // --- Equalizer (PROTOCOL.md §9). Read on demand by the EQ screen, re-read after every write. ---
    @Volatile var eqCurrent: Int? = null
        private set
    @Volatile var eqCustom: List<EqCodec.Preset> = emptyList()
        private set
    @Volatile var bassWaveLevel: Int? = null
        private set

    fun refreshEq() = sendThenRead()

    // Each write updates the cached state FIRST, then sends and re-reads. The three replies land one
    // by one and each repaints; without this, the replies that arrive before the new values snapped
    // the UI back to the old ones for a moment (the slider "jump" he saw 2026-09-23).

    fun selectBuiltInEq(id: Int) {
        eqCurrent = id
        sendThenRead(OpoProtocol.setBuiltInEq(id) to "EQ built-in $id")
    }

    /** Selects AND saves a custom preset — `0x0418` is both (a band edit or rename is the same frame). */
    fun saveCustomEq(p: EqCodec.Preset) {
        eqCurrent = p.id
        eqCustom = eqCustom.map { if (it.id == p.id) p else it }
        sendThenRead(OpoProtocol.customEq(EqCodec.ACTION_SAVE, p) to "EQ custom ${p.id} '${p.name}'")
    }

    /** No local update for create/delete: the buds assign and renumber ids, so only the re-read knows. */
    fun createCustomEq(name: String) =
        sendThenRead(OpoProtocol.customEq(EqCodec.ACTION_CREATE, EqCodec.newPreset(name)) to "EQ create '$name'")

    fun deleteCustomEq(p: EqCodec.Preset) =
        sendThenRead(OpoProtocol.customEq(EqCodec.ACTION_DELETE, p) to "EQ delete ${p.id} '${p.name}'")

    fun setBassWaveLevel(level: Int) {
        bassWaveLevel = level
        sendThenRead(OpoProtocol.setBassWaveLevel(level) to "BassWave level $level")
    }

    /** Optional write, then the three EQ reads, in order on one thread. */
    private fun sendThenRead(write: Pair<ByteArray, String>? = null) {
        Thread {
            try {
                if (write != null) { sendRawBlocking(write.first, write.second); Thread.sleep(250) }
                sendRawBlocking(OpoProtocol.queryEq(), "query EQ")
                Thread.sleep(120)
                sendRawBlocking(OpoProtocol.queryEqAll(), "query custom EQ")
                Thread.sleep(120)
                sendRawBlocking(OpoProtocol.queryBassWaveLevel(), "query BassWave level")
            } catch (e: Exception) {
                log("EQ: ${e.message}")
            }
        }.start()
    }

    /** Find my earbuds: both buds' own locator tone. `[CAPTURE]` 2026-09-23, PROTOCOL.md §9. */
    fun setFindTone(on: Boolean) = sendRaw(OpoProtocol.findTone(on), "Find tone -> $on")

    private val FEATURES_KEY = "lastFeatureStates"

    /**
     * Latest `0x810D` reply as feature id -> value (PROTOCOL.md §9). Persisted, so the switches
     * open at the last known state instead of jumping when the connect-time read lands.
     */
    @Volatile var featureStates: Map<Int, Int> = featurePrefs().getString(FEATURES_KEY, "")!!
        .split(',').mapNotNull { e ->
            val kv = e.split('=')
            val k = kv[0].toIntOrNull(); val v = kv.getOrNull(1)?.toIntOrNull()
            if (k != null && v != null) k to v else null
        }.toMap()
        private set(value) {
            field = value
            featurePrefs().edit()
                .putString(FEATURES_KEY, value.entries.joinToString(",") { "${it.key}=${it.value}" })
                .apply()
        }

    private fun featurePrefs() = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * `0x0403` feature writes, sent IN ORDER on one thread (HeyMelody sends spatial before
     * codec, `[CAPTURE]` 2026-09-23), then a status query so [Listener.onFeatureStates]
     * reports what the buds actually took. A codec change drops the link, in which case the
     * connect sequence's own status query does that instead.
     */
    fun setFeatures(vararg changes: Pair<Int, Boolean>) {
        Thread {
            try {
                for ((id, on) in changes) {
                    sendRawBlocking(OpoProtocol.setFeature(id, on), "Feature 0x%02X -> %s".format(id, on))
                }
                Thread.sleep(400)
                sendRawBlocking(OpoProtocol.queryStatus(), "verify features")
            } catch (e: Exception) {
                log("FEATURE WRITE failed: ${e.message}")
            }
        }.start()
    }

    /** Alert-sound volume 1..10 from `0x8130` / `0x8427`, null until read. `[CAPTURE]` 2026-09-25. */
    @Volatile var alertVolume: Int? = null
        private set

    fun refreshAlertVolume() = sendRaw(OpoProtocol.queryAlertVolume(), "query alert volume")

    /** Write, then read back — as HeyMelody does after every change. */
    fun setAlertVolume(level: Int) {
        alertVolume = level
        Thread {
            try {
                sendRawBlocking(OpoProtocol.setAlertVolume(level), "Alert volume $level")
                Thread.sleep(250)
                sendRawBlocking(OpoProtocol.queryAlertVolume(), "query alert volume")
            } catch (e: Exception) {
                log("ALERT VOLUME write failed: ${e.message}")
            }
        }.start()
    }

    /**
     * The hold's ANC-cycle membership — `setSupportNoiseReduction`, `[CAPTURE]` 2026-09-22
     * (PROTOCOL.md §5). `mask` uses [OpoProtocol.HOLD_MASK_BIT_OFF]/`_ON`/`_TRANSPARENCY`/
     * `_ADAPTIVE`. Verified the same way every gesture write is: re-read after a delay and log
     * the diff, because a wrong command number here would be ignored exactly as silently as
     * everywhere else in this protocol.
     */
    fun sendHoldAncModes(mask: Int) {
        sendRaw(OpoProtocol.setHoldAncModes(mask), "Hold ANC modes -> 0x%04X".format(mask))
        Thread {
            try {
                Thread.sleep(400)
                sendRawBlocking(OpoProtocol.queryNoiseSwitchModes(), "verify hold ANC modes")
            } catch (e: Exception) {
                log("HOLD MODES WRITE: verify query failed: ${e.message}")
            }
        }.start()
    }

    /**
     * On-call gesture writes — `btn 0x06`, `[CAPTURE]` 2026-09-22 (see
     * [com.spizganed.quickbuds.protocol.KeyFunctionParser.BUTTON_ON_CALL]). Unlike
     * [writeGestureBinding], these do NOT need [lastKeyFnTable]: HeyMelody's own capture shows
     * a single-entry write here, not a whole-table rewrite, and the buds fan it out to both
     * sides themselves. Verified the same way — re-read and let the `KEYFN DIFF:` line show
     * what actually changed.
     */
    fun sendOnCallDoubleTap(enabled: Boolean) {
        sendRaw(OpoProtocol.setOnCallDoubleTap(enabled), "On-call double tap -> $enabled")
        verifyKeyFunctionAfterDelay()
    }

    fun sendOnCallLongHold(enabled: Boolean) {
        sendRaw(OpoProtocol.setOnCallLongHold(enabled), "On-call long hold -> $enabled")
        verifyKeyFunctionAfterDelay()
    }

    private fun verifyKeyFunctionAfterDelay() {
        Thread {
            try {
                Thread.sleep(400)
                sendRawBlocking(OpoProtocol.queryKeyFunction(), "verify key function")
            } catch (e: Exception) {
                log("ON-CALL WRITE: verify query failed: ${e.message}")
            }
        }.start()
    }

    fun requestFullStatus() { sendRaw(OpoProtocol.queryStatus(), "manual status") }

    /** Timestamp of the last ANC command we flushed; see noteUnattributed(). */
    private var lastAncFlushAt = 0L

    /**
     * Marks that an ANC frame is now on the wire.
     *
     * Called from sendRawBlocking() right after the write succeeds, so the ONLY
     * requirement is that ANC goes through sendRaw() — which every ANC path does
     * (service, widget, tile, app all end up in BudsConnectionManager.sendAnc*).
     * If an ANC command is ever written directly to ConnectedThread, this must be
     * called alongside it or the attribution probe silently stops working.
     */
    private fun markAncFlush() { lastAncFlushAt = System.currentTimeMillis() }

    /**
     * The ANC LEVEL the app last sent ("ANC-Light"/"ANC-Medium"/"ANC-Deep", or null).
     *
     * Used only to interpret the buds' subType 0x03 push when its value is the
     * ambiguous "ANC on" stop: that stop echoes whichever level was last active, so
     * the level the app just set is the best available hint. It never overrides a
     * value we can name outright — see AncEventParser.modeForRaw().
     *
     * Deliberately a plain field rather than a WidgetStateStore read: the manager
     * has no Context, and the store is owned by the service.
     */
    private var lastAncLevelSent: String? = null

    /**
     * The most recent `0x8108` table, i.e. the buds' own view of their bindings.
     *
     * Kept because a `setKeyFunction` write takes the COMPLETE list: to change one slot
     * we must send every entry back, and the untouched ones have to come from somewhere
     * truthful. Inventing them from our own UI would wipe the bindings we do not model
     * (the `btn 0x06` group, `act 0x06`, and anything else the firmware carries), so the
     * last reading is the only safe source. Read-only until then: null means no write
     * may happen at all.
     *
     * @Volatile because it is written on the socket reader thread and read from the
     * service/main thread that issues a write.
     */
    @Volatile
    private var lastKeyFnTable: KeyFunctionParser.Table? = null

    /**
     * The hold's ANC-cycle mask, as last read from `0x010C` `02 01` (`[CAPTURE]`,
     * PROTOCOL.md §5). Bits per [OpoProtocol.HOLD_MASK_BIT_OFF] etc. Null until the first
     * reply arrives — same "do not invent it" rule as [lastKeyFnTable], though there is
     * nothing here that writes FROM this field; it exists purely so a UI can show the
     * current cycle without threading its own query/response plumbing.
     */
    @Volatile
    var lastHoldAncMask: Int? = null
        private set

    /**
     * Changes ONE binding on ONE bud and writes the whole table back.
     *
     * `side` is the reply's `deviceType` (0x01 left, 0x02 right) and `keyFnAction` is the
     * keyfn `buttonAction` — NOT the F1 action byte. The two numberings genuinely differ
     * (keyfn 1=single; F1 0x00=single), so passing an F1 byte here would write the wrong
     * slot. See `Gesture.keyFnAction`.
     *
     * WHICH SLOTS ARE WRITTEN IS DECIDED BY THE TABLE, NOT BY A LIST IN OUR CODE. That is
     * the correction of a real bug, and the reason is worth keeping: a table in this very
     * project's capture history has held a gesture in THREE different places.
     *
     *     LEFT   19 entries   dev=0x01/btn=0x01[.. 05:07]      slide in btn 0x01
     *     RIGHT  19 entries   dev=0x02/btn=0x02[05:00]         slide split out
     *                         dev=0x02/btn=0x03[05:00]
     *     both   20 entries   (an earlier session)             slide split out, other side
     *
     * A hardcoded per-gesture button list was tried and it broke: aiming slide's write at
     * `btn 0x02`/`0x03` produced `NO SLOT MATCHED` on a bud whose slide sits in `btn 0x01`,
     * and aiming at `0x01` misses the split shape. So the rule is now simply "every slot
     * this bud actually has for this action", excluding only [KeyFunctionParser.BUTTON_ON_CALL].
     *
     * MEASURED BONUS: writing slide's two split groups makes the DEVICE CONSOLIDATE them
     * into `btn 0x01 act 0x05` and drop the extras. So the split shape is not permanent,
     * the device normalises it, and our table-driven rule follows that automatically.
     *
     * ZERO MATCHES REFUSES THE WRITE, LOUDLY. A write that matches no slot is by definition
     * a no-op, and silently sending one is what made slide look "broken but sometimes
     * working": the writes went to a group that bud did not have, and came back unchanged
     * with nothing in the log to say why. A one-line answer beats a mystery.
     *
     * Returns false and does nothing when no table has been read yet. That is the point:
     * a write needs all the other entries, and guessing them would silently destroy
     * bindings this app cannot even display.
     */
    fun writeGestureBinding(
        side: Int,
        keyFnAction: Int,
        functionByte: Int
    ): Boolean {
        val table = lastKeyFnTable
        if (table == null || table.entries.isEmpty()) {
            log("KEYFN WRITE: refused - no 0x8108 reading yet, will not invent a table")
            return false
        }

        var matched = 0
        val buttons = LinkedHashSet<Int>()
        val updated = table.entries.map { e ->
            if (e.deviceType == side && e.action == keyFnAction &&
                e.button != KeyFunctionParser.BUTTON_ON_CALL) {
                matched++
                buttons.add(e.button)
                e.copy(function = functionByte)
            } else {
                e
            }
        }
        if (matched == 0) {
            log("KEYFN WRITE: no slot for dev=0x%02X act=0x%02X - nothing sent"
                .format(side, keyFnAction))
            return false
        }

        log("KEYFN WRITE: dev=0x%02X btn=%s act=0x%02X -> 0x%02X (%d slot(s), full table %d entries)"
            .format(
                side, buttons.joinToString(",") { "0x%02X".format(it) },
                keyFnAction, functionByte, matched, updated.size
            ))

        // Adopt the change IN MEMORY before the reply comes back. The verify re-read
        // below takes ~600ms, and without this a second write inside that window would be
        // built from the pre-write table and silently revert the first change. The
        // reply still overwrites this with the device's own truth, so a write that the
        // buds reject does not leave us believing a lie for long.
        lastKeyFnTable = table.copy(entries = updated)

        sendRaw(OpoProtocol.setKeyFunction(updated), "set key function")

        // Read it straight back. The write's payload layout is [OSS], and the device has
        // shown that it ignores a WRONG command number in total silence (0x0402 produced no
        // ack and no change), so the ONLY evidence the write landed is the device's own
        // table — and because the reply re-runs the diff, any change is printed for us
        // rather than assumed. A short delay: the buds answer a query in ~40ms, but the
        // write needs to be applied before the read is meaningful.
        Thread {
            try {
                Thread.sleep(600)
                sendRawBlocking(OpoProtocol.queryKeyFunction(), "verify key function")
            } catch (e: Exception) {
                log("KEYFN WRITE: verify query failed: ${e.message}")
            }
        }.start()
        return true
    }

    /**
     * Baseline for the gesture-binding diff, in the app's own prefs file.
     *
     * PERSISTED, NOT A FIELD, and that is the whole point: the enum hunt requires
     * disconnecting our app (the vendor app needs the RFCOMM socket) and reconnecting
     * it, which may restart the process. An in-memory baseline would be lost in exactly
     * the gap it exists to span, and a lost baseline reads as "nothing changed", which
     * is the one wrong answer that would end the hunt early.
     *
     * Deliberately NOT WidgetStateStore: that store is state the widget and home screen
     * render, and this is a diagnostic no UI reads. Putting it there would push a
     * question about `0x8108` into the widget's data model.
     *
     * The baseline is updated on every reading, so each reconnect is compared against
     * the immediately previous one. A reply with no entries does NOT overwrite a good
     * baseline — a garbled read must not cost us the reading we already had.
     */
    private fun keyFnDiff(table: KeyFunctionParser.Table): String {
        if (table.entries.isEmpty()) return "no entries in reply - baseline left untouched"

        val prefs = context.getSharedPreferences(KEYFN_PREFS, Context.MODE_PRIVATE)
        val signature = KeyFunctionParser.signature(table)
        val previous = prefs.getString(KEYFN_BASELINE, null)
        prefs.edit().putString(KEYFN_BASELINE, signature).apply()

        if (previous == null) {
            return "baseline stored (${table.entries.size} slots) - change ONE gesture in " +
                "the vendor app, then reconnect for a real diff"
        }
        return KeyFunctionParser.diff(previous, signature)
    }

    private val KEYFN_PREFS = "QuickBudsKeyFnDiff"
    private val KEYFN_BASELINE = "keyFnBaseline"

    private fun sendRaw(data: ByteArray, label: String = "") {
        Thread {
            sendRawBlocking(data, label)
        }.start()
    }

    private fun sendRawBlocking(data: ByteArray, label: String = "") {
        try {
            val thread = connectedThread
            if (thread == null) {
                log("sendRaw[$label]: no active connection.")
                return
            }
            if (bluetoothSocket?.isConnected != true) {
                log("sendRaw[$label]: socket reports not connected.")
                return
            }
            // Write bytes FIRST, log after. The buds ACK most commands in under 100ms
            // (see any handshake in local/logs), so logging first is a race: a fast reply
            // could land in the log ABOVE our own TX line and look causally backwards.
            thread.write(data)
            markAncFlush()
            log("TX[$label]: ${OpoProtocol.bytesToHex(data)}")
        } catch (e: Exception) {
            log("sendRaw[$label] error: ${e.message}")
        }
    }

    private fun delay(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun wearLabel(st: Int): String = when (st) {
        3, 7 -> "EAR"
        4 -> "CASE"
        1, 5 -> "out"
        0 -> "off"
        else -> "?"
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val inputStream: InputStream = socket.inputStream
        private val outputStream: OutputStream = socket.outputStream
        private val buffer = ByteArray(1024)
        private val framer = OppoPacketFramer()

        override fun run() {
            while (true) {
                try {
                    val bytes = inputStream.read(buffer)
                    // Target 37+: a dropped RFCOMM link returns -1 instead of throwing.
                    if (bytes < 0) throw IOException("stream closed")
                    if (bytes > 0) {
                        val frames = framer.append(buffer, bytes)
                        for (frame in frames) {
                            handlePacket(frame)
                        }
                    }
                } catch (e: IOException) {
                    // Our own disconnect() closed this socket, or a newer connection has already
                    // replaced it — either way this thread must not tear anything down.
                    if (cancelled || connectedThread !== this) break
                    log("Connection lost: ${e.message}")
                    disconnect()
                    reconnectAfterLoss()
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                outputStream.write(bytes)
                outputStream.flush()
            } catch (e: IOException) {
                log("Write failed: ${e.message}")
            }
        }

        @Volatile var cancelled = false

        fun cancel() {
            cancelled = true
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun payloadOf(packet: ByteArray): ByteArray {
        if (packet.size < 9) return ByteArray(0)
        val payLen = (packet[7].toInt() and 0xFF) or ((packet[8].toInt() and 0xFF) shl 8)
        val end = minOf(9 + payLen, packet.size)
        return if (end > 9) packet.copyOfRange(9, end) else ByteArray(0)
    }

    /**
     * Attribution probe for frames we do not decode.
     *
     * WHY THIS EXISTS, AND WHAT IT USED TO CLAIM: it was written to settle whether a
     * gesture produced no frame at all, or a frame we simply did not name. At the time
     * the working belief was "ANC changes made on the BUDS raise no 0x0204 event",
     * based on three captures — **and that belief was WRONG.** ANC does push, as
     * `0x0204` subType `0x03` (see [AncEventParser]). It looked silent for a reason
     * this comment is the best evidence of: `noteUnattributed()` excludes cmd
     * `0x0204` from its output, so an undecoded `0x0204` subType printed NOTHING, and
     * "no frame" was indistinguishable from "unparsed frame". Two separate fixes came
     * out of that: subscribe to ANC (`0x03`) in the `0x0205` registration, and name
     * the subType. Both are done.
     *
     * The probe is still worth keeping, for the genuinely unknown families: it stamps
     * unattributed frames, and `sendAnc`'s flush flag shows whether an ANC write had
     * just gone out, which is what attributes a frame to our own command.
     *
     * THE BLIND SPOT IS STILL THERE, deliberately: cmd `0x0204` is in the `explained`
     * list below, so an undecoded `0x0204` subType prints no `UNATTR RX:` line. The
     * Dev Tools decoder DOES show those (`Unattributed active report: subType=0x..`),
     * so the information exists — it is just not in the raw log. **If a capture needs
     * undecoded `0x0204` frames, remove that exemption first.**
     *
     * DIAGNOSTIC only — it never changes state and never touches the UI.
     *
     * Classification is deliberately "everything not already explained", because the
     * frames we DO decode (handshake, battery, wear, game mode, status/ANC query
     * replies) are handled by their own branches further down.
     */
    private fun noteUnattributed(packet: ByteArray) {
        if (packet.size < 9) return
        val cmd = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        val payload = payloadOf(packet)

        val explained = cmd == 0x8100 ||                 // handshake
            cmd == 0x8103 ||                             // product id
            cmd == 0x8106 ||                             // battery query reply
            cmd == 0x8109 ||                             // wearing query reply
            cmd == OpoProtocol.CMD_RESP_WEARING ||
            cmd == 0x810C ||                             // ANC query reply
            cmd == 0x810D ||                             // status query reply
            cmd == 0x8130 ||                             // alert volume reply
            cmd == 0x8112 ||                             // device list reply
            cmd == 0x8122 || cmd == 0x810F || cmd == 0x8124 || cmd == OpoProtocol.CMD_EQ_CHANGED || // EQ
            cmd == OpoProtocol.CMD_ACTIVE_REPORT ||
            cmd == OpoProtocol.CMD_RESP_KEY_FUNCTION ||   // gesture-config query reply
            cmd in 0x8400..0x84FF ||                     // acks for 0x04xx set commands
            cmd == OpoProtocol.CMD_REGISTER_NOTIFY
        if (explained) return

        val head = payload.take(4).joinToString(" ") { "%02X".format(it) }
        log("UNATTR RX: cmd=0x${"%04X".format(cmd)} len=${payload.size} " +
            "head=[$head] ancFlush=${if (System.currentTimeMillis() - lastAncFlushAt < 4000) "YES" else "no"}")
    }

    private fun handlePacket(packet: ByteArray) {
        log("RX: ${OpoProtocol.bytesToHex(packet)}")
        noteUnattributed(packet)
        handler.post { listeners.forEach { it.onPacketReceived(packet) } }

        if (packet.size < 9) return
        val cmd = (packet[4].toInt() and 0xFF) or ((packet[5].toInt() and 0xFF) shl 8)
        val payload = payloadOf(packet)

        // --- Wearing / in-case state: 0x8109 query response OR 0x0204 spontaneous event ---
        var wearing: WearingStatusParser.Result? = null
        var fromEvent = false
        if (cmd == OpoProtocol.CMD_RESP_WEARING) {
            wearing = WearingStatusParser.parseQueryResponse(payload)
        } else if (cmd == OpoProtocol.CMD_ACTIVE_REPORT && payload.isNotEmpty() &&
                   payload[0].toInt() and 0xFF == OpoProtocol.EVT_WEARING) {
            wearing = WearingStatusParser.parseActiveReport(payload)
            fromEvent = true
        }
        if (wearing != null) {
            if (wearing.leftValid) {
                lastLeftInCase = wearing.leftInCase
                lastLeftStatus = wearing.leftStatus
            }
            if (wearing.rightValid) {
                lastRightInCase = wearing.rightInCase
                lastRightStatus = wearing.rightStatus
            }
            if (wearing.caseStatus >= 0) lastCaseStatus = wearing.caseStatus
            log("WEAR ${if (fromEvent) "EVT" else "QRY"}: " +
                "L=${wearLabel(lastLeftStatus)} " +
                "R=${wearLabel(lastRightStatus)} " +
                "case=${wearLabel(lastCaseStatus)}")
            handler.post {
                listeners.forEach {
                    it.onEarStatus(lastLeftInCase, lastRightInCase)
                    it.onWearState(lastLeftStatus, lastRightStatus, lastCaseStatus)
                }
            }
            return
        }

        // --- getKeyFunction reply: 0x8108, the CURRENT gesture bindings ---
        // This reply is BOTH a diagnostic and the source of truth a write is built from:
        // a setKeyFunction payload carries the WHOLE table, so the entries we are not
        // changing have to come from here. The last good reading is parked in
        // lastKeyFnTable for exactly that, and writeGestureBinding() refuses to run
        // without it rather than inventing the other entries.
        if (cmd == OpoProtocol.CMD_RESP_KEY_FUNCTION) {
            log("KEYFN: ${KeyFunctionParser.describe(payload)}")

            // The diff is the whole point of reading this reply: it describes the
            // CURRENT bindings, so ONE change made in the vendor app names that
            // function's `fn` value. Printed on its own line, and only when there is a
            // baseline to compare against, so the ordinary init-sequence reading does
            // not grow a second line that says nothing.
            val table = KeyFunctionParser.parse(payload)
            if (table.entries.isNotEmpty()) {
                // Only a reply with entries replaces the stored table, for the same
                // reason the diff baseline is not overwritten by an empty one: a
                // garbled read must not cost us the table we would write back from.
                lastKeyFnTable = table
                log("KEYFN DIFF: ${keyFnDiff(table)}")

                // Repaint the LOCAL record from the buds' own truth, not just our diagnostic
                // log — `[USER]` 2026-09-22. This is what makes GestureActivity/on-call show
                // what the buds actually have bound after a reconnect, even if something other
                // than this app changed it (HeyMelody, another phone, a PC tool). See
                // GestureConfigStore.syncFromDevice() / OnCallConfigStore.syncFromDevice().
                GestureConfigStore.syncFromDevice(context, table)
                OnCallConfigStore.syncFromDevice(context, table)
                lastHoldAncMask?.let { GestureConfigStore.syncHoldFromDevice(context, table, it) }
            }
            return
        }

        // --- ANC query reply: 0x810C, carries several questions (see OpoProtocol) ---
        // Shape for both branches below: `[status][echo x2][value LE]`, `[CAPTURE]`.
        if (cmd == 0x810C && payload.size >= 5) {
            val echo1 = payload[1].toInt() and 0xFF
            val echo2 = payload[2].toInt() and 0xFF
            val value = (payload[3].toInt() and 0xFF) or ((payload[4].toInt() and 0xFF) shl 8)

            // Current-mode answer, echo `01 01` — queried once on every connect
            // (OpoProtocol.queryAncMode()). FIXED 2026-09-22: this used to be un-handled here
            // entirely, on the wrong assumption that AncEventParser's PUSH path already covered
            // it. It does not: a push only arrives on a CHANGE, so a reconnect where nothing
            // changed since the last (possibly stale, possibly bogus) persisted value left the
            // display wrong indefinitely — exactly what surfaced as "UI shows ANC-Low after a
            // fresh install, buds are really Off, no tone played" and sent us looking for the
            // real bug (a DIFFERENT one, see AncEventParser.isAncEvent()). Reading this reply
            // corrects the display on every connect regardless of what was persisted before.
            if (echo1 == 0x01 && echo2 == 0x01) {
                val mode = AncEventParser.modeForRaw(value, lastAncLevelSent)
                if (mode != null) {
                    log("ANC QUERY: raw=0x%04X -> %s".format(value, mode))
                    handler.post { listeners.forEach { it.onAncModeState(mode) } }
                }
            }

            // Hold's switch-list answer, echo `02 01`/`02 03`/`02 04` — see OpoProtocol.setHoldAncModes().
            if (echo1 == 0x02) {
                lastHoldAncMask = value
                log("HOLD MODES: mask=0x%04X".format(lastHoldAncMask))
                // Same repaint as GestureConfigStore.syncFromDevice() above, from the other
                // direction: the mask usually arrives AFTER the key-function table in the init
                // sequence, so this is the hook that actually fires the hold's sync in practice.
                lastKeyFnTable?.let { GestureConfigStore.syncHoldFromDevice(context, it, value) }
            }
        }

        // --- User-interaction (button/gesture) report: 0x0204 subType 0xF1 ---
        // This is the F1 family that has been sitting unexplained in the captures.
        // We now at least NAME it, so an ANC-gesture capture can answer the question
        // "nothing arrived" vs "a frame arrived that we don't decode".
        // Handled before the game-mode branch because both are 2..6 byte payloads.
        if (cmd == OpoProtocol.CMD_ACTIVE_REPORT &&
            UserInteractionParser.isUserInteractionEvent(payload)) {
            log("BTN EVT: ${UserInteractionParser.describe(payload)}")
            return
        }

        // --- ANC changed on the buds: 0x0204 spontaneous event, subType 0x03 ---
        // Raised by an ANC gesture on either bud. Mapped and confirmed — see
        // AncEventParser for the frame shape and the four known values.
        //
        // Handled BEFORE the length-based checks below because the payload is only
        // 5 bytes.
        //
        // The event reflects the buds' real state whether the change came from a
        // gesture or from our own command, so there is deliberately NO suppression
        // of "our own" echoes: BudsService drops a mode equal to the stored one, so
        // an echo is a no-op and a genuine external change always lands.
        //
        // A mode that cannot be named (unrecognised value) is logged but NOT pushed,
        // so we never invent a mode the buds did not report.
        if (cmd == OpoProtocol.CMD_ACTIVE_REPORT &&
            AncEventParser.isAncEvent(payload)) {
            val mode = AncEventParser.parseActive(payload, lastAncLevelSent)
            log("ANC EVT: ${AncEventParser.describe(payload, lastAncLevelSent)}")
            if (mode != null) {
                handler.post { listeners.forEach { it.onAncModeState(mode) } }
            }
            return
        }

        // --- Equalizer replies and push, [CAPTURE] 2026-09-23 ---
        when (cmd) {
            0x810F -> if (payload.size >= 2 && payload[0].toInt() == 0) eqCurrent = payload[1].toInt() and 0xFF
            0x8122 -> EqCodec.parseList(payload)?.let { eqCustom = it }
                ?: log("EQ: could not parse custom list RAW=[${OpoProtocol.bytesToHex(payload)}]")
            0x8124 -> if (payload.size >= 4 && payload[0].toInt() == 0) bassWaveLevel = payload[3].toInt()
            OpoProtocol.CMD_EQ_CHANGED -> if (payload.isNotEmpty()) eqCurrent = payload[0].toInt() and 0xFF
        }
        // 0x8418 ack: `00 <id>` — the id the preset now has (a created one's id comes from here).
        if (cmd == 0x8418) log("EQ ack: RAW=[${OpoProtocol.bytesToHex(payload)}]")
        if (cmd == 0x810F || cmd == 0x8122 || cmd == 0x8124 || cmd == OpoProtocol.CMD_EQ_CHANGED) {
            log("EQ: current=$eqCurrent bassWave=$bassWaveLevel custom=" +
                eqCustom.joinToString { "${it.id}:${it.name}${if (it.selected) "*" else ""}${it.gains}" })
            handler.post { listeners.forEach { it.onEqState() } }
            return
        }

        // --- Paired devices: read reply 0x8112 `00 <list>`, or push 0x0204 subType 06 `06 <list>` ---
        val deviceList = when {
            cmd == 0x8112 && payload.isNotEmpty() && payload[0].toInt() == 0 -> parseDevices(payload, 1)
            cmd == OpoProtocol.CMD_ACTIVE_REPORT && payload.isNotEmpty() && payload[0].toInt() == 0x06 ->
                parseDevices(payload, 1)
            else -> null
        }
        if (deviceList != null) {
            devices = deviceList
            log("DEVICES: " + deviceList.joinToString { "${it.name} ${it.mac} ${if (it.connected) "on" else "off"}" })
            handler.post { listeners.forEach { it.onDevices(deviceList) } }
            return
        }

        // --- Alert volume: read reply 0x8130 and set ack 0x8427, both `00 <level>` ---
        if ((cmd == 0x8130 || cmd == 0x8427) && payload.size >= 2 && payload[0].toInt() == 0) {
            val level = payload[1].toInt() and 0xFF
            alertVolume = level
            log("ALERT VOLUME: $level")
            handler.post { listeners.forEach { it.onAlertVolume(level) } }
            return
        }

        // --- Status reply: [status][count] then [featureId][value] pairs, [CAPTURE] 2026-09-23 ---
        if (cmd == 0x810D && payload.size >= 2 && payload[0].toInt() == 0) {
            val count = payload[1].toInt() and 0xFF
            val states = (0 until count).mapNotNull { i ->
                val o = 2 + i * 2
                if (o + 1 < payload.size) (payload[o].toInt() and 0xFF) to (payload[o + 1].toInt() and 0xFF)
                else null
            }.toMap()
            featureStates = states
            log("FEATURES: " + states.entries.joinToString(" ") { "%02X=%d".format(it.key, it.value) })
            // Game mode has its own push (0x0204 subType 0x05) but none at connect time; this is
            // the connect-time read, fed through the same path so the widget follows too.
            val game = states[OpoProtocol.FEATURE_GAME_MODE]
            handler.post {
                listeners.forEach {
                    it.onFeatureStates(states)
                    if (game != null) it.onGameModeState(game == 1)
                }
            }
            return
        }

        // --- Game mode changed on the buds: 0x0204 spontaneous event, subType 0x05 ---
        // Raised for BOTH our own setGameMode() and the user's buds gesture, so this
        // is what keeps the widget/app buttons honest. Previously this frame fell
        // through to the battery checks below, matched neither, and was discarded —
        // which is why the buttons never updated after a gesture.
        // Payload is only 2 bytes, so this must come before any length assumption.
        if (cmd == OpoProtocol.CMD_ACTIVE_REPORT &&
            GameModeParser.isGameModeEvent(payload)) {
            val on = GameModeParser.parseActive(payload)
            if (on != null) {
                log("GAME EVT: ${if (on) "ON" else "OFF"}")
                handler.post { listeners.forEach { it.onGameModeState(on) } }
            }
            return
        }

        val battery = BatteryParser.parse(packet)
        val activeBattery = BatteryParser.parseActive(packet)

        if (battery != null) {
            if (battery.left != null) lastLeft = battery.left
            if (battery.right != null) lastRight = battery.right
            if (battery.case != null) lastCase = battery.case
            emitBattery()
        } else if (activeBattery != null) {
            if (activeBattery.left != null) lastLeft = activeBattery.left
            if (activeBattery.right != null) lastRight = activeBattery.right
            if (activeBattery.case != null) lastCase = activeBattery.case
            emitBattery()
        }
    }

    private fun emitBattery() {
        handler.post {
            listeners.forEach {
                it.onBattery(
                    lastLeft?.level, lastCase?.level, lastRight?.level,
                    lastLeft?.isCharging ?: false,
                    lastCase?.isCharging ?: false,
                    lastRight?.isCharging ?: false
                )
            }
        }
    }

    private fun log(msg: String) {
        Log.d("BudsConn", msg)
        PacketLogger.log(msg)
        handler.post { listeners.forEach { it.onStatus(msg) } }
    }
}
