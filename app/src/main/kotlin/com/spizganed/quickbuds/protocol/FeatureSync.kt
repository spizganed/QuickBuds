package com.spizganed.quickbuds.protocol

/**
 * The sync engine for one setting write. No clock, no threads: the caller passes `now` (ms).
 * One operation is active. The others wait in a queue, in tap order.
 */
class FeatureSync {
    enum class Confirm { READ, PUSH, ACK }
    enum class ToastKind { REFUSED_MISSING, REFUSED_NOW, NOT_CHANGED, NO_ANSWER }

    class Operation(
        val key: Int,
        val writes: List<Pair<Int, Int>>,
        val confirm: Confirm,
        val restarts: Boolean = false,
    )

    sealed interface Out {
        data class Busy(val key: Int, val on: Boolean) : Out        // "busy:<key>:on|off"
        data class Send(val id: Int, val value: Int, val seq: Int) : Out   // "send:<id>:<value>:s<seq>"
        data class Read(val seq: Int) : Out                          // "read:s<seq>"
        data class Changed(val id: Int, val value: Int) : Out        // "changed:<id>:<value>"
        data class Toast(val key: Int, val kind: ToastKind) : Out    // "toast:<key>:refused_missing|refused_now|not_changed|no_answer"
    }

    private enum class Phase { ACK, REPLY, PUSH, RESTART }

    private class Active(val op: Operation) {
        var write = 0
        var phase = Phase.ACK
        var seq = 0
        var deadline = 0L
        var readSeq = 0
        var acked = 0               // writes of this operation that got status 0
        var reconnected = false
        var dropped = false         // the link went down after a restarts write
    }

    private val values = HashMap<Int, Int>()
    private val busyKeys = HashSet<Int>()
    private val queue = ArrayDeque<Operation>()
    private var active: Active? = null
    private var nextSeq = 1         // one counter for every send and every read

    fun init(values: Map<Int, Int>) {
        this.values.putAll(values)
    }

    fun tap(op: Operation, now: Long): List<Out> {
        if (op.key in busyKeys) return emptyList()
        busyKeys += op.key
        val out = mutableListOf<Out>(Out.Busy(op.key, true))
        if (active == null) start(op, now, out) else queue.addLast(op)
        return out
    }

    fun ack(seq: Int, status: Int, now: Long): List<Out> {
        val a = active ?: return emptyList()
        if (a.phase != Phase.ACK || seq != a.seq) return emptyList()
        val out = mutableListOf<Out>()

        if (status != 0) {
            out += Out.Toast(a.op.key, if (status == 1) ToastKind.REFUSED_MISSING else ToastKind.REFUSED_NOW)
            stop(a, out)
            // An earlier write went through: one read re-syncs the controls.
            if (a.acked > 0) out += Out.Read(nextSeq++)
            startNext(now, out)
            return out
        }

        a.acked++
        a.write++
        if (a.write < a.op.writes.size) {
            sendWrite(a, now, out)
            return out
        }

        when {
            a.op.restarts -> {
                a.phase = Phase.RESTART
                a.deadline = now + RESTART_MS
            }
            a.op.confirm == Confirm.READ -> {
                a.readSeq = nextSeq++
                a.phase = Phase.REPLY
                a.deadline = now + WAIT_MS
                out += Out.Read(a.readSeq)
            }
            a.op.confirm == Confirm.PUSH -> {
                // The buds can report the new value BEFORE the ack (a fast push: 151 ms in report #68, realme Clip).
                // That report already confirms the write, and a second push never comes. Wait only when the value
                // is still the old one.
                val written = a.op.writes.lastOrNull { it.first == a.op.key }?.second
                if (written != null && values[a.op.key] == written) {
                    stop(a, out)
                    startNext(now, out)
                } else {
                    a.phase = Phase.PUSH
                    a.deadline = now + WAIT_MS
                }
            }
            else -> {          // ACK: the ack decides
                applyWrites(a, out)
                stop(a, out)
                startNext(now, out)
            }
        }
        return out
    }

    /** [push]: the buds sent it on their own (an event). A read reply that is not ours is NOT a push: it only updates values. */
    fun report(values: Map<Int, Int>, seq: Int?, now: Long, push: Boolean = false): List<Out> {
        val out = mutableListOf<Out>()
        for ((id, value) in values.toSortedMap()) {
            if (this.values[id] != value) {
                this.values[id] = value
                out += Out.Changed(id, value)
            }
        }

        val a = active ?: return out
        val confirms = when (a.phase) {
            Phase.REPLY -> seq != null && seq == a.readSeq
            Phase.PUSH -> push && a.op.key in values
            Phase.RESTART -> a.reconnected
            Phase.ACK -> false
        }
        if (!confirms) return out

        val written = a.op.writes.lastOrNull { it.first == a.op.key }?.second
        if (written != null && this.values[a.op.key] != written) {
            out += Out.Toast(a.op.key, ToastKind.NOT_CHANGED)
        }
        stop(a, out)
        startNext(now, out)
        return out
    }

    fun tick(now: Long): List<Out> {
        val a = active ?: return emptyList()
        if (now < a.deadline) return emptyList()
        // A restarts write, and the link never dropped: these buds did not restart (realme Buds T500 Pro, report 128).
        // One read decides.
        if (a.phase == Phase.RESTART && !a.dropped) {
            a.readSeq = nextSeq++
            a.phase = Phase.REPLY
            a.deadline = now + WAIT_MS
            return listOf(Out.Read(a.readSeq))
        }
        val out = mutableListOf<Out>(Out.Toast(a.op.key, ToastKind.NO_ANSWER))
        stop(a, out)
        startNext(now, out)
        return out
    }

    fun disconnect(now: Long): List<Out> {
        val a = active
        val out = mutableListOf<Out>()
        // A restarts write that was acked keeps waiting across the drop.
        if (a != null && a.op.restarts && a.phase == Phase.RESTART) a.dropped = true
        if (a != null && !(a.op.restarts && a.phase == Phase.RESTART)) {
            out += Out.Toast(a.op.key, ToastKind.NO_ANSWER)
            stop(a, out)
        }
        // Queued operations sent nothing: busy off, no toast.
        while (queue.isNotEmpty()) {
            val op = queue.removeFirst()
            busyKeys -= op.key
            out += Out.Busy(op.key, false)
        }
        return out
    }

    fun reconnect(now: Long): List<Out> {
        active?.reconnected = true
        return emptyList()
    }

    fun value(id: Int): Int? = values[id]

    fun busy(key: Int): Boolean = key in busyKeys

    /** An operation is running or waiting: the caller's failure-detector tick is needed. */
    fun active(): Boolean = active != null

    private fun start(op: Operation, now: Long, out: MutableList<Out>) {
        val a = Active(op)
        active = a
        sendWrite(a, now, out)
    }

    private fun sendWrite(a: Active, now: Long, out: MutableList<Out>) {
        val (id, value) = a.op.writes[a.write]
        a.seq = nextSeq++
        a.phase = Phase.ACK
        a.deadline = now + ACK_MS
        out += Out.Send(id, value, a.seq)
    }

    /** Set the written values and report each real change, in ascending id order. */
    private fun applyWrites(a: Active, out: MutableList<Out>) {
        for ((id, value) in a.op.writes.toMap().toSortedMap()) {
            if (values[id] != value) {
                values[id] = value
                out += Out.Changed(id, value)
            }
        }
    }

    /** End the active operation: busy off. */
    private fun stop(a: Active, out: MutableList<Out>) {
        busyKeys -= a.op.key
        active = null
        out += Out.Busy(a.op.key, false)
    }

    private fun startNext(now: Long, out: MutableList<Out>) {
        if (queue.isNotEmpty()) start(queue.removeFirst(), now, out)
    }

    private companion object {
        const val ACK_MS = 3000L      // wait for the ack of one write
        const val WAIT_MS = 3000L     // wait for the reply or the push
        const val RESTART_MS = 15000L // wait for the first report after the reconnect
    }
}

fun FeatureSync.Out.token(): String = when (this) {
    is FeatureSync.Out.Busy -> "busy:$key:" + if (on) "on" else "off"
    is FeatureSync.Out.Send -> "send:$id:$value:s$seq"
    is FeatureSync.Out.Read -> "read:s$seq"
    is FeatureSync.Out.Changed -> "changed:$id:$value"
    is FeatureSync.Out.Toast -> "toast:$key:" + when (kind) {
        FeatureSync.ToastKind.REFUSED_MISSING -> "refused_missing"
        FeatureSync.ToastKind.REFUSED_NOW -> "refused_now"
        FeatureSync.ToastKind.NOT_CHANGED -> "not_changed"
        FeatureSync.ToastKind.NO_ANSWER -> "no_answer"
    }
}
