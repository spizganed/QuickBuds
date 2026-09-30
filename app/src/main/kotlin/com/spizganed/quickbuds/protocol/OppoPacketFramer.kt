package com.spizganed.quickbuds.protocol

/**
 * Reassembles 0xAA-framed OPPO protocol packets from a byte stream.
 * Handles partial frames, multiple frames in one read, and garbage before headers.
 *
 * `TotalLen` (byte 1) is LEB128, so a frame over 127 bytes has a longer header (Golden Sound's
 * ear-scan data, PROTOCOL.md §9). Every frame comes out [normalise]d, so the rest of the app
 * keeps its fixed layout: cmd at 4-5, seq at 6, payload length at 7-8, payload from 9.
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
            val (totalLen, lenBytes) = readLength(pending) ?: break

            val frameLen = 1 + lenBytes + totalLen
            if (totalLen < 7 || frameLen > 2048) {
                onDiscard?.invoke(pending.copyOfRange(0, 1))
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < frameLen) break

            frames += normalise(pending.copyOfRange(0, frameLen))
            pending = pending.copyOfRange(frameLen, pending.size)
        }
        return frames
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

        /** The frame with a one-byte length field (its low 7 bits), so the header is always 9 bytes. */
        fun normalise(frame: ByteArray): ByteArray {
            if (frame.isEmpty() || frame[0] != 0xAA.toByte()) return frame
            val lenBytes = readLength(frame)?.second ?: return frame
            if (lenBytes == 1) return frame
            return byteArrayOf(frame[0], (frame[1].toInt() and 0x7F).toByte()) + frame.copyOfRange(1 + lenBytes, frame.size)
        }
    }
}
