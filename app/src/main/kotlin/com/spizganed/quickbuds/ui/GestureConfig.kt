package com.spizganed.quickbuds.ui

import android.content.Context
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.AncModes
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.KeyFunctionParser
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gesture configuration — the model behind the Earbud controls screen.
 *
 * BOTH HALVES ARE NOW KNOWN, AND THE ENUM IS MEASURED RATHER THAN GUESSED.
 *
 * The READ half: `0x0108` -> `0x8108` returns the current bindings, payload
 * `<status> <count> <4-byte entries>...` (`[CAPTURE]`, `KeyFunctionParser`).
 *
 * The `function` enum: it was unknown for a long time, and [GestureAction] deliberately
 * carried no protocol byte while that was true — filling in a guessed byte is exactly
 * what produced the SET-vs-NOTIFY regression this project had to revert (PROTOCOL.md
 * §5). It was filled in the honest way instead: he rebound slots in the vendor app, the
 * app diffed two `0x8108` readings, and he reported what he had bound. Every value
 * agreed, two of them across two separate slots. See PROTOCOL.md §6.
 *
 * THE ONE THING THAT IS NOT WHAT IT LOOKS: tap-and-hold. All four ANC actions share ONE
 * key-function byte, because the device models the hold as a single "ANC cycle" function.
 * That byte says only "the hold cycles ANC" — it does NOT carry which modes.
 *
 * WHICH MODES THE CYCLE CONTAINS is settable through a DIFFERENT command —
 * `setSupportNoiseReduction` (`0x0404` action `02`, payload `[02][01][modeMask LE]`,
 * `[CAPTURE]` 2026-09-22 — see [GestureModel.holdMask] and PROTOCOL.md §5), with `0x010C`
 * `02 01` reading the mask back. `GestureActivity.writeToBuds()` sends this as a SEPARATE
 * write from the key-function bind, deliberately: two commands, so a failure in either is
 * attributable, never merged into one "save".
 *
 * The write's payload layout is `[OSS]`, and the device ignores a WRONG command number in
 * complete silence, so a write is verified by READING THE KEY-FUNCTION TABLE BACK rather
 * than trusting it. That read-back is the only evidence there is.
 */
enum class GestureSide(val labelRes: Int, val deviceType: Int) {
    LEFT(R.string.gesture_bud_left, 0x01),
    RIGHT(R.string.gesture_bud_right, 0x02)
}

/**
 * A physical gesture on one bud.
 *
 * @param reportBytes the 0x0204 subType 0xF1 `action` byte(s) this gesture raises.
 *                    `[CAPTURE]`+`[OSS]` — from `UserInteractionParser`'s action
 *                    table (0x00 single, 0x02 double, 0x03 triple, 0x04 long press,
 *                    0x07 slide up, 0x08 slide down).
 * @param multiSelect true if more than one action may be bound, so the gesture
 *                    CYCLES through them on each use. Only tap-and-hold, by request.
 */
enum class Gesture(
    val labelRes: Int,
    val reportBytes: IntArray,
    val multiSelect: Boolean,
    /**
     * The `buttonAction` byte this gesture carries in a `setKeyFunction` write.
     *
     * *** THIS IS NOT [reportBytes], AND THE TWO NUMBERINGS GENUINELY DIFFER. ***
     * They are easy to confuse because both start at 1-ish and both look ordinal, and
     * using the wrong one is the kind of error that writes a binding somewhere else on
     * the bud rather than failing loudly. Measured, from the `0x8108` reading:
     *
     *     keyfn `act`   1=single  2=double  3=triple  4=hold  5=slide  6=always 0x00
     *     F1 `action`   0=single  2=double  3=triple  4=hold  7/8=slide up/down
     *
     * `[USER]`-confirmed: he rebound single/double/triple/slide and the reply moved
     * exactly `act` 01/02/03/05, i.e. NOT the F1 values.
     *
     * SLIDE HAS ONE keyfn BYTE BUT TWO F1 BYTES. The read reply carries a single
     * `act 0x05` entry per bud, while the buds REPORT slide up as `0x07` and slide down
     * as `0x08`. Both directions are driven by that one `0x05` binding — which is why the
     * write needs one entry, not the two that `reportBytes` would suggest.
     */
    val keyFnAction: Int = 0,
    /**
     * `[VENDOR]` The `action` values of HeyMelody's per-model `control` list that are this row
     * (`[VENDOR]`: 16/17/18 are the stem-press models' 1/2/3, and 11, 20 and 27 are
     * all the hold's ANC cycle on `act 0x04`). See [GestureModel].
     */
    val controlActions: IntArray = intArrayOf()
) {
    SINGLE_TAP(R.string.gesture_single, intArrayOf(0x00), false, keyFnAction = 0x01, controlActions = intArrayOf(1, 16)),
    DOUBLE_TAP(R.string.gesture_double, intArrayOf(0x02), false, keyFnAction = 0x02, controlActions = intArrayOf(2, 17)),
    TRIPLE_TAP(R.string.gesture_triple, intArrayOf(0x03), false, keyFnAction = 0x03, controlActions = intArrayOf(3, 18)),

    /**
     * SLIDE IS ONE ROW, TWO REPORT BYTES, AND A MOVING TARGET.
     *
     * The buds REPORT slide up (0x07) and slide down (0x08) separately — [reportBytes]
     * holds both — and where its BINDING lives depends on the bud and on whether it is
     * currently bound. Measured, in one single capture:
     *
     *     LEFT   dev=0x01/btn=0x01[.. 05:07]      slide inside btn 0x01, bound (volume)
     *     RIGHT  dev=0x02/btn=0x02[05:00]         slide split into its own groups,
     *            dev=0x02/btn=0x03[05:00]         both unbound (fn=0x00)
     *
     * *** THIS IS WHY SLIDE KEPT "NOT WORKING", AND TWICE THE FIX WAS WRONG. ***
     * Revision 1 wrote `btn 0x01` and silently missed the split shape. Revision 2 replaced
     * that with a hardcoded `btn 0x02`/`0x03` and then silently missed the LEFT bud, whose
     * slide is in `btn 0x01` — the log line was `NO SLOT MATCHED for dev=0x01`. Both
     * hardcodings are wrong half the time.
     *
     * THE FIX IS THAT NEITHER THIS ENUM NOR THE UI DECIDES. `writeGestureBinding()` reads
     * the buds' own table and writes EVERY slot that bud has for `act 0x05`, so whichever
     * shape is in front of it is the shape it writes.
     *
     * AND THE DEVICE THEN NORMALISES IT — captured, not assumed: after that right-bud write
     * the re-read showed the two split groups GONE and `dev=0x02/btn=0x01[.. 05:07]` in
     * their place. The split shape is a "two unbound halves" state that the firmware folds
     * back into one `btn 0x01` slot once both are given the same function. Writing every
     * matching slot is also what makes both halves agree, which is what triggers that.
     */
    SLIDE(R.string.gesture_slide, intArrayOf(0x07, 0x08), false, keyFnAction = 0x05, controlActions = intArrayOf(5)),

    /**
     * `[VENDOR]` A plain single-select hold on `act 0x04` (volume up / down on most models that
     * have it). No model has both this and [TAP_HOLD], so it shares the "Hold" label.
     */
    LONG_PRESS(R.string.gesture_hold, intArrayOf(0x04), false, keyFnAction = 0x04, controlActions = intArrayOf(4)),

    /** `[VENDOR]` HeyMelody's "super long press", `act 0x06` (volume or switch devices). */
    EXTRA_LONG_PRESS(R.string.gesture_extra_long, intArrayOf(), false, keyFnAction = 0x06, controlActions = intArrayOf(6)),

    TAP_HOLD(R.string.gesture_hold, intArrayOf(0x04), true, keyFnAction = 0x04, controlActions = intArrayOf(11, 20, 27))
}

/**
 * An assignable action, with the MEASURED `function` byte a write sends.
 *
 * This enum carried a label and no protocol byte for a long time, on purpose: the
 * `function` enum was unknown, and a guessed byte is the error that already cost this
 * project one regression. The bytes below are not guesses — they come from the
 * `0x8108` diff experiment, cross-checked against what he had actually bound. See the
 * class comment for the evidence, or PROTOCOL.md §6.
 *
 * TWO LABELS, ON PURPOSE. [labelRes] is the full name and is what the dialog sheet
 * shows, where there is a whole row per option and room to be unambiguous.
 * [shortLabelRes] is used only by the row SUMMARY, where up to four actions are
 * joined into one line — "ANC, Adapt, Trans, Off" fits where "ANC, Adaptive,
 * Transparency, ANC off" does not, and a summary that wraps makes its row taller
 * than every other row.
 *
 * Only the four ANC actions need a short form, because tap-and-hold is the only
 * multi-select gesture and those are the only actions it offers. A short form that
 * matches the full name is left at 0 and falls back to [labelRes].
 */
enum class GestureAction(
    val labelRes: Int,
    val shortLabelRes: Int = 0,
    /**
     * The `function` byte a `setKeyFunction` write stores for this action.
     *
     * EVERY VALUE HERE IS MEASURED, NOT GUESSED. The enum was filled in from a
     * `0x8108` diff: he rebound slots in the vendor app and reported what he set each
     * one to, and every value matched the reply with no contradictions (two of them
     * twice, on two different slots). See PROTOCOL.md §6. HeyMelody's own label -> byte map
     * agrees on every one, and is the
     * `[VENDOR]` source for volume up / down and switch devices.
     */
    val functionByte: Int = 0x00,
    /**
     * `[VENDOR]` This action's bit in a `control` entry's `support` mask.
     * 0 = not offered through `support` (the ANC modes).
     */
    val supportBit: Int = 0,
    /**
     * The ANC mode's HeyMelody `modeType` (as in [com.spizganed.quickbuds.protocol.AncModes]), or -1
     * for every action that is not an ANC mode. Its bit in the hold's cycle mask is that mode's
     * `protocolIndex`, which differs per model — see [GestureModel.holdBits].
     */
    val holdModeType: Int = -1
) {
    NONE(R.string.gesture_action_none, supportBit = 512),
    PLAY_PAUSE(R.string.gesture_action_play_pause, functionByte = 0x01, supportBit = 4),
    PREV_TRACK(R.string.gesture_action_prev, functionByte = 0x05, supportBit = 32),
    NEXT_TRACK(R.string.gesture_action_next, functionByte = 0x06, supportBit = 64),
    VOICE_ASSISTANT(R.string.gesture_action_assistant, functionByte = 0x03, supportBit = 1),
    VOLUME_UP(R.string.gesture_action_volume_up, functionByte = 0x0B, supportBit = 8),
    VOLUME_DOWN(R.string.gesture_action_volume_down, functionByte = 0x0C, supportBit = 16),
    VOLUME(R.string.gesture_action_volume, functionByte = 0x07, supportBit = 1024),
    SWITCH_TRACK(R.string.gesture_action_switch_track, functionByte = 0x0A, supportBit = 2048),
    SWITCH_DEVICE(R.string.gesture_action_switch_device, functionByte = 0x0D, supportBit = 4096),
    GAME_MODE(R.string.gesture_action_game, functionByte = 0x11, supportBit = 8192),

    // ALL FOUR ANC ACTIONS SHARE THE SAME `functionByte` (0x08), and that is not a mistake.
    //
    // The device models the hold as ONE key-function — "ANC cycle" — not as a set of modes.
    // He once set his hold to just "adaptive + ANC off" (TWO modes) and the byte stayed
    // 0x08; another time, same byte, the same gesture cycled FOUR modes. So the cycle's
    // MEMBERSHIP was never stored in this byte — it lives in a DIFFERENT command,
    // `setSupportNoiseReduction` (`0x0404` action `02`), confirmed `[CAPTURE]` 2026-09-22
    // (PROTOCOL.md §5). `GestureActivity.writeToBuds()` sends BOTH writes for the hold: the
    // key-function bind (`functionByte`, bind/unbind the gesture to "cycles ANC" at all) and the
    // mask ([GestureModel.holdMask], which modes it cycles through) — two separate commands on
    // purpose, so a failure in either is attributable, never merged into one "save".
    ANC_ON(R.string.gesture_action_anc_on, functionByte = 0x08, holdModeType = 5),
    /** `[VENDOR]` "strong" / "weak" as top-level modes of their own (Buds Z2, Enco X). */
    ANC_HIGH(R.string.widget_mode_anc_high, R.string.anc_mode_high, functionByte = 0x08, holdModeType = 4),
    ANC_LOW(R.string.widget_mode_anc_low, R.string.anc_mode_low, functionByte = 0x08, holdModeType = 3),
    ANC_ADAPTIVE(
        R.string.gesture_action_anc_adaptive,
        R.string.gesture_action_anc_adaptive_short,
        functionByte = 0x08,
        holdModeType = 10
    ),
    ANC_TRANSPARENCY(
        R.string.gesture_action_anc_transparency,
        R.string.gesture_action_anc_transparency_short,
        functionByte = 0x08,
        holdModeType = 2
    ),
    ANC_OFF(
        R.string.gesture_action_anc_off,
        R.string.gesture_action_anc_off_short,
        functionByte = 0x08,
        holdModeType = 1
    );

    /** Label for the row summary — the short form when one is defined. */
    fun summaryLabelRes(): Int = if (shortLabelRes != 0) shortLabelRes else labelRes
}

/**
 * Which gesture rows the current model has and what each may be bound to — `[VENDOR]`, from
 * HeyMelody's per-model `control` / `callControl` lists (copied into `assets/models.json`,
 * PROTOCOL.md §6). A row is a `control` entry; its options are the [GestureAction.supportBit]s in
 * its `support` mask, in HeyMelody's own order. For Buds 4
 * this gives exactly the rows and options the screen had when they were hand-written from
 * HeyMelody's UI (`[USER]` 2026-09-21).
 *
 * The hold ([Gesture.TAP_HOLD]) offers the model's top-level noise modes, and its cycle mask
 * uses each mode's `protocolIndex`, so the
 * bits differ per model (Buds 4: ANC 1, Adaptive 11, Transparency 2, Off 0, as captured).
 * Models with `longPressType` set give each bud its own hold ([perBudHold]): a choice of
 * [holdChoices], one of them the noise cycle with its own mask per bud (noise type 3 / 4).
 */
class GestureModel private constructor(
    /** Rows in [Gesture] order, each with its options. */
    val rows: Map<Gesture, List<GestureAction>>,
    /** ANC mode -> its bit in the hold's cycle mask. */
    private val holdBits: Map<GestureAction, Int>,
    /** The on-call rows this model has. */
    val onCall: List<OnCallGesture>,
    /** OnePlus Buds / Buds Z number previous / next track 4 / 5 (see [functionByte]). */
    private val oldTrackBytes: Boolean,
    /** `[VENDOR]` `longPressType` set: each bud has its own hold, written and read per side. */
    val perBudHold: Boolean = false,
    /**
     * `[VENDOR]` The per-bud hold's choices, `longPressType` bits in HeyMelody's order:
     * 512 none, 128 the noise cycle, 1 voice assistant, 8192 game mode.
     */
    val holdChoices: List<Int> = emptyList(),
    /** `[VENDOR]` The fewest noise modes the hold may cycle (`CustomMultiSelectPreference`). */
    val holdMin: Int = 1,
    /**
     * `[VENDOR]` OnePlus Buds Pro only: its ANC levels are top-level modes shown as one "ANC"
     * option, whose bit is the current level's (see [holdMask]).
     */
    private val ancLevelBits: List<Int> = emptyList(),
    /**
     * Our own key `sharedHoldMask` (realme Link data, PROTOCOL.md §6): a per-bud hold whose noise
     * cycle is one mask for both buds, written and read as [OpoProtocol.HOLD_TYPE_SHARED].
     */
    private val sharedHoldMask: Boolean = false,
    /** Our own key `oneButton` (realme Link data, PROTOCOL.md §6): a neckband, one button on `dev 01`. */
    val oneButton: Boolean = false,
    /** Our own key `bothHold` (realme Link data): the [OnCallGesture.BOTH_HOLD] row. */
    val bothHold: Boolean = false
) {
    val isEmpty get() = rows.isEmpty() && onCall.isEmpty()

    /**
     * `[VENDOR]` `getFunctionCommand`: previous / next track are 4 / 5 on "OnePlus Buds" and
     * "OnePlus Buds Z", 5 / 6 everywhere else.
     */
    fun functionByte(action: GestureAction): Int = when {
        oldTrackBytes && action == GestureAction.PREV_TRACK -> 0x04
        oldTrackBytes && action == GestureAction.NEXT_TRACK -> 0x05
        else -> action.functionByte
    }

    /**
     * The byte to WRITE for a whole selection. Empty -> `0x00` (unbind). Non-empty -> the FIRST
     * action's byte: every gesture but the hold is single-select, and for the hold any non-empty
     * selection is the ANC cycle `0x08`, whichever modes are ticked (see ANC_ON above). Uses
     * `firstOrNull`, not `single()`: the hold's multi-select holds several actions.
     */
    fun functionByteFor(actions: List<GestureAction>): Int = actions.firstOrNull()?.let { functionByte(it) } ?: 0x00

    /**
     * The hold's ANC-cycle mask for a selection. `levelBit` is the buds' current ANC level's bit:
     * `[VENDOR]` on Buds Pro "ANC" takes that level's bit, or Smart's (the first) when the buds
     * are not in an ANC level (HeyMelody's own default when it has no last level).
     */
    fun holdMask(actions: List<GestureAction>, levelBit: Int? = null): Int = actions.fold(0) { acc, a ->
        val bit = if (a == GestureAction.ANC_ON && ancLevelBits.isNotEmpty())
            levelBit?.takeIf { it in ancLevelBits } ?: ancLevelBits.first()
        else holdBits[a]
        bit?.let { acc or (1 shl it) } ?: acc
    }

    /** The hold's selection that a cycle mask read from the buds stands for. */
    fun holdActions(mask: Int): List<GestureAction> = rows[Gesture.TAP_HOLD].orEmpty().filter { a ->
        val bits = if (a == GestureAction.ANC_ON && ancLevelBits.isNotEmpty()) ancLevelBits else listOfNotNull(holdBits[a])
        bits.any { (mask shr it) and 1 == 1 }
    }

    /** The hold cycle's noise type for a bud ([OpoProtocol.HOLD_TYPE_SHARED] unless [perBudHold]). */
    fun holdType(side: GestureSide): Int = when {
        !perBudHold || sharedHoldMask -> OpoProtocol.HOLD_TYPE_SHARED
        side == GestureSide.LEFT -> OpoProtocol.HOLD_TYPE_LEFT
        else -> OpoProtocol.HOLD_TYPE_RIGHT
    }

    /** Every noise type the hold's masks are read with. */
    fun holdTypes(): List<Int> = GestureSide.values().map { holdType(it) }.distinct()

    /** A per-bud hold's non-noise choice that a key-function byte stands for. */
    fun holdChoiceFor(fn: Int): GestureAction? = holdChoices.filter { it != HOLD_NOISE }
        .map { bit -> GestureAction.values().first { it.supportBit == bit } }
        .firstOrNull { functionByte(it) == fn }

    companion object {
        private const val BUDS4 = "065414"
        /** `[VENDOR]` OnePlus Buds Pro (HeyMelody's `396308`), whose hold shows its levels as one ANC. */
        private const val BUDS_PRO = "060C14"
        /** The noise cycle's bit among [holdChoices]. */
        const val HOLD_NOISE = 128
        /** `[VENDOR]` HeyMelody's option order (`f2769a`), limited to the options the app has. */
        private val ORDER = intArrayOf(512, 4, 32, 64, 1, 8, 16, 1024, 2048, 4096, 8192)
        private val EMPTY = GestureModel(emptyMap(), emptyMap(), emptyList(), false)

        @Volatile private var cached: Pair<String, GestureModel>? = null

        /**
         * The current model's gestures. Nothing detected yet = Buds 4, as [AncModes.of] does; a
         * product id HeyMelody does not list gets none (its function codes are unknown).
         */
        fun of(context: Context): GestureModel {
            val model = ModelCatalog.current(context)
                ?: ModelCatalog.all(context).firstOrNull { it.id == BUDS4 }?.takeIf {
                    context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(Capabilities.KEY_PRODUCT_ID, null) == null
                }
                ?: return EMPTY
            cached?.let { if (it.first == model.id) return it.second }
            return runCatching { parse(model.json, model.name, model.id) }.getOrDefault(EMPTY).also { cached = model.id to it }
        }

        private fun parse(json: JSONObject, name: String, id: String): GestureModel {
            val control = json.optJSONArray("control") ?: JSONArray()
            val rows = LinkedHashMap<Gesture, List<GestureAction>>()
            var holdBits = emptyMap<GestureAction, Int>()
            val levelBits = ArrayList<Int>()
            val longPressType = json.optInt("longPressType")
            var holdMin = 1
            for (gesture in Gesture.values()) {
                val entry = (0 until control.length()).map { control.getJSONObject(it) }
                    .firstOrNull { it.getInt("action") in gesture.controlActions } ?: continue
                if (gesture == Gesture.TAP_HOLD) {
                    holdBits = holdBits(json, if (id == BUDS_PRO) levelBits else null) ?: continue
                    rows[gesture] = holdBits.keys.toList()
                    // `[VENDOR]` the entry's own minimum, else 2 on OnePlus and per-bud holds.
                    holdMin = entry.optInt("minSelectCount").takeIf { it > 0 }
                        ?: if (name.startsWith("OnePlus") || longPressType != 0) 2 else 1
                } else {
                    val support = entry.optInt("support")
                    val options = ORDER.filter { support and it != 0 }
                        .map { bit -> GestureAction.values().first { it.supportBit == bit } }
                    if (options.isNotEmpty()) rows[gesture] = options
                }
            }
            // `[VENDOR]` a callControl row is only a choice if it offers None (512); the others
            // (36 / 37 and the 32 / 33 of four models) are fixed, with nothing to write.
            val call = json.optJSONArray("callControl") ?: JSONArray()
            fun hasCall(action: Int) = (0 until call.length()).map { call.getJSONObject(it) }
                .any { it.getInt("action") == action && it.optInt("support") and 512 != 0 }
            val onCall = OnCallGesture.values().filter { hasCall(it.callAction) }
            // `[VENDOR]` order, without spy tap (8388608), which needs a phone-side feature.
            val holdChoices = if (longPressType == 0) emptyList()
                else intArrayOf(512, HOLD_NOISE, 1, 8192).filter { longPressType and it != 0 }
            return GestureModel(rows, holdBits, onCall, name == "OnePlus Buds" || name == "OnePlus Buds Z",
                longPressType != 0, holdChoices, holdMin, levelBits, json.optInt("sharedHoldMask") == 1,
                json.optInt("oneButton") == 1, json.optInt("bothHold") == 1)
        }

        /**
         * The hold's options and their mask bits: the model's top-level noise modes, in its own
         * order. Null (no hold row) when a mode has no action here. With [levelBits] (Buds Pro),
         * the ANC levels 3 / 4 / 7 become one [GestureAction.ANC_ON] and their bits go there
         * (HeyMelody gives them one label on that model).
         */
        private fun holdBits(json: JSONObject, levelBits: MutableList<Int>?): Map<GestureAction, Int>? {
            val modes = json.optJSONArray("noiseReductionMode") ?: return null
            val bits = LinkedHashMap<GestureAction, Int>()
            for (i in 0 until modes.length()) {
                val mode = modes.getJSONObject(i)
                // HeyMelody shows these only if a per-bud read says so, which we do not make.
                if (mode.optBoolean("decideByEarDevice")) continue
                if (levelBits != null && mode.getInt("modeType") in intArrayOf(3, 4, 7)) {
                    levelBits += mode.getInt("protocolIndex")
                    bits.putIfAbsent(GestureAction.ANC_ON, mode.getInt("protocolIndex"))
                    continue
                }
                val action = GestureAction.values().firstOrNull { it.holdModeType == mode.getInt("modeType") }
                    ?: return null
                bits[action] = mode.getInt("protocolIndex")
            }
            return bits.takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * The per-bud, per-gesture selection as stored ON THE PHONE.
 *
 * This is the screen's own record of what the user picked, NOT the authority —
 * the buds' key-function table is. A save sends a `0x0401` write and then READS
 * THE TABLE BACK to confirm it, because a wrong command number is ignored in
 * complete silence; this store is what the UI renders from between those reads.
 *
 * Stored per SIDE because the whole point of the Left/Right selector is that the
 * two buds can be bound differently.
 *
 * Uses the shared prefs file every other screen uses, so there is one place to
 * look and no second preferences file to discover later.
 */
object GestureConfigStore {

    private fun prefs(context: Context) =
        context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * TAP_HOLD IS DELIBERATELY SIDE-INDEPENDENT, unlike every other gesture. `[USER]` 2026-09-22,
     * confirmed by a captured HeyMelody write with no `deviceType` field at all in the hold-mask
     * command (PROTOCOL.md §5.1): the ANC cycle's membership is ONE setting for both buds, not two.
     * Keying its storage by `side` — as this function did until now — meant switching the Left/Right
     * tab showed a SEPARATE, likely stale selection for Hold that had nothing to do with either bud's
     * actual state. Every other gesture genuinely can differ per bud (their key-function bind is a
     * real per-`deviceType` table entry), so only `TAP_HOLD` gets the fixed key.
     */
    private fun key(context: Context, side: GestureSide, gesture: Gesture) =
        if (gesture == Gesture.TAP_HOLD && !GestureModel.of(context).perBudHold) "gesture_SHARED_${gesture.name}"
        else "gesture_${side.name}_${gesture.name}"

    /** What a gesture does when the user has never opened this screen. */
    fun defaultFor(gesture: Gesture): List<GestureAction> = when (gesture) {
        // TAP-AND-HOLD DEFAULTS TO NOTHING, not to a single mode.
        //
        // "Nothing" is the honest default until the buds report their own state: any
        // preset would guess which modes the user wants, and the mask write (see
        // [GestureModel.holdMask]) is real now — guessing a default membership
        // would send a write nobody asked for. Every other gesture defaults to NONE,
        // so this is also consistent.
        Gesture.TAP_HOLD -> emptyList()
        else -> listOf(GestureAction.NONE)
    }

    fun load(context: Context, side: GestureSide, gesture: Gesture): List<GestureAction> {
        val raw = prefs(context).getString(key(context, side, gesture), null)
            ?: return defaultFor(gesture)
        if (raw.isEmpty()) return emptyList()
        // Unknown names are dropped rather than crashing, so a value written by a
        // future version of this enum cannot brick the screen.
        return raw.split(",").mapNotNull { name ->
            GestureAction.values().firstOrNull { it.name == name }
        }
    }

    fun save(
        context: Context,
        side: GestureSide,
        gesture: Gesture,
        actions: List<GestureAction>
    ) {
        prefs(context).edit()
            .putString(key(context, side, gesture), actions.joinToString(",") { it.name })
            .apply()
    }

    /**
     * Summary of a binding, for the row's right-hand value.
     *
     * Uses the SHORT labels (see GestureAction): this string is a one-line glance at
     * what a gesture does, and up to four actions are joined into it. Full names made
     * it wrap, and a wrapped value made the row taller than its neighbours.
     */
    fun describe(context: Context, actions: List<GestureAction>): String = when {
        actions.isEmpty() -> context.getString(R.string.gesture_not_set)
        else -> actions.joinToString(", ") { context.getString(it.summaryLabelRes()) }
    }

    /**
     * The slot a (side, gesture) binding actually lives in on the buds — NOT restricted to
     * [KeyFunctionParser.BUTTON_PRIMARY]. Mirrors `BudsConnectionManager.writeGestureBinding()`'s
     * own matching rule on purpose: a gesture's button GROUP is not fixed (slide has been seen
     * split across `btn 0x02`/`0x03` on one bud and inside `btn 0x01` on the other, in the same
     * capture — PROTOCOL.md §6), so reading has to use the same "any group but on-call" rule the
     * write does, or the two could disagree about where a binding lives.
     */
    private fun deviceEntryFor(
        table: KeyFunctionParser.Table,
        side: GestureSide,
        action: Int
    ): KeyFunctionParser.Entry? = table.entries.firstOrNull {
        it.deviceType == side.deviceType && it.action == action &&
            it.button != KeyFunctionParser.BUTTON_ON_CALL
    }

    /**
     * Overwrites the LOCAL record from the buds' own table — `[USER]` 2026-09-22. Called by
     * `BudsConnectionManager` after every `0x8108` read (every connect, and after any write's
     * verify-read), so this screen shows what the buds are actually doing rather than whatever
     * we last wrote ourselves. Without this, a binding changed by HeyMelody, another phone, or a
     * PC tool while we were disconnected would keep showing our stale local guess indefinitely —
     * the exact gap he asked to close.
     *
     * TAP_HOLD is skipped here — see [syncHoldFromDevice]: all four ANC actions share one
     * `functionByte` (0x08), so this table alone cannot say which modes are in the cycle.
     *
     * Silently SKIPS a slot rather than guessing when the device's `function` byte is not one
     * this gesture's own vocabulary offers ([GestureModel.rows]) — e.g. a stray value from firmware or a
     * future vendor feature. Storing an action the UI would never have offered is worse than
     * leaving the old (possibly also-stale) local value in place.
     */
    fun syncFromDevice(context: Context, table: KeyFunctionParser.Table) {
        val model = GestureModel.of(context)
        for (side in GestureSide.values()) {
            for ((gesture, options) in model.rows) {
                if (gesture == Gesture.TAP_HOLD) continue
                val entry = deviceEntryFor(table, side, gesture.keyFnAction) ?: continue
                val action = options.firstOrNull { model.functionByte(it) == entry.function }
                    ?: continue
                save(context, side, gesture, listOf(action))
            }
        }
    }

    /**
     * Hold's local record needs BOTH signals together: whether it is bound at all (the
     * key-function `fn`, `0x08` vs `0x00`) and, if bound, which modes (the separate cycle mask —
     * see [GestureModel.holdMask] and PROTOCOL.md §5). Called whenever either one arrives
     * with the other already cached, from either `0x8108` or `0x010C` `02 01` handling in
     * `BudsConnectionManager`.
     *
     * An unbound hold (`fn` not `0x08` on either side) syncs to the empty selection, same as
     * [defaultFor]. A bound hold with a mask of `0` is left ALONE rather than synced to empty —
     * that value has never been observed or tested (PROTOCOL.md §5 says so explicitly), so
     * treating it as "no modes" would be a guess, not a reading.
     *
     * A per-bud hold ([GestureModel.perBudHold]) syncs each side from its own entry: the noise
     * cycle with that side's mask, or the other choice its `fn` names.
     */
    fun syncHoldFromDevice(context: Context, table: KeyFunctionParser.Table, masks: Map<Int, Int>) {
        val model = GestureModel.of(context)
        if (model.perBudHold) {
            for (side in GestureSide.values()) {
                val fn = deviceEntryFor(table, side, Gesture.TAP_HOLD.keyFnAction)?.function ?: continue
                val actions = if (fn == 0x08) model.holdActions(masks[model.holdType(side)] ?: continue)
                    else listOfNotNull(model.holdChoiceFor(fn))
                if (actions.isNotEmpty()) save(context, side, Gesture.TAP_HOLD, actions)
            }
            return
        }
        val bound = GestureSide.values().any {
            deviceEntryFor(table, it, Gesture.TAP_HOLD.keyFnAction)?.function == 0x08
        }
        if (!bound) {
            save(context, GestureSide.LEFT, Gesture.TAP_HOLD, emptyList())
            return
        }
        val actions = model.holdActions(masks[OpoProtocol.HOLD_TYPE_SHARED] ?: return)
        if (actions.isNotEmpty()) save(context, GestureSide.LEFT, Gesture.TAP_HOLD, actions)
    }
}

/**
 * On-call gestures — `btn 0x06`, `[CAPTURE]` 2026-09-22 (see
 * [com.spizganed.quickbuds.protocol.KeyFunctionParser.BUTTON_ON_CALL] for the full capture
 * evidence and PROTOCOL.md §6).
 *
 * DELIBERATELY NOT A [Gesture]/[GestureAction] PAIR. Those model a per-bud choice from a
 * list, written as a full-table diff — neither fits here: on-call is SHARED between both
 * buds (one write, `deviceType 0x04`, covers both), and each row is a plain on/off, not a
 * choice among several actions.
 *
 * [serviceRow] is the string [com.spizganed.quickbuds.bluetooth.BudsService.EXTRA_ON_CALL_ROW]
 * expects — kept as a small string rather than reusing this enum's `name` so the wire value
 * does not silently change if this enum is ever renamed.
 *
 * [act]/[enabledFn] are [OpoProtocol]'s own named constants, not re-typed literals — carried here
 * so [OnCallConfigStore.syncFromDevice] can reverse-map a device reading back to a row without a
 * second copy of these numbers drifting from the write side.
 */
enum class OnCallGesture(
    val rowLabelRes: Int,
    val enabledLabelRes: Int,
    val serviceRow: String,
    val act: Int,
    val enabledFn: Int,
    /** `[VENDOR]` The model list's `callControl` action for this row. */
    val callAction: Int,
    val button: Int = KeyFunctionParser.BUTTON_ON_CALL
) {
    /** `[VENDOR]` callControl 32: single tap answers / ends (Buds Pro 3, Enco X3). */
    SINGLE_TAP(
        R.string.gesture_single, R.string.gesture_on_call_answer_end, "single_tap",
        OpoProtocol.ON_CALL_ACT_SINGLE_TAP, OpoProtocol.ON_CALL_FN_ANSWER_END, 32
    ),
    DOUBLE_TAP(
        R.string.gesture_on_call_double_tap, R.string.gesture_on_call_answer_end, "double_tap",
        OpoProtocol.ON_CALL_ACT_DOUBLE_TAP, OpoProtocol.ON_CALL_FN_ANSWER_END, 29
    ),
    /** `[VENDOR]` callControl 33: double tap declines, where single tap answers. */
    DOUBLE_TAP_DECLINE(
        R.string.gesture_on_call_double_tap, R.string.gesture_on_call_decline, "double_tap_decline",
        OpoProtocol.ON_CALL_ACT_DOUBLE_TAP, OpoProtocol.ON_CALL_FN_DECLINE, 33
    ),
    LONG_HOLD(
        R.string.gesture_on_call_long_hold, R.string.gesture_on_call_decline, "long_hold",
        OpoProtocol.ON_CALL_ACT_LONG_HOLD, OpoProtocol.ON_CALL_FN_DECLINE, 31
    ),
    /**
     * `[VENDOR]` realme Link (issue #8): holding both buds, `04 01 04 <fn>`, game mode or none.
     * Not an on-call row (shown with the gestures, `"bothHold":1`); read back from its own
     * `dev 04` entry, so it is never confused with a bud's hold (`dev 01` / `02`, same act).
     */
    BOTH_HOLD(
        R.string.gesture_both_hold, R.string.gesture_action_game, "both_hold",
        0x04, GestureAction.GAME_MODE.functionByte, -1, KeyFunctionParser.BUTTON_PRIMARY
    )
}

/** Phone-side record of the on-call switches, same prefs file as [GestureConfigStore]. */
object OnCallConfigStore {

    private fun prefs(context: Context) =
        context.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(gesture: OnCallGesture) = "on_call_${gesture.name}"

    fun isEnabled(context: Context, gesture: OnCallGesture): Boolean =
        prefs(context).getBoolean(key(gesture), false)

    fun setEnabled(context: Context, gesture: OnCallGesture, enabled: Boolean) {
        prefs(context).edit().putBoolean(key(gesture), enabled).apply()
    }

    /**
     * Overwrites the LOCAL record from the buds' own table — `[USER]` 2026-09-22, same reasoning
     * and call sites as [GestureConfigStore.syncFromDevice]. Both on-call rows mirror to
     * `dev=0x01` AND `dev=0x02` identically (PROTOCOL.md §6), so either side's entry is enough;
     * this reads `dev=0x01`.
     *
     * Skips a row rather than guessing when the device's `fn` is neither `0x00` nor this row's
     * own [OnCallGesture.enabledFn] — e.g. `act 0x03`, the still-unexplained third on-call slot,
     * must never be misread as one of these two rows.
     */
    fun syncFromDevice(context: Context, table: KeyFunctionParser.Table) {
        for (gesture in OnCallGesture.values()) {
            val entry = table.entries.firstOrNull {
                it.button == gesture.button && it.action == gesture.act &&
                    (gesture != OnCallGesture.BOTH_HOLD || it.deviceType == KeyFunctionParser.DEVICE_TYPE_BOTH)
            } ?: continue
            when (entry.function) {
                0x00 -> setEnabled(context, gesture, false)
                gesture.enabledFn -> setEnabled(context, gesture, true)
                else -> {} // unrecognized fn for this row - leave the local value alone
            }
        }
    }
}
