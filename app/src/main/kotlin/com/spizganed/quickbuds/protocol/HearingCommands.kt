package com.spizganed.quickbuds.protocol

/*
 * The Golden Sound builders: ear scan, hearing test, tone, filter, record and restore.
 * The app calls them directly. PROTOCOL.md section 20 documents every frame.
 */

internal object HearingCommands {

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    /** 12 x `<side> <freq> <value>`: left 1..6 then right 1..6, as HeyMelody sends them. */
    private fun hearingInfo(values: IntArray) = ByteArray(36) { i ->
        when (i % 3) { 0 -> (i / 18 + 1).toByte(); 1 -> (i / 3 % 6 + 1).toByte(); else -> values[i / 3].toByte() }
    }

    /** Ear scan start / stop, `04 01|00 <uid>`; the result comes as event `0x0E`. */
    fun earScan(on: Boolean, uid: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_DETECT, payload = earScanPayload(on, uid))

    internal fun earScanPayload(on: Boolean, uid: Int): ByteArray =
        byteArrayOf(0x04, if (on) 0x01 else 0x00) + int32(uid)

    /** Hearing test start / stop, `02 01|00`. */
    fun hearingTest(on: Boolean): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_DETECT, payload = hearingTestPayload(on))

    internal fun hearingTestPayload(on: Boolean): ByteArray = byteArrayOf(0x02, if (on) 0x01 else 0x00)

    /** Plays a tone on one bud: `03 01 <side 1 L / 2 R> <freq 1..6> <value>`. */
    fun hearingTone(side: Int, freq: Int, value: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_RECORD, payload = hearingTonePayload(side, freq, value))

    internal fun hearingTonePayload(side: Int, freq: Int, value: Int): ByteArray =
        byteArrayOf(0x03, 0x01, side.toByte(), freq.toByte(), value.toByte())

    /** Stops the tone, `04`; HeyMelody sends it before every new level. */
    fun hearingToneStop(): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_RECORD, payload = hearingToneStopPayload())

    internal fun hearingToneStopPayload(): ByteArray = byteArrayOf(0x04)

    /** Asks for the filter of a result; the `0x8116` reply's byte 9 is the enhance type. */
    fun hearingFilter(uid: Int, values: IntArray): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_FILTER, payload = hearingFilterPayload(uid, values))

    internal fun hearingFilterPayload(uid: Int, values: IntArray): ByteArray =
        byteArrayOf(0x0C) + hearingInfo(values) + int32(uid)

    /** Asks for the ear-scan filter of a record: `<length little-endian> <data> <uid>`. */
    fun earScanFilter(uid: Int, data: ByteArray): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_SCAN_FILTER, payload = earScanFilterPayload(uid, data))

    internal fun earScanFilterPayload(uid: Int, data: ByteArray): ByteArray =
        le16(data.size) + data + int32(uid)

    /** Applies a record: `03 0c <12 x info> <uid> <name>`. */
    fun hearingRecord(uid: Int, name: String, values: IntArray): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_RECORD, payload = hearingRecordPayload(uid, name, values))

    internal fun hearingRecordPayload(uid: Int, name: String, values: IntArray): ByteArray =
        byteArrayOf(0x03, 0x0C) + hearingInfo(values) + int32(uid) + name.toByteArray()

    /** The record's description id, `01 01 01 00 <id>` (count 1, type 1, length 1 little-endian). */
    fun hearingRestore(descId: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_RESTORE, payload = hearingRestorePayload(descId))

    internal fun hearingRestorePayload(descId: Int): ByteArray =
        byteArrayOf(0x01, 0x01, 0x01, 0x00, descId.toByte())

    /** Applies ear-scan data: `03 <length little-endian> <data> <uid>`. */
    fun earScanData(uid: Int, data: ByteArray): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_SCAN_DATA, payload = earScanDataPayload(uid, data))

    internal fun earScanDataPayload(uid: Int, data: ByteArray): ByteArray =
        byteArrayOf(0x03) + le16(data.size) + data + int32(uid)

    fun queryGoldenActive(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_ACTIVE)
    fun queryGoldenActiveScan(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_GOLDEN_ACTIVE_SCAN)
}
