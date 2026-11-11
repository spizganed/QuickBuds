package com.spizganed.quickbuds.protocol

import com.spizganed.quickbuds.bluetooth.PacketLogger
import org.json.JSONException
import org.json.JSONObject

/*
 * The replies to the buds' requests and the batch layer: the one reply layer.
 * The app behaviour is the contract. PROTOCOL.md section 11 documents the request answers.
 */

object Replies {
    /** Time request `0x0500` from the buds (PROTOCOL.md §11). */
    const val REQ_TIME = 0x0500

    /** Command groups HeyMelody knows, as the command's high byte. A request from any other group gets `01`. */
    private val KNOWN_GROUPS = setOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x0F)

    /** True when [cmd] is in a group the buds may use without an answer. The check uses the command's high byte. */
    fun knownGroup(cmd: Int): Boolean = (cmd shr 8) in KNOWN_GROUPS

    /**
     * The answer to a frame the buds send on their own, as a whole packet, or null when no answer is due.
     * [cmd], [seq] and [payload] are the incoming frame's. [unixSeconds] is the phone's clock. `[VENDOR]`
     */
    fun answerFor(cmd: Int, seq: Int, payload: ByteArray, unixSeconds: Long): ByteArray? =
        answerBody(cmd, payload, unixSeconds)?.let { OpoProtocol.buildPacket(cmd or 0x8000, seq, it) }

    /** The reply payload, or null. Shared by [answerFor] and [Decode.answer]. */
    internal fun answerBody(cmd: Int, payload: ByteArray, unixSeconds: Long): ByteArray? {
        if (cmd and 0x8000 != 0) return null
        return when (cmd) {
            REQ_TIME -> unixSeconds.toInt().let { t ->
                byteArrayOf(0, t.toByte(), (t shr 8).toByte(), (t shr 16).toByte(), (t shr 24).toByte())
            }
            0x050C -> byteArrayOf(0, 1, 0)
            0x051C -> byteArrayOf(0, 0, 0)
            // ponytail: always "invalid"; HeyMelody answers the phone's spatial mode, which we do not have.
            0x051D -> byteArrayOf(1)
            OpoProtocol.CMD_ACTIVE_REPORT -> when (payload.firstOrNull()?.toInt()?.and(0xFF)) {
                0xF2 -> byteArrayOf(0, 0xF2.toByte())
                0xF4 -> jsonAck(payload.copyOfRange(1, payload.size))
                else -> null
            }
            else -> if (!knownGroup(cmd)) byteArrayOf(1) else null
        }
    }

    /** `F4` JSON push: `00 F4 {"cmd":<its cmd>}`, `01 F4` if it does not parse. */
    private fun jsonAck(json: ByteArray): ByteArray {
        val head = byteArrayOf(0, 0xF4.toByte())
        val text = String(json, Charsets.UTF_8)
        if (text.isEmpty()) return head
        return try {
            val cmd = JSONObject(text).opt("cmd")
            head + JSONObject().apply { if (cmd is String) put("cmd", cmd) }.toString().toByteArray()
        } catch (e: JSONException) {
            PacketLogger.error("JSON push", e)
            byteArrayOf(1, 0xF4.toByte())
        }
    }

    /**
     * `0x012F` packet from built query [packets]: `<count>` then `<cmd LE> <len LE> <payload>` each.
     * Each packet's length fits in one byte (short queries).
     */
    fun batch(packets: List<ByteArray>): ByteArray = OpoProtocol.buildPacket(
        OpoProtocol.CMD_BATCH,
        payload = batchPayload(packets.map { p -> OpoProtocol.u16(p, 4) to p.copyOfRange(9, 9 + OpoProtocol.u16(p, 7)) }),
    )

    /** The `0x012F` payload from (command, payload) pairs. */
    internal fun batchPayload(queries: List<Pair<Int, ByteArray>>): ByteArray {
        val body = queries.flatMap { (cmd, payload) ->
            listOf(
                (cmd and 0xFF).toByte(), ((cmd shr 8) and 0xFF).toByte(),
                (payload.size and 0xFF).toByte(), ((payload.size shr 8) and 0xFF).toByte(),
            ) + payload.toList()
        }
        return byteArrayOf(queries.size.toByte()) + body
    }

    /**
     * `0x812F` = `00 <count>`, then `<cmd LE> <len LE> <reply payload>` per answer, `cmd` being the query's:
     * (reply cmd, payload) pairs, stopping at the first that runs past the end.
     */
    fun batchReplies(payload: ByteArray): List<Pair<Int, ByteArray>> {
        if (payload.size < 2 || payload[0].toInt() != 0) return emptyList()
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var o = 2
        repeat(payload[1].toInt() and 0xFF) {
            if (o + 4 > payload.size) return out
            val len = OpoProtocol.u16(payload, o + 2)
            if (o + 4 + len > payload.size) return out
            out += (OpoProtocol.u16(payload, o) or 0x8000) to payload.copyOfRange(o + 4, o + 4 + len)
            o += 4 + len
        }
        return out
    }
}
