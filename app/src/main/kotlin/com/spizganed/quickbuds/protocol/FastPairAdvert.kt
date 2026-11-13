package com.spizganed.quickbuds.protocol

/**
 * Battery in a BLE advertisement (PROTOCOL.md section 13, "Other battery sources"). No connection is needed.
 * Two packets carry it: the OPPO manufacturer data and the Google Fast Pair service data `FE2C`.
 */
object FastPairAdvert {
    /** Manufacturer ids of the OPPO packet: OPPO and OnePlus. */
    val COMPANY_IDS = intArrayOf(0x079A, 0x072F)
    /** The 16-bit Fast Pair service, as a full UUID. */
    const val SERVICE_UUID = "0000fe2c-0000-1000-8000-00805f9b34fb"

    /** [productId] is the 24-bit id of the OPPO packet; null for the Fast Pair packet. */
    data class Battery(val productId: Int?, val left: Int?, val right: Int?, val case: Int?, val caseCharging: Boolean)

    /**
     * The OPPO manufacturer data, without the company id. Flag bit 0 must be set and bytes 5-6 must be `0x98xx`.
     * A battery byte: bit 0 valid, level in bits 1-7. Null when the packet has no battery.
     */
    fun oppo(data: ByteArray): Battery? {
        if (data.size < 7) return null
        val flags = u8(data, 3) or (u8(data, 4) shl 8)
        if (flags and 1 == 0 || u8(data, 6) != 0x98 || flags and (1 shl 4) == 0) return null
        var p = 7
        if (flags and (1 shl 1) != 0) p += 1
        if (flags and (1 shl 2) != 0) p += 6
        if (flags and (1 shl 3) != 0) p += 1
        if (data.size < p + 3) return null
        fun level(b: Int) = if (b and 1 == 0) null else (b shr 1) and 0x7F
        val pid = u8(data, 0) or (u8(data, 1) shl 8) or (u8(data, 2) shl 16)
        return Battery(pid, level(u8(data, p)), level(u8(data, p + 1)), level(u8(data, p + 2)), false)
    }

    /**
     * The Fast Pair service data when the buds are not discoverable (Google spec): a flags byte, then fields
     * `0bLLLLTTTT` + L bytes. Type 3 or 4 with L = 3 is the battery: L R C, bit 7 charging, `7F` unknown.
     */
    fun google(data: ByteArray): Battery? {
        if (data.size <= 3) return null
        var p = 1
        while (p < data.size) {
            val len = u8(data, p) shr 4
            val type = u8(data, p) and 0x0F
            if ((type == 3 || type == 4) && len == 3 && p + 3 < data.size) {
                val l = FastPairStream.level(data[p + 1]); val r = FastPairStream.level(data[p + 2]); val c = FastPairStream.level(data[p + 3])
                return Battery(null, l.level, r.level, c.level, c.charging)
            }
            p += 1 + len
        }
        return null
    }

    /**
     * The one Fast Pair sender with a case level whose left and right levels equal the link's.
     * Null when no link level is known, or when not exactly one sender matches.
     */
    fun matchLink(senders: Collection<Battery>, left: Int?, right: Int?): Battery? {
        if (left == null && right == null) return null
        return senders.filter { it.case != null && (left == null || it.left == left) && (right == null || it.right == right) }
            .singleOrNull()
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
}
