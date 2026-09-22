package com.spizganed.quickbuds.ui

import android.content.Context
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.KeyFunctionParser
import com.spizganed.quickbuds.protocol.OpoProtocol

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
 * `[CAPTURE]` 2026-09-22 — see [GestureAction.holdMaskBit] and PROTOCOL.md §5), with `0x010C`
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
    val keyFnAction: Int = 0
) {
    SINGLE_TAP(R.string.gesture_single, intArrayOf(0x00), false, keyFnAction = 0x01),
    DOUBLE_TAP(R.string.gesture_double, intArrayOf(0x02), false, keyFnAction = 0x02),
    TRIPLE_TAP(R.string.gesture_triple, intArrayOf(0x03), false, keyFnAction = 0x03),

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
    SLIDE(R.string.gesture_slide, intArrayOf(0x07, 0x08), false, keyFnAction = 0x05),

    TAP_HOLD(R.string.gesture_hold, intArrayOf(0x04), true, keyFnAction = 0x04)
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
     * twice, on two different slots). See PROTOCOL.md §6.
     */
    val functionByte: Int = 0x00,
    /**
     * The bit this action sets in [com.spizganed.quickbuds.protocol.OpoProtocol.setHoldAncModes]'s
     * mask, or -1 for every action that is not an ANC mode. `[CAPTURE]` — see
     * [OpoProtocol.HOLD_MASK_BIT_OFF] etc. and PROTOCOL.md §5.
     */
    val holdMaskBit: Int = -1
) {
    NONE(R.string.gesture_action_none),
    PLAY_PAUSE(R.string.gesture_action_play_pause, functionByte = 0x01),
    PREV_TRACK(R.string.gesture_action_prev, functionByte = 0x05),
    NEXT_TRACK(R.string.gesture_action_next, functionByte = 0x06),
    VOICE_ASSISTANT(R.string.gesture_action_assistant, functionByte = 0x03),
    GAME_MODE(R.string.gesture_action_game, functionByte = 0x11),
    VOLUME(R.string.gesture_action_volume, functionByte = 0x07),
    SWITCH_TRACK(R.string.gesture_action_switch_track, functionByte = 0x0A),

    // ALL FOUR ANC ACTIONS SHARE THE SAME `functionByte` (0x08), and that is not a mistake.
    //
    // The device models the hold as ONE key-function — "ANC cycle" — not as a set of modes.
    // He once set his hold to just "adaptive + ANC off" (TWO modes) and the byte stayed
    // 0x08; another time, same byte, the same gesture cycled FOUR modes. So the cycle's
    // MEMBERSHIP was never stored in this byte — it lives in a DIFFERENT command,
    // `setSupportNoiseReduction` (`0x0404` action `02`), confirmed `[CAPTURE]` 2026-09-22
    // (PROTOCOL.md §5). [holdMaskBit] is that command's bit for each mode, and
    // `GestureActivity.writeToBuds()` sends BOTH writes for the hold: the key-function bind
    // (`functionByte`, bind/unbind the gesture to "cycles ANC" at all) and the mask
    // (`holdMaskBit`, which modes it cycles through) — two separate commands on purpose, so
    // a failure in either is attributable, never merged into one "save".
    ANC_ON(R.string.gesture_action_anc_on, functionByte = 0x08, holdMaskBit = 1),
    ANC_ADAPTIVE(
        R.string.gesture_action_anc_adaptive,
        R.string.gesture_action_anc_adaptive_short,
        functionByte = 0x08,
        holdMaskBit = 11
    ),
    ANC_TRANSPARENCY(
        R.string.gesture_action_anc_transparency,
        R.string.gesture_action_anc_transparency_short,
        functionByte = 0x08,
        holdMaskBit = 2
    ),
    ANC_OFF(
        R.string.gesture_action_anc_off,
        R.string.gesture_action_anc_off_short,
        functionByte = 0x08,
        holdMaskBit = 0
    );

    /** Label for the row summary — the short form when one is defined. */
    fun summaryLabelRes(): Int = if (shortLabelRes != 0) shortLabelRes else labelRes

    companion object {
        /**
         * The byte to WRITE for a whole selection — a list, not one action, because
         * tap-and-hold is multi-select and the collapse described on ANC_ON above
         * happens here.
         *
         * Empty -> `0x00` (unbind). Non-empty -> the FIRST action's byte, which for
         * every gesture except the hold is simply that action (the gestures are
         * single-select, so the list holds one item). For the hold, any non-empty
         * selection is the ANC cycle `0x08`, whichever of the four modes is ticked.
         *
         * Uses `firstOrNull` and not `single()` deliberately: the hold's multi-select
         * can legitimately hold several actions, and a crash on the user's own valid
         * selection would be the worst possible outcome here.
         */
        fun functionByteFor(actions: List<GestureAction>): Int =
            actions.firstOrNull()?.functionByte ?: 0x00

        /**
         * The hold's ANC-cycle mask for a whole selection. Only meaningful for
         * [Gesture.TAP_HOLD]'s selection — every other gesture's actions all have
         * [holdMaskBit] `-1` and contribute nothing.
         */
        fun holdMaskFor(actions: List<GestureAction>): Int =
            actions.fold(0) { acc, a -> if (a.holdMaskBit >= 0) acc or (1 shl a.holdMaskBit) else acc }
    }
}

/**
 * Which actions each gesture may be bound to, exactly as specified.
 *
 * Kept as a function rather than a field on the enum so the vocabulary and the
 * permitted pairings stay separate: adding an action does not mean adding it to
 * every gesture, and the allowed set is the part a user can see.
 */
fun actionsFor(gesture: Gesture): List<GestureAction> = when (gesture) {
    Gesture.SINGLE_TAP -> listOf(GestureAction.NONE, GestureAction.PLAY_PAUSE)

    Gesture.DOUBLE_TAP -> listOf(
        GestureAction.NONE, GestureAction.PLAY_PAUSE, GestureAction.PREV_TRACK,
        GestureAction.NEXT_TRACK, GestureAction.VOICE_ASSISTANT, GestureAction.GAME_MODE
    )

    Gesture.TRIPLE_TAP -> listOf(
        GestureAction.NONE, GestureAction.PREV_TRACK, GestureAction.NEXT_TRACK,
        GestureAction.VOICE_ASSISTANT, GestureAction.GAME_MODE
    )

    Gesture.SLIDE -> listOf(
        GestureAction.NONE, GestureAction.VOLUME, GestureAction.SWITCH_TRACK
    )

    // Tap-and-hold: the ANC modes, and nothing else.
    //
    // THE SELECTION HERE IS NOT WHAT GETS WRITTEN, and that is deliberate rather than
    // unfinished. All four actions share ONE function byte (0x08), because the device
    // models the hold as a single "ANC cycle" whose MEMBERSHIP lives in the firmware and
    // is not part of this protocol: he once set the hold to two modes and once to four,
    // and the byte was 0x08 both times. So this list is what the user picks from, and
    // `GestureAction.functionByteFor` collapses any non-empty selection to that one byte.
    //
    // The list is kept rather than replaced by a single on/off switch because it is how
    // the vendor app presents the hold, and because collapsing the UI too would silently
    // discard the user's intent — see the note in `functionByteFor`. If he prefers a plain
    // on/off, that is a UI change he decides, not one to sneak in here.
    //
    // THIS VOCABULARY IS NO LONGER JUST HIS SPEC — it matches what the vendor app itself
    // allows. [USER] 2026-09-21: on the hold it offers the ANC modes and nothing else in
    // exactly this order, and GAME_MODE is offered on double and triple tap only. So the
    // constraint is the DEVICE's, not a preference of ours, and it must not be widened to
    // "offer everything everywhere".
    Gesture.TAP_HOLD -> listOf(
        GestureAction.ANC_ON, GestureAction.ANC_ADAPTIVE,
        GestureAction.ANC_TRANSPARENCY, GestureAction.ANC_OFF
    )
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
    private fun key(side: GestureSide, gesture: Gesture) =
        if (gesture == Gesture.TAP_HOLD) "gesture_SHARED_${gesture.name}"
        else "gesture_${side.name}_${gesture.name}"

    /** What a gesture does when the user has never opened this screen. */
    fun defaultFor(gesture: Gesture): List<GestureAction> = when (gesture) {
        // TAP-AND-HOLD DEFAULTS TO NOTHING, not to a single mode.
        //
        // It used to default to ANC alone, which the none-or-at-least-two rule now
        // forbids — a one-item cycle is not a cycle. Of the two valid options
        // (nothing, or a real cycle) "nothing" is the honest default: a two-mode
        // cycle would guess which modes the user wants, and the mask write (see
        // [GestureAction.holdMaskBit]) is real now — guessing a default membership
        // would send a write nobody asked for. Every other gesture defaults to NONE,
        // so this is also consistent.
        Gesture.TAP_HOLD -> emptyList()
        else -> listOf(GestureAction.NONE)
    }

    fun load(context: Context, side: GestureSide, gesture: Gesture): List<GestureAction> {
        val raw = prefs(context).getString(key(side, gesture), null)
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
            .putString(key(side, gesture), actions.joinToString(",") { it.name })
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
     * this gesture's own vocabulary offers ([actionsFor]) — e.g. a stray value from firmware or a
     * future vendor feature. Storing an action the UI would never have offered is worse than
     * leaving the old (possibly also-stale) local value in place.
     */
    fun syncFromDevice(context: Context, table: KeyFunctionParser.Table) {
        for (side in GestureSide.values()) {
            for (gesture in Gesture.values()) {
                if (gesture == Gesture.TAP_HOLD) continue
                val entry = deviceEntryFor(table, side, gesture.keyFnAction) ?: continue
                val action = actionsFor(gesture).firstOrNull { it.functionByte == entry.function }
                    ?: continue
                save(context, side, gesture, listOf(action))
            }
        }
    }

    /**
     * Hold's local record needs BOTH signals together: whether it is bound at all (the
     * key-function `fn`, `0x08` vs `0x00`) and, if bound, which modes (the separate cycle mask —
     * see [GestureAction.holdMaskBit] and PROTOCOL.md §5). Called whenever either one arrives
     * with the other already cached, from either `0x8108` or `0x010C` `02 01` handling in
     * `BudsConnectionManager`.
     *
     * An unbound hold (`fn` not `0x08` on either side) syncs to the empty selection, same as
     * [defaultFor]. A bound hold with a mask of `0` is left ALONE rather than synced to empty —
     * that value has never been observed or tested (PROTOCOL.md §5 says so explicitly), so
     * treating it as "no modes" would be a guess, not a reading.
     */
    fun syncHoldFromDevice(context: Context, table: KeyFunctionParser.Table, mask: Int) {
        val bound = GestureSide.values().any {
            deviceEntryFor(table, it, Gesture.TAP_HOLD.keyFnAction)?.function == 0x08
        }
        if (!bound) {
            save(context, GestureSide.LEFT, Gesture.TAP_HOLD, emptyList())
            return
        }
        val actions = GestureAction.values().filter {
            it.holdMaskBit >= 0 && (mask shr it.holdMaskBit) and 1 == 1
        }
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
    val enabledFn: Int
) {
    DOUBLE_TAP(
        R.string.gesture_on_call_double_tap, R.string.gesture_on_call_answer_end, "double_tap",
        OpoProtocol.ON_CALL_ACT_DOUBLE_TAP, OpoProtocol.ON_CALL_FN_ANSWER_END
    ),
    LONG_HOLD(
        R.string.gesture_on_call_long_hold, R.string.gesture_on_call_decline, "long_hold",
        OpoProtocol.ON_CALL_ACT_LONG_HOLD, OpoProtocol.ON_CALL_FN_DECLINE
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
                it.button == KeyFunctionParser.BUTTON_ON_CALL && it.action == gesture.act
            } ?: continue
            when (entry.function) {
                0x00 -> setEnabled(context, gesture, false)
                gesture.enabledFn -> setEnabled(context, gesture, true)
                else -> {} // unrecognized fn for this row - leave the local value alone
            }
        }
    }
}
