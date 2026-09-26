package com.spizganed.quickbuds.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.BudsService

/**
 * Earbud controls — bind a gesture to an action, per bud.
 *
 * LAYOUT, top to bottom (as specified):
 *   1. the selected bud's icon
 *   2. a Left / Right selector — the two buds are configured separately, because
 *      they can be bound to different actions
 *   3. "When not on call", then one row per gesture
 *   4. "When on call", two rows (double tap / long hold) — added 2026-09-22, see
 *      [OnCallGesture]. UNLIKE EVERYTHING ABOVE, this section has no Left/Right
 *      reach: it is one shared setting for both buds (`[USER]`-confirmed), so the
 *      side selector at the top does not affect it.
 *
 * Tapping a gesture row opens a bottom dialog listing the actions that gesture
 * may be bound to. Tap-and-hold accepts MORE THAN ONE, which makes the gesture
 * cycle through them on each use; every other gesture takes exactly one. The
 * on-call rows are a plain two-item None/action dialog — see [showOnCallDialog].
 *
 * THE SELECTIONS ARE WRITTEN TO THE BUDS. Each save sends a `0x0401` setKeyFunction
 * write (see BudsConnectionManager.writeGestureBinding), then reads the table back
 * and diffs it, because a wrong command number fails in complete silence. The
 * `function` bytes in [GestureAction] are MEASURED, not guessed — see [GestureConfig]
 * and PROTOCOL.md §6. The on-call writes ([OnCallGesture]) are a single small entry
 * instead of a full-table rewrite — see BudsConnectionManager.sendOnCallDoubleTap().
 */
class GestureActivity : Activity() {

    private var side: GestureSide = GestureSide.LEFT

    private lateinit var btnLeft: Button
    private lateinit var btnRight: Button
    private lateinit var gestureList: LinearLayout
    private lateinit var onCallList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate — see ThemeRes.select.
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)

        val dp = { v: Float -> ThemeRes.dp(this, v) }
        val root = SettingRowFactory.screen(this)

        root.addView(SettingRowFactory.title(this, R.string.gesture_title))

        // --- 2. Left / Right selector ---
        //
        // Two equal buttons rather than a segmented control: the project has no
        // segmented widget and the chip drawables already exist, so this matches
        // the Dev Tools tab row exactly. Selection is shown by the active chip
        // drawable, the same language updateTabButtons() uses.
        val sideRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnLeft = sideButton(R.string.gesture_bud_left) { selectSide(GestureSide.LEFT) }
        btnRight = sideButton(R.string.gesture_bud_right) { selectSide(GestureSide.RIGHT) }
        sideRow.addView(btnLeft)
        sideRow.addView(btnRight)
        root.addView(sideRow)

        // --- 3. Gesture list ---
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        column.addView(SettingRowFactory.sectionLabel(this, R.string.gesture_section_not_in_call))

        gestureList = SettingRowFactory.card(this)
        column.addView(gestureList)

        // --- On-call gestures, BELOW the normal list ---
        //
        // No Left/Right selector reads into this card — `[USER]`-confirmed 2026-09-22,
        // these two rows are ONE shared setting for both buds, unlike everything above.
        // See PROTOCOL.md §6 and [OnCallGesture].
        column.addView(SettingRowFactory.sectionLabel(this, R.string.gesture_section_on_call))

        onCallList = SettingRowFactory.card(this)
        column.addView(onCallList)

        // The status note. Deliberately below the list and in secondary colour: it
        // is information, not an error state, and it must not look like a warning
        // that something failed. It states what actually happens now — a write to the
        // buds, read back to confirm — rather than the old "phone only", which the
        // measured enum made untrue.
        column.addView(TextView(this).apply {
            setText(R.string.gesture_write_note)
            setTextColor(ThemeRes.color(this@GestureActivity, R.attr.appColorTextSecondary))
            textSize = 12f
            setPadding(dp(4f), dp(16f), dp(4f), dp(8f))
        })

        scroll.addView(column)
        root.addView(scroll)

        setContentView(root)
        // No render() here: onResume() always follows onCreate() in the Activity lifecycle and
        // does it below — calling it twice on first open would be redundant.
    }

    /**
     * Repaints on every return to this screen, not just on create. `[USER]` 2026-09-22: the
     * local record this screen reads ([GestureConfigStore]/[OnCallConfigStore]) is now kept in
     * sync with the buds' own table on every connect (see `BudsConnectionManager`'s `0x8108`/
     * `0x010C` handling), but only `onResume` — never `onCreate` alone — catches a sync that
     * happened while this Activity was merely backgrounded (e.g. a reconnect while the user was
     * on another screen), since Android does not recreate an Activity just for that.
     */
    override fun onResume() {
        super.onResume()
        render()
    }

    /** A Left/Right chip. Equal width via weight, so neither label can clip. */
    private fun sideButton(labelRes: Int, onClick: () -> Unit): Button {
        val dp = { v: Float -> ThemeRes.dp(this, v) }
        return Button(this).apply {
            setText(labelRes)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(4f); marginEnd = dp(4f) }
            minWidth = 0
            minHeight = dp(42f)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onClick() }
        }
    }

    private fun selectSide(newSide: GestureSide) {
        side = newSide
        render()
    }

    /**
     * Sends ONE gesture's new binding to the buds.
     *
     * Goes through BudsService rather than binding it here, which is the same pattern
     * Dev Tools uses for Reconnect/Disconnect: the service already owns the connection
     * and the manager, and an Activity that bound the service itself would need its own
     * lifecycle handling for no gain.
     *
     * This is fire-and-forget on purpose. The write is confirmed by the SERVICE re-reading
     * the table and logging the resulting diff (`KEYFN DIFF:`), not by a callback into
     * this screen — the write crosses a socket to a device that may be asleep, and a UI
     * that waited for it would either lie about success or stall. If no table has been read
     * yet the manager REFUSES the write and says so in the log, rather than inventing the
     * other entries; that refusal is deliberate, not a silent no-op.
     *
     * WHICH BUTTON GROUPS ARE WRITTEN is NOT decided here or in [Gesture]. The manager reads
     * the buds' own table and writes every slot that bud actually has for this action, which
     * is the only rule that survives what the hardware does: slide has been seen inside
     * `btn 0x01` on one bud and split across `btn 0x02`/`0x03` on the other, in the SAME
     * capture. Both a hardcoded `0x01` and a hardcoded `0x02,0x03` are wrong half the time,
     * and each failure is silent — which is exactly how slide looked "broken but sometimes
     * working" for a session. See BudsConnectionManager.writeGestureBinding().
     *
     * THE HOLD'S EMPTY CASE SENDS NOTHING AT ALL, and that is deliberate too. Measured on
     * the device: clearing the hold's function byte to `0x00` does NOT stop the ANC cycle —
     * the very next long press still changed the noise mode. So writing `0x00` cannot turn
     * the gesture off; all it does is make the buds' stored table disagree with what the
     * gesture actually does. Sending nothing keeps the table honest. The mask write below
     * could in principle be sent with an empty mask, but that value has never been tried on
     * the device — only ADDING a bit to a non-empty mask is `[CAPTURE]`-confirmed
     * (PROTOCOL.md §5) — so an empty selection sends neither write rather than guessing what
     * an all-zero mask does.
     *
     * THE HOLD'S NON-EMPTY CASE SENDS THREE WRITES, ON PURPOSE. The key-function write binds the
     * gesture to "cycles ANC" at all — sent for BOTH [GestureSide.LEFT] and [GestureSide.RIGHT],
     * unlike every other gesture, because `[USER]`-confirmed 2026-09-22 the hold is a SHARED control
     * (PROTOCOL.md §5.1) and every capture has shown both buds' key-function slots holding the same
     * `fn`. Writing only the currently-selected side would leave the OTHER bud's table entry
     * disagreeing with what the cycle actually does on it. A third write, to
     * [BudsService.ACTION_SET_HOLD_MODES], carries WHICH modes are in the cycle
     * ([GestureAction.holdMaskFor], `setSupportNoiseReduction`, `[CAPTURE]` 2026-09-22) — that
     * command has no per-side concept at all, so there is only ever one of it. Three separate
     * commands, kept separate here too, so a failure in any one is attributable — see
     * [GestureConfigStore] and PROTOCOL.md §5.
     */
    private fun writeToBuds(gesture: Gesture, actions: List<GestureAction>) {
        if (gesture == Gesture.TAP_HOLD && actions.isEmpty()) {
            // Nothing to send: `0x00` does not disable the cycle, so writing it would only
            // corrupt the table's description of a gesture that still works.
            return
        }

        val sides = if (gesture == Gesture.TAP_HOLD) GestureSide.values().toList() else listOf(side)
        for (s in sides) {
            val intent = Intent(this, BudsService::class.java).apply {
                action = BudsService.ACTION_SET_GESTURE
                putExtra(BudsService.EXTRA_GESTURE_DEVICE, s.deviceType)
                putExtra(BudsService.EXTRA_GESTURE_ACTION, gesture.keyFnAction)
                putExtra(BudsService.EXTRA_GESTURE_FUNCTION, GestureAction.functionByteFor(actions))
            }
            startService(intent)
        }

        if (gesture == Gesture.TAP_HOLD) {
            val maskIntent = Intent(this, BudsService::class.java).apply {
                action = BudsService.ACTION_SET_HOLD_MODES
                putExtra(BudsService.EXTRA_HOLD_MASK, GestureAction.holdMaskFor(actions))
            }
            startService(maskIntent)
        }
    }

    /**
     * Repaints the whole screen for the current side.
     *
     * Called on create and on every side switch. Rebuilding rather than mutating
     * in place keeps the two buds' states from bleeding into each other — the
     * whole reason the selector exists is that they differ.
     */
    private fun render() {

        paintSideButton(btnLeft, side == GestureSide.LEFT)
        paintSideButton(btnRight, side == GestureSide.RIGHT)

        gestureList.removeAllViews()
        val gestures = Gesture.values()
        for ((index, gesture) in gestures.withIndex()) {
            if (index > 0) gestureList.addView(SettingRowFactory.buildDivider(this))
            gestureList.addView(buildGestureRow(gesture))
        }

        // Rebuilt on every render() too, same as gestureList above, even though it does
        // not depend on `side` — one repaint path is simpler than tracking which parts of
        // the screen a side switch actually touches.
        onCallList.removeAllViews()
        val onCallGestures = OnCallGesture.values()
        for ((index, gesture) in onCallGestures.withIndex()) {
            if (index > 0) onCallList.addView(SettingRowFactory.buildDivider(this))
            onCallList.addView(buildOnCallRow(gesture))
        }
    }

    private fun paintSideButton(button: Button, active: Boolean) {
        button.background = ThemeRes.chip(this, active)
        // Active chips are drawn on the accent fill, so the label has to be the
        // on-accent colour, not the theme's primary text colour — reading
        // appColorTextPrimary here would put dark text on a dark fill in OLED.
        button.setTextColor(
            if (active) ThemeRes.palette(this).onAccent
            else ThemeRes.color(this, R.attr.appColorTextPrimary)
        )
    }

    /**
     * One gesture row: name on the left, current binding on the right, chevron.
     *
     * Reuses SettingRowFactory so a row looks identical to the main screen's rows
     * (same 56dp height, same 14dp inset, same chevron). A second row style would
     * be a second thing to keep in sync.
     */
    private fun buildGestureRow(gesture: Gesture): View {
        val current = GestureConfigStore.load(this, side, gesture)
        val summary = GestureConfigStore.describe(this, current)


        // NO SUBTITLE, on any gesture row.
        //
        // The hold row used to carry "cycles through the actions you pick". It was
        // removed because it made that row TALLER than the others and pushed the
        // binding value onto an extra line — so the row grew to fit its own
        // explanation and stood out as a different size. The behaviour is
        // discoverable without it (the rule explains itself when it is broken), and
        // every row now has the same shape.
        val row = SettingRowFactory.build(
            this,
            iconFor(gesture),
            gesture.labelRes,
            0,
            SettingRowFactory.buildChevron(this),
            SettingRowFactory.buildValue(this, summary)
        ) { showActionDialog(gesture) }

        return row
    }

    /**
     * One on-call row. Same [SettingRowFactory] shape as [buildGestureRow] so the section
     * does not look like a second, different screen — but the value shown and the dialog
     * behind it are a plain on/off, not a multi-option picker, because that is all HeyMelody
     * itself offers here (`[USER]`, PROTOCOL.md §6).
     */
    private fun buildOnCallRow(gesture: OnCallGesture): View {
        val enabled = OnCallConfigStore.isEnabled(this, gesture)
        val summary = getString(if (enabled) gesture.enabledLabelRes else R.string.gesture_action_none)


        return SettingRowFactory.build(
            this,
            R.drawable.ic_bolt,
            gesture.rowLabelRes,
            0,
            SettingRowFactory.buildChevron(this),
            SettingRowFactory.buildValue(this, summary)
        ) { showOnCallDialog(gesture) }
    }

    /** Two-item sheet (None / the one action HeyMelody offers this row), single-select. */
    private fun showOnCallDialog(gesture: OnCallGesture) {
        val enabled = OnCallConfigStore.isEnabled(this, gesture)
        BottomSheetDialog(this)
            .title(getString(gesture.rowLabelRes))
            .items(
                listOf(
                    BottomSheetDialog.Item(
                        label = getString(R.string.gesture_action_none),
                        selected = !enabled,
                        onClick = { setOnCall(gesture, false) }
                    ),
                    BottomSheetDialog.Item(
                        label = getString(gesture.enabledLabelRes),
                        selected = enabled,
                        onClick = { setOnCall(gesture, true) }
                    )
                )
            )
            .show()
    }

    /**
     * Persists and sends ONE on-call row. Same fire-and-forget shape as [writeToBuds]: the
     * service re-reads `0x8108` and logs the diff, this screen does not wait on it.
     */
    private fun setOnCall(gesture: OnCallGesture, enabled: Boolean) {
        OnCallConfigStore.setEnabled(this, gesture, enabled)
        val intent = Intent(this, BudsService::class.java).apply {
            action = BudsService.ACTION_SET_ON_CALL
            putExtra(BudsService.EXTRA_ON_CALL_ROW, gesture.serviceRow)
            putExtra(BudsService.EXTRA_ON_CALL_ENABLED, enabled)
        }
        startService(intent)
        render()
    }

    /**
     * Icon per gesture. Slide and hold still borrow existing drawables.
     *
     * One dot per tap (`[USER]` 2026-09-25) — the same dot, so the three still read as
     * variants of one gesture. They were all a single dot before, which could not tell them apart.
     */
    private fun iconFor(gesture: Gesture): Int = when (gesture) {
        Gesture.SINGLE_TAP -> R.drawable.ic_tap_single
        Gesture.DOUBLE_TAP -> R.drawable.ic_tap_double
        Gesture.TRIPLE_TAP -> R.drawable.ic_tap_triple
        Gesture.SLIDE -> R.drawable.ic_chevron_right
        Gesture.TAP_HOLD -> R.drawable.ic_bolt
    }

    /**
     * Bottom sheet listing the actions this gesture may take.
     *
     * SINGLE-SELECT gestures commit on tap and the sheet closes. TAP-AND-HOLD is
     * multi-select ([dismissOnSelect] false): tapping toggles and the sheet stays
     * open with a Done button, because "add or remove several" cannot work if the
     * first tap dismisses it.
     *
     * THE TAP-AND-HOLD RULE — at least one — matches HeyMelody (`[USER]` screenshot
     * 2026-09-23): any single mode is allowed, the last ticked mode cannot be unticked,
     * and with exactly one ticked the sheet shows an info note that the hold will not
     * switch modes. Done with an empty selection (only reachable from a never-set hold)
     * just closes without writing.
     */
    private fun showActionDialog(gesture: Gesture) {
        val options = actionsFor(gesture)
        val selected = GestureConfigStore.load(this, side, gesture).toMutableList()

        if (!gesture.multiSelect) {
            BottomSheetDialog(this)
                .title(getString(gesture.labelRes))
                .items(options.map { action ->
                    BottomSheetDialog.Item(
                        label = getString(action.labelRes),
                        selected = selected.contains(action),
                        onClick = {
                            GestureConfigStore.save(this, side, gesture, listOf(action))
                            writeToBuds(gesture, listOf(action))
                            render()
                        }
                    )
                })
                .show()
            return
        }

        // ONE sheet, updated in place. Toggling rebuilds only the rows and the
        // message, so the sheet does not stack or flicker on every tap.
        val working = options.filter { selected.contains(it) }.toMutableList()

        val sheet = BottomSheetDialog(this)
        sheet.title(getString(gesture.labelRes))
            .dismissOnSelect(false)
            .confirm(getString(R.string.gesture_done)) {
                if (working.isNotEmpty()) {
                    // Persist in the declared order, not tap order, so the cycle is
                    // deterministic and matches the list the user just saw.
                    val ordered = options.filter { working.contains(it) }
                    GestureConfigStore.save(this, side, gesture, ordered)
                    writeToBuds(gesture, ordered)
                }
                sheet.close()
                render()
            }

        fun refresh() {
            sheet.items(options.map { action ->
                BottomSheetDialog.Item(
                    label = getString(action.labelRes),
                    selected = working.contains(action),
                    onClick = {
                        // The last ticked mode cannot be unticked — minimum one, as HeyMelody.
                        if (working.contains(action)) { if (working.size > 1) working.remove(action) }
                        else working.add(action)
                        refresh()
                    }
                )
            })
            sheet.message(if (working.size == 1) getString(R.string.gesture_hold_rule) else null)
        }

        refresh()
        sheet.show()
    }
}
