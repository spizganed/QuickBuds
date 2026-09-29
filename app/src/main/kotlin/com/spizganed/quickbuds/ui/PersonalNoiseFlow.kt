package com.spizganed.quickbuds.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.widget.WidgetStateStore

/**
 * Turning personalized noise cancellation on, in HeyMelody's order (`[VENDOR]`, PROTOCOL.md §9):
 * both buds worn, then the stored result is offered if the buds hold one, else the ear canal test
 * (`0x0412 01`, a result within 15 s, closing the sheet cancels it). The buds switch `0x0C` on
 * themselves; [onApplied] re-reads it.
 */
class PersonalNoiseFlow(
    private val activity: Activity,
    private val send: (Int) -> Unit,
    private val queryStored: () -> Unit,
    private val onApplied: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { fail(0) }
    private val noReply = Runnable { askTest() }
    private var sheet: BottomSheetDialog? = null
    private var asking = false
    private var testing = false

    private fun s(res: Int) = activity.getString(res)

    private fun worn() = WidgetStateStore.read(activity).let { st ->
        listOf(st.leftStatus, st.rightStatus).all { it == 3 || it == 7 }
    }

    fun start() {
        if (!worn()) {
            Toast.makeText(activity, R.string.pnc_wear, Toast.LENGTH_SHORT).show()
            return
        }
        asking = true
        queryStored()
        handler.postDelayed(noReply, 5_000)   // HeyMelody waits 5 s for the answer
    }

    fun stored(stored: Boolean) {
        if (!asking) return
        handler.removeCallbacks(noReply)
        if (!stored) return askTest()
        asking = false
        ConfirmDialog.show(activity, s(R.string.pnc_stored_title), s(R.string.pnc_stored_body), s(R.string.pnc_use),
            cancelRes = R.string.pnc_test_again, onCancel = { test() }) {
            send(OpoProtocol.PERSONAL_NOISE_USE_STORED)
            handler.postDelayed({ onApplied() }, 600)
        }
    }

    private fun askTest() {
        asking = false
        ConfirmDialog.show(activity, s(R.string.pnc_test_title), s(R.string.pnc_test_body), s(R.string.pnc_start)) { test() }
    }

    private fun test() {
        val sh = sheet ?: BottomSheetDialog(activity).onDismiss {
            handler.removeCallbacks(timeout)
            if (testing) send(OpoProtocol.PERSONAL_NOISE_CANCEL)
            testing = false
            sheet = null
        }.also { sheet = it }
        if (!worn()) {
            sh.title(s(R.string.pnc_title)).message(s(R.string.pnc_wear)).confirm(s(R.string.pnc_retry)) { test() }.show()
            return
        }
        sh.title(s(R.string.pnc_title)).message(s(R.string.pnc_testing))
            .confirm(s(R.string.dialog_cancel)) { sh.close() }
            .show()
        testing = true
        send(OpoProtocol.PERSONAL_NOISE_TEST)
        handler.postDelayed(timeout, 15_000)
    }

    fun result(result: Int) {
        if (!testing) return
        if (result != 0) return fail(result)
        testing = false
        handler.removeCallbacks(timeout)
        sheet?.close()
        Toast.makeText(activity, R.string.pnc_done, Toast.LENGTH_LONG).show()
        onApplied()
    }

    /** A rejected write fails the test as well (HeyMelody names status 15, another device busy). */
    fun ack(status: Int) { if (testing && status != 0) fail(0) }

    private fun fail(result: Int) {
        testing = false
        handler.removeCallbacks(timeout)
        val reason = when (result) {
            1 -> R.string.pnc_fail_quiet
            2 -> R.string.pnc_fail_fit
            3 -> R.string.pnc_fail_wind
            4 -> R.string.pnc_fail_still
            5 -> R.string.pnc_fail_audio
            else -> 0
        }
        sheet?.title(s(R.string.pnc_failed))?.message(if (reason == 0) null else s(reason))
            ?.confirm(s(R.string.pnc_retry)) { test() }
    }
}
