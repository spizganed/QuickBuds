package com.spizganed.quickbuds.protocol

/*
 * The command builders: each payload is built here.
 * The app calls them directly. The Golden Sound builders are in
 * HearingCommands.kt. PROTOCOL.md documents every command.
 */

internal object Commands {

    // --- Connection ---

    fun handshake(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_HANDSHAKE)
    fun queryProductId(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_PRODUCT_ID)
    fun queryBroadcastCodes(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_BROADCAST)
    fun queryFirmware(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_FIRMWARE)

    /**
     * The events to subscribe to: ours, kept to those the buds offer in `0x8200` when they sent one,
     * as HeyMelody subscribes to the offered list only (PROTOCOL.md §6). Some firmwares never ack a
     * list with others.
     */
    fun notifyIds(fitTest: Boolean, golden: Boolean, personalNoise: Boolean, offered: Set<Int>?): List<Int> =
        (listOf(0x01, 0x02, 0x03) + (if (fitTest) listOf(OpoProtocol.EVT_FIT_TEST) else emptyList()) +
            (if (golden) listOf(OpoProtocol.EVT_GOLDEN_STATUS) else emptyList()) +
            (if (personalNoise) listOf(OpoProtocol.EVT_PERSONAL_NOISE) else emptyList()))
            .filter { offered == null || it in offered }

    /**
     * Subscribe to spontaneous 0x0204 notifications (battery + wearing + ANC).
     *
     * Payload is canonical: a count byte followed by event ids.
     *
     * CONFIRMED on device: with count=2 and events battery (0x01) + wearing (0x02),
     * the ACK lists BOTH and the buds then emit 0x0204 subtype 02 on every wear
     * change. Wear updates are effectively instant — no polling needed.
     *
     * The earlier literal 01 01 02 02 was a misread: under the count-first
     * shape it means count=1 (register battery only) with 02 02 left over. The
     * firmware ACKed that happily and silently never sent wear events, which is
     * why wear appeared to be poll-only. Do NOT revert to that.
     *
     * 0x03 (ANC): the buds advertise their event ids in
     * the 0x8200 broadcast-codes reply, which lists `01 02 03 04 08 0B F1 F2 F3` —
     * 03 is offered, and the ANC subType 0x03 push arrived only unreliably while we
     * had not subscribed to it. Subscribing is the documented way to make a 0x0204
     * subType arrive, and it is how wear was fixed, so the same reasoning applies.
     *
     * CONFIRMED ON DEVICE: the 0x8205 ACK lists `01 02 03`, and a bud-side
     * gesture now updates the app circles AND the widget, including a gesture REBOUND
     * to a different mode cycle. If ANC ever stops following gestures, check the ACK
     * still lists `01 02 03` first — a firmware that rejects the longer list would ACK
     * a shorter one.
     *
     * 0x04 (fit test result) is added when the buds have the fit test (`0x0405`):
     * HeyMelody registers every id the buds list in 0x8200 (`registerMultiNotification`).
     * 0x08 (Golden Sound test status) likewise, when the buds have the test (`0x040D`).
     */
    fun registerNotifications(ids: List<Int>): ByteArray =
        OpoProtocol.buildPacket(
            OpoProtocol.CMD_REGISTER_NOTIFY,
            payload = registerNotificationsPayload(ids)
        )

    internal fun registerNotificationsPayload(ids: List<Int>): ByteArray =
        byteArrayOf(ids.size.toByte()) + ids.map { it.toByte() }

    /** Subscribe one event, for buds without `0x0205` in their bitmap. `[VENDOR]` */
    fun registerOne(id: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_REGISTER_ONE, payload = registerOnePayload(id))

    internal fun registerOnePayload(id: Int): ByteArray = byteArrayOf(id.toByte())

    // --- ANC and features ---

    /**
     * SET_ANC (0x0404): `01 01` then a little-endian bit field with bit [bit] set, `index / 8 + 1`
     * bytes long. The bit is the mode's `protocolIndex` for the connected model ([AncModes],
     * PROTOCOL.md §14). Buds 4: Off 0 `01 01 01`, Transparency 2 `01 01 04`, Deep 4, Medium 5,
     * Light 6, Adaptive 11 `01 01 00 08`.
     *
     * Reports (`0x810C`, `0x0204` subType 3) name the SAME tree's bits, but a mode's child where it
     * has one (Buds 4 reports Off as bit 3, Transparency as bit 8). Setting a child bit was tried
     * once and sent the wrong mode: SET uses the parent. Adaptive's bit is 11, not 8: `8` gives
     * `01 01 00 01`, a different mode.
     */
    fun anc(bit: Int): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_SET_ANC, payload = ancPayload(bit))

    internal fun ancPayload(bit: Int): ByteArray {
        val arr = ByteArray(2 + bit / 8 + 1)
        arr[0] = 0x01
        arr[1] = 0x01
        arr[2 + bit / 8] = (1 shl (bit % 8)).toByte()
        return arr
    }

    /**
     * setSupportNoiseReduction — WRITE which ANC modes the hold cycles through.
     *
     * `[CAPTURE]` 2026-09-22, via an HCI capture of HeyMelody itself (Option C,
     * PACKET-CAPTURE.md): he added Adaptive to a 3-stop hold cycle in HeyMelody, and the
     * app sent `AA 0A 00 00 04 04 <seq> 04 00 02 01 07 08` — `0x0404` action `02`, noiseType
     * `01`, then the mask `07 08` (little-endian `0x0807`). Acked (`0x8404` status `00`), and
     * the immediate `0x010C` `02 01` read-back returned the same `07 08`, up from `07 00`
     * (`0x0007`) beforehand.
     *
     * THIS CONFIRMS THE MASK REUSES [anc]'S OWN BIT NUMBERING, not a separate scheme —
     * bit 11 (`0x0800`) is exactly Adaptive's bit in the plain SET_ANC table above, and it is
     * the only bit that moved. PROTOCOL.md §14 previously carried this as `[INFERRED]`; this
     * capture settles it. Off = bit 0, Transparency = bit 2 (matching [anc]); bit 1 is
     * some generic "On" that resolves to whichever level was last hand-set, seen set in every
     * capture so far and never independently isolated.
     *
     * A DIFFERENT COMMAND FROM [anc], same command NUMBER. `0x0404`'s first payload
     * byte selects the question: `01 01 <bits>` sets the CURRENT mode (see [ancOff] etc.),
     * `02 01 <mask LE>` sets the cycle's MEMBERSHIP. Do not merge these two payload shapes.
     */
    fun holdAncModes(mask: Int, type: Int = OpoProtocol.HOLD_TYPE_SHARED): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_ANC, payload = holdAncModesPayload(mask, type))

    internal fun holdAncModesPayload(mask: Int, type: Int): ByteArray {
        // `[VENDOR]` `NoiseReductionInfo.getData()`: the mask in as few bytes as it needs (1-4), LE.
        var len = 4
        while (len > 1 && (mask ushr ((len - 1) * 8)) and 0xFF == 0) len--
        return byteArrayOf(0x02, type.toByte()) + ByteArray(len) { (mask ushr (it * 8)).toByte() }
    }

    /**
     * getNoiseReductionSwitchMode — WHICH ANC modes the hold cycles through.
     *
     * `0x010C` is one command number carrying several different questions, selected by the
     * payload. `[OSS]`, from the Melody-derived tables:
     *
     *     01 01   getCurrentNoiseReductionMode   what is active right now  <- queryAncMode()
     *     02 01   getNoiseReductionSwitchMode    the HOLD's mode list
     *     02 03   "                              (list may be one of these)
     *     02 04   "                              (…the source lists all three)
     *     04 01   getIntelligentNoiseReductionMode
     *
     * WHY THIS EXISTS: the hold's ANC cycle has its own write (holdAncModes).
     * Its key-function byte (`0x08`) only *reports* that the hold cycles ANC — measured on
     * the device, clearing that byte did not stop the cycle, and which modes it stepped
     * through was decided entirely on the vendor side. The mode list lives HERE instead, and
     * this read is the cheap, read-only way to see it before anyone writes `0x0404`.
     *
     * READ-ONLY, so it cannot change a binding. `[VENDOR]`: `02 01` on
     * most models, `02 03` (left) and `02 04` (right) on models with a per-bud hold
     * (`longPressType`), see [OpoProtocol.HOLD_TYPE_SHARED].
     */
    fun queryNoiseSwitchModes(type: Int = OpoProtocol.HOLD_TYPE_SHARED): ByteArray =
        queryAnc(0x02, type)

    fun queryAncMode(): ByteArray = queryAnc(0x01, 0x01)

    /** The level Smart chose: `00 04 01 <SET bit mask LE>`, also pushed as `03 04 01 <mask>` `[VENDOR]`. */
    fun querySmartLevel(): ByteArray = queryAnc(0x04, 0x01)

    private fun queryAnc(what: Int, arg: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_ANC, payload = ancReadPayload(what, arg))

    internal fun ancReadPayload(what: Int, arg: Int): ByteArray = byteArrayOf(what.toByte(), arg.toByte())

    /** Any `0x0403` feature switch. Spatial (`0x1B`) and Hi-Res (`0x18`) are `[CAPTURE]` — PROTOCOL.md §16. */
    fun feature(featureId: Int, on: Boolean, seq: Int? = null): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_FEATURE, seq = seq, payload = featurePayload(featureId, on))

    internal fun featurePayload(featureId: Int, on: Boolean): ByteArray =
        byteArrayOf(featureId.toByte(), if (on) 0x01 else 0x00)

    /** Find my earbuds — `0x0400` `01` start / `00` stop, both buds, no side byte. `[CAPTURE]` 2026-09-23. */
    fun findTone(on: Boolean): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_FIND_BUDS, payload = findTonePayload(on))

    internal fun findTonePayload(on: Boolean): ByteArray = byteArrayOf(if (on) 0x01 else 0x00)

    /** Earbud fit test: `0x0405` `01` start / `00` stop (HeyMelody stops it when its sheet closes). */
    fun fitTest(on: Boolean): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_FIT_TEST, payload = fitTestPayload(on))

    internal fun fitTestPayload(on: Boolean): ByteArray = byteArrayOf(if (on) 0x01 else 0x00)

    fun personalNoise(action: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_PERSONAL_NOISE, payload = personalNoisePayload(action))

    internal fun personalNoisePayload(action: Int): ByteArray = byteArrayOf(action.toByte())

    fun queryPersonalNoise(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_PERSONAL_NOISE)

    fun gameEqState(inGame: Boolean): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GAME_EQ_STATE, payload = gameEqStatePayload(inGame))

    internal fun gameEqStatePayload(inGame: Boolean): ByteArray = byteArrayOf(1, if (inGame) 1 else 0)

    fun setTapLevel(level: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_TAP_LEVEL, payload = tapLevelPayload(level))

    internal fun tapLevelPayload(level: Int): ByteArray = byteArrayOf(level.toByte())

    fun queryTapLevel(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_TAP_LEVEL)

    /** `0x0427 <level>`, clamped to 1..10. */
    fun alertVolume(level: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_ALERT_VOLUME, payload = alertVolumePayload(level))

    internal fun alertVolumePayload(level: Int): ByteArray = byteArrayOf(level.coerceIn(1, 10).toByte())

    fun queryAlertVolume(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_ALERT_VOLUME)

    fun spatialType(type: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_SPATIAL_TYPE, payload = spatialTypePayload(type))

    internal fun spatialTypePayload(type: Int): ByteArray = byteArrayOf(type.toByte())

    fun querySpatialType(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_SPATIAL_TYPE)

    fun spatialOnBuds(): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SPATIAL_SOURCE, payload = spatialSourcePayload(0))

    internal fun spatialSourcePayload(source: Int): ByteArray = byteArrayOf(source.toByte())

    fun queryCodec(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_CODEC)

    fun queryCodecList(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_CODEC_LIST)

    /** HeyMelody sends Hi-Res only with LDAC (`3`) or LHDC V5 (`8`), else `0`; the last byte is always `0`. */
    fun codec(codec: Int, hiRes: Boolean): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_CODEC, payload = codecPayload(codec, hiRes))

    internal fun codecPayload(codec: Int, hiRes: Boolean): ByteArray =
        byteArrayOf(codec.toByte(), if (hiRes && (codec == 3 || codec == 8)) 1 else 0, 0)

    /** HeyMelody sends every pick, Off (`0`) included, with enable `01`. */
    fun gameSoundType(type: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_GAME_SOUND, payload = gameSoundPayload(type))

    internal fun gameSoundPayload(type: Int): ByteArray = byteArrayOf(type.toByte(), 0x01)

    fun queryGameSound(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_GAME_SOUND)

    fun headMotionType(type: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_HEAD_MOTION_TYPE, payload = headMotionPayload(type))

    internal fun headMotionPayload(type: Int): ByteArray = byteArrayOf(type.toByte())

    fun queryHeadMotionType(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_HEAD_MOTION_TYPE)

    // --- Battery and status ---

    /** Battery query `0x0106` with the fixed sequence `0xF0`. */
    fun queryBattery(): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_BATTERY, seq = SEQ_BATTERY)

    /** getEarBudsStatus `0x0109` with the fixed sequence `0xF2` — returns `[count][comp,st]` pairs, st=4 means in case. */
    fun queryWearingStatus(): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_WEARING, seq = SEQ_WEARING)

    /** The fixed sequences. The counter stops at `0xBF`, so these never collide with it. */
    internal const val SEQ_BATTERY = 0xF0
    internal const val SEQ_WEARING = 0xF2

    /**
     * `0x010D <count> <ids>`. [statusQuery]: the model's `statusQuery` (hex ids, HeyMelody's per-model list,
     * PROTOCOL.md §16); a model without one gets [RowRules.DEFAULT_IDS]. [windNoise]: the model's
     * `windMode` flag adds [Feature.WIND_NOISE].
     */
    fun queryStatus(statusQuery: String? = null, windNoise: Boolean = false, seq: Int = 0x00): ByteArray {
        val ids = if (statusQuery != null) {
            statusQuery.split(' ').map { it.toInt(16).toByte() }.toByteArray()
        } else {
            RowRules.DEFAULT_IDS.map { it.toByte() }.toByteArray()
        }
        val all = ids + if (windNoise) byteArrayOf(Feature.WIND_NOISE.toByte()) else byteArrayOf()
        return OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_STATUS, seq = seq, payload = statusPayload(all))
    }

    internal fun statusPayload(ids: ByteArray): ByteArray = byteArrayOf(ids.size.toByte()) + ids

    // --- Equalizer ---

    /** Current EQ -> `0x810F` `00 <id>` (00-02 built-in, 04+ custom). */
    fun queryEq(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_EQ)

    /** Custom preset list -> `0x8122`, see [EqCodec.parseList]. Empty payload, as HeyMelody sends it. */
    fun queryEqAll(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_EQ_ALL)

    fun setBuiltInEq(id: Int): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_EQ, payload = builtInEqPayload(id))

    /** realme Buds T01 volume enhancer: `0x0407 <01 on, 00 off>`, acked, no state read ([CAPTURE] report 25). */
    fun volumeEnhancer(on: Boolean): ByteArray =
        OpoProtocol.buildPacket(0x0407, payload = byteArrayOf(if (on) 1 else 0))

    internal fun builtInEqPayload(id: Int): ByteArray = byteArrayOf(id.toByte())

    /** `0x0418 <action> <tag> <id> <name> <bands>`. [EqCodec.encode] builds the payload; the tag bytes are the source. */
    fun customEq(action: Int, p: EqCodec.Preset): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SAVE_CUSTOM_EQ, payload = customEqPayload(action, p))

    internal fun customEqPayload(action: Int, p: EqCodec.Preset): ByteArray = EqCodec.encode(action, p)

    /**
     * BassWave level, signed -5..+5; `FB 05` is the range (min, max) HeyMelody sends.
     * [band] 0 sends no band byte; realme dynamic sound (`bassBands` 3) sends 1 low, 2 medium, 3 high.
     */
    fun setBassWaveLevel(level: Int, band: Int = 0): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_BASSWAVE_LEVEL, payload = bassWaveLevelPayload(level, band))

    internal fun bassWaveLevelPayload(level: Int, band: Int = 0): ByteArray =
        if (band > 0) byteArrayOf(0xFB.toByte(), 0x05, level.toByte(), band.toByte())
        else byteArrayOf(0xFB.toByte(), 0x05, level.toByte())

    /** -> `0x8124` `00 FB 05 <level>`. */
    fun queryBassWaveLevel(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_BASSWAVE_LEVEL)

    // --- Gestures ---

    /**
     * getKeyFunction (0x0108) — read the CURRENT gesture bindings.
     *
     * READ-ONLY, and deliberately so: it reports what the buds are already doing,
     * so it cannot change state or break a binding. The 0x8108 reply carries the function
     * enum; PROTOCOL.md §15 lists the measured values.
     *
     * Payload is a bare query (no payload), matching the other 0x01xx reads. The buds
     * DO answer it: the reply is `<status> <count> <4-byte entries>...` and its layout
     * is `[CAPTURE]`-confirmed — see PROTOCOL.md §15 and `KeyFunctionParser`, which
     * logs it RAW and grouped so two readings can be diffed.
     *
     * The `function` VALUES are no longer a mystery either: they were MEASURED by
     * diffing two of these replies around a change made in the vendor app. See
     * `GestureAction.functionByte`.
     */
    fun queryKeyFunction(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_KEY_FUNCTION)

    /**
     * setKeyFunction (0x0401) — write gesture bindings back.
     *
     * Payload is `<count>` then 4 bytes per entry, and the entries are the SAME
     * [KeyFunctionParser.Entry] type the read reply decodes into, deliberately: using
     * one type for both directions is what stops the two from drifting, and it means a
     * table read back from the buds can be written out again unmodified.
     *
     * NO LEADING STATUS BYTE, unlike the reply. The reply is `<status> <count> ...`
     * (`[CAPTURE]`), but a status byte is something the device REPORTS; a write supplies
     * only the count and its entries.
     *
     * WHY THE CALLER STILL RE-READS: the payload layout above is `[OSS]`, and the ONE
     * thing the device has taught us about this command is that it ignores a wrong
     * command number in total silence (see CMD_SET_KEY_FUNCTION). A silent ignore looks
     * exactly like success unless the table is read back, so the read-back is not
     * belt-and-braces — it is the only evidence there is.
     *
     * LEB128 is safe here: the largest realistic payload is an 18-entry table,
     * `1 + 18*4 = 73` bytes, so `TotalLen = 80` fits in one LEB128 byte. `buildPacket()`
     * would only need real LEB128 above ~30 entries, which this device does not have.
     */
    fun setKeyFunction(entries: List<KeyFunctionParser.Entry>): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_KEY_FUNCTION, payload = keyFunctionPayload(entries.map {
            intArrayOf(it.deviceType, it.button, it.action, it.function)
        }))

    /** The write payload: `<count>`, then `deviceType, button, action, function` per entry. */
    internal fun keyFunctionPayload(entries: List<IntArray>): ByteArray {
        val payload = ByteArray(1 + entries.size * KeyFunctionParser.ENTRY_SIZE)
        payload[0] = entries.size.toByte()
        for ((i, e) in entries.withIndex()) {
            val o = 1 + i * KeyFunctionParser.ENTRY_SIZE
            for (k in 0 until KeyFunctionParser.ENTRY_SIZE) payload[o + k] = e[k].toByte()
        }
        return payload
    }

    /**
     * setKeyFunction write for the on-call group (`btn 0x06`) — `[CAPTURE]`, same capture as
     * [holdAncModes]. HeyMelody writes ONE entry, `deviceType = 0x04`
     * ([KeyFunctionParser.DEVICE_TYPE_BOTH]), which the buds fan out to both sides: the
     * following `0x8108` read-back always showed the SAME `fn` on `dev=0x01` AND `dev=0x02`,
     * never `dev=0x04` itself. See [KeyFunctionParser.BUTTON_ON_CALL] for the full capture and
     * the confirmed act-to-row mapping.
     */
    fun setOnCall(act: Int, fn: Int, button: Int = KeyFunctionParser.BUTTON_ON_CALL): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_SET_KEY_FUNCTION, payload = onCallPayload(act, fn, button))

    internal fun onCallPayload(act: Int, fn: Int, button: Int): ByteArray = byteArrayOf(
        0x01,
        KeyFunctionParser.DEVICE_TYPE_BOTH.toByte(),
        button.toByte(),
        act.toByte(),
        fn.toByte()
    )

    // --- Dual connection ---

    fun queryDevices(): ByteArray = OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_DEVICES)

    /** The MAC in written order, as the connect and preferred writes send it. */
    private fun macBytes(mac: String): ByteArray = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()

    /** `0x0429 01|02 <MAC>`: connect or disconnect a device. */
    fun connectDevice(mac: String, on: Boolean): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_MULTI_CONNECT, payload = connectDevicePayload(macBytes(mac), on))

    internal fun connectDevicePayload(mac: ByteArray, on: Boolean): ByteArray =
        byteArrayOf(if (on) 0x01 else 0x02) + mac

    /** `0x0429 04 00` automatic, `04 01 <MAC>` a chosen device. A null [mac] means automatic. */
    fun setPreferred(mac: String?): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_MULTI_CONNECT, payload = preferredPayload(mac?.let { macBytes(it) }))

    internal fun preferredPayload(mac: ByteArray?): ByteArray =
        if (mac == null) byteArrayOf(0x04, 0x00) else byteArrayOf(0x04, 0x01) + mac

    fun queryPreferred(): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_QUERY_PREFERRED, payload = preferredReadPayload())

    internal fun preferredReadPayload(): ByteArray = byteArrayOf(0x02)

    /** `08 00 01` after dual OFF, `08 00 00` after ON — copied from HeyMelody, not understood. The literal stays. */
    fun dualFollowup(dualOn: Boolean): ByteArray =
        OpoProtocol.buildPacket(OpoProtocol.CMD_DUAL_FOLLOWUP, payload = dualFollowupPayload(dualOn))

    internal fun dualFollowupPayload(dualOn: Boolean): ByteArray =
        byteArrayOf(0x08, 0x00, if (dualOn) 0x00 else 0x01)
}
