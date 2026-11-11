package com.spizganed.quickbuds.protocol

/**
 * The link layer of docs/PROTOCOL.md §2: frames, slices, reassembly. No Bluetooth, no commands.
 * Plain Kotlin, so a JVM test runs it.
 */
object Wire {
    /** One message: what a frame, or the slices of one message, carry. */
    data class Message(val cmd: Int, val seq: Int, val payload: ByteArray) {
        override fun equals(other: Any?) = other is Message && cmd == other.cmd && seq == other.seq && payload.contentEquals(other.payload)
        override fun hashCode() = (cmd * 31 + seq) * 31 + payload.contentHashCode()
    }

    /** The largest frame the sender writes unless the buds report a smaller one. */
    const val BUDGET = 660
    private const val START = 0xAA.toByte()

    /** Bytes `Len` takes: LEB128, 7 bits per byte. */
    private fun lenSize(len: Int) = if (len < 1 shl 7) 1 else if (len < 1 shl 14) 2 else 3

    private fun frameSize(data: Int, sliced: Boolean): Int {
        val len = 2 + (if (sliced) 1 else 0) + data
        return 1 + lenSize(len) + len
    }

    private fun frame(slice: Int, fsn: Int?, data: ByteArray, from: Int, to: Int): ByteArray {
        val len = 2 + (if (fsn != null) 1 else 0) + (to - from)
        val out = java.io.ByteArrayOutputStream(1 + lenSize(len) + len)
        out.write(START.toInt())
        var v = len
        do {
            val b = v and 0x7F
            v = v ushr 7
            out.write(if (v != 0) b or 0x80 else b)
        } while (v != 0)
        out.write(slice)
        out.write(0)
        if (fsn != null) out.write(fsn)
        out.write(data, from, to - from)
        return out.toByteArray()
    }

    /** The frames that carry [m]: one whole frame, or slices when the whole frame exceeds [budget]. */
    fun encode(m: Message, budget: Int = BUDGET): List<ByteArray> {
        val n = m.payload.size
        val msg = ByteArray(5 + n)
        msg[0] = m.cmd.toByte(); msg[1] = (m.cmd shr 8).toByte(); msg[2] = m.seq.toByte()
        msg[3] = n.toByte(); msg[4] = (n shr 8).toByte()
        m.payload.copyInto(msg, 5)
        if (frameSize(msg.size, false) <= budget) return listOf(frame(0, null, msg, 0, msg.size))
        // The most data a slice holds, at least 1 byte so a tiny budget still ends.
        val per = (budget downTo 1).firstOrNull { frameSize(it, true) <= budget } ?: 1
        val count = (msg.size + per - 1) / per
        return (0 until count).map { i ->
            frame(if (i == 0) 1 else if (i == count - 1) 3 else 2, i, msg, i * per, minOf(msg.size, (i + 1) * per))
        }
    }

    /**
     * Turns the byte stream into messages through [OppoPacketFramer], so both give the same result
     * for the same bytes. Noise before a frame is skipped and handed to [onDiscard].
     */
    class Reassembler(var onDiscard: ((ByteArray) -> Unit)? = null) {
        private val framer = OppoPacketFramer()

        fun push(bytes: ByteArray, length: Int = bytes.size): List<Message> {
            framer.onDiscard = onDiscard
            return framer.append(bytes, length).mapNotNull { message(it) }
        }

        /** `Cmd (2 LE)  Seq  PayLen (2 LE)  Payload` at the fixed 9-byte header. */
        private fun message(frame: ByteArray): Message? {
            if (frame.size < 9) return null
            val n = (frame[7].toInt() and 0xFF) or ((frame[8].toInt() and 0xFF) shl 8)
            val rest = frame.size - 9
            return Message(
                (frame[4].toInt() and 0xFF) or ((frame[5].toInt() and 0xFF) shl 8),
                frame[6].toInt() and 0xFF,
                frame.copyOfRange(9, 9 + minOf(n, rest))
            )
        }
    }
}

// ===== OppoPacketFramer =====

/**
 * Reassembles 0xAA-framed OPPO protocol packets from a byte stream.
 * Handles partial frames, multiple frames in one read, and garbage before headers.
 *
 * `TotalLen` (byte 1) is LEB128, so a frame over 127 bytes has a longer header (Golden Sound's
 * ear-scan data, PROTOCOL.md §20). Every frame comes out [normalise]d, so the rest of the app
 * keeps its fixed layout: cmd at 4-5, seq at 6, payload length at 7-8, payload from 9.
 * Slices of one message (PROTOCOL.md §2) are joined into one such frame.
 */
class OppoPacketFramer {
    private var pending = ByteArray(0)

    /** Bytes thrown away because they are not part of a frame (noise before a header, a bad length). */
    var onDiscard: ((ByteArray) -> Unit)? = null

    fun append(buffer: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0) return emptyList()
        pending += buffer.copyOfRange(0, length)
        val frames = mutableListOf<ByteArray>()

        while (pending.isNotEmpty()) {
            val start = pending.indexOf(0xAA.toByte())
            if (start < 0) {
                onDiscard?.invoke(pending)
                pending = ByteArray(0)
                break
            }
            if (start > 0) {
                onDiscard?.invoke(pending.copyOfRange(0, start))
                pending = pending.copyOfRange(start, pending.size)
            }
            val length = readLength(pending)
            if (length == null) {
                // Three length bytes with no terminator cannot be a header: drop the AA and keep scanning.
                if (pending.size > 3) {
                    onDiscard?.invoke(pending.copyOfRange(0, 1))
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                break
            }
            val (totalLen, lenBytes) = length

            if (pending.size < 2 + lenBytes) break
            // Slice byte (PROTOCOL.md §2): 0 a whole message, 1 first, 2 middle, 3 last slice. A slice has 3 header bytes, a message 7.
            val slice = pending[1 + lenBytes].toInt()
            val sliced = slice in 1..3
            val frameLen = 1 + lenBytes + totalLen
            if (totalLen < (if (sliced) 4 else 7) || frameLen > 2048) {
                onDiscard?.invoke(pending.copyOfRange(0, 1))
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < frameLen) break

            val frame = pending.copyOfRange(0, frameLen)
            pending = pending.copyOfRange(frameLen, pending.size)
            if (sliced) join(slice, frame.copyOfRange(1 + lenBytes + 3, frameLen))?.let { frames += it }
            else frames += normalise(frame)
        }
        return frames
    }

    private var slices: ByteArray? = null

    /**
     * Collects the data of slices 1, 2, 3 and returns the whole message as one normalised frame after slice 3.
     * A middle or last slice with no first slice before it is dropped. A `PayLen` past the bytes present keeps the bytes present.
     */
    private fun join(slice: Int, data: ByteArray): ByteArray? {
        if (slice == 1) slices = data
        else slices = (slices ?: return null) + data
        if (slice != 3) return null
        val msg = slices ?: return null
        slices = null
        if (msg.size < 5) return null
        val payload = minOf((msg[3].toInt() and 0xFF) or ((msg[4].toInt() and 0xFF) shl 8), msg.size - 5)
        val fixed = msg.copyOfRange(0, 3) + byteArrayOf(payload.toByte(), (payload shr 8).toByte()) + msg.copyOfRange(5, 5 + payload)
        return byteArrayOf(0xAA.toByte(), ((2 + fixed.size) and 0x7F).toByte(), 0, 0) + fixed
    }

    companion object {
        /** `TotalLen` and how many bytes it takes, or null while the frame is still too short. */
        fun readLength(frame: ByteArray): Pair<Int, Int>? {
            var value = 0
            for (i in 1 until minOf(frame.size, 4)) {
                val b = frame[i].toInt() and 0xFF
                value = value or ((b and 0x7F) shl (7 * (i - 1)))
                if (b and 0x80 == 0) return value to i
            }
            return null
        }

        /** The one-byte length field (low 7 bits) keeps the 9-byte header; `protocol_cases.json:130` freezes it. */
        fun normalise(frame: ByteArray): ByteArray {
            if (frame.isEmpty() || frame[0] != 0xAA.toByte()) return frame
            val lenBytes = readLength(frame)?.second ?: return frame
            if (lenBytes == 1) return frame
            return byteArrayOf(frame[0], (frame[1].toInt() and 0x7F).toByte()) + frame.copyOfRange(1 + lenBytes, frame.size)
        }
    }
}
