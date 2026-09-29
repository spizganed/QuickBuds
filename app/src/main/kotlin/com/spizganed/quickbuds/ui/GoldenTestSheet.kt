package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.GoldenSound
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.widget.WidgetStateStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * The Golden Sound hearing test, a bottom sheet (HeyMelody's flow, PROTOCOL.md §9):
 * ear scan (models with one), then a tone per frequency, left 1..6 then right 1..6, each set with
 * the slider to where it just disappears; then the enhance type is read, the record saved and applied.
 * Music or a bud coming out (status event `0x08`) stops the test; closing the sheet stops it too.
 */
class GoldenTestSheet(
    private val activity: Activity,
    private val scan: Boolean,
    private val send: (Array<ByteArray>) -> Unit,
    private val onSaved: (GoldenSound.Record) -> Unit
) {
    private enum class State { IDLE, SCAN, TEST, FINISH, DONE }

    private val sheet = BottomSheetDialog(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var uid = 0
    private var step = 0
    private var scanData = ByteArray(0)
    private val values = IntArray(12)
    private var warnedLoud = false
    private var lastTone = 0L
    private val slider = LevelSliderView(activity, 0, GoldenSound.STOPS.size - 1).apply {
        showValue = false
        onChange = { toneSoon() }
        onRelease = { toneSoon() }
    }
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val dp = ThemeRes.dp(activity, 12f)
        setPadding(0, dp, 0, dp)
        visibility = View.GONE
        addView(slider)
    }
    private val scanTimeout = Runnable { if (state == State.SCAN) stop(R.string.golden_scan_failed) }
    private val filterTimeout = Runnable { if (state == State.FINISH) save(0) }
    private val tone = Runnable { playTone() }

    private fun s(res: Int) = activity.getString(res)

    fun show() {
        sheet.title(s(R.string.golden_test_title))
            .message(s(R.string.golden_prepare))
            .content(panel)
            .confirm(s(R.string.golden_start)) { start() }
            .onDismiss {
                handler.removeCallbacksAndMessages(null)
                // Closed while the enhance type was being read: the result is still kept.
                if (state == State.FINISH) save(0) else abort()
            }
            .show()
    }

    private fun start() {
        val st = WidgetStateStore.read(activity)
        if (listOf(st.leftStatus, st.rightStatus).any { it != 3 && it != 7 }) {
            sheet.title(s(R.string.fit_insert))
            return
        }
        uid = Random.nextInt(1, Int.MAX_VALUE)
        scanData = ByteArray(0)
        if (scan) {
            state = State.SCAN
            sheet.title(s(R.string.golden_scanning)).message(s(R.string.golden_scan_hint)).confirm("…") {}
            send(arrayOf(OpoProtocol.earScan(true, uid)))
            handler.postDelayed(scanTimeout, 15_000)
        } else {
            send(arrayOf(OpoProtocol.hearingTest(true)))
            begin(0)
        }
    }

    /** `0x0204` subType `0x0E`: the scan is done; stop it and start the hearing test. */
    fun earScan(id: Int, data: ByteArray) {
        if (state != State.SCAN || id != uid) return
        handler.removeCallbacks(scanTimeout)
        scanData = data
        send(arrayOf(OpoProtocol.earScan(false, uid), OpoProtocol.hearingTest(true)))
        begin(0)
    }

    private fun begin(i: Int) {
        state = State.TEST
        step = i
        panel.visibility = View.VISIBLE
        slider.value = GoldenSound.START_STOP
        sheet.title(s(if (i < 6) R.string.golden_left else R.string.golden_right))
            .message(activity.getString(R.string.golden_step, i % 6 + 1))
            .confirm(s(if (i < 11) R.string.golden_next else R.string.golden_finish)) { next() }
        playTone()
    }

    private fun next() {
        if (state != State.TEST) return
        values[step] = GoldenSound.snap(GoldenSound.STOPS[slider.value])
        if (step < 11) { begin(step + 1); return }
        state = State.FINISH
        handler.removeCallbacks(tone)
        panel.visibility = View.GONE
        sheet.title(s(R.string.golden_creating)).message(null).confirm("…") {}
        send(arrayOf(OpoProtocol.hearingToneStop(), OpoProtocol.hearingTest(false), OpoProtocol.hearingFilter(uid, values)))
        handler.postDelayed(filterTimeout, 3_000)
    }

    /** `0x8116`: the result's enhance type, which picks the stored description id. */
    fun filter(id: Int, enhanceType: Int) {
        if (state != State.FINISH || id != uid) return
        handler.removeCallbacks(filterTimeout)
        save(GoldenSound.descId(enhanceType))
    }

    private fun save(descId: Int) {
        state = State.DONE
        val name = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date())
        val record = GoldenSound.Record(uid, name, values.copyOf(), scanData, descId)
        GoldenSound.put(activity, record)
        onSaved(record)
        sheet.title(s(R.string.golden_saved)).message(s(R.string.golden_saved_msg))
            .confirm(s(R.string.gesture_done)) { sheet.close() }
    }

    /** At most one tone every 300 ms while dragging, as HeyMelody does; the last position always plays. */
    private fun toneSoon() {
        if (state != State.TEST) return
        handler.removeCallbacks(tone)
        val wait = 300 - (SystemClock.elapsedRealtime() - lastTone)
        if (wait <= 0) playTone() else handler.postDelayed(tone, wait)
    }

    private fun playTone() {
        if (state != State.TEST) return
        lastTone = SystemClock.elapsedRealtime()
        if (slider.value >= GoldenSound.LOUD_STOP && !warnedLoud) {
            warnedLoud = true
            sheet.message(s(R.string.golden_loud))
        }
        send(arrayOf(OpoProtocol.hearingToneStop(),
            OpoProtocol.hearingTone(step / 6 + 1, step % 6 + 1, GoldenSound.STOPS[slider.value])))
    }

    /** Status event `0x08` (kind 2 test, 4 scan): 1 / 3 audio playing, 5 a bud out, 7 / 10 / 255 failed. */
    fun status(kind: Int, status: Int) {
        if (state != State.SCAN && state != State.TEST) return
        when {
            status == 1 || status == 3 -> stop(R.string.golden_stopped_music)
            status == 5 -> stop(R.string.golden_stopped_wear)
            kind == 4 && status in setOf(7, 10, 255) -> stop(R.string.golden_scan_failed)
        }
    }

    private fun stop(reason: Int) {
        abort()
        panel.visibility = View.GONE
        sheet.title(s(R.string.golden_stopped)).message(s(reason)).confirm(s(R.string.golden_again)) { start() }
    }

    /** Stops whatever is running on the buds. */
    private fun abort() {
        handler.removeCallbacks(tone)
        handler.removeCallbacks(scanTimeout)
        when (state) {
            State.SCAN -> send(arrayOf(OpoProtocol.earScan(false, uid)))
            State.TEST -> send(arrayOf(OpoProtocol.hearingToneStop(), OpoProtocol.hearingTest(false)))
            else -> {}
        }
        if (state != State.DONE) state = State.IDLE
    }
}
