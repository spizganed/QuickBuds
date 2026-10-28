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
import com.spizganed.quickbuds.protocol.Decode
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.EqCodec
import com.spizganed.quickbuds.protocol.EqSnapshot
import com.spizganed.quickbuds.protocol.EqState
import com.spizganed.quickbuds.protocol.GameModeParser
import com.spizganed.quickbuds.protocol.KeyFunctionParser
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.protocol.Feature
import com.spizganed.quickbuds.protocol.FeaturePushParser
import com.spizganed.quickbuds.protocol.FeatureSync
import com.spizganed.quickbuds.protocol.FeatureSyncConfig
import com.spizganed.quickbuds.protocol.GoldenSound
import com.spizganed.quickbuds.protocol.OppoPacketFramer
import com.spizganed.quickbuds.protocol.RowRules
import com.spizganed.quickbuds.protocol.UserInteractionParser
import com.spizganed.quickbuds.protocol.WearingStatusParser
import com.spizganed.quickbuds.ui.GestureConfigStore
import com.spizganed.quickbuds.ui.GestureModel
import com.spizganed.quickbuds.ui.OnCallConfigStore
import com.spizganed.quickbuds.ui.ThemeRes
import com.spizganed.quickbuds.protocol.Commands
import com.spizganed.quickbuds.protocol.Replies
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("MissingPermission")
class BudsConnectionManager(private val context: Context) {

    companion object {
        /** Last firmware version read, as HeyMelody shows it ([OpoProtocol.firmwareVersion]). */
        const val KEY_FIRMWARE = "firmwareVersion"
        /** The buds apply a write about a second after the ack; a read sooner returns the old value and the switch flickers. */
        private const val VERIFY_DELAY_MS = 2200L
        /** For this long after our own write, a read of that switch shows what we wrote. */
        private const val PENDING_MS = 2000L
        /** A blocked socket.connect() gets its socket closed after this long, so the try can fail. */
        private const val CONNECT_TIMEOUT_MS = 12_000L
        /** A fresh UUID query gets this long for its answer, then its receiver goes away. */
        private const val UUID_REFRESH_TIMEOUT_MS = 10_000L
        /** Virtual buds: a refused or silent TCP connect must not hang the UI. */
        private const val VIRTUAL_CONNECT_TIMEOUT_MS = 5_000
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
        if (ModelCatalog.current(context)?.json?.optInt("seqless") != 1) return null
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
    /** Set only in virtual mode (Dev tools › Virtual buds); [bluetoothSocket] stays null there. */
    private var virtualSocket: Socket? = null
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

    /** The open link, Bluetooth or virtual. A null adapter never reaches this. */
    private fun linkUp(): Boolean =
        bluetoothSocket?.isConnected == true || virtualSocket?.isConnected == true

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
        virtualTarget = null
        val generation = ++connectGeneration
        // Starts the attempt, or clears the latch if the setup before it throws.
        val started = try {
            // Only a connect the user asked for (the pill) brings phone audio up. An automatic one —
            // ACL receiver, retries, reconnect after loss — leaves audio to Android: asking for A2DP
            // while the system is auto-connecting raced it and left audio stuck.
            if (withAudio) setPhoneAudio(device, on = true)
            log("Initiating RFCOMM connection to ${device.name}...")
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
     * Everything after a link is open. The same steps for Bluetooth and virtual buds, so the init
     * sequence, the feature-sync engine and the UI callbacks do not know the difference.
     * [audioDevice] is null in virtual mode: there is no phone audio to bring up.
     */
    private fun onLinkUp(audioDevice: BluetoothDevice?) {
        isReady = true
        reconnectAttempts = 0
        connectedAt = System.currentTimeMillis()
        caseClosing = false
        eqMac = audioDevice?.address
        if (eqMac != null) eqState = EqState.forLink(eqSnapshot, eqMac)
        eqReadGate.onLinkUp()
        if (audioDevice != null && audioAfterRestart) { audioAfterRestart = false; setPhoneAudio(audioDevice, on = true) }
        handler.post { listeners.forEach { it.onConnected(true) } }
        syncPrimed = false
        syncHandler.post { execute(sync.reconnect(SystemClock.elapsedRealtime())) }
        log("Ready for commands. Running init sequence...")
        runInitSequence()
    }

    /** Where a lost virtual link goes back to, so a Hi-Res restart reconnects by itself. */
    private var virtualTarget: Pair<String, Int>? = null

    /**
     * Dev tools › Virtual buds: a TCP socket to a local fake earbuds server instead of RFCOMM.
     * No BluetoothAdapter, no paired device and no Bluetooth permission is touched here.
     * Everything after the socket is the normal path ([onLinkUp]).
     */
    fun connectVirtual(host: String, port: Int, shownName: String) {
        if (isConnecting || isConnected()) {
            log("Already connecting/connected, ignoring.")
            return
        }
        isConnecting = true
        virtualTarget = host to port
        val generation = ++connectGeneration
        Thread {
            try {
                log("Connecting to the fake buds at $host:$port...")
                rememberDeviceName(shownName)
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(host, port), VIRTUAL_CONNECT_TIMEOUT_MS)
                } catch (e: Exception) {
                    log("Virtual connect failed: ${e.message}")
                    try { socket.close() } catch (_: Exception) {}
                    disconnect()
                    return@Thread
                }
                if (generation != connectGeneration) {
                    log("Virtual connect cancelled: the link was dropped meanwhile.")
                    try { socket.close() } catch (_: Exception) {}
                    return@Thread
                }
                socket.tcpNoDelay = true
                virtualSocket = socket
                connectedThread = ConnectedThread(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
                connectedThread?.start()
                log("Connected to $host:$port")
                onLinkUp(null)
            } catch (e: Exception) {
                log("Virtual connect error: ${e.javaClass.simpleName}: ${e.message}")
                disconnect()
            } finally {
                isConnecting = false
            }
        }.start()
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
                if (ModelCatalog.current(context)?.json?.optInt("spatialToggle") == 1 &&
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
        val target = virtualTarget
        if (target != null) {
            // The fake buds drop the link after an accepted Hi-Res write and listen again after 4 s.
            log("Reconnect to the fake buds in 4s...")
            val r = Runnable {
                if (!isConnected() && !isConnecting) connectVirtual(target.first, target.second, VirtualBuds.name(context))
            }
            pendingReconnect = r
            handler.postDelayed(r, 4000)
            return
        }
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
        teardownLink()
    }

    /**
     * Closes the link and clears its state. It does not raise [connectGeneration], so a failed
     * connect attempt can clean up and still schedule its auto-retry. A user Disconnect uses
     * [disconnect], which raises the generation first.
     */
    private fun teardownLink() {
        pendingReconnect?.let { handler.removeCallbacks(it) }
        pendingReconnect = null
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
        isReady = false
        isConnecting = false
        eqMac = null
        eqReadGate.onLinkDown()
        connectedThread?.cancel()
        connectedThread = null
        bluetoothSocket = null
        virtualSocket = null
        bridge?.dropClient()
        handler.post { listeners.forEach { it.onConnected(false) } }
        syncPrimed = false
        syncHandler.post { execute(sync.disconnect(SystemClock.elapsedRealtime())) }
        log("Disconnected")
    }

    private var bridge: RfcommBridge? = null

    /** Dev tools › Bridge ([RfcommBridge]). False when the port is taken. */
    fun setBridge(on: Boolean): Boolean {
        bridge?.stop()
        bridge = null
        if (!on) { log("Bridge off"); return true }
        return try {
            bridge = RfcommBridge { sendRawBlocking(it, "bridge") }
            log("Bridge on 127.0.0.1:${RfcommBridge.PORT}")
            true
        } catch (e: IOException) {
            log("Bridge failed: ${e.message}")
            false
        }
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
    }

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

    /**
     * `[count]` then per device `[MAC, 6 bytes reversed][entry length][state][flags][nameLen][name UTF-8]`.
     * State `02` = connected, `00` = not. Flags `[VENDOR]`: bit 0 this phone, bit 2 audio playing
     * (bit 1 the main audio device, bits 3-5 the device type). PROTOCOL.md §19.
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
            val flags = p[o + 8].toInt()
            val len = p[o + 9].toInt() and 0xFF
            if (o + 10 + len > p.size) return null
            out += PairedDevice(mac, String(p, o + 10, len, Charsets.UTF_8), connected, flags and 1 != 0)
            o += 10 + len
        }
        return out
    }

    // --- Equalizer (PROTOCOL.md §17). Read on demand by the EQ screen, re-read after every write. ---
    /** The events these buds offer (`0x8200`), once read; reset on each connect. */
    @Volatile private var offeredEvents: Set<Int>? = null

    /**
     * The saved snapshot, read at construction. It paints the screen before the bind.
     * It is used for a link only when its MAC matches the device ([EqState.forLink]).
     */
    @Volatile private var eqSnapshot: EqSnapshot? =
        EqSnapshot.decode(featurePrefs().getString(EqSnapshot.KEY, null))
    /** The EQ values the screen reads. Before a link: the snapshot, so a bind repaints no blank. See [EqState]. */
    @Volatile private var eqState = EqState.beforeLink(eqSnapshot)
    /** The whole state, so one render paints one version of it. */
    val eq: EqState get() = eqState
    val eqCurrent: Int? get() = eqState.current
    val eqCustom: List<EqCodec.Preset> get() = eqState.custom
    val bassWaveLevel: Int? get() = eqState.bassWave

    /** The MAC of the linked buds; null while no link is up, and in virtual mode. */
    @Volatile private var eqMac: String? = null
    private val eqReadGate = EqReadGate()

    /** Stores [next] and writes the snapshot. Every EQ change goes through here. */
    private fun setEq(next: EqState) {
        eqState = next
        val mac = eqMac ?: return
        EqSnapshot(mac, next.current, next.bassWave, next.custom, next.bassBands).also {
            eqSnapshot = it
            featurePrefs().edit().putString(EqSnapshot.KEY, it.encode()).apply()
        }
    }

    /** One EQ read set per link: the bind ask before the link up sends nothing. */
    fun refreshEq() { if (eqReadGate.take()) sendThenRead() }

    // Each write updates the cached state FIRST, then sends and re-reads. The three replies land one
    // by one and each repaints; without this, the replies that arrive before the new values snapped
    // the UI back to the old ones for a moment.

    /** The preset last picked, and when. Reads of earlier picks land after it: they must not move the selection back. */
    @Volatile private var eqPicked: Int? = null
    @Volatile private var eqPickedAt = 0L

    private fun picked(id: Int): EqState {
        eqPicked = id
        eqPickedAt = System.currentTimeMillis()
        return eqState.copy(current = id)
    }

    /** The buds report [id] as current: kept unless an unconfirmed pick of another preset is under 4 s old. */
    private fun reportedEq(id: Int) {
        val want = eqPicked
        if (want != null && want != id && System.currentTimeMillis() - eqPickedAt < 4000) return
        eqPicked = null
        setEq(eqState.copy(current = id))
    }

    /** Counts picks: a queued pick that a newer one replaced is not sent. */
    @Volatile private var eqPickSeq = 0

    fun selectBuiltInEq(id: Int) {
        setEq(picked(id))
        sendThenRead(Commands.setBuiltInEq(id) to "EQ built-in $id", ++eqPickSeq)
    }

    /** Selects a custom preset unchanged: the same frame as a save, but a newer pick may replace it. */
    fun selectCustomEq(p: EqCodec.Preset) {
        setEq(picked(p.id))
        sendThenRead(Commands.customEq(EqCodec.ACTION_SAVE, p) to "EQ custom ${p.id} '${p.name}'", ++eqPickSeq)
    }

    /** Selects AND saves a custom preset — `0x0418` is both (a band edit or rename is the same frame). */
    fun saveCustomEq(p: EqCodec.Preset) {
        val next = picked(p.id)
        setEq(next.withList(next.custom.map { if (it.id == p.id) p else it }))
        sendThenRead(Commands.customEq(EqCodec.ACTION_SAVE, p) to "EQ custom ${p.id} '${p.name}'")
    }

    /** No local update for create/delete: the buds assign and renumber ids, so only the re-read knows. */
    fun createCustomEq(name: String, freqs: List<Int>) =
        sendThenRead(Commands.customEq(EqCodec.ACTION_CREATE,EqCodec.newPreset(name, freqs)) to "EQ create '$name'")

    fun deleteCustomEq(p: EqCodec.Preset) =
        sendThenRead(Commands.customEq(EqCodec.ACTION_DELETE,p) to "EQ delete ${p.id} '${p.name}'")

    fun setBassWaveLevel(level: Int) {
        setEq(eqState.copy(bassWave = level))
        sendThenRead(Commands.setBassWaveLevel(level) to "BassWave level $level")
    }

    /** realme dynamic sound: [band] 1 low, 2 medium, 3 high. */
    fun setBassBand(band: Int, level: Int) {
        setEq(eqState.copy(bassBands = eqState.bassBands.toMutableList().also { it[band - 1] = level }))
        sendThenRead(Commands.setBassWaveLevel(level, band) to "BassWave band $band level $level")
    }

    /** Optional write, then the EQ reads these buds list (see Capabilities), in order on one thread. */
    /** EQ writes go out one at a time, in order: a thread each let a quick second pick overtake the first. */
    private val eqQueue = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** [pick]: the pick's number; the write is skipped when a newer pick is queued. */
    private fun sendThenRead(write: Pair<ByteArray, String>? = null, pick: Int? = null) {
        eqQueue.execute {
            if (pick != null && pick != eqPickSeq) return@execute
            try {
                if (write != null) { sendRawBlocking(write.first, write.second); Thread.sleep(400) }
                for ((cmd, read) in listOf(
                        OpoProtocol.CMD_QUERY_EQ to (Commands.queryEq() to "query EQ"),
                        OpoProtocol.CMD_QUERY_EQ_ALL to (Commands.queryEqAll() to "query custom EQ"),
                        OpoProtocol.CMD_QUERY_BASSWAVE_LEVEL to (Commands.queryBassWaveLevel() to "query BassWave level"))) {
                    if (!Capabilities.supports(context, cmd)) continue
                    sendRawBlocking(read.first, read.second)
                    Thread.sleep(120)
                }
            } catch (e: Exception) {
                log("EQ: ${e.message}")
            }
        }
    }

    /** Find my earbuds: both buds' own locator tone. `[CAPTURE]` 2026-09-23, PROTOCOL.md §18. */
    fun setFindTone(on: Boolean) = sendRaw(Commands.findTone(on),"Find tone -> $on")

    private val FEATURES_KEY = Capabilities.KEY_FEATURES

    /**
     * Latest `0x810D` reply as feature id -> value (PROTOCOL.md §16). Persisted, so the switches
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

    /**
     * The Bluetooth name HeyMelody matches the model list by ([ModelCatalog]). Other buds than last
     * time: the old product id, manual pick, bitmap and feature reply go, so neither names the new
     * buds until `0x8103`, and no saved row of the old buds shows on the new ones ([RowRules]).
     */
    private fun rememberDeviceName(device: BluetoothDevice) {
        rememberDeviceName(runCatching { device.name }.getOrNull())
    }

    /** [name] is the Bluetooth name, or the "Name shown" field in virtual mode (Dev tools › Virtual buds). */
    private fun rememberDeviceName(name: String?) {
        if (name == null) return
        val prefs = featurePrefs()
        if (prefs.getString(ModelCatalog.KEY_DEVICE_NAME, null) == name) return
        prefs.edit().putString(ModelCatalog.KEY_DEVICE_NAME, name)
            .remove(Capabilities.KEY_PRODUCT_ID).remove(ModelCatalog.KEY_MANUAL)
            .remove(Capabilities.KEY_COMMANDS).remove(Capabilities.KEY_FEATURES).apply()
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
    fun setFeatures(vararg changes: Pair<Int, Boolean>) {
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
     * One user action on a setting. The engine sends the writes in order and waits for the confirmation
     * kind given; [key] is the control that shows "applying". See docs/FEATURE-SYNC.md.
     */
    fun requestSetting(key: Int, writes: List<Pair<Int, Int>>, confirm: FeatureSync.Confirm, restarts: Boolean = false) {
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
                        bridge?.forward(buffer, bytes)
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
            cmd == 0x8122 || cmd == 0x810F || cmd == 0x8124 || cmd == OpoProtocol.CMD_EQ_CHANGED || // EQ
            cmd == OpoProtocol.CMD_ACTIVE_REPORT ||
            cmd == OpoProtocol.CMD_RESP_KEY_FUNCTION ||   // gesture-config query reply
            cmd == 0x8115 || cmd == 0x8116 || cmd == 0x811E || cmd == 0x811F || // Golden Sound replies
            cmd == 0x812A || cmd == 0x812B || cmd == OpoProtocol.CMD_SPATIAL_TYPE_PUSH || // spatial / game sound type
            cmd == 0x811A || cmd == 0x8133 || cmd == 0x8132 || // personalized ANC, tap sensitivity, preferred device
            cmd == 0x0500 ||                             // time request from the buds, answered (answer 0500)
            cmd == OpoProtocol.CMD_FEATURE_PUSH ||        // feature push, decoded by FeaturePushParser
            cmd in 0x8400..0x84FF ||                     // acks for 0x04xx set commands
            cmd in LogDecoder.REPLY_NAMES                // decoded elsewhere, see there
        if (explained) return

        val head = OpoProtocol.bytesToHex(payload.take(4).toByteArray())
        log("UNATTR RX: cmd=0x${"%04X".format(cmd)} len=${payload.size} " +
            "head=[$head] ancFlush=${if (System.currentTimeMillis() - lastAncFlushAt < 4000) "YES" else "no"}")
    }

    /** [batched]: one answer unpacked from a `0x812F` batch reply, as if it came on its own. */
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

        if (cmd == 0x8200) { offeredEvents = OpoProtocol.offeredEvents(payload); return }
        if (cmd == OpoProtocol.CMD_BATCH_REPLY) {
            for ((c, p) in Replies.batchReplies(payload))
                handlePacket(OpoProtocol.buildPacket(c, packet[6].toInt() and 0xFF, p), batched = true)
            return
        }

        // --- What these buds are and accept: handshake 0x8100 and product id 0x8103 ---
        if (cmd == 0x8100) {
            val commands = Capabilities.parse(payload)
            if (commands != null) {
                Capabilities.save(context, commands)
                log("CAPABILITIES: " + commands.sorted().joinToString(" ") { "%04X".format(it) })
                handler.post { listeners.forEach { it.onCapabilities() } }
            }
            return
        }
        if (cmd == 0x8103) {
            Capabilities.productId(payload)?.let { onProductId(it) }
            return
        }

        // `00 <count>` + UTF-8 `deviceType,versionType,version` triples (PROTOCOL.md §7), shown in the
        // Earbud settings hub the way HeyMelody shows it.
        if (cmd == 0x8105) {
            val text = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
            val version = OpoProtocol.firmwareVersion(text)
            log("FIRMWARE: $version text=\"$text\" RAW=[${OpoProtocol.bytesToHex(payload)}]")
            if (payload.firstOrNull()?.toInt() == 0 && version != null)
                featurePrefs().edit().putString(KEY_FIRMWARE, version).apply()
            return
        }

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
                lastLeftStatus = wearing.leftStatus
            }
            if (wearing.rightValid) {
                lastRightStatus = wearing.rightStatus
            }
            if (wearing.caseStatus >= 0) lastCaseStatus = wearing.caseStatus
            caseClosing = fromEvent && wearing.caseStatus == 0 &&
                wearing.leftStatus == 0 && wearing.rightStatus == 0
            log("WEAR ${if (fromEvent) "EVT" else "QRY"}: " +
                "L=${wearLabel(lastLeftStatus)} " +
                "R=${wearLabel(lastRightStatus)} " +
                "case=${wearLabel(lastCaseStatus)}")
            handler.post {
                listeners.forEach {
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
            onKeyFunctions(KeyFunctionParser.parse(payload))
            return
        }

        // --- ANC query reply: 0x810C, carries several questions (see OpoProtocol) ---
        // Shape for both branches below: `[status][echo x2][value LE]`, `[CAPTURE]`.
        if (cmd == 0x810C && payload.size >= 4) {
            val echo1 = payload[1].toInt() and 0xFF
            val echo2 = payload[2].toInt() and 0xFF
            // The value is as long as it needs to be (`NoiseReductionInfo`, 1-4 bytes LE).
            var value = 0
            for (i in minOf(payload.size, 7) - 1 downTo 3) value = (value shl 8) or (payload[i].toInt() and 0xFF)

            // Current-mode answer, echo `01 01` — queried once on every connect
            // (OpoProtocol.queryAncMode()). A push arrives only on a CHANGE, so without this reply a
            // reconnect where nothing changed keeps a stale persisted value on screen. Reading it
            // corrects the display on every connect.
            if (echo1 == 0x01 && echo2 == 0x01) {
                val mode = AncModes.of(context).modeForRaw(value, lastAncLevelSent)
                if (mode != null) {
                    log("ANC QUERY: raw=0x%04X -> %s".format(value, mode))
                    handler.post { listeners.forEach { it.onAncModeState(mode) } }
                }
            }

            if (echo1 == 0x04 && echo2 == 0x01) onSmartMask(value)

            // Hold's switch-list answer, echo `02 01`/`02 03`/`02 04` — see OpoProtocol.setHoldAncModes().
            if (echo1 == 0x02) {
                lastHoldAncMasks[echo2] = value
                log("HOLD MODES ($echo2): mask=0x%04X".format(value))
                // Same repaint as GestureConfigStore.syncFromDevice() above, from the other
                // direction: the mask usually arrives AFTER the key-function table in the init
                // sequence, so this is the hook that actually fires the hold's sync in practice.
                lastKeyFnTable?.let { GestureConfigStore.syncHoldFromDevice(context, it, lastHoldAncMasks) }
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
            val mode = AncModes.of(context).modeForRaw(AncEventParser.rawValue(payload), lastAncLevelSent)
            log("ANC EVT: ${AncEventParser.describe(payload)} -> ${mode ?: "unknown for this model"}")
            if (mode != null) {
                handler.post { listeners.forEach { it.onAncModeState(mode) } }
            }
            return
        }

        // --- Equalizer replies and push, [CAPTURE] 2026-09-23 ---
        when (cmd) {
            0x810F -> if (payload.size >= 2 && payload[0].toInt() == 0) reportedEq(payload[1].toInt() and 0xFF)
            0x8122 -> EqCodec.parseList(payload)?.let { setEq(eqState.withList(it)) }
                ?: log("EQ: could not parse custom list RAW=[${OpoProtocol.bytesToHex(payload)}]")
            0x8124 -> if (payload.size >= 4 && payload[0].toInt() == 0) setEq(eqState.copy(bassWave = payload[3].toInt()))
            OpoProtocol.CMD_EQ_CHANGED -> if (payload.isNotEmpty()) reportedEq(payload[0].toInt() and 0xFF)
        }
        // A refused feature write. Status 1 is a switch the buds lack. Status 2 is a switch they have but will not take now
        // (high-quality audio needs an LDAC or LHDC link on buds that offer a codec list; reports #33, #42, #44).
        if (cmd == OpoProtocol.CMD_SET_FEATURE or 0x8000) {
            val status = payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
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
        if (cmd == OpoProtocol.CMD_SET_ANC or 0x8000 && payload.isNotEmpty() && payload[0].toInt() != 0) {
            log("ANC write refused (status ${payload[0].toInt() and 0xFF}), re-reading")
            sendRaw(Commands.queryAncMode(), "query anc")
            // From the app or the widget alike: say why the mode did not change.
            if (payload[0].toInt() == 0x0E) handler.post {
                Toast.makeText(context, R.string.anc_need_ear, Toast.LENGTH_SHORT).show()
            }
        }

        // 0x8418 ack: `00 <id>` — the id the preset now has (a created one's id comes from here).
        if (cmd == 0x8418) log("EQ ack: RAW=[${OpoProtocol.bytesToHex(payload)}]")
        if (cmd == 0x810F || cmd == 0x8122 || cmd == 0x8124 || cmd == OpoProtocol.CMD_EQ_CHANGED) {
            log("EQ: current=$eqCurrent bassWave=$bassWaveLevel custom=" +
                eqCustom.joinToString { "${it.id}:${it.name}${if (it.selected) "*" else ""}${it.gains}" })
            handler.post { listeners.forEach { it.onEqState() } }
            return
        }

        // --- Preferred device: `00 02 <00 auto | 01 <MAC>>`, [VENDOR] ---
        if (cmd == 0x8132 && payload.size >= 3 && payload[0].toInt() == 0 && payload[1].toInt() == 2) {
            val mac = if (payload[2].toInt() == 0 || payload.size < 9) null
                else (3..8).joinToString(":") { "%02X".format(payload[it]) }
            log("PREFERRED: ${mac ?: "auto"}")
            handler.post { listeners.forEach { it.onPreferred(mac) } }
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
            // The raw values from the buds: the engine compares these, not the pending-faked ones.
            val raw = (0 until count).mapNotNull { i ->
                val o = 2 + i * 2
                if (o + 1 < payload.size) (payload[o].toInt() and 0xFF) to (payload[o + 1].toInt() and 0xFF)
                else null
            }.toMap()
            val states = withPending(raw)
            featureStates = states
            log("FEATURES: " + states.entries.joinToString(" ") { "%02X=%d".format(java.util.Locale.ROOT, it.key, it.value) })
            // Game mode has its own push (0x0204 subType 0x05) but none at connect time; this is
            // the connect-time read, fed through the same path so the widget follows too.
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
            return
        }

        // --- Spatial type: read reply 0x812A `00 <type>`, push 0x0510 `<type>`; game sound 0x812B ---
        val spatial = when {
            cmd == 0x812A && payload.size >= 2 && payload[0].toInt() == 0 -> payload[1].toInt() and 0xFF
            cmd == OpoProtocol.CMD_SPATIAL_TYPE_PUSH && payload.isNotEmpty() -> payload[0].toInt() and 0xFF
            else -> null
        }
        if (spatial != null) {
            spatialType = spatial
            log("SPATIAL TYPE: $spatial RAW=[${OpoProtocol.bytesToHex(payload)}]")
            handler.post { listeners.forEach { it.onFeatureStates(featureStates) } }
            return
        }
        if (cmd == 0x8114 && payload.size >= 2 && payload[0].toInt() == 0) {
            codec = payload[1].toInt() and 0xFF
            log("CODEC: $codec RAW=[${OpoProtocol.bytesToHex(payload)}]")
            handler.post { listeners.forEach { it.onFeatureStates(featureStates) } }
            return
        }
        if (cmd == 0x8123 && payload.size >= 3 && payload[0].toInt() == 0) {
            val mask = OpoProtocol.u16(payload, 1)
            // HeyMelody drops LHDC (7) when LHDC V5 (8) is offered too.
            codecs = Decode.codecsOf(mask)
            log("CODECS: $codecs RAW=[${OpoProtocol.bytesToHex(payload)}]")
            handler.post { listeners.forEach { it.onFeatureStates(featureStates) } }
            return
        }
        // The level Smart chose: push `03 04 01 <mask LE>`.
        if (cmd == OpoProtocol.CMD_ACTIVE_REPORT && payload.size >= 4 &&
            payload[0].toInt() == 0x03 && payload[1].toInt() == 0x04 && payload[2].toInt() == 0x01) {
            var mask = 0
            for (i in minOf(payload.size, 7) - 1 downTo 3) mask = (mask shl 8) or (payload[i].toInt() and 0xFF)
            onSmartMask(mask)
            return
        }
        if (cmd == 0x812B && payload.size >= 3 && payload[0].toInt() == 0) {
            val count = payload[2].toInt() and 0xFF
            gameSoundType = payload[1].toInt() and 0xFF
            gameSoundTypes = (3 until minOf(payload.size, 3 + count)).map { payload[it].toInt() and 0xFF }
            log("GAME SOUND: type=$gameSoundType offered=$gameSoundTypes RAW=[${OpoProtocol.bytesToHex(payload)}]")
            handler.post { listeners.forEach { it.onFeatureStates(featureStates) } }
            return
        }

        // The push `F5 <type>` and the `0x8134` reply `00 <type>`.
        if (payload.size >= 2 && (cmd == OpoProtocol.CMD_QUERY_HEAD_MOTION_TYPE or 0x8000 && payload[0].toInt() == 0 ||
            cmd == OpoProtocol.CMD_ACTIVE_REPORT && payload[0].toInt() and 0xFF == OpoProtocol.EVT_HEAD_MOTION_TYPE)) {
            headMotionType = payload[1].toInt() and 0xFF
            log("HEAD MOTION TYPE: $headMotionType RAW=[${OpoProtocol.bytesToHex(payload)}]")
            handler.post { listeners.forEach { it.onFeatureStates(featureStates) } }
            return
        }

        // --- Golden Sound (PROTOCOL.md §20) ---
        if (cmd == OpoProtocol.CMD_ACTIVE_REPORT && payload.size >= 3 && payload[0].toInt() == OpoProtocol.EVT_GOLDEN_STATUS) {
            val kind = payload[1].toInt() and 0xFF
            val status = payload[2].toInt() and 0xFF
            log("GOLDEN STATUS: kind=$kind status=$status")
            handler.post { listeners.forEach { it.onGoldenStatus(kind, status) } }
            return
        }
        if (cmd == OpoProtocol.CMD_ACTIVE_REPORT && payload.isNotEmpty() && payload[0].toInt() == OpoProtocol.EVT_EAR_SCAN) {
            val scan = GoldenSound.parseScan(payload, 1)
            log("EAR SCAN: uid=${scan?.first?.let { "%08X".format(it) }} len=${scan?.second?.size}")
            if (scan != null) handler.post { listeners.forEach { it.onEarScan(scan.first, scan.second) } }
            return
        }
        if (cmd == 0x8116 && payload.size >= 10 && payload[0].toInt() == 0) {
            val uid = ((payload[1].toInt() and 0xFF) shl 24) or ((payload[2].toInt() and 0xFF) shl 16) or
                ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)
            val type = payload[9].toInt() and 0xFF
            val curves = GoldenSound.parseCurves(payload, scan = false)
            log("GOLDEN FILTER: uid=${"%08X".format(uid)} enhanceType=$type floats=${curves?.second?.size?.times(2)}")
            handler.post {
                listeners.forEach {
                    it.onGoldenFilter(uid, type)
                    if (curves != null) it.onGoldenCurves(uid, false, curves.first, curves.second, curves.third)
                }
            }
            return
        }
        if (cmd == 0x811F && payload.size >= 11 && payload[0].toInt() == 0) {
            val curves = GoldenSound.parseCurves(payload, scan = true)
            val uid = GoldenSound.uidAt(payload, 1)
            log("GOLDEN SCAN FILTER: uid=${"%08X".format(uid)} fs=${curves?.first} floats=${curves?.second?.size?.times(2)}")
            if (curves != null) handler.post { listeners.forEach { it.onGoldenCurves(uid, true, curves.first, curves.second, curves.third) } }
            return
        }
        if (cmd == 0x8115) {
            val active = GoldenSound.parseActive(payload)
            log("GOLDEN ACTIVE: ${active?.let { "%08X \"%s\" %s".format(it.first, it.second, it.third.joinToString(",")) } ?: "none"} RAW=[${OpoProtocol.bytesToHex(payload)}]")
            if (active != null) handler.post { listeners.forEach { it.onGoldenActive(active.first, active.second, active.third) } }
            return
        }
        if (cmd == 0x811E) {
            val scan = if (payload.firstOrNull()?.toInt() == 0) GoldenSound.parseScan(payload, 1) else null
            log("GOLDEN ACTIVE SCAN: uid=${scan?.first?.let { "%08X".format(it) }} len=${scan?.second?.size}")
            if (scan != null) handler.post { listeners.forEach { it.onGoldenActiveScan(scan.first, scan.second) } }
            return
        }

        // --- Personalized ANC, [VENDOR] (PROTOCOL.md §20) ---
        if (cmd == 0x811A && payload.size >= 2 && payload[0].toInt() == 0) {
            log("PERSONAL ANC stored=${payload[1]}")
            handler.post { listeners.forEach { it.onPersonalNoiseStored(payload[1].toInt() != 0) } }
            return
        }
        if (cmd == 0x8133 && payload.size >= 3 && payload[0].toInt() == 0) {
            tapLevelDefault = payload[2].toInt()
            log("TAP LEVEL: ${payload[1]} default ${payload[2]}")
            handler.post { listeners.forEach { it.onTapLevel(payload[1].toInt(), payload[2].toInt()) } }
            return
        }
        // Public event `0F <flags u16 LE> <data>` (PROTOCOL.md section 10): the first flag that matches wins. Bit 0: the alert-sound
        // volume changed on the buds; bit 1: nothing; bit 2: handheld-device info (not used here); bit 4: a tap level change.
        if (cmd == OpoProtocol.CMD_ACTIVE_REPORT && payload.size >= 4 && payload[0].toInt() == OpoProtocol.EVT_PUBLIC) {
            val flags = (payload[1].toInt() and 0xFF) or ((payload[2].toInt() and 0xFF) shl 8)
            if (flags and OpoProtocol.PUBLIC_FLAG_ALERT_VOLUME != 0) {
                val level = payload[3].toInt() and 0xFF
                alertVolume = level
                log("ALERT VOLUME push: $level RAW=[${OpoProtocol.bytesToHex(payload)}]")
                handler.post { listeners.forEach { it.onAlertVolume(level) } }
                return
            }
            if (flags and 0x06 == 0 && flags and OpoProtocol.PUBLIC_FLAG_TAP_LEVEL != 0) {
                log("TAP LEVEL push: ${payload[3]} RAW=[${OpoProtocol.bytesToHex(payload)}]")
                handler.post { listeners.forEach { it.onTapLevel(payload[3].toInt(), tapLevelDefault) } }
                return
            }
        }
        if ((cmd == 0x840D || cmd == 0x840E) && payload.isNotEmpty() && payload[0].toInt() != 0) {
            val status = payload[0].toInt() and 0xFF
            log("GOLDEN REFUSED: cmd=0x${"%04X".format(cmd)} status=$status")
            handler.post { listeners.forEach { it.onGoldenRefused(status) } }
            return
        }
        if (cmd == 0x8412 && payload.isNotEmpty()) {
            handler.post { listeners.forEach { it.onPersonalNoiseAck(payload[0].toInt() and 0xFF) } }
            return
        }
        if (cmd == OpoProtocol.CMD_ACTIVE_REPORT && payload.size >= 2 &&
            payload[0].toInt() == OpoProtocol.EVT_PERSONAL_NOISE) {
            log("PERSONAL ANC result=${payload[1]} RAW=[${OpoProtocol.bytesToHex(payload)}]")
            handler.post { listeners.forEach { it.onPersonalNoiseResult(payload[1].toInt() and 0xFF) } }
            return
        }

        // --- Fit test result: 0x0204 subType 0x04, `04 [dev status] [dev status]` [VENDOR] ---
        if (cmd == OpoProtocol.CMD_ACTIVE_REPORT && payload.size >= 5 &&
            payload[0].toInt() == OpoProtocol.EVT_FIT_TEST) {
            var left = -1
            var right = -1
            for (i in intArrayOf(1, 3)) when (payload[i].toInt()) {
                1 -> left = payload[i + 1].toInt() and 0xFF
                2 -> right = payload[i + 1].toInt() and 0xFF
            }
            log("FIT: left=$left right=$right")
            handler.post { listeners.forEach { it.onFitResult(left, right) } }
            return
        }

        // --- Feature changed: 0x0503 `<count> <id> <value>...`, [CAPTURE] reports #2 #4 #63 ---
        // The buds send this after a 0x0403 write (before or after the ack) and on a buds gesture.
        // It is the push that confirms a "push" write, game mode included.
        if (cmd == OpoProtocol.CMD_FEATURE_PUSH) {
            val pushed = FeaturePushParser.parse(payload)
            if (pushed != null) {
                log("FEATURE PUSH: " + pushed.entries.joinToString(" ") { "%02X=%d".format(it.key, it.value) })
                syncHandler.post {
                    execute(sync.report(pushed, null, SystemClock.elapsedRealtime(), push = true))
                }
            }
            return
        }

        // --- Game mode changed on the buds: 0x0204 spontaneous event, subType 0x05 ---
        // Raised for BOTH our own game-mode write and the user's buds gesture, so this
        // is what keeps the widget/app buttons honest. Previously this frame fell
        // through to the battery checks below, matched neither, and was discarded —
        // which is why the buttons never updated after a gesture.
        // Payload is only 2 bytes, so this must come before any length assumption.
        if (cmd == OpoProtocol.CMD_ACTIVE_REPORT &&
            GameModeParser.isGameModeEvent(payload)) {
            val on = GameModeParser.parseActive(payload, featurePrefs().getString(Capabilities.KEY_PRODUCT_ID, null))
            if (on != null) {
                log("GAME EVT: ${if (on) "ON" else "OFF"}")
                handler.post { listeners.forEach { it.onGameModeState(on) } }
                // Also a push report for the engine: it confirms a game-mode operation.
                val gameId = Capabilities.gameModeId(context)
                syncHandler.post {
                    execute(sync.report(mapOf(gameId to (if (on) 1 else 0)), null, SystemClock.elapsedRealtime(), push = true))
                }
            }
            return
        }

        val battery = BatteryParser.parse(packet)
        val activeBattery = BatteryParser.parseActive(packet)

        (battery ?: activeBattery)?.let { b ->
            // Settings › Hide old battery levels: a part the report leaves out is cleared, not kept.
            val clear = BudsService.clearMissingBattery(context)
            if (b.left != null || clear) lastLeft = b.left
            if (b.right != null || clear) lastRight = b.right
            if (b.case != null || clear) lastCase = b.case
            emitBattery()
        }
    }

    private fun onProductId(id: String) {
        val prefs = featurePrefs()
        // Other buds than last time: a model picked by hand was for those.
        val edit = prefs.edit().putString(Capabilities.KEY_PRODUCT_ID, id)
        if (prefs.getString(Capabilities.KEY_PRODUCT_ID, null) != id) edit.remove(ModelCatalog.KEY_MANUAL)
        edit.apply()
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
    }

    /** API 34 and later: the error code of a [BluetoothSocketException] that ended the try. */
    private fun logSocketError(e: Exception) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val code = (e as? BluetoothSocketException)?.errorCode ?: return
        log(ConnectDiagnostics.socketErrorLine(code))
    }
}
