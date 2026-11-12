package com.spizganed.quickbuds.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothSocketException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.AncEventParser
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.protocol.BatteryParser
import com.spizganed.quickbuds.protocol.LogDecoder
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.Anc
import com.spizganed.quickbuds.protocol.Decode
import com.spizganed.quickbuds.protocol.Msg
import com.spizganed.quickbuds.protocol.Public
import com.spizganed.quickbuds.protocol.Wire.Message
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.ModelProfile
import com.spizganed.quickbuds.protocol.EqCodec
import com.spizganed.quickbuds.protocol.EqSnapshot
import com.spizganed.quickbuds.protocol.EqState
import com.spizganed.quickbuds.protocol.KeyFunctionParser
import com.spizganed.quickbuds.protocol.FastPairStream
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.protocol.Feature
import com.spizganed.quickbuds.protocol.FeatureSync
import com.spizganed.quickbuds.protocol.FeatureSyncConfig
import com.spizganed.quickbuds.protocol.OppoPacketFramer
import com.spizganed.quickbuds.protocol.RowRules
import com.spizganed.quickbuds.protocol.UserInteractionParser
import com.spizganed.quickbuds.ui.GestureConfigStore
import com.spizganed.quickbuds.ui.GestureModel
import com.spizganed.quickbuds.ui.OnCallConfigStore
import com.spizganed.quickbuds.ui.ThemeRes
import com.spizganed.quickbuds.protocol.Commands
import com.spizganed.quickbuds.protocol.Replies
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("MissingPermission")
class BudsConnectionManager(private val context: Context) {

    companion object {
        /** The buds apply a write about a second after the ack; a read sooner returns the old value and the switch flickers. */
        private const val VERIFY_DELAY_MS = 2200L
        /** The buds do not report these: [setFeature] writes them once. */
        private val WRITE_ONLY = setOf(Feature.AUTO_ANSWER)
        /** The buds restart after these writes. */
        private val RESTARTS = setOf(Feature.POWER_SAVING, Feature.HIRES_CODEC)
        private const val ANC_VERIFY_MS = 900L
        /** For this long after our own write, a read of that switch shows what we wrote. */
        private const val PENDING_MS = 2000L
        /** A blocked socket.connect() gets its socket closed after this long, so the try can fail. */
        private const val CONNECT_TIMEOUT_MS = 12_000L
        /** The shortest gap between two case reads over the Fast Pair stream. */
        private const val FP_CASE_READ_GAP_MS = 5 * 60_000L
        /** A case read beside the link closes after this time, also when the buds send no battery message. */
        private const val FP_CASE_READ_MS = 5_000L
        /** A fresh UUID query gets this long for its answer, then its receiver goes away. */
        private const val UUID_REFRESH_TIMEOUT_MS = 10_000L
    }

    /**
     * The A2DP and HEADSET proxies, kept for the life of the manager, so [audioLinkState] answers at once.
     * Declared before `init`: the proxy callbacks run on a Binder thread and can come before the constructor ends.
     */
    private val audioProxies = java.util.concurrent.ConcurrentHashMap<Int, BluetoothProfile>()

    init {
        openAudioProxies()
        PacketLogger.init(context)
    }

    interface Listener {
        fun onConnected(connected: Boolean)
        fun onPacketReceived(bytes: ByteArray) {}
        fun onBattery(left: Int?, case: Int?, right: Int?, chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean)

        /**
         * Full wear state (raw status codes from 0x0109 / 0x0204):
         * 4 = in case, 1/5 = out idle, 3/7 = wearing, 0 = in case with the lid closed, -1 = side not reported.
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

        /** Smart noise cancelling picked a level ([BudsConnectionManager.smartLevel]). */
        fun onSmartLevel() {}

        /**
         * Fit test result (`0x0204` subType `0x04`), per bud: 1 good, 0 average, 6 poor, anything
         * else an error (`[VENDOR]`); -1 = that bud not reported.
         */
        fun onFitResult(left: Int, right: Int) {}

        /** Personalized ANC: `0x811A`, whether the buds hold a result from an earlier test. */
        fun onPersonalNoiseStored(stored: Boolean) {}
        /** Personalized ANC: the test's result (`0x0204` subType `0x0B`), 0 done, 1-5 why it failed. */
        fun onPersonalNoiseResult(result: Int) {}
        /** Personalized ANC: the `0x8412` ack status (0 = accepted). */
        fun onPersonalNoiseAck(status: Int) {}
        /** Tap sensitivity from `0x8133`: the level (1..5) and the buds' default. */
        fun onTapLevel(level: Int, default: Int) {}

        /** Golden Sound test status (`0x0204` subType `0x08`): kind 2 hearing test / 4 ear scan. */
        fun onGoldenStatus(kind: Int, status: Int) {}
        /** A Golden Sound start or tone (`0x040D` / `0x040E`) acked with a non-zero status. */
        fun onGoldenRefused(status: Int) {}
        /** Ear scan result (`0x0204` subType `0x0E`). */
        fun onEarScan(uid: Int, data: ByteArray) {}
        /** `0x8116`: the enhance type of a hearing result (0 low, 1 middle, 2 high). */
        fun onGoldenFilter(uid: Int, enhanceType: Int) {}
        /**
         * `0x8116` / `0x811F`: a record's filters per ear, biquads of 6 floats (a0 a1 a2 b0 b1 b2),
         * [scan] = the ear-scan ones. Drawn by [com.spizganed.quickbuds.ui.HearingRadarView].
         */
        fun onGoldenCurves(uid: Int, scan: Boolean, fs: Int, left: FloatArray, right: FloatArray) {}
        /** `0x8115`: the record on the buds. */
        fun onGoldenActive(uid: Int, name: String, values: IntArray) {}
        /** `0x811E`: the ear-scan data on the buds. */
        fun onGoldenActiveScan(uid: Int, data: ByteArray) {}

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

        /** A setting the user touched is being applied on the buds: its row shows "Applying…" while `on`. */
        fun onSettingBusy(key: Int, on: Boolean) {}

        /** Any EQ reading changed — see [eqCurrent], [eqCustom], [bassWaveLevel]. */
        fun onEqState() {}

        /** The buds reported their alert-sound volume (1..10), see [alertVolume]. */
        fun onAlertVolume(level: Int) {}

        /** The paired-device list changed, see [devices]. */
        fun onDevices(list: List<PairedDevice>) {}
        /** The preferred device from `0x8132`: null = automatic. */
        fun onPreferred(mac: String?) {}

        /** The buds reported which commands they accept (`0x8100`), see [Capabilities]. */
        fun onCapabilities() {}
        /** The firmware version, as HeyMelody shows it (`0x8105`). */
        fun onFirmware(version: String) {}
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

    // --- Feature sync (docs/FEATURE-SYNC.md): one engine, one thread ---
    /** Every engine call and every output it produces runs on this thread. */
    private val syncThread = HandlerThread("feature-sync").apply { start() }
    private val syncHandler = Handler(syncThread.looper)
    private val sync = FeatureSync()
    /** Wire sequence byte -> the engine's own number, for the writes and reads the engine sent. */
    private val wireToEngine = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /**
     * The engine number for a reply with wire seq [seq]. A model with `"seqless":1` (realme Buds T100, report #100)
     * answers with its own counter, so the newest outstanding engine request is the one that the reply answers.
     */
    private fun takeEngineSeq(seq: Int): Int? {
        wireToEngine.remove(seq)?.let { return it }
        if (!ModelCatalog.profile(context).seqless) return null
        val wire = wireToEngine.entries.maxByOrNull { it.value }?.key ?: return null
        log("SYNC: reply seq=0x%02X pairs with request seq=0x%02X (seqless model)".format(seq, wire))
        return wireToEngine.remove(wire)
    }

    /** The only timer of the feature sync: asks the engine to give up on a silent buds. */
    private val tickRunnable = Runnable { execute(sync.tick(SystemClock.elapsedRealtime())) }

    /**
     * False until the first status reply of a connection primes the engine with the buds' raw values.
     * Without it that first reply emits `Changed` for every id and rewrites the old-path switches.
     */
    @Volatile private var syncPrimed = false

    private var bluetoothSocket: BluetoothSocket? = null
    private var connectedThread: ConnectedThread? = null
    private var isReady = false
    private var isConnecting = false
    private var reconnectAttempts = 0
    /** The receiver of the pending fresh-UUID query, and its timeout. Both live on [handler]. */
    private var uuidReceiver: BroadcastReceiver? = null
    private var uuidTimeout: Runnable? = null

    // Reconnect-after-loss state — see reconnectAfterLoss().
    private var lastDevice: BluetoothDevice? = null
    private var lossRetries = 0
    private var connectedAt = 0L
    private var pendingReconnect: Runnable? = null

    /** The 5 s auto-retry of a failed connect, so a deliberate disconnect cancels it. */
    private var pendingRetry: Runnable? = null

    /** Bumped on every connect and on every disconnect: a late connect thread must not install a socket. */
    private var connectGeneration = 0
    /** Set by the all-zero wear push the buds send just before a lid close drops the link (PROTOCOL.md §13). */
    @Volatile private var caseClosing = false
    /**
     * Set by a write that restarts the buds (power saving, PROTOCOL.md §16). They come back with
     * our link only, not the phone's audio, so the next successful connect asks for it: the
     * restart was the user's own action, unlike a system auto-connect (see [connect]).
     */
    @Volatile private var audioAfterRestart = false

    private var lastLeft: BatteryParser.Info? = null
    private var lastRight: BatteryParser.Info? = null
    private var lastCase: BatteryParser.Info? = null

    private var lastLeftStatus = -1
    private var lastRightStatus = -1
    private var lastCaseStatus = -1

    fun isConnected(): Boolean = isReady && linkUp()

    /** No link, no connect in progress and no retry queued: the service can stop (see BudsService.park). */
    fun isIdle(): Boolean = !isConnected() && !isConnecting && pendingRetry == null && pendingReconnect == null

    /** The open link. A null adapter never reaches this. */
    private fun linkUp(): Boolean = bluetoothSocket?.isConnected == true

    /**
     * Drops or restores the phone's own audio link, the way HeyMelody's Connect/Disconnect does:
     * the hidden `connect()`/`disconnect()` on the A2DP and Headset proxies, by reflection. Headset `connect()`
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

    private fun openAudioProxies() {
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            for (p in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { audioProxies[profile] = proxy }
                    override fun onServiceDisconnected(profile: Int) { audioProxies.remove(profile) }
                }, p)
            }
        } catch (e: Exception) {
            log("Audio proxies: ${e.message}")
        }
    }

    /**
     * Whether Android reports [device] connected on A2DP or HEADSET: the live audio link of buds that are
     * out of the case. `BluetoothManager.getConnectedDevices` takes GATT only, so this asks the profile
     * proxies for this device. [LinkState.UNKNOWN] means no answer (no proxy yet, Bluetooth off, no
     * permission): the caller connects as before.
     */
    @SuppressLint("MissingPermission")
    fun audioLinkState(device: BluetoothDevice): LinkState = try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val proxies = audioProxies.values.toList()
        when {
            adapter == null || !adapter.isEnabled -> LinkState.UNKNOWN
            proxies.any { it.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED } -> LinkState.CONNECTED
            // "Disconnected" needs both answers: a missing proxy can hide a live HFP or A2DP link.
            proxies.size < 2 -> LinkState.UNKNOWN
            else -> LinkState.DISCONNECTED
        }
    } catch (e: Exception) {
        LinkState.UNKNOWN
    }

    /**
     * An automatic connect: it starts only with the live audio link. With the buds in the closed case Android reports
     * the audio profiles disconnected, and the socket tries would only fail. An unknown state connects. False: skipped.
     */
    fun autoConnect(device: BluetoothDevice, withAudio: Boolean): Boolean {
        if (!mayAutoConnect(device)) return false
        connect(device, withAudio)
        return true
    }

    /** The rule of [autoConnect]. No device found counts as an unknown state: it connects. */
    fun mayAutoConnect(device: BluetoothDevice?): Boolean =
        ConnectPolicy.autoConnectAllowed(device?.let { audioLinkState(it) } ?: LinkState.UNKNOWN)

    /**
     * Starts the socket tries. [withAudio] brings the phone audio up (a user connect only).
     * [user] is true for the Connect button, the widget tap and Dev tools: only then the tries
     * show toasts. An automatic connect stays silent.
     */
    fun connect(device: BluetoothDevice, withAudio: Boolean = false, user: Boolean = false) {
        if (isConnecting || isConnected()) {
            log("Already connecting/connected, ignoring.")
            return
        }
        isConnecting = true
        lastDevice = device
        stopFastPair()
        val generation = ++connectGeneration
        // Starts the attempt, or clears the latch if the setup before it throws.
        val started = try {
            // Only a connect the user asked for (the pill) brings phone audio up. An automatic one —
            // ACL receiver, retries, reconnect after loss — leaves audio to Android: asking for A2DP
            // while the system is auto-connecting raced it and left audio stuck.
            if (withAudio) setPhoneAudio(device, on = true)
            log("Initiating RFCOMM connection...")
            rememberDeviceName(device)
            context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
            true
        } catch (e: Exception) {
            log("Connect error: ${e.javaClass.simpleName}: ${e.message}")
            isConnecting = false
            false
        }
        if (!started) return

        Thread {
            try {
                log("Bond state: ${ConnectDiagnostics.bondStateWord(device.bondState)}")
                log("Cached service UUIDs: ${ConnectDiagnostics.formatUuids(device.uuids?.map { it.uuid.toString() }.orEmpty())}")

                // 079A first: it is the one that works (HeyMelody connects straight to it), while
                // 1107 never connects on the Buds 4 and burns ~5 s. 1107 stays for other models.
                val uuids = listOf(
                    OpoProtocol.SPP_UUID_FALLBACK,
                    OpoProtocol.SPP_UUID_PRIMARY
                )
                // Secure first, then insecure: a stale bond or a firmware that refuses an
                // authenticated link fails both secure tries, and the desktop app never asks for one.
                val tries = listOf(false, true).flatMap { insecure -> uuids.map { it to insecure } }
                var socket: BluetoothSocket? = null
                var lastError: String? = null
                // Every socket try counts: the 4 UUID tries and channel 15, 5 in total, then the app stops.
                val totalTries = tries.size + 1
                var failedTries = 0
                val failures = mutableListOf<Pair<String?, Long>>()
                fun tryFailed() {
                    failedTries++
                    if (generation == connectGeneration && ConnectPolicy.showTryToast(user))
                        toast(context.getString(R.string.connect_try_failed, failedTries, totalTries))
                }

                // socket.connect() has no timeout. A close after 12 s makes a stuck call throw.
                val watchdog = Runnable { try { socket?.close() } catch (_: Exception) {} }

                for ((uuidStr, insecure) in tries) {
                    val kind = if (insecure) "insecure" else "secure"
                    val tryStart = SystemClock.elapsedRealtime()
                    try {
                        log("Trying UUID: $uuidStr ($kind)")
                        val uuid = UUID.fromString(uuidStr)
                        socket = if (insecure) device.createInsecureRfcommSocketToServiceRecord(uuid)
                            else device.createRfcommSocketToServiceRecord(uuid)
                        handler.postDelayed(watchdog, CONNECT_TIMEOUT_MS)
                        socket?.connect()
                        log("Connected via UUID: $uuidStr ($kind) (${SystemClock.elapsedRealtime() - tryStart} ms)")
                        break
                    } catch (e: Exception) {
                        lastError = e.message ?: e.javaClass.simpleName
                        failures += lastError to (SystemClock.elapsedRealtime() - tryStart)
                        log("Failed UUID $uuidStr ($kind): $lastError (${SystemClock.elapsedRealtime() - tryStart} ms)")
                        log("Connect error: ${e.javaClass.simpleName}: ${e.message}")
                        logSocketError(e)
                        tryFailed()
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                    } finally {
                        handler.removeCallbacks(watchdog)
                    }
                }

                if (socket == null) {
                    val tryStart = SystemClock.elapsedRealtime()
                    try {
                        log("Trying RFCOMM channel 15...")
                        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        socket = m.invoke(device, 15) as BluetoothSocket
                        handler.postDelayed(watchdog, CONNECT_TIMEOUT_MS)
                        socket.connect()
                        log("Connected via raw channel 15 (${SystemClock.elapsedRealtime() - tryStart} ms)")
                    } catch (e: Exception) {
                        lastError = e.message ?: e.javaClass.simpleName
                        failures += lastError to (SystemClock.elapsedRealtime() - tryStart)
                        log("Channel 15 failed: $lastError (${SystemClock.elapsedRealtime() - tryStart} ms)")
                        logSocketError(e)
                        tryFailed()
                        socket = null
                    } finally {
                        handler.removeCallbacks(watchdog)
                    }
                }

                if (socket != null && generation != connectGeneration) {
                    log("Connect attempt cancelled: the link was dropped meanwhile.")
                    try { socket.close() } catch (_: Exception) {}
                    return@Thread
                }
                if (socket != null) {
                    bluetoothSocket = socket
                    connectedThread = ConnectedThread(socket.inputStream, socket.outputStream) { socket.close() }
                    connectedThread?.start()
                    onLinkUp(device)
                } else {
                    log("All connection methods failed: $lastError")
                    startUuidRefresh(device, attempts = reconnectAttempts)
                    teardownLink()
                    // One round of 5 socket tries, then stop: no automatic second round. A user
                    // Disconnect or a new connect raised the generation, so that attempt ends quietly.
                    if (generation == connectGeneration) {
                        log("Giving up after $totalTries connect tries. Tap CONNECT to retry manually.")
                        val held = ConnectDiagnostics.heldByOther(failures)
                        if (held) log("Every try failed fast with read ret -1: another device or app probably holds the earbuds.")
                        if (held) startFastPair(device, held = true)
                        if (ConnectPolicy.showTryToast(user))
                            toast(if (held) context.getString(R.string.connect_held_by_other) else context.getString(R.string.connect_gave_up, totalTries))
                    }
                    reconnectAttempts = 0
                    return@Thread
                }
            } finally {
                // Every exit clears the latch. An exception outside the socket tries used to keep it set.
                isConnecting = false
            }
        }.start()
    }

    /**
     * Asks for a fresh UUID list after the failed tries. The receiver waits for the answer or
     * [UUID_REFRESH_TIMEOUT_MS]. An auto-retry does not ask again, and a waiting answer blocks a
     * second receiver.
     */
    private fun startUuidRefresh(device: BluetoothDevice, attempts: Int) {
        handler.post {
            if (!ConnectDiagnostics.shouldFetchFreshUuids(attempts, refreshPending = uuidReceiver != null)) return@post
            val receiver = registerUuidReceiver(device) ?: return@post
            val timeout = Runnable { finishUuidRefresh(receiver) }
            uuidReceiver = receiver
            uuidTimeout = timeout
            handler.postDelayed(timeout, UUID_REFRESH_TIMEOUT_MS)
            val started = runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
            if (!started) {
                log("Fresh UUID query did not start.")
                finishUuidRefresh(receiver)
            }
        }
    }

    /** Stops the wait for [receiver] once. The answer and the timeout both come here. */
    private fun finishUuidRefresh(receiver: BroadcastReceiver) {
        if (uuidReceiver !== receiver) return
        uuidReceiver = null
        uuidTimeout?.let { handler.removeCallbacks(it) }
        uuidTimeout = null
        unregisterUuidReceiver(receiver)
    }

    /** Listens for the fresh UUID list of [device] during one connect. Returns the receiver, or null on failure. */
    private fun registerUuidReceiver(device: BluetoothDevice): BroadcastReceiver? {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val extra = IntentCompat.getParcelableExtra(
                    intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                )
                if (extra?.address != device.address) return
                val uuids = IntentCompat.getParcelableArrayExtra(
                    intent, BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java
                )
                    ?.mapNotNull { (it as? ParcelUuid)?.uuid?.toString() }
                    .orEmpty()
                log("Fresh service UUIDs: ${ConnectDiagnostics.formatUuids(uuids)}")
                finishUuidRefresh(this)
            }
        }
        return try {
            val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
            // The Bluetooth stack sends ACTION_UUID from its own process, so a not-exported receiver never gets it. The action is protected.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiver
        } catch (e: Exception) {
            log("UUID refresh unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun unregisterUuidReceiver(receiver: BroadcastReceiver) {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    /**
     * Everything after a link is open: the init sequence, the feature-sync engine and the UI callbacks.
     */
    private fun onLinkUp(audioDevice: BluetoothDevice?) {
        isReady = true
        reconnectAttempts = 0
        connectedAt = System.currentTimeMillis()
        caseClosing = false
        equalizer.onLinkUp(audioDevice?.address)
        if (audioDevice != null && audioAfterRestart) { audioAfterRestart = false; setPhoneAudio(audioDevice, on = true) }
        (audioDevice ?: lastDevice)?.let { audioCheck(it, connectGeneration) }
        handler.post { listeners.forEach { it.onConnected(true) } }
        syncPrimed = false
        syncHandler.post { execute(sync.reconnect(SystemClock.elapsedRealtime())) }
        log("Ready for commands. Running init sequence...")
        runInitSequence()
    }

    /**
     * Android sometimes leaves the phone audio down after the buds connect (no media, no calls). An automatic
     * connect does not ask for audio at once: that raced Android's own audio connect. 1 s after the link is up,
     * audio that is still fully down (not connecting) is brought up, as the Connect button does.
     */
    @SuppressLint("MissingPermission")
    private fun audioCheck(device: BluetoothDevice, generation: Int) {
        handler.postDelayed({
            if (generation != connectGeneration || !isConnected()) return@postDelayed
            val proxies = audioProxies.values.toList()
            val down = try {
                proxies.size == 2 && proxies.all { it.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED }
            } catch (_: Exception) { false }
            if (down) { log("Audio check: phone audio down, connecting"); setPhoneAudio(device, on = true) }
        }, 1000)
    }

    private fun runInitSequence() {
        offeredEvents = null
        Thread {
            try {
                delay(300); sendRawBlocking(Commands.handshake(), "handshake")
                delay(200); sendRawBlocking(Commands.queryProductId(), "query product id")
                delay(200); sendRawBlocking(Commands.queryBroadcastCodes(), "query broadcast codes")
                // The handshake reply (0x8100) has landed by now; the reads below go out only if
                // these buds list them, as HeyMelody does (see Capabilities). Buds with the `0x012F`
                // batch get them in one frame at the end, as HeyMelody sends them.
                val batch = if (Capabilities.supports(context, OpoProtocol.CMD_BATCH)) mutableListOf<ByteArray>() else null
                fun query(cmd: Int, packet: ByteArray, label: String) {
                    if (!Capabilities.supports(context, cmd)) { log("skip $label: not supported"); return }
                    if (batch != null) { batch += packet; return }
                    delay(200); sendRawBlocking(packet, label)
                }
                delay(200)
                val ids = Commands.notifyIds(Capabilities.supports(context, OpoProtocol.CMD_FIT_TEST),
                    Capabilities.supports(context, OpoProtocol.CMD_GOLDEN_DETECT),
                    Capabilities.supports(context, OpoProtocol.CMD_PERSONAL_NOISE), offeredEvents)
                when {
                    ids.isEmpty() -> log("skip register notify: the buds offer none of ours")
                    Capabilities.supports(context, OpoProtocol.CMD_REGISTER_NOTIFY) ->
                        sendRawBlocking(Commands.registerNotifications(ids), "register notify")
                    else -> ids.forEach { sendRawBlocking(Commands.registerOne(it), "register notify $it") }
                }
                query(OpoProtocol.CMD_QUERY_STATUS, statusQuery(), "query status")
                query(OpoProtocol.CMD_QUERY_ANC, Commands.queryAncMode(), "query anc")
                if (AncModes.of(context).supports(AncModes.SMART))
                    query(OpoProtocol.CMD_QUERY_ANC, Commands.querySmartLevel(), "query smart level")
                query(OpoProtocol.CMD_QUERY_ALERT_VOLUME, Commands.queryAlertVolume(), "query alert volume")
                query(OpoProtocol.CMD_QUERY_BATTERY, Commands.queryBattery(), "query battery")
                query(OpoProtocol.CMD_QUERY_WEARING, Commands.queryWearingStatus(), "query wearing")
                // The current gesture bindings, the table every gesture write is built from.
                query(OpoProtocol.CMD_QUERY_KEY_FUNCTION, Commands.queryKeyFunction(), "query key function")
                // The hold's ANC cycle, see OpoProtocol.queryNoiseSwitchModes().
                for (type in GestureModel.of(context).holdTypes())
                    query(OpoProtocol.CMD_QUERY_ANC, Commands.queryNoiseSwitchModes(type), "query noise switch $type")
                // Logged only until a Buds 4 reply confirms the [OSS] format (ROADMAP, firmware version).
                query(OpoProtocol.CMD_QUERY_FIRMWARE, Commands.queryFirmware(), "query firmware")
                query(OpoProtocol.CMD_QUERY_SPATIAL_TYPE, Commands.querySpatialType(), "query spatial type")
                query(OpoProtocol.CMD_QUERY_CODEC_LIST, Commands.queryCodecList(), "query codec list")
                query(OpoProtocol.CMD_QUERY_CODEC, Commands.queryCodec(), "query codec")
                query(OpoProtocol.CMD_QUERY_GAME_SOUND, Commands.queryGameSound(), "query game sound")
                query(OpoProtocol.CMD_QUERY_HEAD_MOTION_TYPE, Commands.queryHeadMotionType(), "query head motion type")
                if (!batch.isNullOrEmpty()) { delay(200); sendRawBlocking(Replies.batch(batch), "batch query (${batch.size})") }
                // realme Link hands 3D audio to the buds on every connect; else the `1B` switch can do nothing (issue #9).
                if (ModelCatalog.profile(context).spatialToggle &&
                    Capabilities.supports(context, OpoProtocol.CMD_SPATIAL_SOURCE)) {
                    delay(200); sendRawBlocking(Commands.spatialOnBuds(),"3D audio on the buds")
                }
            } catch (e: Exception) {
                log("Init sequence error: ${e.message}")
            }
        }.start()
    }

    /**
     * A link we did not close ourselves dropped — typically the buds restarting after a codec
     * change, which drops us 2-3 times in a row while they settle. Nothing else
     * would reconnect: KeepAliveReceiver connects when an audio profile reports connected; the
     * buds can drop just our RFCOMM channel while the Bluetooth link stays up. Growing delays
     * give them time to settle; a connection that lived 30 s starts the count over.
     */
    private fun reconnectAfterLoss() {
        val device = lastDevice ?: return
        if (System.currentTimeMillis() - connectedAt > 30_000) lossRetries = 0
        if (lossRetries >= 8) {
            log("Reconnect after loss: giving up after 8 tries.")
            return
        }
        lossRetries++
        val wait = 3000L * lossRetries
        log("Reconnect after loss $lossRetries/8 in ${wait / 1000}s...")
        val r = Runnable { if (!isConnected() && !isConnecting) connect(device) }
        pendingReconnect = r
        handler.postDelayed(r, wait)
    }

    fun disconnect() {
        // A deliberate disconnect must not be undone by a queued reconnect.
        connectGeneration++
        stopFastPair()
        teardownLink()
    }

    /**
     * Closes the link and clears its state. It does not raise [connectGeneration], so a failed
     * connect attempt can clean up and still schedule its auto-retry. A user Disconnect uses
     * [disconnect], which raises the generation first.
     */
    private fun teardownLink() {
        if (isReady) com.spizganed.quickbuds.ui.SupportAsk.countConnection(context, System.currentTimeMillis() - connectedAt)
        pendingReconnect?.let { handler.removeCallbacks(it) }
        pendingReconnect = null
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
        isReady = false
        isConnecting = false
        equalizer.onLinkDown()
        connectedThread?.cancel()
        connectedThread = null
        bluetoothSocket = null
        stopFastPair()
        fpCaseReadAt = -FP_CASE_READ_GAP_MS
        handler.post { listeners.forEach { it.onConnected(false) } }
        syncPrimed = false
        syncHandler.post { execute(sync.disconnect(SystemClock.elapsedRealtime())) }
        log("Disconnected")
    }

    /**
     * Sets a noise mode by its app name ([AncModes]), with the connected model's bit. A mode the
     * model does not have is logged and not sent: its bit would be another mode's, or nothing.
     */
    fun sendAnc(mode: String) {
        val bit = AncModes.of(context).bit(mode)
            ?: return log("ANC: $mode is not a mode of these buds, not sent")
        if (mode in AncModes.LEVELS) lastAncLevelSent = mode
        sendRaw(Commands.anc(bit),"ANC $mode")
        // Quick taps can lose a push from the buds. One read after the last tap puts the real mode on screen.
        handler.removeCallbacks(verifyAnc)
        handler.postDelayed(verifyAnc, ANC_VERIFY_MS)
    }

    private val verifyAnc = Runnable { sendRaw(Commands.queryAncMode(), "verify anc") }

    private val pending = java.util.concurrent.ConcurrentHashMap<Int, Pair<Int, Long>>()

    /** The feature id of each `0x0403` write by sequence byte, so a refusal in the log names its switch. */
    private val featureWrites = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    private fun notePending(id: Int, on: Boolean) { pending[id] = (if (on) 1 else 0) to System.currentTimeMillis() }

    /** A status read inside [PENDING_MS] of our own write keeps the written value for that switch (no flicker). */
    private fun withPending(states: Map<Int, Int>): Map<Int, Int> {
        val now = System.currentTimeMillis()
        val out = states.toMutableMap()
        for ((id, v) in pending) { if (now - v.second > PENDING_MS) pending.remove(id) else if (id in out) out[id] = v.first }
        return out
    }

    /**
     * Dual connection, in HeyMelody's exact order (`[CAPTURE]` 2026-09-25): `0x0403 11 xx`, a status
     * re-read, then `0x0413 08 00 xx`. The buds answer with a `0x0204` subType `06` device list.
     */
    fun setDualDevice(on: Boolean) {
        Thread {
            try {
                notePending(Feature.DUAL_DEVICE, on)
                sendRawBlocking(Commands.feature(Feature.DUAL_DEVICE, on), "Dual device -> $on")
                Thread.sleep(VERIFY_DELAY_MS)
                sendRawBlocking(statusQuery(), "verify features")
                sendRawBlocking(Commands.dualFollowup(on),"Dual follow-up")
            } catch (e: Exception) {
                log("DUAL WRITE failed: ${e.message}")
            }
        }.start()
    }

    /** One entry of the buds' paired-device list (`0x8112`, `0x0204` subType `06`). */
    data class PairedDevice(val mac: String, val name: String, val connected: Boolean,
        val thisPhone: Boolean = false)

    /** Latest device list, empty until read. See [refreshDevices]. */
    @Volatile var devices: List<PairedDevice> = emptyList()
        private set

    fun refreshDevices() = sendRaw(Commands.queryDevices(), "query devices")

    /** Connects or disconnects another device on the list; the buds push the new list. */
    fun connectDevice(mac: String, on: Boolean) =
        sendRaw(Commands.connectDevice(mac, on),"${if (on) "Connect" else "Disconnect"} $mac")

    fun setPreferred(mac: String?) {
        Thread {
            try {
                sendRawBlocking(Commands.setPreferred(mac), "Preferred ${mac ?: "auto"}")
                Thread.sleep(VERIFY_DELAY_MS)
                sendRawBlocking(Commands.queryPreferred(), "query preferred")
            } catch (e: Exception) {
                log("PREFERRED write failed: ${e.message}")
            }
        }.start()
    }

    fun refreshPreferred() = sendRaw(Commands.queryPreferred(), "query preferred")

    // --- Equalizer (PROTOCOL.md §17). Read on demand by the EQ screen, re-read after every write. ---
    /** The events these buds offer (`0x8200`), once read; reset on each connect. */
    @Volatile private var offeredEvents: Set<Int>? = null

    /** The equalizer: state, snapshot and pick rules ([Equalizer]). Its writes go out on [eqQueue], in order. */
    private val eqQueue = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val equalizer = Equalizer(
        now = System::currentTimeMillis,
        send = { frame, label -> sendRawBlocking(frame, label) },
        supports = { Capabilities.supports(context, it) },
        saved = featurePrefs().getString(EqSnapshot.KEY, null),
        save = { featurePrefs().edit().putString(EqSnapshot.KEY, it).apply() },
        onThread = { eqQueue.execute(it) },
        log = ::log,
    )
    /** The whole state, so one render paints one version of it. */
    val eq: EqState get() = equalizer.state
    val eqCurrent: Int? get() = equalizer.state.current
    val eqCustom: List<EqCodec.Preset> get() = equalizer.state.custom
    val bassWaveLevel: Int? get() = equalizer.state.bassWave

    fun refreshEq() = equalizer.refresh()
    fun selectBuiltInEq(id: Int) = equalizer.selectBuiltIn(id)
    fun selectCustomEq(p: EqCodec.Preset) = equalizer.selectCustom(p)
    fun saveCustomEq(p: EqCodec.Preset) = equalizer.saveCustom(p)
    fun createCustomEq(name: String, freqs: List<Int>) = equalizer.createCustom(name, freqs)
    fun deleteCustomEq(p: EqCodec.Preset) = equalizer.deleteCustom(p)
    fun setBassWaveLevel(level: Int) = equalizer.setBassWave(level)
    fun setBassBand(band: Int, level: Int) = equalizer.setBassBand(band, level)

    /** Find my earbuds: both buds' own locator tone. `[CAPTURE]` 2026-09-23, PROTOCOL.md §18. */
    fun setFindTone(on: Boolean) = sendRaw(Commands.findTone(on),"Find tone -> $on")

    /**
     * Latest `0x810D` reply as feature id -> value (PROTOCOL.md §16). Persisted, so the switches
     * open at the last known state instead of jumping when the connect-time read lands.
     */
    @Volatile var featureStates: Map<Int, Int> = Capabilities.reply(context).orEmpty()
        private set(value) {
            field = value
            Capabilities.saveReply(context, value)
        }

    /**
     * The Bluetooth name HeyMelody matches the model list by ([ModelCatalog]). Other buds than last
     * time: the old product id, manual pick, bitmap and feature reply go, so neither names the new
     * buds until `0x8103`, and no saved row of the old buds shows on the new ones ([RowRules]).
     */
    private fun rememberDeviceName(device: BluetoothDevice) {
        rememberDeviceName(runCatching { device.name }.getOrNull())
    }

    /** [name] is the Bluetooth name. */
    private fun rememberDeviceName(name: String?) {
        if (name == null) return
        val prefs = featurePrefs()
        if (prefs.getString(ModelCatalog.KEY_DEVICE_NAME, null) == name) return
        prefs.edit().putString(ModelCatalog.KEY_DEVICE_NAME, name).remove(ModelCatalog.KEY_MANUAL).apply()
        Capabilities.forget(context)
        // The in-memory copy is the saved reply of the old buds: drop it too, until the first fresh reply.
        featureStates = emptyMap()
    }

    private fun featurePrefs() = context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * `0x0403` feature writes, sent IN ORDER on one thread (HeyMelody sends spatial before
     * codec, `[CAPTURE]` 2026-09-23), then a status query so [Listener.onFeatureStates]
     * reports what the buds actually took. A codec change drops the link, in which case the
     * connect sequence's own status query does that instead.
     */
    fun setVolumeEnhancer(on: Boolean) = sendRaw(Commands.volumeEnhancer(on), "Volume enhancer -> $on")

    private fun setFeatures(vararg changes: Pair<Int, Boolean>) {
        Thread {
            try {
                for ((id, on) in changes) {
                    notePending(id, on)
                    // The buds restart after these two and come back without phone audio (a 3D audio switch that drops hi-res restarts them too).
                    if (id == Feature.POWER_SAVING || id == Feature.HIRES_CODEC) audioAfterRestart = true
                    sendRawBlocking(Commands.feature(id, on), "Feature 0x%02X -> %s".format(id, on))
                }
                Thread.sleep(VERIFY_DELAY_MS)
                sendRawBlocking(statusQuery(), "verify features")
            } catch (e: Exception) {
                log("FEATURE WRITE failed: ${e.message}")
            }
        }.start()
    }

    /**
     * One `0x0403` switch as the user wants it: the one way a screen, widget or tile writes a switch. This picks the
     * confirmation (game mode by the model's `gameConfirm`, else a status read), the restart (power saving and
     * high-quality audio restart the buds) and the coupled switch: 3D audio and high-quality audio exclude each other
     * unless the model has `spatialToggle`, so turning one on turns the other off in the same operation, in
     * HeyMelody's order (spatial first). A write-only switch (auto-answer) is sent once with no confirmation.
     */
    fun setFeature(id: Int, on: Boolean) {
        if (id in WRITE_ONLY) return setFeatures(id to on)
        val model = ModelCatalog.current(context)?.json
        val exclusive = !ModelProfile(model).spatialToggle
        val v = if (on) 1 else 0
        val writes = when {
            exclusive && on && id == Feature.SPATIAL_SOUND && featureStates[Feature.HIRES_CODEC] == 1 ->
                listOf(Feature.SPATIAL_SOUND to 1, Feature.HIRES_CODEC to 0)
            exclusive && on && id == Feature.HIRES_CODEC && featureStates[Feature.SPATIAL_SOUND] == 1 ->
                listOf(Feature.SPATIAL_SOUND to 0, Feature.HIRES_CODEC to 1)
            else -> listOf(id to v)
        }
        val confirm = if (id == Capabilities.gameModeId(context)) FeatureSyncConfig.gameConfirm(model) else FeatureSync.Confirm.READ
        requestSetting(id, writes, confirm, restarts = writes.any { it.first in RESTARTS })
    }

    /**
     * One user action on a setting. The engine sends the writes in order and waits for the confirmation
     * kind given; [key] is the control that shows "applying". See docs/FEATURE-SYNC.md. Screens call [setFeature].
     */
    private fun requestSetting(key: Int, writes: List<Pair<Int, Int>>, confirm: FeatureSync.Confirm, restarts: Boolean = false) {
        if (key !in FeatureSyncConfig.ENABLED) {
            // This key is switched back to the old path: one write per pair, then a status read.
            setFeatures(*writes.map { it.first to (it.second == 1) }.toTypedArray())
            return
        }
        log("SYNC: tap key=0x%02X writes=%s confirm=%s restarts=%s".format(
            key, writes.joinToString { "0x%02X=%d".format(it.first, it.second) }, confirm, restarts))
        syncHandler.post {
            if (restarts) audioAfterRestart = true
            execute(sync.tap(FeatureSync.Operation(key, writes, confirm, restarts), SystemClock.elapsedRealtime()))
        }
    }

    /** Engine seqs travel as 0xC0..0xDF; the generic counter in OpoProtocol never uses that range. */
    private fun wireSeq(seq: Int) = 0xC0 + (seq % 0x20)

    /** Runs an engine output list on the sync thread. */
    private fun execute(outs: List<FeatureSync.Out>) {
        for (out in outs) when (out) {
            is FeatureSync.Out.Send -> {
                val wire = wireSeq(out.seq)
                wireToEngine[wire] = out.seq
                featureWrites[wire] = out.id
                sendRawBlocking(
                    Commands.feature(out.id, out.value == 1, wire),
                    "Feature 0x%02X -> %s".format(out.id, out.value == 1)
                )
            }
            is FeatureSync.Out.Read -> {
                val wire = wireSeq(out.seq)
                wireToEngine[wire] = out.seq
                sendRawBlocking(statusQuery(wire), "verify features")
            }
            is FeatureSync.Out.Changed -> {
                log("SYNC: value 0x%02X -> %d".format(out.id, out.value))
                featureStates = featureStates + (out.id to out.value)
                handler.post {
                    listeners.forEach {
                        it.onFeatureStates(featureStates)
                        if (out.id == Capabilities.gameModeId(context)) it.onGameModeState(out.value == 1)
                    }
                }
            }
            is FeatureSync.Out.Busy -> {
                log("SYNC: key 0x%02X busy=%s".format(out.key, out.on))
                handler.post { listeners.forEach { it.onSettingBusy(out.key, out.on) } }
            }
            is FeatureSync.Out.Toast -> {
                log("FEATURE %s: key=0x%02X kind=%s codec=%s".format(
                    java.util.Locale.ROOT, toastLogName(out.kind), out.key, toastKindName(out.kind), codec
                ))
                val text = toastText(out.key, out.kind)
                handler.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
            }
        }
        // Re-arm only while an operation is running: this is the feature's only timer.
        syncHandler.removeCallbacks(tickRunnable)
        if (sync.active()) syncHandler.postDelayed(tickRunnable, 500)
    }

    /** A short toast from any thread. */
    private fun toast(text: String) = handler.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

    private fun toastLogName(kind: FeatureSync.ToastKind) = when (kind) {
        FeatureSync.ToastKind.REFUSED_MISSING, FeatureSync.ToastKind.REFUSED_NOW -> "REFUSED"
        FeatureSync.ToastKind.NOT_CHANGED -> "NOT CHANGED"
        FeatureSync.ToastKind.NO_ANSWER -> "NO ANSWER"
    }

    private fun toastKindName(kind: FeatureSync.ToastKind) = when (kind) {
        FeatureSync.ToastKind.REFUSED_MISSING -> "refused_missing"
        FeatureSync.ToastKind.REFUSED_NOW -> "refused_now"
        FeatureSync.ToastKind.NOT_CHANGED -> "not_changed"
        FeatureSync.ToastKind.NO_ANSWER -> "no_answer"
    }

    private fun toastText(key: Int, kind: FeatureSync.ToastKind): String {
        val name = settingName(key)
        return when (kind) {
            FeatureSync.ToastKind.REFUSED_MISSING -> context.getString(R.string.feature_refused_missing, name)
            FeatureSync.ToastKind.REFUSED_NOW -> {
                // Hi-Res refused now: the phone plays no LDAC or LHDC (reports #33 #42 #44) and the
                // model names a better codec. Without that name the generic text is used.
                val best = if (key == Feature.HIRES_CODEC) ModelCatalog.hiresBestCodec(context, codecs) else null
                val played = codec?.takeIf { it != 3 && it != 7 && it != 8 }
                if (best != null && played != null)
                    context.getString(R.string.hires_needs_codec, codecName(played), best)
                else context.getString(R.string.feature_refused_now, name)
            }
            FeatureSync.ToastKind.NOT_CHANGED -> context.getString(R.string.feature_not_changed, name)
            FeatureSync.ToastKind.NO_ANSWER -> context.getString(R.string.feature_no_answer, name)
        }
    }

    private fun settingName(key: Int): String = when (key) {
        Feature.HIRES_CODEC -> context.getString(R.string.row_hires_title)
        Capabilities.gameModeId(context) -> context.getString(R.string.row_game_title)
        Feature.SPATIAL_SOUND -> context.getString(R.string.row_spatial_title)
        Feature.GOLDEN_SOUND -> context.getString(R.string.row_golden_title)
        Feature.WIND_NOISE -> context.getString(R.string.row_wind_noise_title)
        Feature.BASSWAVE -> context.getString(R.string.eq_basswave)
        Feature.AUTO_PLAY_PAUSE -> context.getString(R.string.wear_firmware_title)
        Feature.VOCAL_ENHANCE -> context.getString(R.string.row_vocal_title)
        Feature.GAME_SOUND -> context.getString(R.string.row_game_sound_title)
        Feature.GAME_EQ -> context.getString(R.string.row_game_eq_title)
        Feature.SMART_VOLUME -> context.getString(R.string.row_smart_volume_title)
        Feature.ADAPTIVE_VOLUME -> context.getString(R.string.row_adaptive_volume_title)
        Feature.ADAPTIVE_EAR -> context.getString(R.string.row_adaptive_ear_title)
        Feature.SLEEP_PAUSE -> context.getString(R.string.row_sleep_title)
        Feature.LOST_REMIND -> context.getString(R.string.row_lost_title)
        Feature.SPEECH_PERCEPTION -> context.getString(R.string.row_speech_title)
        Feature.HEARING_OPTIMIZE -> context.getString(R.string.row_hearing_optimize_title)
        Feature.LONG_PRESS_VOLUME -> context.getString(R.string.row_long_press_volume_title)
        Feature.HEAD_MOTION -> context.getString(R.string.row_head_motion_title)
        Feature.SWIFT_PAIR -> context.getString(R.string.row_swift_pair_title)
        Feature.FIND_PHONE -> context.getString(R.string.row_find_phone_title)
        Feature.VIRTUAL_BASS -> context.getString(R.string.row_virtual_bass_title)
        else -> "0x%02X".format(key)
    }

    /** Same names as the UI uses for a codec ([MainActivity] keeps its own copy). */
    private fun codecName(codec: Int) =
        listOf("SBC", "AAC", "LDAC", "aptX", "aptX HD", "aptX Adaptive", "LHDC", "LHDC")
            .getOrNull(codec - 1) ?: "#$codec"

    /**
     * Golden Sound test frames, sent in order on one thread (PROTOCOL.md §20): a tone is a stop then
     * the tone, a record is applied in four writes. A `0x0403` among them is followed by a status read.
     */
    fun golden(vararg packets: ByteArray) {
        Thread {
            try {
                for (p in packets) { sendRawBlocking(p, "golden"); Thread.sleep(60) }
                if (packets.any { OppoPacketFramer.normalise(it).let { f -> f[4].toInt() == 0x03 && f[5].toInt() == 0x04 } }) {
                    Thread.sleep(VERIFY_DELAY_MS)
                    sendRawBlocking(statusQuery(), "verify features")
                }
            } catch (e: Exception) {
                log("GOLDEN WRITE failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Spatial type on buds with `0x0422` (0 off, 1 fixed, 2 head tracking), null until read. Changes
     * repaint through [Listener.onFeatureStates]. `[VENDOR]`, unverified on buds (PROTOCOL.md §18).
     */
    @Volatile var spatialType: Int? = null
        private set

    /** Game sound type on buds with `0x0423`: the selected one (0 = off), null until read. */
    @Volatile var gameSoundType: Int? = null
        private set
    /** The types these buds offer, from the same `0x812B` reply. */
    @Volatile var gameSoundTypes: List<Int> = emptyList()
        private set

    /** The level Smart chose (`ANC-Light` ...), from `0x810C 04 01` or the push `03 04 01`. */
    @Volatile var smartLevel: String? = null
        private set

    private fun onSmartMask(mask: Int) {
        smartLevel = if (mask == 0) null else AncModes.of(context).levelForBit(Integer.numberOfTrailingZeros(mask))
        log("SMART LEVEL: mask=0x%04X -> %s".format(mask, smartLevel))
        handler.post { listeners.forEach { it.onSmartLevel() } }
    }

    /** Head gestures' mapping (0 nod answers, 1 shake answers), null until the `0x8134` reply or the `F5` push. */
    @Volatile var headMotionType: Int? = null
        private set

    fun setHeadMotionType(type: Int) = writeThenRead(Commands.headMotionType(type),"Head motion type $type",
        Commands.queryHeadMotionType(), "query head motion type") { headMotionType = type }

    /** Codec picker (`hiRes` models): the current codec and the offered ones, from `0x8114` / `0x8123`. */
    @Volatile var codec: Int? = null
        private set
    @Volatile var codecs: List<Int> = emptyList()
        private set

    /** Reads the phone link's codec again: the phone can switch codec after the connect. The reply sets [codec]. */
    fun refreshCodec() {
        if (Capabilities.supports(context, OpoProtocol.CMD_QUERY_CODEC)) sendRaw(Commands.queryCodec(), "query codec")
    }

    /** The buds restart after it and come back without phone audio, as after power saving. */
    fun setCodec(codec: Int, hiRes: Boolean) {
        audioAfterRestart = true
        sendRaw(Commands.codec(codec, hiRes),"Codec $codec hiRes=$hiRes")
    }

    fun setSpatialType(type: Int) = writeThenRead(Commands.spatialType(type),"Spatial type $type",
        Commands.querySpatialType(), "query spatial type") { spatialType = type }

    fun setGameSoundType(type: Int) = writeThenRead(Commands.gameSoundType(type),"Game sound type $type",
        Commands.queryGameSound(), "query game sound") { gameSoundType = type }

    private fun writeThenRead(write: ByteArray, label: String, read: ByteArray, readLabel: String, optimistic: () -> Unit) {
        optimistic()
        Thread {
            try {
                sendRawBlocking(write, label)
                Thread.sleep(VERIFY_DELAY_MS)
                sendRawBlocking(read, readLabel)
            } catch (e: Exception) {
                log("$label failed: ${e.message}")
            }
        }.start()
    }

    /** Personalized ANC action ([OpoProtocol.PERSONAL_NOISE_TEST] ...); see [Listener.onPersonalNoiseResult]. */
    fun personalNoise(action: Int) = sendRaw(Commands.personalNoise(action),"Personalized ANC $action")
    fun queryPersonalNoise() = sendRaw(Commands.queryPersonalNoise(), "query personalized ANC")

    /** The default from the last `0x8133` reply; the push carries the level only. */
    private var tapLevelDefault = 0

    /** `0x0420 01 <1|0>`: a game app is in the foreground (PROTOCOL.md §18). */
    fun setGameEqState(inGame: Boolean) = sendRaw(Commands.gameEqState(inGame),"Game equalizer in game=$inGame")

    fun refreshTapLevel() = sendRaw(Commands.queryTapLevel(), "query tap level")

    /** Write, then read back. */
    fun setTapLevel(level: Int) {
        Thread {
            try {
                sendRawBlocking(Commands.setTapLevel(level),"Tap level $level")
                Thread.sleep(VERIFY_DELAY_MS)
                sendRawBlocking(Commands.queryTapLevel(), "query tap level")
            } catch (e: Exception) {
                log("TAP LEVEL write failed: ${e.message}")
            }
        }.start()
    }

    /** Starts (`true`) or stops the earbud fit test; the result comes as [Listener.onFitResult]. */
    fun fitTest(on: Boolean) = sendRaw(Commands.fitTest(on),"Fit test ${if (on) "start" else "stop"}")

    /** Alert-sound volume 1..10 from `0x8130` / `0x8427`, null until read. `[CAPTURE]` 2026-09-25. */
    @Volatile var alertVolume: Int? = null
        private set

    fun refreshAlertVolume() = sendRaw(Commands.queryAlertVolume(), "query alert volume")

    /** Write, then read back — as HeyMelody does after every change. */
    fun setAlertVolume(level: Int) {
        alertVolume = level
        Thread {
            try {
                sendRawBlocking(Commands.alertVolume(level),"Alert volume $level")
                Thread.sleep(VERIFY_DELAY_MS)
                sendRawBlocking(Commands.queryAlertVolume(), "query alert volume")
            } catch (e: Exception) {
                log("ALERT VOLUME write failed: ${e.message}")
            }
        }.start()
    }

    /**
     * The hold's ANC-cycle membership — `setSupportNoiseReduction`, `[CAPTURE]` 2026-09-22
     * (PROTOCOL.md §14). `mask` bits use [OpoProtocol.anc]'s numbering (Off 0, On 1,
     * Transparency 2, Adaptive 11). Verified the same way every gesture write is: re-read after a delay and log
     * the diff, because a wrong command number here would be ignored exactly as silently as
     * everywhere else in this protocol.
     */
    fun sendHoldAncModes(mask: Int, type: Int) {
        sendRaw(Commands.holdAncModes(mask, type),"Hold ANC modes ($type) -> 0x%04X".format(mask))
        Thread {
            try {
                Thread.sleep(VERIFY_DELAY_MS)
                sendRawBlocking(Commands.queryNoiseSwitchModes(type), "verify hold ANC modes")
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
    fun sendOnCall(act: Int, fn: Int, button: Int = KeyFunctionParser.BUTTON_ON_CALL) {
        sendRaw(Commands.setOnCall(act, fn, button), "Both-buds btn 0x%02X act 0x%02X -> fn 0x%02X".format(button, act, fn))
        verifyKeyFunctionAfterDelay()
    }

    private fun verifyKeyFunctionAfterDelay() {
        Thread {
            try {
                Thread.sleep(VERIFY_DELAY_MS)
                sendRawBlocking(Commands.queryKeyFunction(), "verify key function")
            } catch (e: Exception) {
                log("ON-CALL WRITE: verify query failed: ${e.message}")
            }
        }.start()
    }

    fun requestFullStatus() { sendRaw(statusQuery(), "manual status") }

    /**
     * A model picked by hand ([ModelCatalog.setManual]): the connect reads again, because some depend on the model
     * (the `0x010D` id list, smart level, noise switch types). Every screen relays out. Without it the features stayed
     * the old model's until the next connect.
     */
    fun modelPicked() {
        if (isConnected()) runInitSequence()
        handler.post { listeners.forEach { it.onCapabilities() } }
    }

    /**
     * `0x010D` for the model in use (PROTOCOL.md §16): its own id list plus the ids a model
     * flag names and both game-mode ids ([RowRules.askQuery]). A model without a list uses the
     * default list, which already holds those ids except wind noise.
     */
    private fun statusQuery(seq: Int = 0x00) = ModelCatalog.current(context)?.json.let {
        val query = RowRules.askQuery(it, Capabilities.commands(context))
        Commands.queryStatus(query, query == null && RowRules.asksWind(it), seq)
    }

    /** Timestamp of the last ANC command we flushed; see noteUnattributed(). */
    private var lastAncFlushAt = 0L

    /**
     * Marks that an ANC frame is now on the wire.
     *
     * Called from sendRawBlocking() after the write call returns, also when the write failed.
     * So the ONLY requirement is that ANC goes through sendRaw() — which every ANC path does
     * (service, widget, app all end up in BudsConnectionManager.sendAnc*).
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
     * value we can name outright — see AncModes.modeForRaw().
     *
     * Deliberately a plain field rather than a WidgetStateStore read: the store is owned by
     * the service.
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
     * The hold's ANC-cycle masks, as last read from `0x010C` `02 <type>` (`[CAPTURE]`,
     * PROTOCOL.md §14), by noise type ([OpoProtocol.HOLD_TYPE_SHARED], or `_LEFT` / `_RIGHT`).
     * Empty until the first reply arrives — same "do not invent it" rule as [lastKeyFnTable], though there is
     * nothing here that writes FROM this field; it exists purely so a UI can show the
     * current cycle without threading its own query/response plumbing.
     */
    val lastHoldAncMasks: MutableMap<Int, Int> = java.util.concurrent.ConcurrentHashMap()

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
            .format(java.util.Locale.ROOT,
                side, buttons.joinToString(",") { "0x%02X".format(it) },
                keyFnAction, functionByte, matched, updated.size
            ))

        // Adopt the change IN MEMORY before the reply comes back. The verify re-read
        // below takes ~600ms, and without this a second write inside that window would be
        // built from the pre-write table and silently revert the first change. The
        // reply still overwrites this with the device's own truth, so a write that the
        // buds reject does not leave us believing a lie for long.
        lastKeyFnTable = table.copy(entries = updated)

        sendRaw(Commands.setKeyFunction(updated),"set key function")

        // Read it straight back. The write's payload layout is [OSS], and the device has
        // shown that it ignores a WRONG command number in total silence (0x0402 produced no
        // ack and no change), so the ONLY evidence the write landed is the device's own
        // table — and because the reply re-runs the diff, any change is printed for us
        // rather than assumed. A short delay: the buds answer a query in ~40ms, but the
        // write needs to be applied before the read is meaningful.
        Thread {
            try {
                Thread.sleep(600)
                sendRawBlocking(Commands.queryKeyFunction(), "verify key function")
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
            if (!linkUp()) {
                log("sendRaw[$label]: socket reports not connected.")
                return
            }
            // Write bytes FIRST, log after. The buds ACK most commands in under 100ms
            // (see any handshake in a developer capture), so logging first is a race: a fast reply
            // could land in the log ABOVE our own TX line and look causally backwards.
            thread.write(data)
            markAncFlush()
            if (data.size > 9 && OpoProtocol.u16(data, 4) == OpoProtocol.CMD_SET_FEATURE) {
                if (featureWrites.size > 16) featureWrites.clear()
                featureWrites[data[6].toInt() and 0xFF] = data[9].toInt() and 0xFF
            }
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

    private inner class ConnectedThread(
        private val inputStream: InputStream,
        private val outputStream: OutputStream,
        private val closeLink: () -> Unit,
    ) : Thread() {
        private val buffer = ByteArray(1024)
        private val framer = OppoPacketFramer().also { f -> f.onDiscard = { log("DISCARDED RX bytes (not a frame): ${OpoProtocol.bytesToHex(it)}") } }

        override fun run() {
            while (true) {
                try {
                    val bytes = inputStream.read(buffer)
                    // Target 37+: a dropped RFCOMM link returns -1 instead of throwing.
                    if (bytes < 0) throw IOException("stream closed")
                    if (bytes > 0) {
                        // The handlers read the fixed 9-byte header, so each packet comes out in that form.
                        for (frame in framer.append(buffer, bytes)) handlePacket(frame)
                    }
                } catch (e: IOException) {
                    // Our own disconnect() closed this socket, or a newer connection has already
                    // replaced it — either way this thread must not tear anything down.
                    if (cancelled || connectedThread !== this) break
                    if (caseClosing) {
                        // The buds are off in a closed case; opening it brings ACL_CONNECTED back.
                        log("Case closed")
                        disconnect()
                        break
                    }
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
            try { closeLink() } catch (_: IOException) {}
        }
    }

    private fun payloadOf(packet: ByteArray): ByteArray {
        if (packet.size < 9) return ByteArray(0)
        val payLen = OpoProtocol.u16(packet, 7)
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
        val cmd = OpoProtocol.u16(packet, 4)
        val payload = payloadOf(packet)

        val explained = cmd == 0x8100 ||                 // handshake
            cmd == 0x8103 ||                             // product id
            cmd == 0x8105 ||                             // firmware version
            cmd == 0x8106 ||                             // battery query reply
            cmd == 0x8109 ||                             // wearing query reply
            cmd == OpoProtocol.CMD_RESP_WEARING ||
            cmd == 0x810C ||                             // ANC query reply
            cmd == 0x810D ||                             // status query reply
            cmd == 0x8130 ||                             // alert volume reply
            cmd == 0x8112 ||                             // device list reply
            cmd == 0x8122 || cmd == 0x810F || cmd == 0x8124 || cmd == 0x812C || cmd == OpoProtocol.CMD_EQ_CHANGED || // EQ
            cmd == OpoProtocol.CMD_ACTIVE_REPORT ||
            cmd == OpoProtocol.CMD_RESP_KEY_FUNCTION ||   // gesture-config query reply
            cmd == 0x8115 || cmd == 0x8116 || cmd == 0x811E || cmd == 0x811F || // Golden Sound replies
            cmd == 0x812A || cmd == 0x812B || cmd == OpoProtocol.CMD_SPATIAL_TYPE_PUSH || // spatial / game sound type
            cmd == 0x811A || cmd == 0x8133 || cmd == 0x8132 || // personalized ANC, tap sensitivity, preferred device
            cmd == 0x0500 ||                             // time request from the buds, answered (answer 0500)
            cmd == OpoProtocol.CMD_FEATURE_PUSH ||        // feature push
            cmd in 0x8400..0x84FF ||                     // acks for 0x04xx set commands
            cmd in LogDecoder.REPLY_NAMES                // decoded elsewhere, see there
        if (explained) return

        val head = OpoProtocol.bytesToHex(payload.take(4).toByteArray())
        log("UNATTR RX: cmd=0x${"%04X".format(cmd)} len=${payload.size} " +
            "head=[$head] ancFlush=${if (System.currentTimeMillis() - lastAncFlushAt < 4000) "YES" else "no"}")
    }

    /**
     * One frame from the buds. [Decode] is the only parser: this function answers requests, unpacks a batch, and passes
     * the typed [Msg] to [apply]. [batched]: one answer unpacked from a `0x812F` batch reply, as if it came on its own.
     */
    private fun handlePacket(packet: ByteArray, batched: Boolean = false) {
        log("${if (batched) "RX[batch]" else "RX"}: ${OpoProtocol.bytesToHex(packet)}")
        noteUnattributed(packet)
        handler.post { listeners.forEach { it.onPacketReceived(packet) } }

        if (packet.size < 9) return
        val cmd = OpoProtocol.u16(packet, 4)
        val payload = payloadOf(packet)

        // Some buds drop the link a few seconds after an unanswered request (issue #2: the time request).
        val seq = packet[6].toInt() and 0xFF
        val now = System.currentTimeMillis() / 1000
        val answer = Replies.answerFor(cmd, seq, payload, now)
        answer?.let { sendRaw(it, "answer %04X".format(cmd)) }
        if (cmd == OpoProtocol.REQ_TIME) return
        // Find phone (realme): `0x0502 05` rings the phone, `06` stops it. HeyMelody models use 0x0502 for the
        // camera, so only a model with the `findPhone` flag acts on it (PROTOCOL.md section 11).
        if (cmd == OpoProtocol.REQ_FIND_PHONE) {
            val action = payload.firstOrNull()?.toInt()
            log("FIND PHONE: action=$action")
            if (ModelCatalog.profile(context).findPhone) when (action) {
                0x05 -> PhoneRinger.ring(context)
                0x06 -> PhoneRinger.stop(context)
            }
            return
        }

        val message = Message(cmd, seq, payload)
        if (cmd == OpoProtocol.CMD_BATCH_REPLY) {
            for (m in Decode.unbatch(message)) handlePacket(OpoProtocol.buildPacket(m.cmd, m.seq, m.payload), batched = true)
            return
        }
        // An empty write ack counts as status 0, as before the decoder took over.
        val msg = Decode.decode(message)
            ?: if (cmd and 0xFF00 == 0x8400) Msg.Ack(cmd and 0x7FFF, 0, emptyList()) else return
        apply(msg, cmd, seq, payload)
    }

    /** What one decoded message changes. [payload] is only for the log lines. */
    private fun apply(msg: Msg, cmd: Int, seq: Int, payload: ByteArray) {
        val push = cmd == Decode.PUSH
        fun raw() = OpoProtocol.bytesToHex(payload)
        fun featuresChanged() = handler.post { listeners.forEach { it.onFeatureStates(featureStates) } }
        when (msg) {
            is Msg.Offered -> offeredEvents = msg.codes.toSet()

            // --- What these buds are and accept: handshake 0x8100 and product id 0x8103 ---
            is Msg.BitmapReply -> msg.bitmap.commands?.let { commands ->
                Capabilities.save(context, commands)
                log("CAPABILITIES: " + commands.sorted().joinToString(" ") { "%04X".format(it) })
                handler.post { listeners.forEach { it.onCapabilities() } }
            }
            is Msg.ProductId -> onProductId(Capabilities.productId(msg.id))

            // Firmware triples (PROTOCOL.md §7), shown in the Earbud settings hub the way HeyMelody shows it.
            is Msg.Firmware -> {
                val version = msg.version()
                val text = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
                log("FIRMWARE: $version text=\"$text\" RAW=[${raw()}]")
                if (version != null) {
                    Capabilities.saveFirmware(context, version)
                    handler.post { listeners.forEach { it.onFirmware(version) } }
                }
            }

            // --- Wearing / in-case state: 0x8109 query reply or 0x0204 push ---
            is Msg.Wear -> {
                val parts = msg.parts.toMap()
                parts[1]?.let { lastLeftStatus = it }
                parts[2]?.let { lastRightStatus = it }
                parts[3]?.let { lastCaseStatus = it }
                caseClosing = push && parts[3] == 0 && parts[1] == 0 && parts[2] == 0
                log("WEAR ${if (push) "EVT" else "QRY"}: " +
                    "L=${wearLabel(lastLeftStatus)} R=${wearLabel(lastRightStatus)} case=${wearLabel(lastCaseStatus)}")
                handler.post { listeners.forEach { it.onWearState(lastLeftStatus, lastRightStatus, lastCaseStatus) } }
            }

            // --- 0x8108: the CURRENT gesture bindings ---
            // A setKeyFunction payload carries the WHOLE table, so the entries we do not change come from here.
            // writeGestureBinding() refuses to run without lastKeyFnTable rather than invent the other entries.
            is Msg.Gestures -> {
                log("KEYFN: ${KeyFunctionParser.describe(payload)}")
                val entries = msg.table.map { KeyFunctionParser.Entry(it.dev, it.button, it.act, it.func) }
                val trailing = ByteArray(msg.trailing.size) { msg.trailing[it].toByte() }
                onKeyFunctions(KeyFunctionParser.Table(msg.status, entries.size, entries, trailing, trailing.isNotEmpty()))
            }

            // --- ANC: the 0x810C reply and the 0x0204 push 03 share one shape (PROTOCOL.md §14) ---
            is Msg.AncMsg -> when (val anc = msg.anc) {
                // A push arrives only on a CHANGE, so the reply to the connect-time read corrects a stale value.
                // An echo of our own write is a no-op: BudsService drops a mode equal to the stored one.
                // A mode that cannot be named is logged but not pushed, so no mode is invented.
                is Anc.Mode -> {
                    val mode = AncModes.of(context).modeForRaw(anc.mask, lastAncLevelSent)
                    if (push) log("ANC EVT: ${AncEventParser.describe(payload)} -> ${mode ?: "unknown for this model"}")
                    else if (mode != null) log("ANC QUERY: raw=0x%04X -> %s".format(anc.mask, mode))
                    if (mode != null) handler.post { listeners.forEach { it.onAncModeState(mode) } }
                }
                is Anc.Smart -> onSmartMask(anc.mask)
                // The hold's switch list, echo `02 01`/`02 03`/`02 04` (Commands.holdAncModes). The push `03 02 ...`
                // is the echo of our own mask write and changes nothing.
                is Anc.HoldCycle -> if (!push) {
                    lastHoldAncMasks[anc.kind] = anc.mask
                    log("HOLD MODES (${anc.kind}): mask=0x%04X".format(anc.mask))
                    // The mask usually arrives after the key-function table, so this repaint is the one that fires.
                    lastKeyFnTable?.let { GestureConfigStore.syncHoldFromDevice(context, it, lastHoldAncMasks) }
                }
                is Anc.Level -> {}
            }

            // --- A button or gesture on the buds: push F1. Named only, for captures. ---
            is Msg.Gesture -> log("BTN EVT: ${UserInteractionParser.describe(payload)}")

            // --- Equalizer replies and push, [CAPTURE] 2026-09-23 ---
            is Msg.EqCurrent -> { equalizer.onCurrent(msg.id); eqChanged() }
            is Msg.EqCustom -> {
                equalizer.onCustom(msg.list.map {
                    EqCodec.Preset(it.id, it.name, it.bands.map { b -> b.first }, it.bands.map { b -> b.second }, it.selected,
                        byteArrayOf(it.range.first.toByte(), it.range.second.toByte()))
                })
                eqChanged()
            }
            is Msg.BassLevel -> { equalizer.onBassLevel(msg.level); eqChanged() }
            is Msg.BassBands -> { equalizer.onBassBands(msg.list); eqChanged() }

            is Msg.Ack -> onAck(msg, seq, payload)

            // --- Preferred device, [VENDOR] ---
            is Msg.Preferred -> {
                val mac = msg.mac?.joinToString(":") { "%02X".format(it) }
                log("PREFERRED: ${mac ?: "auto"}")
                handler.post { listeners.forEach { it.onPreferred(mac) } }
            }

            // --- Paired devices: read reply 0x8112 or push 06 ---
            is Msg.Devices -> {
                val list = msg.list.map { d -> PairedDevice(d.mac.joinToString(":") { "%02X".format(it) }, d.name, d.connected, d.thisPhone) }
                devices = list
                log("DEVICES: " + list.joinToString { "${it.name} ${it.mac} ${if (it.connected) "on" else "off"}" })
                handler.post { listeners.forEach { it.onDevices(list) } }
            }

            is Msg.AlertVolume -> onAlertVolume(msg.level, "ALERT VOLUME: ${msg.level}")

            // --- Status reply: (feature id, value) pairs, [CAPTURE] 2026-09-23 ---
            is Msg.Features -> {
                // The raw values from the buds: the engine compares these, not the pending-faked ones.
                val raw = msg.list.toMap()
                val states = withPending(raw)
                featureStates = states
                log("FEATURES: " + states.entries.joinToString(" ") { "%02X=%d".format(java.util.Locale.ROOT, it.key, it.value) })
                // Game mode has its own push but none at connect time; this read feeds the same path, so the widget follows.
                val game = states[Capabilities.gameModeId(context)]
                handler.post {
                    listeners.forEach {
                        it.onFeatureStates(states)
                        if (game != null) it.onGameModeState(game == 1)
                    }
                }
                // A reply to our own read carries the engine's number back to it (null for any other read).
                val engineSeq = takeEngineSeq(seq)
                if (engineSeq != null) log("SYNC: status reply seq=0x%02X answers engine read".format(seq))
                syncHandler.post {
                    // The first reply of a connection primes the engine silently: a report with equal
                    // values emits nothing, so the old-path switches keep their own paint.
                    if (!syncPrimed) { sync.init(raw); syncPrimed = true }
                    execute(sync.report(raw, engineSeq, SystemClock.elapsedRealtime()))
                }
            }

            // --- Spatial type, codec, codec list, game sound, head motion type ---
            is Msg.Spatial -> { spatialType = msg.type; log("SPATIAL TYPE: ${msg.type} RAW=[${raw()}]"); featuresChanged() }
            is Msg.Codec -> { codec = msg.codec; log("CODEC: ${msg.codec} RAW=[${raw()}]"); featuresChanged() }
            is Msg.CodecMask -> {
                // HeyMelody drops LHDC (7) when LHDC V5 (8) is offered too.
                codecs = Decode.codecsOf(msg.mask)
                log("CODECS: $codecs RAW=[${raw()}]")
                featuresChanged()
            }
            is Msg.GameSound -> {
                gameSoundType = msg.selected
                gameSoundTypes = msg.offered
                log("GAME SOUND: type=$gameSoundType offered=$gameSoundTypes RAW=[${raw()}]")
                featuresChanged()
            }
            is Msg.HeadGesture -> { headMotionType = msg.type; log("HEAD MOTION TYPE: ${msg.type} RAW=[${raw()}]"); featuresChanged() }

            // --- Golden Sound (PROTOCOL.md §20) ---
            is Msg.Hearing -> {
                log("GOLDEN STATUS: kind=${msg.kind} status=${msg.status}")
                handler.post { listeners.forEach { it.onGoldenStatus(msg.kind, msg.status) } }
            }
            is Msg.EarScan -> {
                log("EAR SCAN: uid=${"%08X".format(msg.id)} len=${msg.data.size}")
                val data = bytes(msg.data)
                handler.post { listeners.forEach { it.onEarScan(msg.id, data) } }
            }
            is Msg.GoldenFilter -> {
                val c = msg.curves
                log("GOLDEN FILTER: uid=${"%08X".format(msg.id)} enhanceType=${msg.enhance} floats=${c?.let { it.left.size * 2 }}")
                handler.post {
                    listeners.forEach {
                        it.onGoldenFilter(msg.id, msg.enhance)
                        if (c != null) it.onGoldenCurves(msg.id, false, c.rate, c.left.toFloatArray(), c.right.toFloatArray())
                    }
                }
            }
            is Msg.GoldenScanFilter -> {
                val c = msg.curves
                log("GOLDEN SCAN FILTER: uid=${"%08X".format(msg.id)} fs=${c.rate} floats=${c.left.size * 2}")
                handler.post { listeners.forEach { it.onGoldenCurves(msg.id, true, c.rate, c.left.toFloatArray(), c.right.toFloatArray()) } }
            }
            is Msg.GoldenRecord -> {
                log("GOLDEN ACTIVE: %08X \"%s\" %s RAW=[%s]".format(msg.id, msg.name, msg.values.joinToString(","), raw()))
                handler.post { listeners.forEach { it.onGoldenActive(msg.id, msg.name, msg.values.toIntArray()) } }
            }
            is Msg.GoldenScan -> {
                log("GOLDEN ACTIVE SCAN: uid=${"%08X".format(msg.id)} len=${msg.data.size}")
                val data = bytes(msg.data)
                handler.post { listeners.forEach { it.onGoldenActiveScan(msg.id, data) } }
            }

            // --- Personalized ANC and tap level, [VENDOR] (PROTOCOL.md §20) ---
            is Msg.PersonalAncStored -> {
                log("PERSONAL ANC stored=${if (msg.stored) 1 else 0}")
                handler.post { listeners.forEach { it.onPersonalNoiseStored(msg.stored) } }
            }
            is Msg.PersonalAnc -> {
                log("PERSONAL ANC result=${msg.result} RAW=[${raw()}]")
                handler.post { listeners.forEach { it.onPersonalNoiseResult(msg.result) } }
            }
            is Msg.TapLevel -> {
                val default = msg.default ?: tapLevelDefault
                tapLevelDefault = default
                log("TAP LEVEL: ${msg.level} default $default")
                handler.post { listeners.forEach { it.onTapLevel(msg.level, default) } }
            }
            // Public event (PROTOCOL.md section 10): the first flag that matches wins.
            is Msg.PublicMsg -> when (val e = msg.event) {
                is Public.AlertVolume -> onAlertVolume(e.level, "ALERT VOLUME push: ${e.level} RAW=[${raw()}]")
                is Public.TapLevel -> {
                    log("TAP LEVEL push: ${e.level} RAW=[${raw()}]")
                    handler.post { listeners.forEach { it.onTapLevel(e.level, tapLevelDefault) } }
                }
                else -> {}
            }

            // --- Fit test result: push 04 `[dev status] [dev status]` [VENDOR] ---
            is Msg.FitTest -> if (msg.left != null || msg.right != null) {
                val left = msg.left ?: -1
                val right = msg.right ?: -1
                log("FIT: left=$left right=$right")
                handler.post { listeners.forEach { it.onFitResult(left, right) } }
            }

            // --- Feature changed: 0x0503, [CAPTURE] reports #2 #4 #63 ---
            // The buds send this after a 0x0403 write (before or after the ack) and on a buds gesture.
            // It is the push that confirms a "push" write, game mode included.
            is Msg.FeaturePush -> {
                log("FEATURE PUSH: " + msg.values.entries.joinToString(" ") { "%02X=%d".format(it.key, it.value) })
                syncHandler.post { execute(sync.report(msg.values, null, SystemClock.elapsedRealtime(), push = true)) }
            }

            // --- Game mode changed on the buds: push 05, for our own write and for a buds gesture ---
            is Msg.GameMode -> msg.state(Capabilities.productId(context))?.let { on ->
                log("GAME EVT: ${if (on) "ON" else "OFF"}")
                handler.post { listeners.forEach { it.onGameModeState(on) } }
                // Also a push report for the engine: it confirms a game-mode operation.
                val gameId = Capabilities.gameModeId(context)
                syncHandler.post {
                    execute(sync.report(mapOf(gameId to (if (on) 1 else 0)), null, SystemClock.elapsedRealtime(), push = true))
                }
            }

            is Msg.Battery -> {
                val cell = msg.cells.associateBy { it.part }
                fun info(part: Int) = cell[part]?.let { BatteryParser.Info(it.level, it.charging) }
                // Settings › Hide old battery levels: a part the report leaves out is cleared, not kept.
                val clear = BudsService.clearMissingBattery(context)
                info(1).let { if (it != null || clear) lastLeft = it }
                info(2).let { if (it != null || clear) lastRight = it }
                info(3).let { if (it != null || clear) lastCase = it }
                caseHidden = clear && info(3) == null
                emitBattery()
                if (!caseHidden && (lastCase == null || lastCase?.level == 0)) readCaseFromFastPair()
            }

            else -> {}
        }
    }

    private fun bytes(list: List<Int>) = ByteArray(list.size) { list[it].toByte() }

    private fun eqChanged() {
        log("EQ: current=$eqCurrent bassWave=$bassWaveLevel bands=${eq.bassBands} custom=" +
            eqCustom.joinToString { "${it.id}:${it.name}${if (it.selected) "*" else ""}${it.gains}" })
        handler.post { listeners.forEach { it.onEqState() } }
    }

    private fun onAlertVolume(level: Int, line: String) {
        alertVolume = level
        log(line)
        handler.post { listeners.forEach { it.onAlertVolume(level) } }
    }

    /** A write ack `0x84xx`: [ack]`.cmd` is the write command. */
    private fun onAck(ack: Msg.Ack, seq: Int, payload: ByteArray) {
        val status = ack.status
        when (ack.cmd) {
            // A refused feature write. Status 1 is a switch the buds lack. Status 2 is a switch they have but will not
            // take now (high-quality audio needs an LDAC or LHDC link on buds that offer a codec list; reports #33, #42, #44).
            OpoProtocol.CMD_SET_FEATURE -> {
                val engineSeq = takeEngineSeq(seq)
                log("SYNC: write ack seq=0x%02X status=%d %s".format(seq, status, if (engineSeq != null) "engine request" else "no engine request"))
                if (engineSeq != null) {
                    // Sent by the feature-sync engine: it decides what the refusal means and what to toast.
                    featureWrites.remove(seq)
                    syncHandler.post { execute(sync.ack(engineSeq, status, SystemClock.elapsedRealtime())) }
                } else {
                    // An old path: keep its log line.
                    val id = featureWrites.remove(seq)
                    if (id != null && status != 0)
                        log("FEATURE REFUSED: id=0x%02X status=%d codec=%s offered=%s".format(id, status, codec, codecs))
                }
            }
            // A refused ANC write (status 14 with no bud in an ear): read the real mode back (PROTOCOL.md section 14).
            OpoProtocol.CMD_SET_ANC -> if (status != 0) {
                log("ANC write refused (status $status), re-reading")
                sendRaw(Commands.queryAncMode(), "query anc")
                // From the app or the widget alike: say why the mode did not change.
                if (status == 0x0E) handler.post { Toast.makeText(context, R.string.anc_need_ear, Toast.LENGTH_SHORT).show() }
            }
            // `00 <id>`: the id the preset now has (a created one's id comes from here).
            0x0418 -> log("EQ ack: RAW=[${OpoProtocol.bytesToHex(payload)}]")
            // The set ack carries the level, as the read reply does.
            0x0427 -> if (status == 0 && ack.data.isNotEmpty()) onAlertVolume(ack.data[0], "ALERT VOLUME: ${ack.data[0]}")
            0x040D, 0x040E -> if (status != 0) {
                log("GOLDEN REFUSED: cmd=0x${"%04X".format(ack.cmd or 0x8000)} status=$status")
                handler.post { listeners.forEach { it.onGoldenRefused(status) } }
            }
            0x0412 -> handler.post { listeners.forEach { it.onPersonalNoiseAck(status) } }
        }
    }

    private fun onProductId(id: String) {
        // Other buds than last time: a model picked by hand was for those.
        if (Capabilities.saveProductId(context, id)) featurePrefs().edit().remove(ModelCatalog.KEY_MANUAL).apply()
        val anc = AncModes.of(context)
        log("PRODUCT ID: $id, model: ${ModelCatalog.current(context)?.name ?: "unknown"} (${if (ModelCatalog.manual(context) != null) "picked by hand" else ModelCatalog.rule(context)}), ANC: " + (listOf(AncModes.OFF, AncModes.TRANSPARENCY, AncModes.ADAPTIVE) +
            AncModes.LEVELS).filter { anc.supports(it) }.joinToString { "$it=${anc.bit(it)}" }.ifEmpty { "none" })
        ModelCatalog.current(context)?.json?.let { log("MODEL DATA: ${modelFlags(it)}") }
        handler.post { listeners.forEach { it.onCapabilities() } }
    }

    /** The model entry in one line: scalar values in full, lists and objects by size. It shows which data rules apply. */
    private fun modelFlags(json: org.json.JSONObject): String = json.keys().asSequence().joinToString(" ") { k ->
        when (val v = json.get(k)) {
            is org.json.JSONArray -> "$k[${v.length()}]"
            is org.json.JSONObject -> "$k{${v.length()}}"
            else -> "$k=$v"
        }
    }

    /** A gesture table read: only a reply with entries replaces the stored table (a write is built from it). */
    private fun onKeyFunctions(table: KeyFunctionParser.Table) {
        if (table.entries.isEmpty()) return
        lastKeyFnTable = table
        log("KEYFN DIFF: ${keyFnDiff(table)}")
        // Repaint the local record from the buds' own truth (GestureConfigStore / OnCallConfigStore).
        GestureConfigStore.syncFromDevice(context, table)
        OnCallConfigStore.syncFromDevice(context, table)
        GestureConfigStore.syncHoldFromDevice(context, table, lastHoldAncMasks)
    }

    /**
     * The Fast Pair stream (PROTOCOL.md section 1). Held: another app holds `079A`, so the stream is the only link and
     * gives the whole battery (issue #18). Not held: one short read beside the link gives the case level when the
     * link gives none or `0` (report #184). The read closes at the first battery message or after 5 s. The next read
     * waits 5 min, so Google Play Services can use the stream too: one phone opens a channel only once.
     * The same read also starts a 10 s BLE scan for the case level in the advertisement ([AdvertCaseScan]):
     * the stream fails while Google Play Services holds it.
     */
    private var fastPair: FastPairBattery? = null
    private var fpCase: FastPairStream.Level? = null
    private var fpCaseAt = 0L
    /** "Hide old battery levels" is on and the last link report left the case out. */
    private var caseHidden = false
    private var fpCaseReadAt = -FP_CASE_READ_GAP_MS
    private var advertScan: AdvertCaseScan? = null

    private fun readCaseFromFastPair() {
        val now = SystemClock.elapsedRealtime()
        if (fastPair != null || now - fpCaseReadAt < FP_CASE_READ_GAP_MS) return
        fpCaseReadAt = now
        lastDevice?.let { startFastPair(it, held = false) }
        startAdvertScan()
    }

    private fun startAdvertScan() {
        advertScan?.stop()
        lateinit var me: AdvertCaseScan
        me = AdvertCaseScan(
            context.getSystemService(BluetoothManager::class.java)?.adapter, handler, Capabilities.productId(context), ::log
        ) { level ->
            if (advertScan !== me) return@AdvertCaseScan
            advertScan = null
            fpCase = level
            fpCaseAt = SystemClock.elapsedRealtime()
            emitBattery()
        }
        advertScan = me
        if (!me.start()) advertScan = null
    }

    private fun startFastPair(device: BluetoothDevice, held: Boolean) {
        if (fastPair != null) return
        if (device.uuids?.any { it.uuid.toString().equals(FastPairStream.UUID, true) } != true) return
        log(if (held) "Fast Pair stream listed: battery only while the earbuds are held" else "Fast Pair stream listed: case level beside the link")
        lateinit var me: FastPairBattery
        me = FastPairBattery(::log, { b ->
            handler.post {
                if (fastPair !== me) return@post
                if (held) listeners.forEach { it.onBattery(b.left.level, b.case.level, b.right.level, b.left.charging, b.case.charging, b.right.charging) }
                else {
                    if (b.case.level != null) { fpCase = b.case; fpCaseAt = SystemClock.elapsedRealtime(); emitBattery() }
                    stopFastPair(keepCase = true)
                }
            }
        }, {
            // Only the current stream acts on its end: a stopped one can end after a new link is up.
            handler.post {
                if (fastPair !== me) return@post
                fastPair = null
                if (held) listeners.forEach { it.onConnected(false) }
            }
        })
        fastPair = me
        me.start(device)
        if (!held) handler.postDelayed({ if (fastPair === me) stopFastPair(keepCase = true) }, FP_CASE_READ_MS)
    }

    private fun stopFastPair(keepCase: Boolean = false) {
        fastPair?.stop()
        fastPair = null
        if (!keepCase) {
            fpCase = null
            advertScan?.stop()
            advertScan = null
        }
    }

    private fun emitBattery() {
        handler.post {
            listeners.forEach {
                // The link's case level wins, except none or 0: then the Fast Pair stream's level, if it is fresh.
                val fromStream = FastPairStream.caseStandIn(
                    fpCase, SystemClock.elapsedRealtime() - fpCaseAt, FP_CASE_READ_GAP_MS, lastCase?.level, caseHidden
                )
                it.onBattery(
                    lastLeft?.level, fromStream?.level ?: lastCase?.level, lastRight?.level,
                    lastLeft?.isCharging ?: false,
                    fromStream?.charging ?: lastCase?.isCharging ?: false,
                    lastRight?.isCharging ?: false
                )
            }
        }
    }

    private fun log(msg: String) {
        Log.d("BudsConn", msg)
        PacketLogger.log(msg)
    }

    /** API 34 and later: the error code of a [BluetoothSocketException] that ended the try. */
    private fun logSocketError(e: Exception) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val code = (e as? BluetoothSocketException)?.errorCode ?: return
        log(ConnectDiagnostics.socketErrorLine(code))
    }
}
