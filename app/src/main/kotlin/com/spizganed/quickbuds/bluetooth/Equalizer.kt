package com.spizganed.quickbuds.bluetooth

import com.spizganed.quickbuds.protocol.Commands
import com.spizganed.quickbuds.protocol.EqCodec
import com.spizganed.quickbuds.protocol.EqSnapshot
import com.spizganed.quickbuds.protocol.EqState
import com.spizganed.quickbuds.protocol.OpoProtocol

/**
 * The equalizer of the linked buds (PROTOCOL.md §17): its state, the snapshot that paints the screen before a bind,
 * and the rules for quick picks. No socket and no Android here, so a test drives it with a fake clock and sender.
 *
 *  - Each write updates the state FIRST, then sends and re-reads. The replies land one by one and each repaints;
 *    without this, a reply that arrives before the new values snaps the screen back for a moment.
 *  - A reply that names another preset than the last pick is ignored while the pick is under [PICK_HOLD_MS] old.
 *  - Writes go out one at a time on one thread ([onThread]). A queued pick that a newer pick replaced is not sent.
 *
 * [now] is the clock in ms. [send] writes one frame and blocks. [supports] checks the bitmap. [saved] is the stored
 * snapshot text and [save] stores a new one. [pause] waits between frames.
 */
class Equalizer(
    private val now: () -> Long,
    private val send: (ByteArray, String) -> Unit,
    private val supports: (Int) -> Boolean,
    saved: String?,
    private val save: (String) -> Unit,
    private val onThread: (() -> Unit) -> Unit,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
) {
    /** The saved snapshot. It is used for a link only when its MAC matches the device ([EqState.forLink]). */
    @Volatile private var snapshot: EqSnapshot? = EqSnapshot.decode(saved)

    /** The EQ values the screen reads. Before a link: the snapshot, so a bind repaints no blank. */
    @Volatile var state: EqState = EqState.beforeLink(snapshot)
        private set

    /** The MAC of the linked buds; null while no link is up. */
    @Volatile private var mac: String? = null
    private val gate = EqReadGate()

    /** The preset last picked, and when. */
    @Volatile private var picked: Int? = null
    @Volatile private var pickedAt = 0L
    /** Counts picks: a queued pick that a newer one replaced is not sent. */
    @Volatile private var pickSeq = 0

    fun onLinkUp(mac: String?) {
        this.mac = mac
        if (mac != null) state = EqState.forLink(snapshot, mac)
        gate.onLinkUp()
    }

    fun onLinkDown() {
        mac = null
        gate.onLinkDown()
    }

    /** One EQ read set per link: the bind ask before the link up sends nothing. */
    fun refresh() { if (gate.take()) sendThenRead() }

    // --- Replies ---

    /** The buds report [id] as current: kept unless an unconfirmed pick of another preset is under 4 s old. */
    fun onCurrent(id: Int) {
        val want = picked
        if (want != null && want != id && now() - pickedAt < PICK_HOLD_MS) return
        picked = null
        set(state.copy(current = id))
    }

    fun onCustom(list: List<EqCodec.Preset>) = set(state.withList(list))

    fun onBassLevel(level: Int) = set(state.copy(bassWave = level))

    /** `(band 1-3, level)` pairs; a band outside the sliders is ignored. */
    fun onBassBands(list: List<Pair<Int, Int>>) {
        val bands = state.bassBands.toMutableList()
        for ((band, level) in list) if (band in 1..bands.size) bands[band - 1] = level
        set(state.copy(bassBands = bands))
    }

    // --- Writes ---

    fun selectBuiltIn(id: Int) {
        set(pick(id))
        sendThenRead(Commands.setBuiltInEq(id) to "EQ built-in $id", ++pickSeq)
    }

    /** Selects a custom preset unchanged: the same frame as a save, but a newer pick may replace it. */
    fun selectCustom(p: EqCodec.Preset) {
        set(pick(p.id))
        sendThenRead(Commands.customEq(EqCodec.ACTION_SAVE, p) to "EQ custom ${p.id} '${p.name}'", ++pickSeq)
    }

    /** Selects AND saves a custom preset — `0x0418` is both (a band edit or rename is the same frame). */
    fun saveCustom(p: EqCodec.Preset) {
        val next = pick(p.id)
        set(next.withList(next.custom.map { if (it.id == p.id) p else it }))
        sendThenRead(Commands.customEq(EqCodec.ACTION_SAVE, p) to "EQ custom ${p.id} '${p.name}'")
    }

    /** No local update for create/delete: the buds assign and renumber ids, so only the re-read knows. */
    fun createCustom(name: String, freqs: List<Int>) =
        sendThenRead(Commands.customEq(EqCodec.ACTION_CREATE, EqCodec.newPreset(name, freqs)) to "EQ create '$name'")

    fun deleteCustom(p: EqCodec.Preset) =
        sendThenRead(Commands.customEq(EqCodec.ACTION_DELETE, p) to "EQ delete ${p.id} '${p.name}'")

    fun setBassWave(level: Int) {
        set(state.copy(bassWave = level))
        sendThenRead(Commands.setBassWaveLevel(level) to "BassWave level $level")
    }

    /** realme dynamic sound: [band] 1 low, 2 medium, 3 high. */
    fun setBassBand(band: Int, level: Int) {
        set(state.copy(bassBands = state.bassBands.toMutableList().also { it[band - 1] = level }))
        sendThenRead(Commands.setBassWaveLevel(level, band) to "BassWave band $band level $level")
    }

    private fun pick(id: Int): EqState {
        picked = id
        pickedAt = now()
        return state.copy(current = id)
    }

    /** Stores [next] and, with a link up, the snapshot. Every EQ change goes through here. */
    private fun set(next: EqState) {
        state = next
        val mac = mac ?: return
        EqSnapshot(mac, next.current, next.bassWave, next.custom, next.bassBands).also {
            snapshot = it
            save(it.encode())
        }
    }

    /** Optional write, then the EQ reads these buds list, in order on the EQ thread. [pick]: skipped when a newer pick is queued. */
    private fun sendThenRead(write: Pair<ByteArray, String>? = null, pick: Int? = null) = onThread {
        if (pick != null && pick != pickSeq) return@onThread
        try {
            if (write != null) { send(write.first, write.second); pause(WRITE_GAP_MS) }
            for ((cmd, read) in READS) {
                if (!supports(cmd)) continue
                send(read.first(), read.second)
                pause(READ_GAP_MS)
            }
        } catch (e: Exception) {
            log("EQ: ${e.message}")
        }
    }

    companion object {
        /** A pick holds the selection this long against replies of earlier picks. */
        const val PICK_HOLD_MS = 4000L
        private const val WRITE_GAP_MS = 400L
        private const val READ_GAP_MS = 120L

        /** The EQ reads, in order: (command, (frame, log label)). */
        private val READS: List<Pair<Int, Pair<() -> ByteArray, String>>> = listOf(
            OpoProtocol.CMD_QUERY_EQ to ({ Commands.queryEq() } to "query EQ"),
            OpoProtocol.CMD_QUERY_EQ_ALL to ({ Commands.queryEqAll() } to "query custom EQ"),
            OpoProtocol.CMD_QUERY_BASSWAVE_LEVEL to ({ Commands.queryBassWaveLevel() } to "query BassWave level"),
            OpoProtocol.CMD_QUERY_BASS_BANDS to ({ Commands.queryBassBands() } to "query bass bands"),
        )
    }
}
